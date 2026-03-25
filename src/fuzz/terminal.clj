(ns fuzz.terminal
  (:require [promesa.exec.csp :as sp]
            [taoensso.timbre :as timbre]
            [clojure.string :as str])
  (:import (java.util.concurrent Executors
                                 LinkedBlockingQueue)))

(defprotocol TerminalProtocol
  (handle [_])
  (log [_ msg])
  (send-stats [_ msg])
  (stop [_]))


(def ^:private output-chan (sp/chan))
(def ^:private progress-bar (atom {:percentage 0 :requests 0}))
(def ^:private bar-length 20)

(defn draw-bar [percentage]
  (let [boxes
        (int (* (/ percentage 100) bar-length))
        
        boxes-str
        (str/join (repeat boxes "\u2593"))
        
        space-str
        (str/join (repeat (- bar-length boxes) "\u2591"))
        
        bar
        ( str "\u2595" boxes-str space-str) ]
    (println bar (:percentage @progress-bar) "% - Requests made: " (:requests @progress-bar))))

(defn draw-panel []
  (println (str (char 27) "[0J"))
  (println)
  (draw-bar (:percentage @progress-bar))
  (print (str (char 27) "[3F")))

(defn- handle-output []
  (sp/go-loop []
              (if-let [{:keys [cmd text]} (sp/take! output-chan)]
                (cond (= cmd :log)
                      (do 
                        (println text)
                        (draw-panel)
                        (recur))

                      (= cmd :stats)
                      (do
                        (swap! progress-bar assoc :percentage (int (* (/ (:processed-words text) (:total text)) 100)) :requests (:processed-words text))
                        (draw-panel)
                        (recur))

                      (= cmd :stop)
                      (do (sp/close! output-chan)
                          (println)
                          (println)
                          (println)
                          nil)
                                                  
                      :else
                      nil)
              (recur))))


(def StandardTerminal 
  (reify TerminalProtocol
    (handle [_] (handle-output))
    (log [_ msg] (sp/put! output-chan {:cmd :log :text msg}))
    (send-stats [_ msg] (sp/put! output-chan {:cmd :stats :text msg}))
    (stop [_] (sp/put! output-chan {:cmd :stop}))))

(def executor (Executors/newFixedThreadPool 2))
(def msg-queue (LinkedBlockingQueue.))

(defn- handle-queue []
  (.submit executor (fn []
                      (loop []
                        (Thread/sleep 50)
                        (if-let [{:keys [cmd text]} (.take msg-queue)]
                          (cond (= cmd :log)
                                (do 
                                  (println text)
                                  (draw-panel)
                                  (recur))

                                (= cmd :stats)
                                (do
                                  (swap! progress-bar assoc :percentage (int (* (/ (:processed-words text) (:total text)) 100)) :requests (:processed-words text))
                                  (draw-panel)
                                  (recur))

                                (= cmd :stop)
                                (do 
                                    (println)
                                    (println)
                                    (println)
                                    nil)

                                :else
                                nil)
                          (recur)))))
  (.shutdown executor))

(def QueueTerminal 
  (reify TerminalProtocol
    (handle [_] (handle-queue))
    (log [_ msg] (.put msg-queue {:cmd :log :text msg}))
    (send-stats [_ msg] (.put msg-queue {:cmd :stats :text msg}))
    (stop [_] (.put msg-queue {:cmd :stop}))))
