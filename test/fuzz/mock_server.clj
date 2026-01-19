(ns fuzz.mock-server
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [ring.adapter.jetty :as jetty]
            [clojure.pprint :as pprint]
            [clojure.tools.cli :refer [parse-opts]]))

(def default-endpoints (-> (slurp "test-resources/default-server.edn")
                           edn/read-string))

(defn parse-mc [codes]
  (->> (str/split codes #",")
       (mapv #(Integer/parseInt %))))


(def cli-options [["-p" "--paths NUMBER OF PATHS" "Number of paths to be created with the server. The path names will be randomly selected from the wordlist"
                   :default 0
                   :parse-fn #(Integer/parseInt %)]

                  [nil "--with-codes HTTP CODES" "http response code list separated by comma that will be asociated randomly to each path"
                   :default [200 204 301 302 307 401 403 405 500]
                   :parse-fn parse-mc
                   :validate [(fn [v]
                                (every? #(< 100 % 600) v))

                              (fn [v] 
                                (->> (filter #(not (< 100 % 600)) v)
                                     (str/join ",") 
                                     (str "invalid http response code(s): ")))]]
                  
                  ["-w" "--wordlist WORDLIST" "wordlist file path"
                   :default "test-resources/wordlist.txt"
                   :validate [#(.exists (io/file %)) "can't find wordlist"]]

                  ["-h" "--help" "prints help"]])

(defn generate-handler [endpoints]
  (letfn [(match-path? [{:keys [uri]}]
            (seq (filter #(= uri (:path %)) endpoints)))
          
          (send-response [{:keys [uri]}]
            (-> (filter #(= uri (:path %)) endpoints)
                first
                (select-keys [:status :body])))]

    (fn [request]
      (if (match-path? request)
        (send-response request)
        
        {:status 404}))))
  
(defn generate-custom-endpoints [{:keys [paths with-codes wordlist]}]
  (let [words (str/split (slurp wordlist) #"\n")

        endpoints (for [_ (range paths)]
                    {:path (str "/" (get words (rand-int (count words))))
                     :status (get with-codes (rand-int (count with-codes)))
                     :body ""})
        
        _ (pprint/pprint endpoints)]

    endpoints))

(defn -main [& args]
  (let [{:keys [options summary errors]} (parse-opts args cli-options)]
    (when (seq errors)
      (println (str/join "\n" errors))
      (System/exit 1))
    (when (:help options)
      (println summary)
      (System/exit 0))
    (if (= 0 (:paths options))
      (jetty/run-jetty (generate-handler default-endpoints) {:port 3001 :join? false})
      (jetty/run-jetty (generate-handler (generate-custom-endpoints options)) {:port 3001 :join? false}))))

