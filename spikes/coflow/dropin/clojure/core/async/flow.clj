;; Drop-in shim (an optional artifact of the coflow spike; nothing else uses it).
;;
;; Put the directory `dropin` BEFORE the core.async jar on the class path. Then
;;   (require '[clojure.core.async.flow :as flow])
;; loads this file instead of the original one, and every public var of `clojure.core.async.flow` is the var of
;; `spike.coflow.flow` (coroutines on kotlinx.coroutines). The namespace name is the original's, so the keywords
;; (::flow/pause ...) and the SPI namespaces (clojure.core.async.flow.spi, ...impl.graph, which are NOT shadowed) are
;; the original's. User code does not change at all, not even its `require`.
;;
;; The original file is under the EPL 1.0. This shim copies no code of it: it only re-publishes the vars of
;; spike.coflow.flow (whose docstrings come from the original) under the original namespace name.

(ns clojure.core.async.flow
  (:require [spike.coflow.flow :as port]))

(set! *warn-on-reflection* true)

(alter-meta! *ns* merge (select-keys (meta (the-ns 'spike.coflow.flow)) [:doc :author]))

;; every public var of the port, with its metadata (doc, arglists), interned here
(doseq [[sym v] (ns-publics 'spike.coflow.flow)]
  (intern *ns* (with-meta sym (select-keys (meta v) [:doc :arglists :added])) (var-get v)))
