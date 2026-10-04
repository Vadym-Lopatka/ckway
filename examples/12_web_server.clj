;; # 12. A web server
;;
;; The real thing: a web server built with a Kotlin library, driven from Clojure. The library is `web`
;; (`examples/kotlin/web`, about 300 lines on the HTTP server of the JDK and kotlinx.coroutines). It has the
;; shapes of a typical Kotlin framework: a class with many default arguments, `typealias`es of suspend
;; function types, `fun interface` hooks, `inline reified` functions, a value class, extension functions.
;; The file starts the server on a free port, sends requests to it with `java.net.http.HttpClient`,
;; checks the answers and stops the server.
;; This file needs the Kotlin compiler (for the `reified` calls): the aliases are `:examples:kotlinc`.
;; Aliases: :kotlinc
(ns examples.12-web-server
  (:require [kt.core :as kt])
  (:import [java.net InetSocketAddress URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]))

(kt/require '[web :as w]
            '[kotlinx.coroutines :as co]
            '[kotlin.time :as t]
            '[shop :as s])

;; ## Create the server
;;
;; Kotlin: Server(listen = InetSocketAddress(port))
;; Port 0 means: any free port.
(def server (w/Server :listen (InetSocketAddress. 0)))

;; ## Extensions
;;
;; Kotlin: use<JsonBody>()
;; `JsonBody` is a type argument of a `reified` function, so it is written after `:<>`.
(def json-body (w/.use server :<> w/JsonBody))

(class json-body)
;; => web.JsonBody

;; Your own `Extension` is a `kt/reify`. `install` has two overloads (for `Server` and for `RouterConfig`),
;; so the parameter needs a type hint, as in `reify`.
;; Kotlin: use(object : Extension { override fun install(server: Server) { server.context("/ping") { get { "pong" } } } })
(def ping-extension
  (kt/reify w/Extension
    (.install [this ^web.Server server]
      (w/.context server "/ping"
                  (fn [r] (w/.get r :handler (fn [ex] "pong")))))))

(w/.use server ping-extension)

;; ## before and after
;;
;; `Before` and `After` are `fun interface`s with a suspend method. A Clojure function goes there.
;; Kotlin: after { ex, err -> ex.header("X-Error", err?.message ?: "none") }
(w/.after server (fn [ex err]
                   (w/.header ex "X-Error" (or (some-> err ex-message) "none"))))

;; `kt/reify` is the other way: an object that implements `Before`.
;; Kotlin: before(object : Before { override suspend fun before(exchange: Exchange) { seen += exchange.path } })
(def seen (atom []))

(w/.before server (kt/reify w/Before
                    (.before [this ex] (swap! seen conj (w/path ex)))))

;; ## Routes
;;
;; Kotlin: context("/hello") { ... }
;; The block is a lambda with a receiver: the `Router` is the first parameter.
(w/.context
 server "/hello"
 (fn [r]
   ;; get { "Hello World" }    -- `path` is skipped, so the handler is named
   (w/.get r :handler (fn [ex] "Hello World"))

   ;; get("/delay") { delay(1.seconds); "Waited" }    -- a suspend handler
   (w/.get r "/delay" (fn [ex]
                        (co/delay (t/milliseconds t/Duration 100))
                        "Waited"))

   ;; get("/failure") { error("Failure") }
   (w/.get r "/failure" (fn [ex] (throw (IllegalStateException. "Failure"))))

   ;; get("/param/:param") { "Path: ${path("param")}, Query: $queryParams" }
   (w/.get r "/param/:param" (fn [ex]
                               (str "Path: " (w/.path ex "param") ", Query: " (w/queryParams ex))))

   ;; get("/typed/:id") { path<Long>("id") + query<Int>("n")!! }    -- reified calls with `:<>`
   (w/.get r "/typed/:id" (fn [ex]
                            (let [id (w/.path ex "id" :<> Long)
                                  n (w/.query ex "n" :<> Int)]
                              (str (class id) ", " (class n) ", sum " (+ id n)))))

   ;; get("/product") { Product(...) }    -- JsonBody renders a Kotlin data class
   (w/.get r "/product" (fn [ex] (s/Product 1 "Tea" (s/Money 350) ["hot"])))

   ;; A Clojure map is also a java.util.Map and an Iterable of entries. `web` is a Kotlin library that
   ;; checks `Iterable` before `Map` (in its JSON renderer, `Body.kt`), so it renders the map as a list of pairs.
   (w/.get r "/clojure-map" (fn [ex] {:a 1 :b 2}))

   ;; A Java map (or a data class) renders as an object.
   (w/.get r "/java-map" (fn [ex] (java.util.HashMap. {"a" 1 "b" 2})))

   ;; post("/order") { body<OrderRequest>() }
   (w/.post r "/order" (fn [ex] (w/.body ex :<> s/OrderRequest)))

   ;; decorator { ex, h -> "<${h(ex)}>" }    -- `h` is a Kotlin suspend function value: call it
   (w/.decorator r (fn [ex h] (str "<" (h ex) ">")))

   ;; get("/decorated") { "!!!" }    -- registered after the decorator
   (w/.get r "/decorated" (fn [ex] "!!!"))))

;; ## Start
;;
;; Kotlin: server.start()
(w/.start server :gracefulStopDelaySec -1)

(def port (.getPort ^InetSocketAddress (w/address server)))

(pos? port)
;; => true

;; ## Send requests
;;
;; A small helper with the HTTP client of the JDK. It returns the status, the body and the header `X-Error`.
(def client (HttpClient/newHttpClient))

(defn request
  ([method path] (request method path nil nil))
  ([method path body accept]
   (let [builder (-> (HttpRequest/newBuilder (URI/create (str "http://localhost:" port path)))
                     (.header "Accept" (or accept "text/plain")))
         builder (if body
                   (-> builder
                       (.header "Content-Type" "application/json")
                       (.method method (HttpRequest$BodyPublishers/ofString body)))
                   (.method builder method (HttpRequest$BodyPublishers/noBody)))
         response (.send client (.build builder) (HttpResponse$BodyHandlers/ofString))]
     {:status (.statusCode response)
      :body (.body response)
      :x-error (.orElse (.firstValue (.headers response) "X-Error") nil)})))

;; A plain route:
(request "GET" "/hello")
;; => {:status 200, :body "Hello World", :x-error "none"}

;; The extension that you wrote with `kt/reify` added a context. (`after` came later, so this route has no `X-Error`.)
(request "GET" "/ping")
;; => {:status 200, :body "pong", :x-error nil}

;; A suspend handler with `delay`:
(let [t0 (System/nanoTime)
      response (request "GET" "/hello/delay")]
  [(:body response) (>= (/ (- (System/nanoTime) t0) 1e6) 90)])
;; => ["Waited" true]

;; A path parameter and the query parameters:
(request "GET" "/hello/param/abc?a=1")
;; => {:status 200, :body "Path: abc, Query: {a=1}", :x-error "none"}

;; Typed parameters. `path<Long>` gives a `Long`, `query<Int>` gives an `Integer`:
(:body (request "GET" "/hello/typed/40?n=2"))
;; => "class java.lang.Long, class java.lang.Integer, sum 42"

;; An exception in a handler is a normal error response of `web`, and `After` sees the error:
(select-keys (request "GET" "/hello/failure") [:status :x-error])
;; => {:status 400, :x-error "Failure"}

;; A decorator wraps the handlers that are registered after it:
(:body (request "GET" "/hello/decorated"))
;; => "<!!!>"

(:body (request "GET" "/hello"))
;; => "Hello World"

;; An unknown path is a 404:
(:status (request "GET" "/nothing"))
;; => 404

;; JSON: the `Accept` header selects `JsonBody`. A Kotlin data class is rendered as a JSON object.
(:body (request "GET" "/hello/product" nil "application/json"))
;; => "{\"id\":1,\"name\":\"Tea\",\"price\":350,\"tags\":[\"hot\"]}"

;; Data for a JSON route. A Clojure map is rendered as a list of pairs (the keys are keywords, so they are text with a colon):
(:body (request "GET" "/hello/clojure-map" nil "application/json"))
;; => "[[\":a\",1],[\":b\",2]]"

;; A `java.util.HashMap` is rendered as an object. Use it, or a Kotlin data class, for JSON:
(:body (request "GET" "/hello/java-map" nil "application/json"))
;; => "{\"a\":1,\"b\":2}"

;; A POST with a typed body. The JSON is parsed to an `OrderRequest`. `quantity` takes its Kotlin default.
(:body (request "POST" "/hello/order" "{\"product\":\"Tea\"}" "application/json"))
;; => "{\"product\":\"Tea\",\"quantity\":1}"

(:body (request "POST" "/hello/order" "{\"product\":\"Cake\",\"quantity\":3}" "application/json"))
;; => "{\"product\":\"Cake\",\"quantity\":3}"

;; `Before` ran for each request:
(count (filter #{"/hello/param/abc" "/hello/typed/40"} @seen))
;; => 2

;; ## Stop
;;
;; Kotlin: server.stop()
(w/.stop server :delaySec 0)

(.shutdownNow ^java.util.concurrent.ExecutorService (w/workerPool server))
;; => []
