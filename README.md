# faraday-atom

*An atom implementation for Amazon DynamoDB backed by [faraday](https://github.com/ptaoussanis/faraday).*

- Atomic context around a single dynamo item.
- Durable atom, persists after restarts.
- Transitions via CAS using dynamo's conditional put, safe for concurrent updates.
- Encodes data such that all edn data is supported yet indexes are still possible.
- Due to encoding the console is still usable and your data is human readable, though byte-arrays are also supported.

API docs can be found [here](http://mixradio.github.io/faraday-atom)

## Why use atoms beyond a single process?

Using a durable atom as your state primitive is of course useful to support clojure's epochal time model at scale in distributed systems.
It surfaces the notion of state as snapshots in time that transition via pure functions. So if you care about keeping more of your code pure this library can help!

It can enable certain guarantees about the correctness of your system by helping avoid race conditions and performing co-ordination services.

**faraday-atom** gives you atom semantics for state that is shared across many machines, or needs to be durable. 

As it is of course much slower than a local atom you want to use this for state that perhaps changes 10 times a second rather than 1000 times.

## Why not zookeeper?

Zookeeper is the obvious candidate for implementing the atom model as seen in [avout](http://github.com/liebke/avout).

Dynamo is better suited for large amounts of state that needs strong durability and availability. You can represent an unlimited amount of atoms for a single table
so you can use it for database level use cases. 

Dynamo can scale to effectively unlimited throughput, and its easy to do so (amazon does all the work!).

Dynamo is very convenient from an operational perspective and therefore may be a more cost-effective and easy solution if you do not already have zookeeper deployed and running.

On the other hand Zookeeper will likely be faster in terms of latency and can be used to implement more features like watches.

## Usage

Include in your lein `project.clj`

```clojure
[mixradio/faraday-atom "0.3.0"]
```

Require `faraday-atom.core` to get started

```clojure
(require '[faraday-atom.core :as dynamo])
```

Use `create-table!` to create a table which will be the durable store for your atoms.

```clojure
(def client-opts
  {;;; For DDB Local just use some random strings here, otherwise include your
   ;;; production IAM keys:
   :access-key "<AWS_DYNAMODB_ACCESS_KEY>"
   :secret-key "<AWS_DYNAMODB_SECRET_KEY>"

   ;;; You may optionally override the default endpoint if you'd like to use DDB
   ;;; Local or a different AWS Region (Ref. http://goo.gl/YmV80o), etc.:
   ;; :endpoint "http://localhost:8000"                   ; For DDB Local
   ;; :endpoint "http://dynamodb.eu-west-1.amazonaws.com" ; For EU West 1 AWS region
  })
  
(def table-client 
  (dynamo/table-client client-opts
    ;;the name of the table as a keyword
    :test-atom-table
    ;;this is the key we will use to store atom identity in the table.
    :atom/id))

;; Creates the table with a default read/write throughput of 8/8.
(dynamo/create-table! table-client)
```

Use `item-atom` to get an atom for a dynamo item.

```clojure
;;this will get me an atom implementation for the item given by the key :foo.
(def foo-atom (dynamo/item-atom table-client :foo))
```

Use `swap!`, `deref`, `@` and `reset!` as you normally would. An exception will be raised for errors thrown by faraday 
or amazon except for `ConditionalCheckFailedException` in which case the operation is retried after a user configurable sleep period.
To configure the retry parameters see the `item-atom` doc string.

```clojure
(reset! foo-atom #{1 2 3})
;; => #{1 2 3}

@foo-atom
;; => #{1 2 3}

(swap! foo-atom conj 4)
;; => #{1 2 3 4}

@foo-atom
;; => #{1 2 3 4}
```

Many other helper operations for doing batch reads and puts to atom tables are provided e.g `find-items`, `put-items!`.

## License

Copyright © 2015 MixRadio

[faraday-atom is released under the 3-clause license ("New BSD License" or "Modified BSD License").](http://github.com/mixradio/faraday-atom/blob/master/LICENSE)
