(ns faraday-atom.core
  "A dynamo atom implementation
   - keys are always encoded as strings
   - maps and vectors are stored natively
   - byte arrays are stored natively as binary blobs
   - numerics are encoded as strings
   - strings are quoted
   - sets/lists are encoded as strings"
  (:require [faraday-atom.impl :as impl]
            [taoensso.faraday :as far])
  (:refer-clojure :exclude [atom])
  (:import (clojure.lang IDeref IAtom)))

(defrecord TableClient [client-opts table key-name])

(defn table-client
  "A table client represents a 'connection' via faraday
  to the given table using the given key-name (hash key column)"
  [client-opts table key-name]
  (map->TableClient {:client-opts client-opts
                     :table       table
                     :key-name    key-name}))

(defn- batch-result-mapping
  [results table key-name]
  (let [read (map impl/read-value (get results table))
        grouped (group-by #(get % key-name) read)]
    (-> (reduce-kv #(assoc! %1 (impl/read-key %2) (impl/read-item (first %3) key-name))
                   (transient {}) grouped)
        persistent!)))

(defn- find-item-mapping*
  [table-client keys opts]
  (when (seq keys)
    (let [{:keys [client-opts table key-name]} table-client
          query (merge opts {:prim-kvs {(impl/write-key key-name) (mapv impl/write-key keys)}})
          results (far/batch-get-item client-opts {table query})]
      (batch-result-mapping results table key-name))))

(defn find-item-mapping
  "Lookups the given coll of `keys` and returns a map
  from key to item. If an item isn't found, the key will be missing from the
  result."
  [table-client keys]
  (find-item-mapping* table-client keys {:consistent? true}))

(defn find-inconsistent-item-mapping
  "Eventually consistent version of `find-item-mapping`"
  [table-client keys]
  (find-item-mapping* table-client keys {:consistent? false}))

(defn- find-items*
  [table-client keys opts]
  (let [rank (into {} (map-indexed (fn [i key] (vector key i)) keys))]
    (->> (sort-by (comp rank key) (seq (find-item-mapping* table-client keys opts)))
         (map val))))

(defn find-items
  "Looks up a the items given by keys.
  Returns a seq of items, keys that could not be found will be omitted.

  Each item will be read consistently."
  [table-client keys]
  (find-items* table-client keys {:consistent? true}))

(defn find-inconsistent-items
  "Eventually consistent version of `find-items`"
  [table-client keys]
  (find-items* table-client keys {:consistent? false}))

(defn find-item
  "Finds the item stored under `key`."
  [table-client key]
  (let [{:keys [key-name]} table-client]
    (-> (impl/find-raw-item table-client key)
        (impl/read-item key-name))))

(defn find-inconsistent-item
  "Eventually consistent version of `find-item`"
  [table-client key]
  (let [{:keys [client-opts table key-name]} table-client]
    (-> (far/get-item client-opts table {(impl/write-key key-name) (impl/write-key key)} {:consistent? false})
        impl/read-value
        (impl/read-item key-name))))

(def ^:dynamic *default-read-throughput*
  "The default amount of read throughput for tables created via `create-table!`"
  8)

(def ^:dynamic *default-write-throughput*
  "The default amount of write throughput for tables created via `create-table!`"
  8)

(defn create-table!
  "Creates a table suitable for storing data according to the encoding scheme."
  ([table-client]
   (create-table! table-client *default-read-throughput* *default-write-throughput*))
  ([table-client read-throughput write-throughput]
   (let [{:keys [client-opts table key-name]} table-client]
     (far/create-table client-opts table
                       [(impl/write-key key-name) :s]
                       {:throughput {:read read-throughput :write write-throughput}}))))
(defn ensure-table!
  "Creates a table suitable for storing data according to the encoding scheme,
  unless it already exists."
  ([table-client]
   (ensure-table! table-client *default-read-throughput* *default-write-throughput*))
  ([table-client read-throughput write-throughput]
   (let [{:keys [client-opts table key-name]} table-client]
     (far/ensure-table client-opts table
                       [(impl/write-key key-name) :s]
                       {:throughput {:read read-throughput :write write-throughput}}))))

(defn put-item!
  "Stores the value under `key`"
  ([table-client key value]
   (let [{:keys [client-opts table key-name]} table-client]
     (far/put-item client-opts table (-> (impl/prepare-value value key-name)
                                         impl/write-value
                                         (impl/add-key key-name key))))))

(defn put-items!
  "Stores each value in `kvs` under its corresponding key.
  `kvs` should be a map or a seq of key value pairs."
  ([table-client kvs]
   (let [{:keys [client-opts table key-name]} table-client
         chunked (partition-all 25 kvs)]
     (doseq [chunk chunked]
       (impl/fixed-batch-write-item client-opts
                                    {table {:put (mapv #(-> (impl/prepare-value (second %) key-name)
                                                            impl/write-value
                                                            (impl/add-key key-name (first %)))
                                                       chunk)}})))))

(defn delete-items!
  "Deletes the items whose key is listed in `keys`"
  [table-client keys]
  (let [{:keys [client-opts table key-name]} table-client
        chunked (partition-all 25 keys)]
    (doseq [chunk chunked]
      (far/batch-write-item client-opts {table {:delete {(impl/write-key key-name)
                                                         (mapv impl/write-key chunk)}}}))))

(defn delete-item!
  "Deletes the item stored under `key`"
  [table-client key]
  (let [{:keys [client-opts table key-name]} table-client]
    (far/delete-item client-opts table {(impl/write-key key-name) (impl/write-key key)})))

(defrecord ItemAtom [table-client key options]
  IDeref
  (deref [this]
    (find-item table-client key))
  IAtom
  (swap [this f]
    (impl/swap-item!* table-client key f options))
  (swap [this f x]
    (swap! this #(f % x)))
  (swap [this f x y]
    (swap! this #(f % x y)))
  (swap [this f x y args]
    (swap! this #(apply f % x y args)))
  (compareAndSet [this old new]
    (swap! this #(if (= old %) new %)))
  (reset [this v]
    (put-item! table-client key v)
    v))

(def deref-printer (get-method print-method IDeref))

(defmethod print-method ItemAtom
  [x writer]
  (deref-printer x writer))

(defn item-atom
  "Returns a clojure.lang.IAtom/IDeref that supports atomic state transition via conditional puts on a single dynamo item.
  In order to use an atom, get started by creating a compatible table via `create-table!` or `ensure-table!`.

  options:
  - `:cas-sleep-ms` the amount of time to wait in the case of contention with other CAS operations (default 500ms)
  - `:cas-timeout-ms` the amount of time that in the case of contention you are willing to retry for.
      if this elapses the `:cas-timeout-val` is returned instead of the result of the `swap!`.
      If you want to retry for ever, use `nil`. (default `nil`)
  - `:cas-timeout-val` the value to return if we timeout due to CAS contention (default `nil`).
  - `:discard-no-op?` if true will not send a CAS request where applying `f` to the input yields the same value as the input.
     (in other words the operation is assumed to have completed immediately, this is safe from a concurrency standpoint).
     (default `true`)"
  ([table-client key]
   (item-atom table-client key nil))
  ([table-client key options]
   (map->ItemAtom
     {:table-client table-client
      :key key
      :options options})))