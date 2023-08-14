(ns fuzz.core
  (:require 
    [clojure.string :as str]
    [clojure.java.io :as io]
    [clj-http.client :as client]
    [promesa.exec.csp :as sp]
    [taoensso.timbre :as timbre]
    [clojure.tools.cli :refer [parse-opts]])
  (:import
    [org.apache.http.impl.client HttpClientBuilder])
  (:gen-class))

(def word-chan (sp/chan :buf 35))
(def threads-chan (sp/chan))
(def matches (atom []))

(defn send-words [queue]
  (sp/go-loop [words queue]
           (if (empty? words)
             (do
               (sp/close! word-chan)
               (timbre/info "fin")
               nil)
             (do
               (comment timbre/info "sending data")
               (sp/put! word-chan (first words))
               (recur (rest words))))))

(defn mk-request [base-url word]
  (try 
    (-> (str/replace base-url #"FUZZ" word)
        (client/get {:throw-exceptions false
                     :http-builder-fns [(fn [^HttpClientBuilder builder _]
                                          (.disableAutomaticRetries builder))]}))
    (catch Exception e
      (timbre/info (str "error connecting to server " (.getMessage e) " for word " word)))))

(defn validate [{:keys [status]} status-list word]
  (when (.contains status-list status)
    (timbre/info {:status status :word word})
    (swap! matches conj {:status status :word word})))

(defn receive [base-url status-list]
  (sp/go-loop [requests 0]
              (if-let [word (sp/take! word-chan)]
                (do 
                  (-> (mk-request base-url word)
                      (validate status-list word))
                  (recur (inc requests)))
                (do 
                  (sp/put! threads-chan requests)
                  requests))))

(def cli-options [["-u" "--url URL" "target URL"
                   :validate [#(str/starts-with? % "http") "http(s):// protocol missing"]]
                  ["-w" "--wordlist WORDLIST" "wordlist file path"
                   :validate [#(.exists (io/file %)) "can't find wordlist"]]
                  ["-h" "--help" "prints help"]])

(defn validate-args [args]
  (let [{:keys [options summary errors]} (parse-opts args cli-options)]
    (cond
      (:help options)
      {:exit-message summary :ok? true}

      (some? errors)
      {:exit-message errors :ok? false}

      (not= (set (keys options)) #{:wordlist :url})
      {:exit-message "missing wordlist or url" :ok? false}
      
      (not (str/includes? (:url options) "FUZZ"))
      {:exit-message "missing FUZZ" :ok? false}
      
      :else
      {:options options})))

(defn exit [status msg]
  (println msg)
  (System/exit status))

(defn start-scan [{:keys [wordlist url]}]
  (let [_
        (timbre/info "starting")

        _
        (send-words (str/split (slurp wordlist) #"\n"))

        _
        (doseq [_ (range 30)] 
          (receive url [200 401]))]
    (loop [threads-done 1 acc-requests 0]
      (let [requests (sp/take! threads-chan)]
        (if (= threads-done 30)
          (do
            (timbre/debug (str "thread finished: " threads-done  " requests: " requests ))
            (timbre/info (str "threads spawned: " threads-done  " total requests: " requests ))
            threads-done)
          (do
            (timbre/debug (str "thread finished: " threads-done  " requests: " requests ))
            (recur (inc threads-done) (+ acc-requests requests))))))))

(defn -main [& args]
  (let [{:keys [exit-message ok? options]} (validate-args args)] 
    (if exit-message
      (exit (if ok? 0 1) exit-message)
      (start-scan options))))
