(ns ckway.web-test
  "Tests against the Kotlin `web` library of the examples (examples/kotlin/web, built by bin/build-fixtures): a small web server, JSON, query DSL and jobs."
  (:require [clojure.repl]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ckway.core :as kt]
            [ckway.rt :as rt]
            [ckway.types])
  (:import [java.net InetSocketAddress URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.util.concurrent CompletableFuture]))

(def ^:private t0 (System/nanoTime))
(kt/require '[web :as w] '[kotlinx.coroutines :as co] '[kotlin.reflect :as r])
(def ^:private require-ms (/ (- (System/nanoTime) t0) 1e6))
(kt/require '[web.query :as db] '[fx :as f] '[web.jobs :as jobs])

(defn- timed-require [pkg as]
  (let [t0 (System/nanoTime)]
    (kt/require [pkg :as as])
    {:ms (/ (- (System/nanoTime) t0) 1e6) :vars (count (ns-interns (symbol (str "ckway.pkg." pkg))))}))

(def ^:private time-stats (timed-require 'kotlin.time 't))
(def ^:private kotlin-stats (timed-require 'kotlin 'kk))

(deftest web-smoke
  (println "WEB kt/require of web (cold):" (format "%.0f ms" require-ms)
           "vars:" (count (ns-interns 'ckway.pkg.web)))
  (is (some? (w/Server :listen (java.net.InetSocketAddress. 0))))
  (is (boolean? (w/isDev w/Config)))
  (is (some? (w/port w/Config)))
  (w/.useEnvFile w/Config)
  (is (str/includes? (with-out-str (clojure.repl/doc w/Server)) "class Server(")))

(deftest web-function-types
  (let [server (w/Server :listen (java.net.InetSocketAddress. 0))
        seen (atom nil)
        router (w/.context server "/hello" (fn [r] (reset! seen r) (w/routes r)))]
    (is (instance? web.Router router))
    (is (instance? web.Router @seen) "the block was called with the Router of the context")
    (is (identical? router @seen)))
  (testing "B4 (step 3 stopped here): the parameter of the block is a Router, so w/.get is resolved; since step 4 the suspend handler type is supported"
    (is (some? (binding [*ns* (the-ns 'ckway.web-test)]
                 ((eval '(fn [server] (w/.context server "/x" (fn [r] (w/.get r "/x" (fn [ex] "hi")))))) (w/Server :listen (java.net.InetSocketAddress. 0))))))))

(deftest web-value-class
  (println "KT kt/require of kotlin.time (after web):" (format "%.0f ms" (:ms time-stats)) "vars:" (:vars time-stats))
  (println "KT kt/require of kotlin (after kotlin.time):" (format "%.0f ms" (:ms kotlin-stats)) "vars:" (:vars kotlin-stats))
  (let [ok (w/OK w/StatusCode)]
    (testing "a constant of the companion arrives boxed"
      (is (instance? web.StatusCode ok))
      (is (= 200 (w/value ok)))
      (is (instance? web.StatusCode (rt/call-dyn #'w/OK [w/StatusCode] {})))
      (is (= 200 (w/value (rt/call-dyn #'w/OK [w/StatusCode] {})))))
    (testing "construct, compare"
      (is (instance? web.StatusCode (w/StatusCode 201)))
      (is (= 201 (w/value (w/StatusCode 201))))
      (is (= 201 (w/value (rt/call-dyn #'w/StatusCode [201] {}))))
      (is (= (w/StatusCode 200) ok))
      (is (= ok (w/OK w/StatusCode)))
      (is (not= ok (w/Created w/StatusCode)))
      (is (= (hash ok) (hash (w/StatusCode 200)))))
    (testing "a constructor and a property with a StatusCode (the jar is on the class path, not a directory)"
      (let [ex (w/StatusCodeException (w/NotFound w/StatusCode) "gone")]
        (is (= 404 (w/value (w/statusCode ex))))
        (is (instance? web.StatusCode (w/statusCode ex)))
        (is (= (w/NotFound w/StatusCode) (w/statusCode ex)))
        (is (= "gone" (ex-message ex))))))
  (testing "kotlin.time from the stdlib jar"
    (is (instance? kotlin.time.Duration (t/seconds t/Duration 5)))
    (is (= 5000 (t/inWholeMilliseconds (t/seconds t/Duration 5))))
    (is (= 5000 (rt/call-dyn #'t/inWholeMilliseconds [(rt/call-dyn #'t/seconds [t/Duration 5] {})] {})))))

;; ---------------------------------------------------------------- end to end with a real server (step 4)

(defn- request [^HttpClient client port path]
  (-> (HttpRequest/newBuilder (URI/create (str "http://localhost:" port path))) (.header "Accept" "text/plain") .build))

(defn- request-json
  ([client port path] (request-json client port path nil))
  ([^HttpClient client port path body]
   (let [b (-> (HttpRequest/newBuilder (URI/create (str "http://localhost:" port path)))
               (.header "Accept" "application/json"))
         b (if body
             (-> b (.header "Content-Type" "application/json") (.POST (java.net.http.HttpRequest$BodyPublishers/ofString body)))
             b)
         r (.send client (.build b) (HttpResponse$BodyHandlers/ofString))]
     {:status (.statusCode r) :body (.body r)})))

(defn- get! [^HttpClient client port path]
  (let [r (.send client (request client port path) (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode r) :body (.body r)}))

(declare await-after)

(deftest web-end-to-end
  (let [server (w/Server :listen (InetSocketAddress. 0))
        after-log (atom [])
        decorated (atom [])]
    (w/.after server (fn [ex err] (swap! after-log conj [(w/path ex) (some-> err class .getSimpleName)])))
    (w/.context server "/t"
                (fn [r]
                  (w/.get r :handler (fn [ex] "root"))
                  (w/.get r "/hello" (fn [ex] "Hello"))
                  (w/.get r "/delay" (fn [ex] (co/delay 100) (co/delay (t/milliseconds t/Duration 50)) "waited"))
                  (w/.get r "/param/:id" (fn [ex] (str (.path ^web.Exchange ex "id") (w/queryParams ex))))
                  (w/.decorator r (fn [ex handler] (swap! decorated conj (w/path ex)) (str "<" (handler ex) ">")))
                  (w/.get r "/decorated" (fn [ex] "!!!"))
                  (w/.get r "/fail" (fn [ex] (throw (IllegalStateException. "nope"))))
                  (w/.get r "/crash" (fn [ex] (throw (RuntimeException. "crash"))))))
    (w/.start server :gracefulStopDelaySec -1)
    (try
      (let [port (.getPort ^InetSocketAddress (w/address server))
            client (HttpClient/newHttpClient)]
        (testing "plain routes: strict form with a skipped default, positional path"
          (is (= {:status 200 :body "root"} (get! client port "/t")))
          (is (= {:status 200 :body "Hello"} (get! client port "/t/hello"))))
        (testing "suspend calls in a handler"
          (let [t0 (System/nanoTime)
                r (get! client port "/t/delay")
                ms (/ (- (System/nanoTime) t0) 1e6)]
            (is (= {:status 200 :body "waited"} r))
            (is (>= ms 120) (str ms "ms: 100 + 50 ms of delay"))))
        (testing "path and query parameters"
          (is (= {:status 200 :body "42{a=1}"} (get! client port "/t/param/42?a=1"))))
        (testing "a decorator that is a Clojure fn: Kotlin passes it the next handler (a suspend function value)"
          (is (= {:status 200 :body "<!!!>"} (get! client port "/t/decorated")))
          (is (= ["/t/decorated"] @decorated) "the decorator is added to the routes registered after it"))
        (testing "an exception in a handler is the normal error response of the library (it maps IllegalStateException to 400, others to 500)"
          (let [r (get! client port "/t/fail")]
            (is (= 400 (:status r)))
            (is (str/includes? (:body r) "nope"))
            (println "WEB /t/fail ->" (pr-str r)))
          (let [r (get! client port "/t/crash")]
            (is (= 500 (:status r)))
            (println "WEB /t/crash ->" (pr-str r))))
        (testing "an unknown path is a 404"
          (is (= 404 (:status (get! client port "/zzz")))))
        (testing "the After hook (fun interface, suspend method) ran for each request, with the error of /fail"
          (await-after after-log 7)
          (println "WEB after-log:" (pr-str @after-log))
          (is (>= (count @after-log) 7))
          (is (some #{["/t/fail" "IllegalStateException"]} @after-log) (pr-str @after-log))
          (is (some #{["/t/hello" nil]} @after-log) (pr-str @after-log)))
        (testing "50 concurrent /delay requests take about the delay, not 50 x the delay (default worker pool)"
          (let [reqs (repeatedly 50 #(request client port "/t/delay"))
                t0 (System/nanoTime)
                fs (doall (map #(.sendAsync client % (HttpResponse$BodyHandlers/ofString)) reqs))
                rs (mapv #(.get ^CompletableFuture %) fs)
                ms (/ (- (System/nanoTime) t0) 1e6)]
            (println (format "WEB 50 concurrent /t/delay (150ms each): %.0f ms" ms))
            (is (every? #(= 200 (.statusCode ^java.net.http.HttpResponse %)) rs))
            (is (every? #(= "waited" (.body ^java.net.http.HttpResponse %)) rs))
            (is (< ms 3000) (str ms "ms; serial would be 7500")))))
      (finally
        (w/.stop server :delaySec 0)
        (.shutdownNow ^java.util.concurrent.ExecutorService (w/workerPool server))))))

(defn- await-after [log n]
  (let [end (+ (System/currentTimeMillis) 3000)]
    (loop [] (cond (>= (count @log) n) true (> (System/currentTimeMillis) end) false :else (do (Thread/sleep 10) (recur))))))

;; ---------------------------------------------------------------- step 5: type arguments against the web library

(deftest web-type-arguments
  (let [server (w/Server :listen (InetSocketAddress. 0))
        used (w/.use server :<> w/JsonBody)]
    (testing "(w/.use server :<> w/JsonBody) registers and installs the extension (reified `use<E>()`)"
      (is (instance? web.JsonBody used))
      (is (pos? (count (filter #(instance? web.JsonBody %) (w/renderers server)))) "installed as a renderer (use() registers it, install() adds it)")
      (is (pos? (count (filter #(instance? web.JsonBody %) (w/parsers server))))))
    (testing "(w/.require server :<> web.JsonBody): the registry lookup, an extension function of Registry"
      (is (identical? used (w/.require server :<> web.JsonBody)))
      (is (identical? used (w/.require server :<> w/JsonBody)))
      (is (identical? used (w/.optional server :<> w/JsonBody))))
    (w/.context server "/t"
                (fn [r]
                  (w/.get r "/p/:id"
                          (fn [ex]
                            (doto (java.util.LinkedHashMap.)
                              (.put "plain" (w/.path ex "id"))
                              (.put "long" (w/.path ex "id" :<> Long))
                              (.put "longClass" (.getName (class (w/.path ex "id" :<> Long))))
                              (.put "int" (w/.query ex "n" :<> Int))
                              (.put "intClass" (.getName (class (w/.query ex "n" :<> Int))))
                              (.put "missing" (w/.query ex "nope" :<> Int)))))
                  (w/.get r "/event" (fn [ex] (w/Event "hello" "greeting" 7)))
                  (w/.post r "/body"
                           (fn [ex]
                             (let [rows (w/.body ex :<> (List (Map String Any?)))]
                               (doto (java.util.LinkedHashMap.)
                                 (.put "size" (count rows))
                                 (.put "class" (.getName (class rows)))
                                 (.put "first" (get (first rows) "a"))))))))
    (w/.start server :gracefulStopDelaySec -1)
    (try
      (let [port (.getPort ^InetSocketAddress (w/address server))
            client (HttpClient/newHttpClient)]
        (testing "(w/.path ex \"id\") is the non-generic twin; with :<> Long it is a Long"
          (let [r (request-json client port "/t/p/42?n=7")]
            (println "WEB /t/p/42?n=7 ->" (pr-str r))
            (is (= 200 (:status r)))
            (is (= "{\"plain\":\"42\",\"long\":42,\"longClass\":\"java.lang.Long\",\"int\":7,\"intClass\":\"java.lang.Integer\"}"
                   (:body r))
                "the JSON renderer drops the null `missing`")))
        (testing "a Kotlin data class as a route result renders as JSON"
          (let [r (request-json client port "/t/event")]
            (println "WEB /t/event ->" (pr-str r))
            (is (= 200 (:status r)))
            (is (str/includes? (:body r) "\"data\":\"hello\""))
            (is (str/includes? (:body r) "\"name\":\"greeting\""))))
        (testing "(w/.body ex :<> (List (Map String Any?))) parses the JSON body"
          (let [r (request-json client port "/t/body" "[{\"a\":1,\"b\":\"x\"},{\"c\":null}]")]
            (println "WEB POST /t/body ->" (pr-str r))
            (is (= 200 (:status r)))
            (is (= "{\"size\":2,\"class\":\"java.util.ArrayList\",\"first\":1}" (:body r))))))
      (finally
        (w/.stop server :delaySec 0)
        (.shutdownNow ^java.util.concurrent.ExecutorService (w/workerPool server))))))

(deftest web-type-arguments-are-static
  (testing "the reified calls of the web library are on the static path: a Kotlin bridge, no call-dyn"
    (let [handler '(fn [ex] [(w/.path ex "id") (w/.path ex "id" :<> Long) (w/.query ex "n" :<> Int)])
          e (binding [*ns* (the-ns 'ckway.web-test)]
              (let [static (atom []) dyn (atom [])
                    emit ckway.resolve/emit dynf ckway.resolve/dynamic-form
                    w (java.io.StringWriter.)]
                (with-redefs [ckway.resolve/emit (fn [p] (let [x (emit p)] (swap! static conj x) x))
                              ckway.resolve/dynamic-form (fn [v parsed] (let [x (dynf v parsed)] (swap! dyn conj x) x))]
                  (binding [*warn-on-reflection* true *err* w]
                    (eval `(fn [server#] (w/.get server# "/x" ~handler)))))
                {:static @static :dynamic @dyn :warnings (str w)}))
          s (pr-str (:static e))]
      (println "WEB EXPANSION (w/.path ex \"id\" :<> Long) =>" (some #(when (and (str/includes? (pr-str %) "path") (str/includes? (pr-str %) "K_web")) (pr-str %)) (:static e)))
      (is (seq (re-seq #"ckway\.bridge\.K_web_Exchange_path__" s)))
      (is (seq (re-seq #"ckway\.bridge\.K_web_Exchange_query__" s)))
      (is (empty? (:dynamic e)))
      (is (not (str/includes? (:warnings e) "Reflection warning")) (:warnings e)))))

(defn- java-class [^kotlin.reflect.KType t] (kotlin.jvm.JvmClassMappingKt/getJavaClass ^kotlin.reflect.KClass (.getClassifier t)))

(deftest web-typeOf
  (testing "(r/typeOf :<> (List String?)): the classifier and the arguments (without kotlin-reflect the printed form is in Java names)"
    (let [t (r/typeOf :<> (List String?))
          a (.getType ^kotlin.reflect.KTypeProjection (first (.getArguments t)))]
      (is (= java.util.List (java-class t)))
      (is (= java.lang.String (java-class a)))
      (is (true? (.isMarkedNullable a))))
    (let [t (r/typeOf :<> (Map String Any?))]
      (is (= java.util.Map (java-class t)))
      (is (= 2 (count (.getArguments t)))))))

;; ---------------------------------------------------------------- step 6: kt/set!, kt/ref, kt/data against the web library

(defn- static-proof
  "What the compiler emits while it compiles `form` in this namespace: the static forms, the dynamic ones, the warnings."
  [form]
  (let [static (atom []) dyn (atom [])
        emit ckway.resolve/emit dynf ckway.resolve/dynamic-form
        w (java.io.StringWriter.)]
    (with-redefs [ckway.resolve/emit (fn [p] (let [x (emit p)] (swap! static conj x) x))
                  ckway.resolve/dynamic-form (fn [v parsed] (let [x (dynf v parsed)] (swap! dyn conj x) x))]
      (binding [*warn-on-reflection* true *err* w *ns* (the-ns 'ckway.web-test)]
        (eval form)))
    {:static @static :dynamic @dyn :warnings (str w)}))

(deftest web-property-references-in-the-sql-dsl
  (let [p (kt/ref f/Person firstName)]
    (testing "a KProperty1 that the web library's own `infix fun <T, V> KProperty1<T, V>.eq(value: V)` takes"
      (is (instance? kotlin.reflect.KProperty1 p))
      (is (= "firstName" (.getName ^kotlin.reflect.KProperty1 p))))
    (testing "(db/.eq ref \"Ann\") is the Pair that Kotlin builds for `Person::firstName eq \"Ann\"`"
      (let [clj (db/.eq p "Ann") kt (webfx.WebFxKt/eqAnn)]
        (is (instance? kotlin.Pair clj))
        (is (= kt clj))
        (is (= "firstName" (.getName ^kotlin.reflect.KProperty1 (.getFirst ^kotlin.Pair clj))))
        (is (= "Ann" (.getSecond ^kotlin.Pair clj)))
        (is (= (str kt) (str clj)))
        (println "WEB (db/.eq (kt/ref f/Person firstName) \"Ann\") =>" (str clj))))
    (testing "ilike, gt: SqlOp values, equal to Kotlin's"
      (is (= (webfx.WebFxKt/ilikeAnn) (db/.ilike p "A%")))
      (is (instance? web.query.SqlOp (.getSecond ^kotlin.Pair (db/.ilike p "A%"))))
      (is (= (webfx.WebFxKt/gtId) (db/.gt (kt/ref f/Person id) 5))))
    (testing "db/or, db/and over them (vararg of Pair)"
      (let [clj (db/or (db/.ilike (kt/ref f/Person firstName) "A%") (db/.ilike (kt/ref f/Person email) "A%"))
            kt (webfx.WebFxKt/orBoth)]
        (is (= kt clj))
        (is (= "firstName" (.getName ^kotlin.reflect.KProperty1 (.getFirst ^kotlin.Pair clj)))))
      (is (= (webfx.WebFxKt/andBoth) (db/and (db/.eq (kt/ref f/Person firstName) "Ann") (db/.gt (kt/ref f/Person id) 5)))))
    (testing "the String overload of .eq is still the String one"
      (is (= (kotlin.Pair. "name" "Ann") (db/.eq "name" "Ann"))))))

(deftest web-ref-calls-are-static
  (testing "`db/.eq` has two declarations (String and KProperty1 receiver): the kt/ref form tells which, at compile time"
    (let [e (static-proof '(fn [] (db/.eq (kt/ref f/Person firstName) "Ann")))]
      (is (= "" (:warnings e)))
      (is (empty? (:dynamic e)))
      (is (not (str/includes? (pr-str (:static e)) "call-dyn")))
      (println "WEB EXPANSION of (db/.eq (kt/ref f/Person firstName) \"Ann\") =>" (pr-str (:static e)))))
  (testing "db/or with two of them"
    (let [e (static-proof '(fn [] (db/or (db/.ilike (kt/ref f/Person firstName) "A%") (db/.ilike (kt/ref f/Person email) "A%"))))]
      (is (= "" (:warnings e)))
      (is (empty? (:dynamic e))))))

(deftest web-class-reference-for-a-kotlin-api
  (let [server (w/Server :listen (InetSocketAddress. 0))
        used (w/.use server :<> w/JsonBody)]
    (testing "KClass from kt/ref goes to `require(KClass)` of the web library: the instance that `use` registered"
      (is (instance? kotlin.reflect.KClass (kt/ref w/JsonBody class)))
      (is (identical? used (w/.require server (kt/ref w/JsonBody class)))))
    (testing "the same KClass that Kotlin's `JsonBody::class` is"
      (is (= (kotlin.jvm.internal.Reflection/getOrCreateKotlinClass web.JsonBody) (kt/ref w/JsonBody class))))
    (w/.stop server :delaySec 0)))

(deftest web-set-a-var-property
  (testing "(kt/set! (w/failure ex) e) on a real HttpExchange.failure (`var failure: Throwable?`), in a handler"
    (let [server (w/Server :listen (InetSocketAddress. 0))
          seen (atom nil)]
      (w/.context server "/t"
                  (fn [r]
                    (w/.get r "/f" (fn [ex]
                                     (let [before (w/failure ex)
                                           ret (kt/set! (w/failure ex) (IllegalStateException. "boom"))]
                                       (reset! seen [before ret (w/failure ex)])
                                       "ok")))))
      (w/.start server :gracefulStopDelaySec -1)
      (try
        (let [port (.getPort ^InetSocketAddress (w/address server))
              client (HttpClient/newHttpClient)]
          (is (= {:status 200 :body "ok"} (get! client port "/t/f")))
          (let [[before ret after] @seen]
            (is (nil? before))
            (is (= "boom" (ex-message ret)))
            (is (identical? ret after) "set! returned the value, and the property holds it")))
        (finally
          (w/.stop server :delaySec 0)
          (.shutdownNow ^java.util.concurrent.ExecutorService (w/workerPool server))))))
  (testing "the handler's set! is on the static path (the parameter of the fn literal is typed web.Exchange)"
    (let [e (static-proof '(fn [server] (w/.get server "/x" (fn [ex] (kt/set! (w/failure ex) nil) "x"))))]
      (is (= "" (:warnings e)) (:warnings e))
      (is (not (str/includes? (pr-str (:static e)) "set-dyn")))))
  (testing "(kt/set! (db/applicationName conn) \"app\"): an extension property on a JDK type"
    (let [info (atom {})
          conn (java.lang.reflect.Proxy/newProxyInstance
                (.getClassLoader java.sql.Connection) (into-array Class [java.sql.Connection])
                (reify java.lang.reflect.InvocationHandler
                  (invoke [_ _ m args]
                    (case (.getName m)
                      "setClientInfo" (do (swap! info assoc (aget args 0) (aget args 1)) nil)
                      "getClientInfo" (get @info (aget args 0))))))]
      (is (= "app" (kt/set! (db/applicationName conn) "app")))
      (is (= {"ApplicationName" "app"} @info))
      (is (= "app" (db/applicationName conn)))
      (is (= "app2" (kt/set! (db/applicationName conn) "app2")))
      (is (= "app2" (db/applicationName conn))))))

(deftest web-data
  (testing "a data class with 9 properties (Cookie), in constructor order"
    (let [m (kt/data (w/Cookie "sid" "abc"))]
      (is (= [:name :value :expires :maxAge :path :domain :httpOnly :secure :sameSite] (keys m)))
      (is (= "sid" (:name m)))
      (is (= "/" (:path m)) "a default value")
      (is (false? (:httpOnly m)))
      (is (nil? (:maxAge m)))
      (println "WEB (kt/data (w/Cookie \"sid\" \"abc\")) =>" (pr-str m))))
  (testing "a nullable value class property stays the boxed object"
    (let [d (t/seconds t/Duration 5)
          m (kt/data (w/Cookie "sid" "abc" :maxAge d))]
      (is (instance? kotlin.time.Duration (:maxAge m)))
      (is (= d (:maxAge m)))))
  (testing "a value class itself"
    (is (= {:value 200} (kt/data (w/OK w/StatusCode))))))

;; ---------------------------------------------------------------- step 7: kt/reify against the web library

(deftest web-reify-hooks-and-extension
  (let [server (w/Server :listen (InetSocketAddress. 0))
        log (atom [])
        ext (kt/reify w/Extension (.install [this ^web.Server s] (swap! log conj :installed)))]
    (testing "Extension: `install` has two overloads (Server, RouterConfig), so the parameter needs a hint (rule 4)"
      (let [e (try (binding [*ns* (the-ns 'ckway.web-test)] (eval '(kt/reify w/Extension (.install [this server] 1)))
                            nil
                            (catch Throwable t (loop [t t] (if-let [c (.getCause t)] (recur c) (ex-message t))))))]
        (is (str/includes? e "`.install` with 2 parameters is ambiguous") e)
        (is (str/includes? e "^web.Server or ^web.RouterConfig") e)
        (println "WEB reify Extension without a hint ->" e))
      (is (identical? ext (w/.use server ext)))
      (is (= [:installed] @log) "the default install(RouterConfig) body called our install(Server)"))
    (w/.before server (kt/reify w/Before (.before [this ex] (swap! log conj [:before (w/path ex)]))))
    (w/.after server (kt/reify w/After (.after [this ex err] (swap! log conj [:after (w/path ex) (some-> err class .getSimpleName)]))))
    (w/.context server "/t"
                (fn [r]
                  (w/.get r "/hello" (fn [ex] "Hello"))
                  (w/.get r "/fail" (fn [ex] (throw (IllegalStateException. "nope"))))))
    (w/.start server :gracefulStopDelaySec -1)
    (try
      (let [port (.getPort ^InetSocketAddress (w/address server))
            client (HttpClient/newHttpClient)]
        (is (= {:status 200 :body "Hello"} (get! client port "/t/hello")))
        (is (= 400 (:status (get! client port "/t/fail"))))
        (is (await-after log 5))
        (println "WEB reify log:" (pr-str @log))
        (is (some #{[:before "/t/hello"]} @log))
        (is (some #{[:after "/t/hello" nil]} @log) "After ran for a request, without an error")
        (is (some #{[:before "/t/fail"]} @log))
        (is (some #{[:after "/t/fail" "IllegalStateException"]} @log) "and with the error of a failed request"))
      (finally
        (w/.stop server :delaySec 0)
        (.shutdownNow ^java.util.concurrent.ExecutorService (w/workerPool server))))))

(deftest web-reify-job
  (let [log (atom [])
        job (kt/reify jobs/Job (.run [this] (co/delay 10) (swap! log conj :ran)))]
    (testing "web.jobs.Job: a suspend `run` on a Clojure object, and the Kotlin defaults of the properties"
      (is (instance? web.jobs.Job job))
      (is (nil? (jobs/.run job)))
      (is (= [:ran] @log))
      (is (string? (jobs/name job)))
      (println "WEB reify Job name (Kotlin default, this::class.simpleName):" (jobs/name job))
      (is (str/starts-with? (jobs/name job) "R_Job__"))
      (is (false? (jobs/allowParallelRun job)))
      (is (false? (jobs/noTransaction job)) "the default reads an annotation of the class of the object"))
    (testing "overriding a property works"
      (let [j2 (kt/reify jobs/Job (.run [this] nil) (allowParallelRun [this] true) (name [this] "mine"))]
        (is (true? (jobs/allowParallelRun j2)))
        (is (= "mine" (jobs/name j2)))
        (is (false? (jobs/noTransaction j2)))))
    (testing "the calls on the object are static (the form is typed)"
      (let [e (static-proof '(fn [] (let [j (kt/reify jobs/Job (.run [this] nil))] [(jobs/.run j) (jobs/name j) (jobs/allowParallelRun j)])))]
        (is (= "" (:warnings e)) (:warnings e))
        (is (empty? (:dynamic e)))))
    (testing "the JobRunner of the library takes it: runOnce runs the Clojure job on the pool"
      (let [pool (java.util.concurrent.Executors/newScheduledThreadPool 1)
            ran (promise)
            j (kt/reify jobs/Job (.run [this] (deliver ran :ran)) (allowParallelRun [this] true) (noTransaction [this] true))
            runner (jobs/JobRunner pool)]
        (try
          (jobs/.runOnce runner j)
          (is (= :ran (deref ran 3000 :timeout)))
          (finally (.shutdownNow pool)))))))

(deftest web-type-aliases
  (testing "H2: `typealias Headers = com.sun.net.httpserver.Headers`: (w/Headers) constructs the Java class"
    (let [h (w/Headers)]
      (is (instance? com.sun.net.httpserver.Headers h))
      (is (instance? com.sun.net.httpserver.Headers (rt/call-dyn #'w/Headers [] {})))))
  (testing "an alias of a Kotlin class is that class var"
    (is (instance? web.TextBody (w/DefaultRenderer)) "typealias DefaultRenderer = TextBody"))
  (testing "an alias of a function type or of a generic instantiation: type forms and doc, no constructor"
    (is (str/includes? (with-out-str (clojure.repl/doc w/Handler)) "typealias Handler = suspend"))
    (is (str/includes? (with-out-str (clojure.repl/doc w/Params)) "typealias Params = collections.Map<String, String?>"))
    (is (= "kotlin.collections.Map<kotlin.String, kotlin.String?>" (ckway.types/source (ckway.types/resolve-form (the-ns 'ckway.web-test) 'w/Params))))
    (is (str/includes? (ckway.types/source (ckway.types/resolve-form (the-ns 'ckway.web-test) 'w/Handler)) "suspend web.Exchange.() -> kotlin.Any?"))
    (let [m (try (binding [*ns* (the-ns 'ckway.web-test)] (eval '(w/Params))) nil
                 (catch Throwable t (ex-message (loop [t t] (if-let [c (.getCause t)] (recur c) t)))))]
      (is (re-find #"(?s)type alias.*not a class" (str m)) m))))

;; ---------------------------------------------------------------- extra: `redirect(...): Nothing` and `send(code, ...)` with a value class argument

(deftest web-redirect-and-send
  (let [server (w/Server :listen (InetSocketAddress. 0))]
    (w/.context server "/t"
                (fn [r]
                  (w/.get r "/old" (fn [ex] (w/.redirect ex "/t/new")))
                  (w/.get r "/made" (fn [ex] (w/.send ex (w/Created w/StatusCode) "made") nil))))
    (w/.start server :gracefulStopDelaySec -1)
    (try
      (let [port (.getPort ^InetSocketAddress (w/address server))
            client (HttpClient/newHttpClient)
            raw (fn [path] (.send client (request client port path) (HttpResponse$BodyHandlers/ofString)))]
        (let [r (raw "/t/old")]
          (is (= 302 (.statusCode r)))
          (is (= "/t/new" (.orElse (.firstValue (.headers r) "Location") nil))))
        (is (= {:status 201 :body "made"} (get! client port "/t/made"))))
      (finally
        (w/.stop server :delaySec 0)
        (.shutdownNow ^java.util.concurrent.ExecutorService (w/workerPool server))))))
