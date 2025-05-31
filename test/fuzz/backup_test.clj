(ns fuzz.backup-test
  (:require [clojure.test :refer :all]
            [fuzz.backup :as bk]))


(deftest edn-transformation-tests
  (testing "basic data transformation"
    (is (bk/data->file-edn {:options {:url "http://test.com/FUZZ"
                                      :header {}
                                      :wordlist "test-resources/wordlist.txt"
                                      :method "GET"
                                      :match-codes [200]
                                      :exclude-codes [ ]
                                      :filter-lengths []
                                      :follow-redirects false
                                      }
                            :wordlist-hash "1aadf7dafde5ca68f5e5160c9206f7be6f6fc701775cdea30ba01bbb6d8db8ad"} 
                           {:total 43007 :processed-words 1000}
                           [{:status 200 :word "index.html" :length 1000}])
        {:url "http://test.com/FUZZ"
         :header {}
         :wordlist "test-resources/wordlist.txt"
         :wordlist-hash "1aadf7dafde5ca68f5e5160c9206f7be6f6fc701775cdea30ba01bbb6d8db8ad"
         :method "GET"
         :match-codes [200]
         :exclude-codes [ ]
         :filter-lengths []
         :follow-redirects false
         :stats {:total 43007
                 :processed-words 1000
                 :matches [{:status 200 :word "index.html" :length 1000}]}})))

(deftest save-edn-test
  (testing "saving edn"
    (is (slurp ".clofuzz/test.com/2025-05-31-1")
        (bk/save {:options {:url "http://test.com/FUZZ"
                            :header {}
                            :wordlist "test-resources/wordlist.txt"
                            :method "GET"
                            :match-codes [200]
                            :exclude-codes [ ]
                            :filter-lengths []
                            :follow-redirects false
                            }
                  :wordlist-hash "1aadf7dafde5ca68f5e5160c9206f7be6f6fc701775cdea30ba01bbb6d8db8ad"} 
                 {:total 43007 :processed-words 1000}
                 [{:status 200 :word "index.html" :length 1000}]))))

;(run-tests)
