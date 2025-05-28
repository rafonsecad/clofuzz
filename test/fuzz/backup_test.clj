(ns fuzz.backup-test
  (:require [clojure.test :refer :all]
            [fuzz.backup :as bk]))


(deftest edn-transformation-tests
  (testing "basic data transformation"
    (is (bk/data->file-edn {:options {:url "http://test.com/FUZZ"
                                      :header {}
                                      :wordlist "resources/test-resources/wordlist.txt"
                                      :method "GET"
                                      :match-codes [200]
                                      :exclude-codes [ ]
                                      :filter-lengths []
                                      :follow-redirects false
                                      }
                            :wordlist-hash "1aadf7dafde5ca68f5e5160c9206f7be6f6fc701775cdea30ba01bbb6d8db8ad"} 
                           {})
        {:url "http://test.com/FUZZ"
         :header {}
         :wordlist "resources/test-resources/wordlist.txt"
         :wordlist-hash "1aadf7dafde5ca68f5e5160c9206f7be6f6fc701775cdea30ba01bbb6d8db8ad"
         :method "GET"
         :match-codes [200]
         :exclude-codes [ ]
         :filter-lengths []
         :follow-redirects false
         })))
