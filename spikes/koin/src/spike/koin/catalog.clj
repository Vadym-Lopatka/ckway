(ns spike.koin.catalog
  "The Catalog service. Pure Clojure: it knows nothing of Koin."
  (:require [spike.koin.domain :as d]))

(set! *warn-on-reflection* true)

(defprotocol CatalogService
  (list-products [svc tag] "All products, or only those with `tag` (nil means all). At most `page-size`.")
  (get-product [svc id] "The product, or nil.")
  (add-product! [svc data] "Validates, stores, returns the new product. Throws ex-info {:type :invalid} on bad data.")
  (delete-product! [svc id] "True if the product existed.")
  (audit [svc] "The log of changes: a vector of {:op :id :at}."))

(defn make-service
  "`store` is a ProductStore. `now` is a function of no arguments that gives the time.
  `page-size` is the largest list that `list-products` returns."
  [store now page-size]
  (let [log (atom [])
        record! (fn [op id] (swap! log conj {:op op :id id :at (now)}))]
    (reify CatalogService
      (list-products [_ tag]
        (->> (d/all-products store)
             (filter #(or (nil? tag) (some #{tag} (:tags %))))
             (take page-size)
             vec))
      (get-product [_ id] (d/product store id))
      (add-product! [_ data]
        (when-let [problem (d/validate data)]
          (throw (ex-info (str "invalid product: " problem) {:type :invalid :problem problem :data data})))
        (let [p (d/add-product! store data)]
          (record! :add (:id p))
          p))
      (delete-product! [_ id]
        (let [deleted? (d/delete-product! store id)]
          (when deleted? (record! :delete id))
          deleted?))
      (audit [_] @log))))
