(ns fuzz.core
  (:require 
    [clojure.string :as str]
    [clojure.java.io :as io]
    [clojure.set :as set]
    [clj-http.client :as client]
    [promesa.exec.csp :as sp]
    [taoensso.timbre :as timbre]
    [clojure.tools.cli :refer [parse-opts]])
  (:import
    [org.apache.http.impl.client HttpClientBuilder])
  (:gen-class))

(timbre/set-min-level! :error)

(def word-chan (sp/chan :buf 35))
(def threads-chan (sp/chan))
(def matches (atom []))

(defn send-words [queue]
  (sp/go-loop [words queue]
           (if (empty? words)
             (do
               (sp/close! word-chan)
               nil)
             (do
               (sp/put! word-chan (first words))
               (recur (rest words))))))

(defn fuzz->word [word base-url header]
  (let [header-fuzz
        (update-vals header #(str/replace % #"FUZZ" word))
        
        url-fuzz
        (str/replace base-url #"FUZZ" word)]
    {:base-url url-fuzz
     :headers header-fuzz}))

(defn mk-request [base-url word header follow-redirects?]
  (try 
    (let [fuzz-params (fuzz->word word base-url header)]
      (client/get (:base-url fuzz-params) 
                  {:throw-exceptions false
                   :headers (:headers fuzz-params)
                   :redirect-strategy (if follow-redirects? :default :none)
                   :http-builder-fns [(fn [^HttpClientBuilder builder _]
                                        (.disableAutomaticRetries builder))]}))
    (catch Exception e
      (timbre/debug (str "error connecting to server " (.getLocalizedMessage e) " for word " word)))))

(defn process-match [{:keys [status length headers body trace-redirects]} word]
  (let [actual-length
        (if (= length -1)
          (count body)
          length)] 
    (cond
      (seq trace-redirects)
      {:status status :word word :length actual-length :redirections trace-redirects}

      (= (int (/ status 100)) 3) 
      {:status status :word word :length actual-length :location (get headers "Location")}

      :else
      {:status status :word word :length actual-length})) )

(defn validate [{:keys [status] :as response} status-list word]
  (when (.contains status-list status)
    (let [match
          (process-match response word)] 
      (timbre/info  match)
      (swap! matches conj match))))

(defn receive [base-url code-list header follow-redirects?]
  (sp/go-loop [requests 0]
              (if-let [word (sp/take! word-chan)]
                (do 
                  (-> (mk-request base-url word header follow-redirects?)
                      (validate code-list word))
                  (recur (inc requests)))
                (do 
                  (sp/put! threads-chan requests)
                  requests))))

(defn parse-mc [codes]
  (->> (str/split codes #",")
       (mapv #(Integer/parseInt %))))

(def cli-options [["-u" "--url URL" "target URL"
                   :validate [#(str/starts-with? % "http") "http(s):// protocol missing"]]
                  ["-w" "--wordlist WORDLIST" "wordlist file path"
                   :validate [#(.exists (io/file %)) "can't find wordlist"]]
                  [nil "--header Header" "Add http header, ex. --header \"Host: FUZZ.domain.org\""
                   :default {}
                   :parse-fn #(apply hash-map (str/split % #": "))
                   :update-fn merge
                   :multi true]
                  ["-v" nil "Verbosity level"
                   :id :verbosity
                   :default 0
                   :update-fn inc]
                  [nil "--match-codes HTTP CODES" "match http response code list separated by comma"
                   :default [200 204 301 302 307 401 403 405 500]
                   :parse-fn parse-mc
                   :validate [(fn [v]
                                (every? #(< 100 % 600) v))

                              (fn [v] 
                                (->> (filter #(not (< 100 % 600)) v)
                                     (str/join ",") 
                                     (str "invalid http response code(s): ")))]]
                  [nil "--follow-redirects" "follow redirects, disabled by default" :default false]
                  ["-h" "--help" "prints help"]])

(defn adjust-verbosity [{:keys [verbosity]}]
  (if (> verbosity 0)
    (timbre/set-ns-min-level! :debug)
    (timbre/set-ns-min-level! :info)))

(defn validate-args [args]
  (let [{:keys [options summary errors]} (parse-opts args cli-options)]
    (cond
      (:help options)
      {:exit-message summary :ok? true}

      (some? errors)
      {:exit-message errors :ok? false}

      (not (set/subset? #{:wordlist :url} (set (keys options))))
      {:exit-message "wordlist and url are required" :ok? false}
      
      (not (or (str/includes? (:url options) "FUZZ")
               (seq (filter #(str/includes? % "FUZZ") (vals (:header options))))))
      {:exit-message "missing FUZZ" :ok? false}
      
      :else
      (do (adjust-verbosity options) 
          {:options options}))))

(defn exit [status msg]
  (println msg)
  (System/exit status))

(defn start-scan [{:keys [wordlist url match-codes header follow-redirects]}]
  (let [_
        (timbre/info "starting")

        _
        (send-words (str/split (slurp wordlist) #"\n"))

        _
        (doseq [_ (range 30)] 
          (receive url match-codes header follow-redirects))]
    (loop [threads-done 1 acc-requests 0]
      (let [requests (sp/take! threads-chan)]
        (if (= threads-done 30)
          (do
            (timbre/debug (str "thread finished: " threads-done  " requests: " requests ))
            (timbre/info (str "threads spawned: " threads-done  " total requests: " (+ acc-requests requests) ))
            threads-done)
          (do
            (timbre/debug (str "thread finished: " threads-done  " requests: " requests ))
            (recur (inc threads-done) (+ acc-requests requests))))))))

(defn -main [& args]
  (let [{:keys [exit-message ok? options]} (validate-args args)] 
    (if exit-message
      (exit (if ok? 0 1) exit-message)
      (start-scan options))))
