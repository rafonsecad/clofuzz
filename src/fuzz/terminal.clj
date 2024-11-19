(ns fuzz.terminal
  (:require [promesa.exec.csp :as sp]
            [taoensso.timbre :as timbre]))

(defprotocol TerminalProtocol
  (handle [_])
  (log [_ msg])
  (send-stats [_ msg])
  (stop [_]))


(def ^:private output-chan (sp/chan))
(def ^:private progress-bar (atom {:percentage 0}))

(defn refresh-bar []
  ;(dotimes [_ 2] (println ""))
  ;(print (str (char 27) "[1E"))
  ;(flush)
  (println (str (char 27) "[0J"))
  (println)
  (println "[                  ]" (:percentage @progress-bar) "%")
  (print (str (char 27) "[3F")))

(defn draw [stats]
  (println (str (char 27) "[0J"))
  (println)
  (println "[                  ]" (:percentage @progress-bar) "%")
  (print (str (char 27) "[3F")))

(defn- handle-output []
  (sp/go-loop []
              (if-let [{:keys [cmd text]} (sp/take! output-chan)]
                (cond (= cmd :log)
                      (do 
                        (println text)
                        (refresh-bar)
                        (recur))

                      (= cmd :stats)
                      (do
                        (swap! progress-bar assoc :percentage (int (* (/ (:processed-words text) (:total text)) 100)))
                        ;(refresh-bar)
                        (draw text)
                        (recur))

                      (= cmd :stop)
                      (do (sp/close! output-chan)
                          ;(print (str (char 27) "[5B"))
                          ;(flush)
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

