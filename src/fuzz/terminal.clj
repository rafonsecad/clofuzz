(ns fuzz.terminal
  (:require [promesa.exec.csp :as sp]
            [taoensso.timbre :as timbre]))

(defprotocol TerminalProtocol
  (handle [_])
  (log [_ msg])
  (stop [_]))


(def ^:private output-chan (sp/chan))

(defn- handle-output []
  (sp/go-loop []
              (if-let [{:keys [cmd text]} (sp/take! output-chan)]
                (cond (= cmd :log)
                      (do 
                        (timbre/info text)
                        (recur))

                      :else
                      (sp/close! output-chan))
                (recur))))


(def StandardTerminal 
  (reify TerminalProtocol
    (handle [_] (handle-output))
    (log [_ msg] (sp/put! output-chan {:cmd :log :text msg}))
    (stop [_] (sp/put! output-chan {:cmd :stop}))))

