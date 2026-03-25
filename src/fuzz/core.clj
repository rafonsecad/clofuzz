(ns fuzz.core
  (:require 
    [fuzz.terminal :as t]
    [fuzz.backup :as bk]
    [clojure.string :as str]
    [clojure.java.io :as io]
    [clojure.set :as set]
    [clojure.edn :as edn]
    [clj-http.client :as client]
    [taoensso.timbre :as timbre]
    [java-time.api :as jt]
    [clojure.tools.cli :refer [parse-opts]])
  (:import
    [org.apache.http.impl.client HttpClientBuilder]
    (java.util.concurrent CountDownLatch
                          Semaphore
                          Executors))
  (:gen-class))

(def banner 
  (str/join "\n"
            [
             ""
             " ▄████████  ▄█        ▄██████▄     ▄████████ ███    █▄   ▄███████▄   ▄███████▄  "
             "███    ███ ███       ███    ███   ███    ███ ███    ███ ██▀     ▄██ ██▀     ▄██ "
             "███    █▀  ███       ███    ███   ███    █▀  ███    ███       ▄███▀       ▄███▀ "
             "███        ███       ███    ███  ▄███▄▄▄     ███    ███  ▀█▀▄███▀▄▄  ▀█▀▄███▀▄▄ "
             "███        ███       ███    ███ ▀▀███▀▀▀     ███    ███   ▄███▀   ▀   ▄███▀   ▀ "
             "███    █▄  ███       ███    ███   ███        ███    ███ ▄███▀       ▄███▀      "
             "███    ███ ███▌    ▄ ███    ███   ███        ███    ███ ███▄     ▄█ ███▄     ▄█"
             "████████▀  █████▄▄██  ▀██████▀    ███        ████████▀   ▀████████▀  ▀████████▀"
             "           ▀                                                                    "
             ""]))

(timbre/set-min-level! :error)

(defonce semaphore (Semaphore. 30 true))
(defonce scheduler-executor (Executors/newFixedThreadPool 2))
(defonce virtual-executor (Executors/newVirtualThreadPerTaskExecutor))

(def stats (agent {:total 0 :processed-words 0}))

(defn init-system [terminal backup state]
  (t/handle terminal)
  (bk/init backup)
  (bk/create-state backup state @stats))

(defn monitor [terminal backup state]
    (.submit scheduler-executor (fn [] 
                                (loop []
                                  (if (not= (:total @stats) (:processed-words @stats))
                                    (do 
                                      (t/send-stats terminal @stats)
                                      (bk/update-state backup (:id state) @stats)
                                      (Thread/sleep 50)
                                      (recur))
                                    (do
                                      (t/send-stats terminal @stats)
                                      (bk/update-state backup (:id state) @stats)
                                      nil)))))
  (.shutdown scheduler-executor))

(defn fuzz->word [word base-url header]
  (let [header-fuzz
        (update-vals header #(str/replace % #"FUZZ" word))
        
        url-fuzz
        (str/replace base-url #"FUZZ" word)]
    {:base-url url-fuzz
     :headers header-fuzz}))

(defn mk-request [url method header follow-redirects?
                  word]
  (.acquire semaphore)
  (try 
    (let [fuzz-params (fuzz->word word url header)]
      (client/request 
        {:method method 
         :url (:base-url fuzz-params) 
         :throw-exceptions false
         :headers (:headers fuzz-params)
         :redirect-strategy (if follow-redirects? :default :none)
         :http-builder-fns [(fn [^HttpClientBuilder builder _]
                              (.disableAutomaticRetries builder))]}))
    (catch Exception e
      (timbre/debug (str "error connecting to server " (.getLocalizedMessage e) " for word " word)))
    (finally
      (.release semaphore))))

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

(defn check-status [status status-list ex-status-list]
  (if (seq ex-status-list)
    (not (.contains ex-status-list status))
    (.contains status-list status)))

(defn validate-request [{:keys [status] :as response}
                        match-codes exclude-codes filter-lengths
                        word 
                        terminal 
                        backup
                        id]
  (when (and (check-status status match-codes exclude-codes)
             (not (.contains filter-lengths (actual-length response))))
    (let [match
          (process-match response (actual-length response) word)] 
      (t/log terminal match)
      (bk/save-match backup id match)
      match)))

(defn process-dictionary [terminal backup dictionary state]
  (let [{:keys [id], {:keys [url match-codes exclude-codes header method filter-lengths follow-redirects?]} :options} state
        latch (CountDownLatch. (count dictionary))]
    (doseq [word dictionary]
      (.submit virtual-executor (fn []
                                  (-> (mk-request url method header follow-redirects? word)
                                      (validate-request match-codes exclude-codes filter-lengths word terminal backup id))
                                  (.countDown latch)
                                  (send stats update :processed-words inc))))

    (.await latch)
    (Thread/sleep 100)))

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
                  [nil "--exclude-codes HTTP CODES" "exclude http response code list separated by comma. This option discards --match-codes"
                   :default []
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
                  [nil "--user-agent PREDEFINED-USER-AGENT" "add predefined user agent header"
                   :default nil]
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
      {:exit-message (str banner summary) :ok? true}

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

(def user-agent-file (str (System/getenv "HOME") "/.clofuzz/user-agents.edn"))

(defn filter-options [options]
  (select-keys options [:url
                        :wordlist
                        :header
                        :method
                        :match-codes
                        :exclude-codes
                        :filter-lengths
                        :follow-redirects]))

(defn select-user-agents [terminal {:keys [user-agent], :as options}]
  (if (.exists (io/file user-agent-file)) 
    (let [u-agents (edn/read-string (slurp user-agent-file))

          full-agent (if-let [picked-agent ((keyword user-agent) u-agents)]
                       picked-agent
                       nil)]

      (if (nil? full-agent) 
        (do
          (t/log terminal (str "[Warning] couldn't find user-agent '" user-agent "' will use the default user-agent instead")) 
          (filter-options options))

        (-> (assoc-in options [:header "User-Agent"] full-agent)
            (filter-options))))

    (do
      (t/log terminal (str "[Warning] file not found: " user-agent-file " will use the default user-agent instead")) 
      (filter-options options))))

(defn init-state [terminal prog-opt]
  (let [wordlist
        (:wordlist prog-opt)

        wordlist-hash
        (bk/sha256sum wordlist)]

    {:id (rand-int 20000)
     :wordlist-hash wordlist-hash
     :wordlist wordlist
     :date (jt/format "YYYY-MM-dd" (jt/local-date))
     :options (if (some? (:user-agent prog-opt))
                (select-user-agents terminal prog-opt)
                (filter-options prog-opt))}))

(defn start-scan [ {:keys [terminal backup]} prog-opt]
  (println banner)
  (let [
        main-state (init-state terminal prog-opt)

        dictionary (str/split (slurp (:wordlist main-state)) #"\n") 

        _
        (send stats assoc :total (count dictionary))

        _
        (init-system terminal backup main-state)

        _
        (monitor terminal backup main-state)

        start-clock
        (. System (nanoTime))]
    (process-dictionary terminal backup dictionary main-state)
    (shutdown-agents)
    (println "Total time " (/ (double (- (. System (nanoTime)) start-clock)) 1000000.0) " ms")
    (t/stop terminal)))

(def system {:terminal t/QueueTerminal
             :backup (bk/->DataBackup bk/ds)})


(defn -main [& args]
  (let [{:keys [exit-message ok? options]} (validate-args args)] 
    (if exit-message
      (exit (if ok? 0 1) exit-message)
      (start-scan system options))))
