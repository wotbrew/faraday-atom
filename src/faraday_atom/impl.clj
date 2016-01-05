(ns faraday-atom.impl
  (:require [taoensso.faraday :as far]
            [clojure.edn :as edn]
            [clojure.walk :as walk])
  (:import (com.amazonaws.services.dynamodbv2.model ConditionalCheckFailedException)
           (clojure.lang Keyword IPersistentMap IPersistentVector)))

(defmulti write-value* class)

(derive IPersistentVector ::native)

(derive (Class/forName "[B") ::native)

(defmethod write-value* :default
  [x]
  (when-some [x x]
    (pr-str x)))

(defmethod write-value* ::native
  [x]
  x)

(deftype Key [x])

(defmethod write-value* Key
  [^Key k]
  (pr-str (.x k)))

;;maps are only natively supported if all keys are `named`
(defmethod write-value* IPersistentMap
  [x]
  (reduce-kv #(assoc %1 (if (or (string? %2) (instance? clojure.lang.Named %2))
                          %2
                          (Key. %2)) %3) {} x))

(defmulti read-value* class)

(defmethod read-value* :default
  [x]
  x)

(defmethod read-value* Keyword
  [x]
  (let [ns (namespace x)]
    (if ns
      (edn/read-string (str ns "/" (name x)))
      (edn/read-string (name x)))))

(defmethod read-value* String
  [x]
  (edn/read-string x))

(defn write-value
  "Encodes the given value for dynamo"
  [x]
  (walk/prewalk write-value* x))

(defn read-value
  "Reads an encoded dynamo value"
  [x]
  (walk/postwalk read-value* x))

(defn read-key
  "Reads an encoded dynamo key"
  [x]
  (if (and (vector? x) (= (first x) :atom/key))
    (second x)
    x))

(defn native-key
  [x]
  (if (and (coll? x) (empty? x))
    [:atom/key x]
    x))

(defn write-key
  "Keys must be encoded differently, as regardless
  of the type, they have to be encoded as strings.

  This function will allow you to write a non-string
  key if one wishes."
  [x]
  (pr-str
    (native-key x)))

(defn prepare-value
  "Makes sure the value is a map, makes sure if there is an existing
  value under the encoded`key-name` it is preserved. Should be called
  before `write-value`"
  ([value key-name]
   (if (map? value)
     (let [key-name key-name]
       (if (contains? value key-name)
         (-> value
             (dissoc key-name)
             (assoc :atom/key (get value key-name)))
         value))
     {:atom/value value})))

(defn add-key
  "Writes the (encoded) key to the value"
  [prepared-value key-name key]
  (assoc prepared-value
    (write-key key-name) (write-key key)))

;;hack all the things
(def ^:dynamic *dynamo-list-hack* false)

(alter-var-root #'far/attr-multi-vs
                (fn [f]
                  (fn [x]
                    (if *dynamo-list-hack*
                      (mapv far/clj-item->db-item x)
                      (f x)))))

(defn fixed-batch-write-item
  "To get around an issue with batch-write-item and lists (namely they aren't supported properly)"
  [client-opts request]
  (binding [*dynamo-list-hack* true]
    (let [r (far/batch-write-item client-opts request)]
      (if (seq (:unprocessed r))
        (throw (Exception.
                 (format "Could not successfully write all items to dynamo. failed to write %s items"
                         (count (:unprocessed r)))))
        r))))

(defn find-raw-value
  "Performs a consistent faraday lookup against `key`"
  [table-client key]
  (let [{:keys [client-opts table key-name]} table-client]
    (far/get-item client-opts table {(write-key key-name) (write-key key)} {:consistent? true})))

(defn find-raw-item
  "Finds the item at `key`, will unencode the value but will not
  do any further pruning."
  [table-client key]
  (-> (find-raw-value table-client key)
      read-value))

(defn read-item
  "Takes a value that has been unencoded and returns the original value
  from the prepared map"
  [read-value key-name]
  (when read-value
    (if (contains? read-value :atom/value)
      (:atom/value read-value)
      (cond-> (dissoc read-value (native-key key-name) :atom/key :atom/version)
              (contains? read-value :atom/key) (assoc key-name (get read-value :atom/key))))))

(defn cas-put-item!
  "Overwrites the value under `key` only if
   - The version provided matches the previous version of the item
   - There is not data currently under `key`"
  [table-client key value version]
  (let [{:keys [client-opts table key-name]} table-client
        version-key (write-key :atom/version)]
    (try
      (far/put-item client-opts table (-> (prepare-value value key-name)
                                          write-value
                                          (add-key key-name key)
                                          (assoc version-key (write-value (if version (inc version) 0))))
                    {:expected {version-key (if version [:eq (write-value version)] :not-exists)}})
      true
      (catch ConditionalCheckFailedException e
        false))))

(def ^:dynamic *default-cas-sleep-ms*
  "When we hit contention we will wait this long before attempting the CAS operation
  again by default."
  500)

(defn swap-item!*
  ([table-client key f {:keys [cas-sleep-ms cas-timeout-ms cas-timeout-val discard-no-op?]
                        :as options}]
   (let [cas-sleep-ms (or cas-sleep-ms *default-cas-sleep-ms*)
         discard-no-op? (if (some? discard-no-op?) discard-no-op? true)]
     (if (and cas-timeout-ms (<= 0 cas-timeout-ms))
       cas-timeout-val
       (let [{:keys [key-name]} table-client
             value (find-raw-item table-client key)
             version (:atom/version value)
             input (read-item value key-name)
             result (f input)]
         (cond
           (and discard-no-op? (= input result))
           result
           (cas-put-item! table-client key result version)
           result
           :else (do (when cas-sleep-ms (Thread/sleep cas-sleep-ms))
                     (recur table-client key f
                            {:cas-sleep-ms cas-sleep-ms
                             :cas-timeout-ms (when cas-timeout-ms
                                               (- cas-timeout-ms cas-sleep-ms))
                             :cas-timeout-val cas-timeout-val
                             :discard-no-op? discard-no-op?}))))))))

(defn table-client->options
  [table-client]
  (select-keys
    [:cas-sleep-ms
     :cas-timeout-ms
     :cas-timeout-val
     :discard-no-op?]
    table-client))

(defn swap-item!
  "Applies the function `f` and any `args` to the value currently under `key` storing
  the result. Returns the result."
  ([table-client key f]
   (swap-item!* table-client key f (table-client->options table-client)))
  ([table-client key f & args]
   (swap-item! table-client key #(apply f % args))))