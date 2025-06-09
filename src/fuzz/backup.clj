(ns fuzz.backup
  (:require [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql])
  (:import (java.nio.file Files
                          Paths) 
           (java.net URI)
           (java.security MessageDigest)))

(def db {:dbtype "sqlite" :dbname "clofuzz.db"})
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
                                                         scanning_id INTEGER,
                                                         FOREIGN KEY (scanning_id) REFERENCES scannings (id))"])

(defn sha256sum [pathfile]
  (let [uri (new URI (str "file://" pathfile))
        path (Paths/get uri)
        data (Files/readAllBytes path)
        hash-data (.digest (MessageDigest/getInstance "SHA-256") data)
        hash-int (new BigInteger 1 hash-data)]
    (.toString hash-int 16)))

(defprotocol BackupProtocol
  (save [_ state stats]))


(defn get-home []
  (System/getenv "HOME"))

(defn get-pwd []
  (System/getenv "PWD"))

(defn init-db []
  (jdbc/execute! ds scannings-table)
  (jdbc/execute! ds matches-table))

(defn data->table-edn [state stats matches]
    [ (-> (:options state)
          (assoc :id (:id state))
          (assoc :date (:date state))
          (assoc :wordlist-hash (:wordlist-hash state))
          (assoc :total (:total stats))
          (assoc :processed-words (:processed-words stats)))

     (map #(assoc % :scanning-id (:id state)) matches)])

(defn save-db [state stats matches]
  (let [[scanning matches-db] (data->table-edn state stats matches)]
    (sql/insert! ds :scannings scanning jdbc/snake-kebab-opts)
    (sql/insert-multi! ds :matches matches-db jdbc/snake-kebab-opts)))

(def DataBackup
  (reify BackupProtocol
    (save [_ state stats] nil)))
