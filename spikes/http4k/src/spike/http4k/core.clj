(ns spike.http4k.core
  "Start and stop the Catalog API on a real http4k server (the SunHttp backend of the JDK)."
  (:require [ckway.core :as kt]
            [spike.http4k.api :as api]
            [spike.http4k.domain :as domain])
  (:import [kotlin.jvm.functions Function1])
  (:gen-class))

(set! *warn-on-reflection* true)

(kt/require '[org.http4k.server :as srv])

(def default-config
  "Every key is optional. Port 0 means: any free port."
  {:port 0
   :store-fn domain/memory-store
   :log-fn (fn [msg] (binding [*out* *err*] (println msg)))})

(defn start!
  "Start a server. Returns the system map: :server, :port, :store, :handler, :config.
  Nothing is global: call it twice and you get two independent servers."
  ([] (start! {}))
  ([config]
   (let [{:keys [port store store-fn log-fn] :as config} (merge default-config config)
         store (or store (store-fn))
         ^Function1 handler (api/make-handler store log-fn)
         ;; Kotlin: handler.asServer(SunHttp(port)).start()
         server (srv/.start (srv/.asServer handler (srv/SunHttp (int port))))]
     {:server server
      ;; Kotlin: server.port()
      :port (srv/.port server)
      :store store
      :handler handler
      :config config})))

(defn stop!
  "Stop the server of a system map. Safe to call more than once."
  [{:keys [server]}]
  ;; Kotlin: server.stop()
  (when server (srv/.stop server))
  nil)

(defn -main [& args]
  (let [port (if-let [p (first args)] (parse-long p) 8080)
        system (start! {:port port})]
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable (fn [] (stop! system))))
    (println (str "Catalog API on http://localhost:" (:port system)))
    @(promise)))
