(ns faraday-atom.impl-test
  (:require [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :as tc]
            [faraday-atom.impl :refer :all]))


(tc/defspec round-trip-conversion-from-edn-value-to-faraday-value
            25
            (prop/for-all
              [value gen/any]
              (= (read-value (write-value value))
                 value)))

(tc/defspec round-trip-conversion-from-edn-value-to-faraday-item
            25
            (prop/for-all
              [{:keys [value key-name key]} (gen/hash-map :value gen/any
                                                          :key-name gen/any
                                                          :key gen/any)]
              (= (-> (prepare-value value key-name)
                     (write-value)
                     (add-key key-name key)
                     read-value
                     (read-item key-name))
                 value)))

(tc/defspec key-in-value-is-kept
            25
            (prop/for-all
              [{:keys [value key-name key]} (gen/hash-map :value (gen/map gen/any gen/any)
                                                          :key-name gen/any
                                                          :key gen/any)
               key-value gen/any]
              (let [value (assoc value key-name key-value)]
                (= (-> (prepare-value value key-name)
                       (write-value)
                       (add-key key-name key)
                       read-value
                       (read-item key-name))
                   value))))

