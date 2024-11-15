(ns fuzz.fuzz-test
  (:require [clojure.test :refer :all]
            [fuzz.core :as fuzz]
            [fuzz.terminal :as fuzz-t]
            [ring.adapter.jetty :as jetty]))

(def MockTerminal 
  (reify fuzz-t/TerminalProtocol
    (handle [_])
    (log [_ msg])
    (stop [_])))

(defn ok-handler [request]
  (case (:uri request)
    
    "/ok"
    {:status 200 :body "ok" :content-length 2}

    "/ok-no-content-length"
    {:status 200 :body "ok"}

    {:status 404 :body "not found"}))

(defn start-mock-server [handler]
  (let [random-port
        (+ (rand-int 16383) 49152)
        
        server
        (jetty/run-jetty handler {:port random-port :join? false})
        
        url-fuzz
        (str "http://localhost:" random-port "/FUZZ")
        
        _
        (println "Creating mock server on port" random-port)] 
    {:port random-port
     :server server
     :url-fuzz url-fuzz}))

(deftest ok-test
  (testing "Test OK response at path /ok"
    (let  [_
           (reset! fuzz/matches [])
           
           mock-server 
           (start-mock-server ok-handler)

           request
           (fuzz/mk-request (:url-fuzz mock-server) "ok" {} false)

           _
           (fuzz/validate request [200] "ok" MockTerminal)

           _
           (.stop (:server mock-server))]
     (is (= (:status request) 200))
     (is (= @fuzz/matches [{:status 200 :word "ok" :length 2}])))))

(deftest ok-no-content-length-test
  (testing "Test OK response at path /ok-no-content-length"
    (let  [_
           (reset! fuzz/matches [])
           
           mock-server 
           (start-mock-server ok-handler)

           request
           (fuzz/mk-request (:url-fuzz mock-server) "ok-no-content-length" {} false)

           _
           (fuzz/validate request [200] "ok-no-content-length" MockTerminal)

           _
           (.stop (:server mock-server))]
     (is (= (:status request) 200))
     (is (= @fuzz/matches [{:status 200 :word "ok-no-content-length" :length 2}])))))

(deftest not-found-test
  (testing "Test NOT FOUND response at path /guess"
    (let  [_
           (reset! fuzz/matches [])

           mock-server 
           (start-mock-server ok-handler)

           request
           (fuzz/mk-request (:url-fuzz mock-server) "guess" {} false)

           _
           (fuzz/validate request [200] "guess" MockTerminal)

           _
           (.stop (:server mock-server))]
      (is (= (:status request) 404))
      (is (= @fuzz/matches [])))))

;(run-tests)
