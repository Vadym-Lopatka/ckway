(ns spike.ktor.core
  "The Catalog API on Ktor (CIO engine), written in Clojure through ckway."
  (:require [ckway.core :as kt]
            [clojure.data.json :as json]
            [spike.ktor.domain :as d])
  (:import (io.ktor.server.application Application ApplicationCall)
           (io.ktor.server.engine EmbeddedServer)
           (io.ktor.server.routing RoutingContext)
           (org.slf4j LoggerFactory))
  (:gen-class))

(set! *warn-on-reflection* true)

;; Kotlin: import io.ktor.server.engine.*  (and the other packages below)
(kt/require '[io.ktor.server.engine :as eng]
            '[io.ktor.server.cio :as cio]
            '[io.ktor.server.application :as app]
            '[io.ktor.server.routing :as rt]
            '[io.ktor.server.response :as resp]
            '[io.ktor.server.request :as req]
            '[io.ktor.server.plugins.statuspages :as sp]
            '[io.ktor.server.plugins.calllogging :as cl]
            '[io.ktor.http :as http])

(def default-config
  {:host "127.0.0.1"
   :port 0                              ; 0 = a free port
   :grace-ms 100
   :timeout-ms 2000
   :start-timeout-ms 10000
   :call-logging? false                 ; CallLogging writes one line per call to the log
   :log-error (fn [^Throwable e]
                (.error (LoggerFactory/getLogger "spike.ktor") "unhandled exception in a handler" e))})

;; ---------------------------------------------------------------------------
;; responses

(defn- status-code
  "The Ktor HttpStatusCode for a keyword."
  [k]
  (case k
    ;; Kotlin: HttpStatusCode.OK
    :ok (http/OK http/HttpStatusCode)
    ;; Kotlin: HttpStatusCode.Created
    :created (http/Created http/HttpStatusCode)
    ;; Kotlin: HttpStatusCode.NoContent
    :no-content (http/NoContent http/HttpStatusCode)
    ;; Kotlin: HttpStatusCode.BadRequest
    :bad-request (http/BadRequest http/HttpStatusCode)
    ;; Kotlin: HttpStatusCode.NotFound
    :not-found (http/NotFound http/HttpStatusCode)
    ;; Kotlin: HttpStatusCode.MethodNotAllowed
    :method-not-allowed (http/MethodNotAllowed http/HttpStatusCode)
    ;; Kotlin: HttpStatusCode.InternalServerError
    :internal-error (http/InternalServerError http/HttpStatusCode)))

(defn- respond-json
  "Send `body` as JSON with status `k`. Headers is a map name -> value."
  ([call k body] (respond-json call k body nil))
  ([call k body headers]
   (doseq [[n v] headers]
     ;; Kotlin: call.response.headers.append("Location", "/products/1")
     (resp/.append (resp/headers (app/response call)) n v))
   ;; Kotlin: call.respondText(text, ContentType.Application.Json, HttpStatusCode.Created)
   (resp/.respondText call (json/write-str body) (http/Json http/ContentType.Application) (status-code k))))

(defn- respond-empty [call k]
  ;; Kotlin: call.respondText("", status = HttpStatusCode.NoContent)
  (resp/.respondText call "" :status (status-code k)))

(defn- error-json [msg] {:error msg})

;; ---------------------------------------------------------------------------
;; request helpers

(defn- parse-id
  "The path parameter as a long, or nil when it is not a number."
  [^String s]
  (when s
    (try (Long/parseLong s)
         (catch NumberFormatException _ nil))))

(defn- parse-json
  "The map in the JSON text, or ::invalid. It reads the text only; no suspend call is made inside the try."
  [^String text]
  (try
    (let [v (json/read-str text :key-fn keyword)]
      (if (map? v) v ::invalid))
    (catch InterruptedException e (throw e))
    (catch Exception _ ::invalid)))

(defn- path-id [call]
  ;; Kotlin: call.parameters["id"]
  (parse-id (http/.get (app/parameters call) "id")))

;; ---------------------------------------------------------------------------
;; handlers: each one is a Clojure function that Ktor runs as `suspend RoutingContext.() -> Unit`

(defn- health [^RoutingContext ctx]
  (respond-json (rt/call ctx) :ok {:status "ok"}))

(defn- list-products [store ^RoutingContext ctx]
  (let [call (rt/call ctx)
        ;; Kotlin: call.request.queryParameters["tag"]
        tag (http/.get (req/queryParameters (app/request call)) "tag")
        items (d/all-products store)]
    (respond-json call :ok (if tag (filterv #(some #{tag} (:tags %)) items) items))))

(defn- get-product [store ^RoutingContext ctx]
  (let [call (rt/call ctx)
        id (path-id call)]
    (if (nil? id)
      (respond-json call :bad-request (error-json "id must be a number"))
      (if-let [p (d/product store id)]
        (respond-json call :ok p)
        (respond-json call :not-found (error-json "not found"))))))

(defn- create-product [store ^RoutingContext ctx]
  (let [call (rt/call ctx)
        ;; Kotlin: val text = call.receiveText()
        text (req/.receiveText call)
        data (parse-json text)]
    (if (= ::invalid data)
      (respond-json call :bad-request (error-json "body must be a JSON object"))
      (if-let [msg (d/validate data)]
        (respond-json call :bad-request (error-json msg))
        (let [p (d/add-product! store (update data :tags #(vec (or % []))))]
          (respond-json call :created p {"Location" (str "/products/" (:id p))}))))))

(defn- wrong-method [^RoutingContext ctx]
  (respond-json (rt/call ctx) :method-not-allowed (error-json "method not allowed")))

(defn- delete-product [store ^RoutingContext ctx]
  (let [call (rt/call ctx)
        id (path-id call)]
    (cond
      (nil? id) (respond-json call :bad-request (error-json "id must be a number"))
      (d/delete-product! store id) (respond-empty call :no-content)
      :else (respond-json call :not-found (error-json "not found")))))

;; ---------------------------------------------------------------------------
;; the module

(defn- install-status-pages [^Application application {:keys [log-error]}]
  ;; Kotlin: install(StatusPages) { exception<Throwable> { call, cause -> ... } }
  (app/.install application (sp/StatusPages)
                (fn [cfg]
                  ;; Kotlin: exception<Throwable> { call, cause -> ... }   (the reified form is not used: see FINDINGS)
                  (sp/.exception cfg (kt/ref Throwable class)
                                 (fn [call cause]
                                   (log-error cause)
                                   (respond-json call :internal-error (error-json "internal error"))))
                  ;; Kotlin: status(HttpStatusCode.NotFound) { call, _ -> call.respondText(...) }
                  ;; The hint on the lambda parameter chooses between the two `status` overloads.
                  (sp/.status cfg (http/NotFound http/HttpStatusCode)
                              (fn [^ApplicationCall call _status]
                                (respond-json call :not-found (error-json "not found"))))
                  ;; Kotlin: status(HttpStatusCode.MethodNotAllowed) { call, _ -> call.respondText(...) }
                  (sp/.status cfg (http/MethodNotAllowed http/HttpStatusCode)
                              (fn [^ApplicationCall call _status]
                                (respond-json call :method-not-allowed (error-json "method not allowed")))))))

(defn module
  "A Ktor module (Application -> Unit) for the Catalog API over `store`."
  [config store]
  (fn [^Application application]
    (when (:call-logging? config)
      ;; Kotlin: install(CallLogging)
      (app/.install application (cl/CallLogging)))
    (install-status-pages application config)
    ;; Kotlin: routing { ... }
    (rt/.routing
     application
     (fn [r]
       ;; Kotlin: get("/health") { call.respondText(...) }
       (rt/.get r "/health" health)
       ;; Kotlin: route("/products") { get { ... } ... }
       (rt/.route r "/products"
                  (fn [products]
                    (rt/.get products (partial list-products store))
                    (rt/.post products (partial create-product store))
                    ;; Kotlin: route("/{id}") { get { ... }; delete { ... }; handle { 405 } }
                    ;; Ktor answers 405 for a wrong method on a constant path, but 404 on a path with `{id}`.
                    ;; The `handle` with no method makes it 405 here too (a `get` or `delete` is preferred).
                    (rt/.route products "/{id}"
                               (fn [one]
                                 (rt/.get one (partial get-product store))
                                 (rt/.delete one (partial delete-product store))
                                 (rt/.handle one wrong-method)))))))))

(defn stop!
  "Stops the server of a system map made by `start!`. Safe to call twice."
  [{:keys [^EmbeddedServer server config]}]
  (when server
    ;; Kotlin: server.stop(gracePeriodMillis = 100, timeoutMillis = 2000)
    (eng/.stop server (long (:grace-ms config 100)) (long (:timeout-ms config 2000))))
  nil)

(defn- await-port
  "The port of the server. Ktor does not tell a caller that the module failed: `start(wait = false)` returns, and
  `resolvedConnectors()` waits for ever. So this polls, and gives up when the module threw or the time is over."
  [^EmbeddedServer server failed timeout-ms]
  (let [;; Kotlin: server.engine.resolvedConnectors()   (a suspend call: it runs in its own thread)
        connectors (future (eng/.resolvedConnectors (eng/engine server)))
        deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (realized? failed) (do (future-cancel connectors)
                               (throw (ex-info "the Ktor module failed to start" {} @failed)))
        (realized? connectors) ;; Kotlin: .first().port
        (long (eng/port (first @connectors)))
        (> (System/currentTimeMillis) deadline) (do (future-cancel connectors)
                                                    (throw (ex-info "the server did not start in time" {:timeout-ms timeout-ms})))
        :else (do (Thread/sleep 20) (recur))))))

(defn start!
  "Starts a server. `config` is merged over `default-config`; `:store` is a ProductStore (default: a memory store).
  Returns {:server :port :store :config}. When the server cannot start it is stopped and an ex-info is thrown."
  [config]
  (let [config (merge default-config config)
        store (or (:store config) (d/memory-store))
        failed (promise)
        app-module (module config store)
        ;; Kotlin: embeddedServer(CIO, port = 0, host = "127.0.0.1") { module }
        ;; (the lambda is the last positional argument: it goes to `module`, `watchPaths` keeps its default.
        ;; The port and the host need a type: with values that kt cannot see (`(:port config)`) it takes the
        ;; overload `embeddedServer(factory, environment, configure, module)` and fails: see FINDINGS)
        server (eng/embeddedServer cio/CIO (long (:port config)) ^String (:host config)
                                   (fn [application]
                                     (try (app-module application)
                                          (catch Throwable e (deliver failed e) (throw e)))))]
    ;; Kotlin: server.start(wait = false)
    (try
      ;; `start` may throw the exception of the module itself ...
      (eng/.start server :wait false)
      {:server server
       :store store
       :config config
       ;; ... or return, and the failure is seen only here
       :port (await-port server failed (:start-timeout-ms config))}
      (catch Throwable e
        (try (stop! {:server server :config config}) (catch Throwable _ nil))
        (throw (if (instance? clojure.lang.ExceptionInfo e)
                 e
                 (ex-info "the Ktor server failed to start" {} e)))))))

(defn -main
  "clojure -M:run [port]   (default 8080)"
  [& [port]]
  (let [system (start! {:port (if port (parse-long port) 8080) :call-logging? true})]
    (println "Catalog API on port" (:port system))
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable #(stop! system)))
    @(promise)))
