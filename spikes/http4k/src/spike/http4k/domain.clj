(ns spike.http4k.domain)

(defprotocol ProductStore
  (all-products [store])
  (product [store id])
  (add-product! [store data])
  (delete-product! [store id]))

(defn validate [{:keys [name price tags]}]
  (cond (not (and (string? name) (seq name))) "name must be a non-empty string"
        (not (and (integer? price) (not (neg? price)))) "price must be a non-negative integer (cents)"
        (not (and (or (nil? tags) (sequential? tags)) (every? string? tags))) "tags must be a list of strings"))

(defn memory-store []
  (let [state (atom {:next-id 1 :items (sorted-map)})]
    (reify ProductStore
      (all-products [_] (vec (vals (:items @state))))
      (product [_ id] (get-in @state [:items id]))
      (add-product! [_ data]
        (let [s (swap! state (fn [{:keys [next-id] :as s}]
                               (-> s (assoc-in [:items next-id] (assoc (select-keys data [:name :price :tags]) :id next-id))
                                   (assoc :last next-id) (update :next-id inc))))]
          (get-in s [:items (:last s)])))
      (delete-product! [_ id]
        (let [[old _] (swap-vals! state update :items dissoc id)] (contains? (:items old) id))))))
