(ns fuzz.backup-test
  (:require [clojure.test :refer :all]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [fuzz.backup :as bk]))


(def db-test {:dbtype "sqlite" :dbname "test-resources/clofuzz.db"})
(def ds-test (jdbc/get-datasource db-test))


(def data-record (bk/->DataBackup ds-test))

(defn with-database [test-fn]
  (bk/init data-record)
  (test-fn)
  (jdbc/execute! ds-test ["DROP TABLE matches"])
  (jdbc/execute! ds-test ["DROP TABLE scannings"]))

(use-fixtures :once with-database)

(deftest save-state-test
  (testing "save data in db"
    (bk/create-state data-record 
                     {:options {:url "http://test.com/FUZZ"
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
                     {:total 43007 :processed-words 1000})

    (is (= (sql/get-by-id ds-test :scannings 1 next.jdbc/unqualified-snake-kebab-opts)
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
            :processed-words 1000})))

  (testing "update state in db"
    (bk/update-state data-record 1 {:total 43007 :processed-words 2000})
    
    (is (= (sql/get-by-id ds-test :scannings 1 next.jdbc/unqualified-snake-kebab-opts)
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
            :processed-words 2000})))

  (testing "save match in db"
    (bk/save-match data-record 1 {:status 200 :word "index.html" :length 1000})

    (is (= (sql/find-by-keys ds-test :matches {:scanning-id 1} next.jdbc/unqualified-snake-kebab-opts)
           [{:id 1 :status 200 :word "index.html" :length 1000 :location nil :redirections nil :scanning-id 1}]))))


;;(run-tests)
