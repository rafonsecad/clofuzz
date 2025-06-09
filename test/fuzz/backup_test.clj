(ns fuzz.backup-test
  (:require [clojure.test :refer :all]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [fuzz.backup :as bk]))


(defn with-database [test-fn]
  (bk/init-db)
  (test-fn)
  (jdbc/execute! bk/ds ["DROP TABLE matches"])
  (jdbc/execute! bk/ds ["DROP TABLE scannings"]))

(use-fixtures :once with-database)

(deftest save-state-test
  (testing "save data in db"
    (bk/save-db {:options {:url "http://test.com/FUZZ"
                                              :header {}
                                              :wordlist "test-resources/wordlist.txt"
                                              :method "GET"
                                              :match-codes [200]
                                              :exclude-codes [ ]
                                              :filter-lengths []
                                              :follow-redirects false
                                              }
                                   :wordlist-hash "1aadf7dafde5ca68f5e5160c9206f7be6f6fc701775cdea30ba01bbb6d8db8ad"
                                   :id 1
                                   :date "2025-05-31"} 
                                   {:total 43007 :processed-words 1000}
                                   [{:status 200 :word "index.html" :length 1000}])
    
    (is (= (sql/get-by-id bk/ds :scannings 1 next.jdbc/unqualified-snake-kebab-opts)
           {:url "http://test.com/FUZZ"
            :header "{}" 
            :wordlist "test-resources/wordlist.txt"
            :wordlist-hash "1aadf7dafde5ca68f5e5160c9206f7be6f6fc701775cdea30ba01bbb6d8db8ad"
            :method "GET"
            :match-codes "[200]" 
            :exclude-codes "[]" 
            :filter-lengths "[]" 
            :follow-redirects 0
            :id 1
            :date "2025-05-31"
            :total 43007
            :processed-words 1000}))
    
    (is (= (sql/find-by-keys bk/ds :matches {:scanning-id 1} next.jdbc/unqualified-snake-kebab-opts)
           [{:id 1 :status 200 :word "index.html" :length 1000 :scanning-id 1}]))))


;(run-tests)
