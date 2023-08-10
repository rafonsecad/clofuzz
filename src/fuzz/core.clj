(ns fuzz.core
  (:require 
    [clojure.string :as str]
    [clj-http.client :as client]
    [promesa.core :as p]
    [promesa.exec :as px]
    [promesa.exec.csp :as sp]
    [taoensso.timbre :as timbre])
  (:import
    [org.apache.http.impl.client HttpClientBuilder]))

(def word-chan (sp/chan :buf 35))
(def threads-chan (sp/chan))
(def matches (atom []))

(defn send-words [queue]
  (sp/go-loop [x queue]
           (if (empty? x)
             (do
               (sp/close! word-chan)
               (timbre/info "fin")
               nil)
             (do
               (comment timbre/info "sending data")
               (sp/put! word-chan (first x))
               (recur (rest x))))))

(defn mk [base-url word]
  (try 
    (-> (str base-url word)
        (client/get {:throw-exceptions false
                     :http-builder-fns [(fn [^HttpClientBuilder builder _]
                                          (.disableAutomaticRetries builder))]}))
    (catch Exception e
      (timbre/info (str "error connecting to server " (.getMessage e) " for word " word)))))

(defn validate [{:keys [status]} status-list word]
  (when (.contains status-list status)
    (timbre/info {:status status :word word})
    (swap! matches conj {:status status :word word})) )

(defn receive [base-url status-list]
  (sp/go-loop [requests 0]
              (if-let [word (sp/take! word-chan)]
                (do 
                  (-> (mk base-url word)
                      (validate status-list word))
                  (recur (inc requests)))
                (do 
                  (sp/put! threads-chan requests)
                  requests))))

(defn main [& args]
  (let [_
        (timbre/info "starting")

        _
        (send-words (str/split (slurp "resources/wordlist.txt") #"\n"))

        _
        (doseq [_ (range 30)] 
          (receive "http://10.10.11.161/" [200 401]))]
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
