(ns spike.ktor.interop-test
  "Pins the ckway forms that the spike needed a fix for, so that a regression of ckway shows up here:
  the trailing lambda after a vararg, the lambda parameter hint that chooses an overload (Ktor `status`),
  the compile-time ambiguity error, the return hint of a defn, the trailing lambda after skipped defaults."
  (:require [ckway.core :as kt]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [spike.ktor.http-helper :refer [request]])
  (:import (io.ktor.client HttpClient)
           (io.ktor.server.application Application ApplicationCall)
           (io.ktor.server.engine EmbeddedServer)
           (io.ktor.server.plugins.statuspages StatusPagesConfig StatusPagesConfig$StatusContext)))

(set! *warn-on-reflection* true)

(kt/require '[io.ktor.server.engine :as eng]
            '[io.ktor.server.cio :as cio]
            '[io.ktor.server.application :as app]
            '[io.ktor.server.routing :as rt]
            '[io.ktor.server.response :as resp]
            '[io.ktor.server.plugins.statuspages :as sp]
            '[io.ktor.client.request :as creq]
            '[io.ktor.http :as http])

(defn- compile-error
  "The message of the `kt:` error that evaluating `form` gives at compile time, or nil."
  [form]
  (binding [*ns* (the-ns 'spike.ktor.interop-test)]
    (try (eval form) nil
         (catch Throwable e (ex-message (or (ex-cause e) e))))))

(defn- reflection-warnings
  "The text that the compiler writes to *err* while it compiles `form`."
  [form]
  (let [w (java.io.StringWriter.)]
    (binding [*ns* (the-ns 'spike.ktor.interop-test) *err* w *warn-on-reflection* true]
      (eval form))
    (str w)))

(defn- with-status-server
  "Runs (f port) on a server whose StatusPages config is `configure` (a fn of the StatusPagesConfig)."
  [configure f]
  (let [server (eng/embeddedServer cio/CIO 0 "127.0.0.1"
                                   (fn [^Application a]
                                     (app/.install a (sp/StatusPages) configure)
                                     (rt/.routing a (fn [r]
                                                      (rt/.get r "/health" (fn [ctx] (resp/.respondText (rt/call ctx) "ok")))))))]
    (eng/.start server :wait false)
    (try (f {:port (long (eng/port (first (eng/.resolvedConnectors (eng/engine server)))))})
         (finally (eng/.stop server 0 100)))))

(deftest status-trailing-lambda-and-parameter-hint
  (testing "^ApplicationCall selects the overload whose handler gets the call"
    (let [seen (atom nil)]
      (with-status-server
        (fn [^StatusPagesConfig cfg]
          ;; Kotlin: status(HttpStatusCode.NotFound) { call, status -> call.respondText("nf", status = status) }
          (sp/.status cfg (http/NotFound http/HttpStatusCode)
                      (fn [^ApplicationCall call status]
                        (reset! seen (class call))
                        (resp/.respondText call "nf" :status status))))
        (fn [s]
          (let [r (request s "GET" "/nope")]
            (is (= [404 "nf"] [(:status r) (:body r)]))
            (is (isa? @seen ApplicationCall)))))))
  (testing "^StatusContext selects the other overload"
    (let [seen (atom nil)]
      (with-status-server
        (fn [^StatusPagesConfig cfg]
          (sp/.status cfg (http/NotFound http/HttpStatusCode)
                      (fn [^StatusPagesConfig$StatusContext ctx _status]
                        (reset! seen (class ctx)))))
        (fn [s]
          (request s "GET" "/nope")
          (is (= StatusPagesConfig$StatusContext @seen))))))
  (testing "two codes are a vararg before the lambda"
    (with-status-server
      (fn [^StatusPagesConfig cfg]
        (sp/.status cfg (http/NotFound http/HttpStatusCode) (http/MethodNotAllowed http/HttpStatusCode)
                    (fn [^ApplicationCall call status] (resp/.respondText call "both" :status status))))
      (fn [s]
        (is (= [404 "both"] ((juxt :status :body) (request s "GET" "/nope"))))
        (is (= [405 "both"] ((juxt :status :body) (request s "POST" "/health"))))))))

(deftest status-without-hint-is-a-compile-error
  (let [msg (compile-error
             '(fn [^Application a]
                (app/.install a (sp/StatusPages)
                              (fn [^StatusPagesConfig cfg]
                                (sp/.status cfg (http/NotFound http/HttpStatusCode) (fn [call status] nil))))))]
    (is (some? msg) "a compile error, not a run-time one")
    (is (str/includes? msg "is ambiguous"))
    (is (str/includes? msg "They differ only in the parameter types of a lambda"))
    (is (str/includes? msg "(fn [^io.ktor.server.application.ApplicationCall x y] ...)") "the way out names the hint")
    (is (str/includes? msg ".statusWithContext x y z)"))))

(deftest untyped-config-parameter-is-a-run-time-error
  ;; `cfg` of `(fn [cfg] ...)` at `.install` has no static type (kt does not infer the type argument of
  ;; `(sp/StatusPages)`), so the hint of the lambda is not seen: the call is "ambiguous" when it runs.
  (is (nil? (compile-error '(fn [^Application a]
                              (app/.install a (sp/StatusPages)
                                            (fn [cfg] (sp/.status cfg (http/NotFound http/HttpStatusCode) (fn [^ApplicationCall c s] nil)))))))
      "it compiles: the error comes only when the code runs, and a reflection warning is the sign")
  (is (str/includes?
       (reflection-warnings '(fn [^Application a]
                               (app/.install a (sp/StatusPages)
                                             (fn [cfg] (sp/.status cfg (http/NotFound http/HttpStatusCode) (fn [^ApplicationCall c s] nil))))))
       "Reflection warning")))

(defn- u ^String [s] (str "http://127.0.0.1:1" s))
(defn- u-untyped [s] (str "http://127.0.0.1:1" s))

(deftest defn-return-hint-is-used
  (is (= "" (reflection-warnings '(fn [^HttpClient c] (creq/.get c (u "/x")))))
      "a return hint on a defn gives a direct call")
  (is (str/includes? (reflection-warnings '(fn [^HttpClient c] (creq/.get c (u-untyped "/x"))))
                     "can't be resolved statically")
      "no hint: the dynamic path, with a warning (a defn parameter or result without a hint has no type)"))

(deftest trailing-lambda-after-skipped-defaults
  ;; Kotlin: embeddedServer(CIO, 0, "127.0.0.1") { }   (`watchPaths` is skipped, the lambda is `module`)
  (let [server (eng/embeddedServer cio/CIO 0 "127.0.0.1" (fn [^Application _a] nil))]
    (is (instance? EmbeddedServer server)))
  (testing "after a named argument a positional one is still an error"
    (is (str/includes? (compile-error '(eng/embeddedServer cio/CIO :port 0 (fn [^Application _a] nil)))
                       "follows a named argument"))))
