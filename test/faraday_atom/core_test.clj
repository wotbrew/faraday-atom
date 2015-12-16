(ns faraday-atom.core-test
  (:require [clojure.test :refer :all]
            [faraday-atom.core :refer :all]
            [clojure.test.check.clojure-test :as tc]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.generators :as gen]))

(def local-client-opts
  {:endpoint "http://localhost:8000"})

(def complex-key-name
  {:fred #{1 2 3} :bar [1 {:x :y :z "hello"}]})

(defonce table-counter (atom 0))

(defn test-client
  []
  (let [cl (table-client local-client-opts (keyword (str "test" (swap! table-counter inc))) complex-key-name)]
    (ensure-table! cl)
    cl))

(tc/defspec ^:dynamo round-trip-put-and-find
            25
            (let [client (test-client)]
              (prop/for-all
                [{:keys [value key]} (gen/resize 10 (gen/hash-map :value gen/any :key gen/any))]
                (put-item! client key value)
                (= value
                   (find-item client key)))))

(tc/defspec ^:dynamo round-trip-put-many-and-find-many
            25
            (let [client (test-client)]
              (prop/for-all
                [kvs (gen/resize 10 (gen/map (gen/resize 10 gen/any) (gen/resize 10 gen/any)))]
                (put-items! client kvs)
                (= (set (vals kvs))
                   (set (find-items client (keys kvs)))))))

(tc/defspec ^:dynamo every-inconsistent-find-refers-to-a-previous-put
            25
            (let [client (test-client)
                  previous (atom {})]
              (prop/for-all
                [{:keys [value key]} (gen/resize 10 (gen/hash-map :value gen/any :key gen/any))]
                (put-item! client key value)
                (swap! previous update key (fnil conj #{}) value)
                (contains? (get @previous key)
                           (find-inconsistent-item client key)))))

(tc/defspec ^:dynamo every-inconsistent-find-refers-to-a-previous-put-many
            25
            (let [client (test-client)
                  previous (atom {})]
              (prop/for-all
                [kvs (gen/resize 10 (gen/map (gen/resize 10 gen/any) (gen/resize 10 gen/any)))]
                (put-items! client kvs)
                (swap! previous #(reduce (fn [m [k v]] (update m k (fnil conj #{}) v)) %1 kvs))
                (let [items (find-inconsistent-item-mapping client (keys kvs))]
                  (every? #(contains? (get @previous (first %)) (second %)) items)))))

(deftest ^:dynamo concurrently-updating-counter-results-in-sum-of-inc-applications
  (let [client (test-client)
        atom (item-atom client :counter {:cas-sleep-ms 1})
        threads (doall (repeatedly 20 #(future (swap! atom (fnil inc 0)))))]
    (doall (map deref threads))
    (is (= (count threads) @atom))))