(ns spike.http4k.api
  "The Catalog API as one http4k HttpHandler. Pure functions of a store and a log function: no global state."
  (:require [ckway.core :as kt]
            [clojure.data.json :as json]
            [spike.http4k.domain :as domain])
  (:import [kotlin.jvm.functions Function1]
           [org.http4k.core Request Response]
           [org.http4k.lens LensFailure]))

(set! *warn-on-reflection* true)

(kt/require '[org.http4k.core :as h]
            '[org.http4k.routing :as r]
            '[org.http4k.lens :as l])

;; ## Small helpers

;; Kotlin: Filter { next -> { request -> next(request) } }
;; Filter(fn) is an inline function and has no JVM method (FINDINGS 5). kt/reify of the interface works.
;; Inside it, `next` is a Clojure fn, and the member can return a plain Clojure fn.
(defn- filter* ^org.http4k.core.Filter [wrap]
  (kt/reify h/Filter
    (.invoke [_ next] (wrap next))))

;; Kotlin: Status.OK
;; Status.OK is a property of the companion object.
(def ^:private OK (h/OK h/Status))
(def ^:private CREATED (h/CREATED h/Status))
(def ^:private NO_CONTENT (h/NO_CONTENT h/Status))
(def ^:private BAD_REQUEST (h/BAD_REQUEST h/Status))
(def ^:private NOT_FOUND (h/NOT_FOUND h/Status))
(def ^:private SERVER_ERROR (h/INTERNAL_SERVER_ERROR h/Status))

;; ## Responses

(defn- with-json
  "The Response with a JSON body."
  ^Response [^Response base body]
  ;; Kotlin: base.header("Content-Type", "application/json").body(json)
  (-> base
      (h/.header "Content-Type" "application/json; charset=utf-8")
      (h/.body ^String (json/write-str body))))

(defn- response
  "A new Response with a JSON body (or no body when `body` is nil)."
  ^Response [status body]
  ;; Kotlin: Response(status)
  (let [^Response base (h/Response status)]
    (if (nil? body)
      base
      (with-json base body))))

(defn- error-response ^Response [status message]
  (response status {:error message}))

(defn- status-code [^Response resp]
  ;; Kotlin: response.status.code
  (h/code (h/status resp)))

;; ## Lenses

;; Kotlin: val id = Path.int().of("id")
(def ^:private id-lens (l/.of (l/.int l/Path) "id"))

;; Kotlin: val tag = Query.optional("tag")
(def ^:private tag-lens (l/.optional l/Query "tag"))

(defn- path-id
  "The id path parameter as a long. A bad number throws LensFailure."
  [^Request req]
  ;; Kotlin: id(request)
  (long (l/.invoke ^org.http4k.lens.LensExtractor id-lens req)))

;; ## Handlers

(defn- health [_req]
  (response OK {:status "ok"}))

(defn- list-products [store]
  (fn [^Request req]
    (let [tag (l/.invoke ^org.http4k.lens.LensExtractor tag-lens req)
          items (domain/all-products store)]
      (response OK (if tag
                     (filterv #(some #{tag} (:tags %)) items)
                     items)))))

(defn- get-product [store]
  (fn [^Request req]
    (if-let [p (domain/product store (path-id req))]
      (response OK p)
      (error-response NOT_FOUND "not found"))))

(defn- parse-body
  "The JSON body as a map with keyword keys, or nil when it is not a JSON object."
  [^Request req]
  (try
    (let [data (json/read-str (h/.bodyString req) :key-fn keyword)]
      (when (map? data) data))
    (catch Exception _ nil)))

(defn- create-product [store]
  (fn [^Request req]
    (let [data (parse-body req)]
      (if-let [problem (if data (domain/validate data) "body must be a JSON object")]
        (error-response BAD_REQUEST problem)
        (let [created (domain/add-product! store (update data :tags #(vec %)))]
          ;; Kotlin: response.header("Location", "/products/$id")
          (-> (response CREATED created)
              (h/.header "Location" (str "/products/" (:id created)))))))))

(defn- delete-product [store]
  (fn [^Request req]
    (if (domain/delete-product! store (path-id req))
      (response NO_CONTENT nil)
      (error-response NOT_FOUND "not found"))))

;; ## Routes

;; Kotlin: routes("/health" bind GET to { ... }, "/products" bind GET to { ... }, ...)
(defn- routes ^org.http4k.routing.RoutingHttpHandler [store]
  (r/routes
   (r/.to (r/.bind "/health" h/Method.GET) health)
   (r/.to (r/.bind "/products" h/Method.GET) (list-products store))
   (r/.to (r/.bind "/products/{id}" h/Method.GET) (get-product store))
   (r/.to (r/.bind "/products" h/Method.POST) (create-product store))
   (r/.to (r/.bind "/products/{id}" h/Method.DELETE) (delete-product store))))

;; ## Filters

;; Kotlin: Filter { next -> { req -> next(req).also { log(...) } } }
(defn- request-logging ^org.http4k.core.Filter [log-fn]
  (filter*
   (fn [next]
     (fn [^Request req]
       (let [t0 (System/nanoTime)
             resp (next req)]
         (log-fn (format "%s %s -> %d (%d ms)"
                         (h/method req) (h/uri req) (status-code resp)
                         (quot (- (System/nanoTime) t0) 1000000)))
         resp)))))

(defn- stack-trace-text [^Throwable e]
  (let [w (java.io.StringWriter.)]
    (.printStackTrace e (java.io.PrintWriter. w))
    (str w)))

;; Kotlin: Filter { next -> { req -> try { next(req) } catch (e: Exception) { 500 } } }
;; A bad lens value is a 400. Anything else is logged and becomes a plain 500.
;; The client never sees a stack trace.
(defn- error-handling ^org.http4k.core.Filter [log-fn]
  (filter*
   (fn [next]
     (fn [req]
       (try
         (next req)
         (catch LensFailure e
           (error-response BAD_REQUEST (str "invalid request: " (ex-message e))))
         (catch Throwable e
           (log-fn (str "ERROR unhandled exception\n" (stack-trace-text e)))
           (error-response SERVER_ERROR "internal error")))))))

;; http4k answers an unknown route with an empty 404, and a known path with the wrong method with an empty 405.
;; This filter gives both a JSON body.
(defn- json-not-found ^org.http4k.core.Filter []
  (filter*
   (fn [next]
     (fn [req]
       (let [resp (next req)
             code (status-code resp)]
         (if (and (#{404 405} code) (empty? (h/.bodyString resp)))
           (with-json resp {:error (if (= 404 code) "not found" "method not allowed")})
           resp))))))

;; ## The handler

(defn make-handler
  "Build the HttpHandler. `store` is a ProductStore, `log-fn` takes a string."
  ^Function1 [store log-fn]
  ;; Kotlin: request_logging.then(error_handling).then(json_not_found).then(routes)
  (let [^org.http4k.core.Filter logging (request-logging log-fn)
        ^org.http4k.core.Filter errors (error-handling log-fn)
        ^org.http4k.core.Filter not-found (json-not-found)
        ^org.http4k.core.Filter pipeline (h/.then (h/.then logging errors) not-found)
        ^org.http4k.routing.RoutingHttpHandler app (routes store)]
    (h/.then pipeline app)))
