(ns ckway.co.vt
  "Starts a virtual thread. Its own namespace, so that ckway.co loads on a JDK without virtual threads."
  (:import [java.lang Thread$Builder$OfVirtual]))

(set! *warn-on-reflection* true)

(defn start!
  "Start `r` (a Runnable) on a new virtual thread."
  [^Runnable r]
  (.start (Thread/ofVirtual) r))
