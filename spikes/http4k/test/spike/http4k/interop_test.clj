(ns spike.http4k.interop-test
  "Pins the ckway forms that the first spike had to work around. A ckway regression shows up here."
  (:require [ckway.core :as kt]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import [kotlin.jvm.functions Function1]
           [org.http4k.core Request Response]
           [org.http4k.routing RoutingHttpHandler]))

(set! *warn-on-reflection* true)

(kt/require '[org.http4k.core :as h]
            '[org.http4k.routing :as r])

(def ^:private OK (h/OK h/Status))

(defn- ok-handler ^RoutingHttpHandler [^String path]
  (r/.to (r/.bind path h/Method.GET) (fn [_req] (h/Response OK))))

(def ^:private one-handler (ok-handler "/a"))

(deftest row-1-request-and-response-constructors
  (let [req (h/Request h/Method.GET "/a")
        resp (h/Response OK)]
    (is (instance? Request req))
    (is (instance? Response resp))
    (is (= "/a" (str (h/uri req))))
    (is (= 200 (h/code (h/status resp))))
    (is (instance? Request (h/.invoke h/Request h/Method.GET "/a")) "the old form still works")))

(deftest row-2-companion-property
  (is (= 404 (h/code (h/NOT_FOUND h/Status)))))

(deftest row-3-invoke-a-routing-handler
  (let [handler (ok-handler "/a")
        resp (h/.invoke handler (h/Request h/Method.GET "/a"))]
    (is (= 200 (h/code (h/status resp))))))

(deftest row-4-routes
  (let [^RoutingHttpHandler a (ok-handler "/a") ^RoutingHttpHandler b (ok-handler "/b")]
    (is (instance? RoutingHttpHandler (r/routes a b)))
    (is (instance? RoutingHttpHandler (r/routes :list [a b])))
    (is (= 200 (h/code (h/status (h/.invoke ^RoutingHttpHandler (r/routes :list [a b])
                                            (h/Request h/Method.GET "/b"))))))
    (let [msg (try (binding [*ns* (the-ns 'spike.http4k.interop-test)] (eval '(r/routes one-handler))) nil
                   (catch Throwable e (ex-message (or (ex-cause e) e))))]
      (is (some? msg))
      (is (str/includes? msg "ambiguous"))
      (is (str/includes? msg "(routes :list ...)") "the way out for the function")
      (is (str/includes? msg "a function and a property are both named `routes`"))
      (is (str/includes? msg "((kt/ref X routes) x)") "the way out for the property"))))

(deftest row-6-reify-returns-a-plain-fn
  (let [filter (kt/reify h/Filter
                 (.invoke [_ ^Function1 next] (fn [req] (h/.invoke next req))))
        ^RoutingHttpHandler handler (h/.then ^org.http4k.core.Filter filter (ok-handler "/a"))]
    (is (= 200 (h/code (h/status (h/.invoke handler (h/Request h/Method.GET "/a"))))))))

(deftest row-7-noop-filter
  (let [noop (h/NoOp h/Filter)
        ^RoutingHttpHandler handler (h/.then ^org.http4k.core.Filter noop (ok-handler "/a"))]
    (is (= 200 (h/code (h/status (h/.invoke handler (h/Request h/Method.GET "/a"))))))))

(deftest row-8-literal-receiver-is-static
  ;; Compiling this file with *warn-on-reflection* shows no warning; here we check the value.
  (is (instance? RoutingHttpHandler (r/.to (r/.bind "/health" h/Method.GET) (fn [_] (h/Response OK))))))
