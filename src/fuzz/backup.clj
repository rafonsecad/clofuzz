(ns fuzz.backup
  (:require [clojure.java.io :as io]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql])
  (:import (java.nio.file Files
                          Paths) 
           (java.net URI)
           (java.security MessageDigest)))


(defn get-db-location []
  (let  [path (str (System/getenv "HOME") "/.clofuzz/clofuzz.db")
         _ (io/make-parents path)]
    path))

(def db {:dbtype "sqlite" :dbname (get-db-location)})
(def ds (jdbc/get-datasource db))

(def scannings-table ["CREATE TABLE IF NOT EXISTS scannings (id INTEGER PRIMARY KEY,
                                                             url TEXT,
                                                             header TEXT,
                                                             wordlist TEXT,
                                                             wordlist_hash VARCHAR(64),
                                                             method VARCHAR(10),
                                                             match_codes TEXT,
                                                             exclude_codes TEXT,
                                                             filter_lengths TEXT,
                                                             date TEXT,
                                                             follow_redirects BOOLEAN,
                                                             total INTEGER,
                                                             processed_words INTEGER)"])

(def matches-table ["CREATE TABLE IF NOT EXISTS matches (id INTEGER PRIMARY KEY,
                                                         status INTEGER,
                                                         word VARCHAR(30),
                                                         length INTEGER,
                                                         location TEXT,
                                                         redirections TEXT,
                                                         scanning_id INTEGER,
                                                         FOREIGN KEY (scanning_id) REFERENCES scannings (id))"])

(defn sha256sum [pathfile]
  (let [uri (.toURI (io/as-file (io/file pathfile)))
        path (Paths/get uri)
        data (Files/readAllBytes path)
        hash-data (.digest (MessageDigest/getInstance "SHA-256") data)
        hash-int (new BigInteger 1 hash-data)]
    (.toString hash-int 16)))

(defprotocol BackupProtocol
  (init [_])
  (create-state [_ state stats])
  (update-state [_ id stats])
  (save-match [_ id match]))

(defrecord DataBackup [data-source]
  BackupProtocol
  (init [{:keys [data-source]}]
    (jdbc/execute! data-source scannings-table)
    (jdbc/execute! data-source matches-table))
  (create-state [{:keys [data-source]} state stats]
    (let [scanning (-> (:options state)
                       (assoc :id (:id state))
                       (assoc :date (:date state))
                       (assoc :wordlist-hash (:wordlist-hash state))
                       (assoc :total (:total stats))
                       (assoc :processed-words (:processed-words stats)))]
      (sql/insert! data-source :scannings scanning jdbc/snake-kebab-opts)))

  (update-state [{:keys [data-source]} id stats]
    (sql/update! data-source
                 :scannings
                 (select-keys stats [:total :processed-words]) 
                 {:id id} 
                 jdbc/snake-kebab-opts))

  (save-match [{:keys [data-source]} id match]
    (sql/insert! data-source
                 :matches 
                 (assoc match :scanning-id id) 
                 jdbc/snake-kebab-opts)))
