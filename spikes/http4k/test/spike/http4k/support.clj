(ns spike.http4k.support
  "Helpers for the tests: build a request, read a response, capture the log."
  (:require [ckway.core :as kt]
            [clojure.data.json :as json])
  (:import [kotlin.jvm.functions Function1]
           [org.http4k.core Method Request Response]))

(set! *warn-on-reflection* true)

(kt/require '[org.http4k.core :as h])

(defn request
  "Kotlin: Request(method, uri).body(body)"
  (^Request [^Method method ^String uri]
   (h/Request method uri))
  (^Request [^Method method ^String uri ^String body]
   (h/.body (h/Request method uri) body)))

(defn handle
  "Call the handler in memory, as http4k tests do. No socket. Returns a plain map."
  ([handler method uri] (handle handler method uri nil))
  ([^Function1 handler method uri body]
   (let [^Response resp (h/.invoke handler (if body (request method uri body) (request method uri)))
         text (h/.bodyString resp)]
     {:status (h/code (h/status resp))
      :body-text text
      :body (when (seq text) (json/read-str text :key-fn keyword))
      :content-type (h/.header resp "Content-Type")
      :location (h/.header resp "Location")})))

(defn json-body [m] (json/write-str m))

(defn log-collector
  "A log function that keeps lines in an atom. Returns [log-fn lines-atom]."
  []
  (let [lines (atom [])]
    [(fn [line] (swap! lines conj line)) lines]))
