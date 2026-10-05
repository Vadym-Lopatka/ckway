(ns spike.ktor.http-helper
  "A small HTTP client for the tests (java.net.http), and a macro that runs a body with a started server."
  (:require [clojure.data.json :as json]
            [spike.ktor.core :as core])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.time Duration)))

(set! *warn-on-reflection* true)

(defn request
  "Sends a request to the system. Returns {:status :body :headers :json}. `:json` is the parsed body, or nil."
  ([system method path] (request system method path nil))
  ([system method path body]
   (let [client (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 5)) .build)
         publisher (if body
                     (HttpRequest$BodyPublishers/ofString ^String body)
                     (HttpRequest$BodyPublishers/noBody))
         req (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" (:port system) path)))
                 (.timeout (Duration/ofSeconds 10))
                 (.method ^String method publisher)
                 .build)
         resp (.send client req (HttpResponse$BodyHandlers/ofString))
         text (str (.body resp))]
     {:status (.statusCode resp)
      :body text
      :location (.orElse (.firstValue (.headers resp) "Location") nil)
      :content-type (.orElse (.firstValue (.headers resp) "Content-Type") nil)
      :json (when (seq text) (try (json/read-str text :key-fn keyword) (catch Exception _ nil)))})))

(defn with-system*
  "Starts a server with `config`, calls (f system), and always stops the server."
  [config f]
  (let [system (core/start! config)]
    (try (f system)
         (finally (core/stop! system)))))

(defmacro with-system [[sym config] & body]
  `(with-system* ~config (fn [~sym] ~@body)))
