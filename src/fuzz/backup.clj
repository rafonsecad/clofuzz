(ns fuzz.backup
  (:import (java.nio.file Files
                          Paths) 
           (java.net URI)
           (java.security MessageDigest)))

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

(defn data->file-edn [state stats matches]
  (let [edn (:options state)]
    (assoc edn :wordlist-hash (:wordlist-hash state))
    (assoc edn :stats stats)
    (assoc-in  edn [:stats :matches] matches)))

(def FileBackup
  (reify BackupProtocol
    (save [_ state stats] nil)))
