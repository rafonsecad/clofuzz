(ns fuzz.core
  (:require 
    [fuzz.terminal :as t]
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
(def stats (atom {:total 0 :processed-words 0}))

(defn send-words [queue]
  (swap! stats assoc :total (count queue))
  (sp/go-loop [words queue]
           (if (empty? words)
             (do
               (sp/close! word-chan)
               nil)
             (do
               (sp/put! word-chan (first words))
               (recur (rest words))))))

(defn monitor [terminal]
  (sp/go-loop []
              (if (not= (:total @stats) (:processed-words @stats))
                (do 
                  (t/send-stats terminal @stats)
                  (Thread/sleep 100)
                  (recur))
                (do
                  (t/send-stats terminal @stats)
                  (sp/put! threads-chan 0)
                  nil))))

(defn fuzz->word [word base-url header]
  (let [header-fuzz
        (update-vals header #(str/replace % #"FUZZ" word))
        
        url-fuzz
        (str/replace base-url #"FUZZ" word)]
    {:base-url url-fuzz
     :headers header-fuzz}))

(defn mk-request [base-url word method header follow-redirects?]
  (try 
    (let [fuzz-params (fuzz->word word base-url header)]
      (client/request 
        {:method method 
         :url (:base-url fuzz-params) 
         :throw-exceptions false
         :headers (:headers fuzz-params)
         :redirect-strategy (if follow-redirects? :default :none)
         :http-builder-fns [(fn [^HttpClientBuilder builder _]
                              (.disableAutomaticRetries builder))]}))
    (catch Exception e
      (timbre/debug (str "error connecting to server " (.getLocalizedMessage e) " for word " word)))))

(defn process-match [{:keys [status  headers  trace-redirects]} actual-length word]
  (cond
    (seq trace-redirects)
    {:status status :word word :length actual-length :redirections trace-redirects}

    (= (int (/ status 100)) 3) 
    {:status status :word word :length actual-length :location (get headers "Location")}

    :else
    {:status status :word word :length actual-length}) )

(defn actual-length [{:keys [body length]}]
  (if (= length -1)
          (count body)
          length))

(defn validate-request [{:keys [status] :as response} status-list length-ex word terminal]
  (when (and (.contains status-list status)
             (not (.contains length-ex (actual-length response))))
    (let [match
          (process-match response (actual-length response) word)] 
      (t/log terminal match)
      (swap! matches conj match))))

(defn process-words [terminal base-url code-list header method filter-lengths follow-redirects?]
  (sp/go-loop [requests 0]
              (if-let [word (sp/take! word-chan)]
                (do 
                  (-> (mk-request base-url word method header follow-redirects?)
                      (validate-request code-list filter-lengths word terminal))
                  (swap! stats update :processed-words inc)
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
                  [nil "--method METHOD" "HTTP method"
                   :default "GET"]
                  [nil "--match-codes HTTP CODES" "match http response code list separated by comma"
                   :default [200 204 301 302 307 401 403 405 500]
                   :parse-fn parse-mc
                   :validate [(fn [v]
                                (every? #(< 100 % 600) v))

                              (fn [v] 
                                (->> (filter #(not (< 100 % 600)) v)
                                     (str/join ",") 
                                     (str "invalid http response code(s): ")))]]
                  [nil "--filter-lengths LENGTHS" "exclude content length list separated by comma"
                   :default []
                   :parse-fn parse-mc]
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

(defn start-scan [ {:keys [terminal]} {:keys [wordlist url match-codes header method filter-lengths follow-redirects]}]
  (let [_
        (t/handle terminal)

        _
        (send-words (str/split (slurp wordlist) #"\n"))

        _
        (monitor terminal)

        _
        (doseq [_ (range 30)] 
          (process-words terminal url match-codes header method filter-lengths follow-redirects))]
    (loop [threads-done 1 acc-requests 0]
      (let [requests (sp/take! threads-chan)]
        (if (= threads-done 31)
          (do
            ;(timbre/debug (str "thread finished: " threads-done  " requests: " requests ))
            (t/log terminal (str "threads spawned: " (dec threads-done)  " total requests: " (+ acc-requests requests) ))
            (t/stop terminal)
            threads-done)
          (do
            ;(timbre/debug (str "thread finished: " threads-done  " requests: " requests ))
            (recur (inc threads-done) (+ acc-requests requests))))))))

(def system {:terminal t/StandardTerminal})

(defn -main [& args]
  (let [{:keys [exit-message ok? options]} (validate-args args)] 
    (if exit-message
      (exit (if ok? 0 1) exit-message)
      (start-scan system options))))
