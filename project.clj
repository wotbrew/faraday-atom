(defproject mixradio/faraday-atom "0.3.2-SNAPSHOT"
  :description "Provides a clojure.lang.IAtom for DynamoDB via faraday."
  :url "http://github.com/mixradio/faraday-atom"
  :license "https://github.com/mixradio/faraday-atom/blob/master/LICENSE"
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [com.taoensso/faraday "1.8.0"]]
  :profiles {:dev         {:dependencies   [[org.clojure/test.check "0.7.0"]]
                           :plugins        [[codox "0.8.11"]]
                           :resource-paths ["test-resources"]
                           :codox          {:src-dir-uri               "http://github.com/mixradio/faraday-atom/blob/master/"
                                            :src-linenum-anchor-prefix "L"
                                            :defaults                  {:doc/format :markdown}}}
             :dynamo-test {:plugins        [[lein-dynamodb-local "0.2.6"]]
                           :dynamodb-local {:in-memory? true}}}

  :test-selectors {:default (complement
                              (some-fn :dynamo))
                   :dynamo :dynamo}
  :aliases
  {"test-dynamo"
   ["do" ["with-profiles" "+dev,+dynamo-test" "dynamodb-local" "test" ":dynamo"]]})
