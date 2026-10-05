(ns spike.ktor.reified-test
  "The `inline reified` forms of Ktor with `:<>` (needs the :kotlinc alias; bin/test adds it)."
  (:require [ckway.core :as kt]
            [clojure.test :refer [deftest is]]
            [spike.ktor.http-helper :refer [request]])
  (:import (io.ktor.server.application Application)))

(set! *warn-on-reflection* true)

(kt/require '[io.ktor.server.engine :as eng]
            '[io.ktor.server.cio :as cio]
            '[io.ktor.server.application :as app]
            '[io.ktor.server.routing :as rt]
            '[io.ktor.server.response :as resp]
            '[io.ktor.server.request :as req]
            '[io.ktor.server.plugins.statuspages :as sp]
            '[io.ktor.http :as http])

(defn- reified-module [^Application application]
  (app/.install application (sp/StatusPages)
                (fn [cfg]
                  ;; Kotlin: exception<Throwable> { call, cause -> call.respondText("reified 500", status = 500) }
                  (sp/.exception cfg
                                 (fn [call _cause]
                                   (resp/.respondText call "reified 500" :status (http/InternalServerError http/HttpStatusCode)))
                                 :<> Throwable)))
  (rt/.routing application
               (fn [r]
                 ;; Kotlin: post("/echo") { val s = call.receive<String>(); call.respond(s) }
                 (rt/.post r "/echo"
                           (fn [ctx]
                             (let [call (rt/call ctx)
                                   s (req/.receive call :<> String)]
                               (resp/.respond call s :<> String))))
                 ;; Kotlin: post("/created") { call.respond(HttpStatusCode.Created, call.receive<String>()) }
                 (rt/.post r "/created"
                           (fn [ctx]
                             (let [call (rt/call ctx)]
                               (resp/.respond call (http/Created http/HttpStatusCode) (req/.receive call :<> String) :<> String))))
                 (rt/.get r "/boom" (fn [_ctx] (throw (ex-info "boom" {})))))))

(deftest reified-calls
  (let [server (eng/embeddedServer cio/CIO :port 0 :host "127.0.0.1" :module reified-module)]
    (eng/.start server :wait false)
    (try
      (let [system {:port (long (eng/port (first (eng/.resolvedConnectors (eng/engine server)))))}]
        (is (= [200 "hello"] ((juxt :status :body) (request system "POST" "/echo" "hello"))))
        (is (= [201 "hello"] ((juxt :status :body) (request system "POST" "/created" "hello"))))
        (is (= [500 "reified 500"] ((juxt :status :body) (request system "GET" "/boom")))))
      (finally (eng/.stop server 100 1000)))))
