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

(defn post-handler [{:keys [uri request-method] }]
  (if (and (= request-method :post) (= uri "/ok"))
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

(deftest filter-by-status-code-test
  (testing "OK response at path /ok: one match for status code 200"
    (let  [_
           (reset! fuzz/matches [])
           
           mock-server 
           (start-mock-server ok-handler)

           request
           (fuzz/mk-request (:url-fuzz mock-server) "ok" "GET" {} false)

           _
           (fuzz/validate-request request [200] [] [] "ok" MockTerminal)

           _
           (.stop (:server mock-server))]
     (is (= (:status request) 200))
     (is (= @fuzz/matches [{:status 200 :word "ok" :length 2}]))))

  (testing "NOT FOUND response at path /guess: no matches for 200 status code"
    (let  [_
           (reset! fuzz/matches [])

           mock-server 
           (start-mock-server ok-handler)

           request
           (fuzz/mk-request (:url-fuzz mock-server) "guess" "GET" {} false)

           _
           (fuzz/validate-request request [200] []  [] "guess" MockTerminal)

           _
           (.stop (:server mock-server))]
      (is (= (:status request) 404))
      (is (= @fuzz/matches [])))))

(deftest no-content-length-response-test
  (testing "OK response at path /ok-no-content-length: one match with content-length of 2"
    (let  [_
           (reset! fuzz/matches [])
           
           mock-server 
           (start-mock-server ok-handler)

           request
           (fuzz/mk-request (:url-fuzz mock-server) "ok-no-content-length" "GET" {} false)

           _
           (fuzz/validate-request request [200] [] [] "ok-no-content-length" MockTerminal)

           _
           (.stop (:server mock-server))]
     (is (= (:status request) 200))
     (is (= @fuzz/matches [{:status 200 :word "ok-no-content-length" :length 2}])))))

(deftest filter-out-by-content-length-test
  (testing "filter out content-length of 9 at path /guess: no matches found"
    (let  [_
           (reset! fuzz/matches [])
           
           mock-server 
           (start-mock-server ok-handler)

           request
           (fuzz/mk-request (:url-fuzz mock-server) "guess" "GET" {} false)

           _
           (fuzz/validate-request request [404] [] [9] "guess" MockTerminal)

           _
           (.stop (:server mock-server))]
     (is (= (:status request) 404))
     (is (= @fuzz/matches [])))))  

(deftest post-method-test
  (testing "scanning with post method at path /ok : one match found"
    (let  [_
           (reset! fuzz/matches [])
           
           mock-server 
           (start-mock-server post-handler)

           request
           (fuzz/mk-request (:url-fuzz mock-server) "ok" "POST" {} false)

           _
           (fuzz/validate-request request [200] [] [] "ok" MockTerminal)

           _
           (.stop (:server mock-server))]
     (is (= (:status request) 200))
     (is (= @fuzz/matches [{:status 200 :word "ok" :length 2}]))))

  (testing "scanning with get method at path /ok : no matches found"
    (let  [_
           (reset! fuzz/matches [])
           
           mock-server 
           (start-mock-server post-handler)

           request
           (fuzz/mk-request (:url-fuzz mock-server) "ok" "GET" {} false)

           _
           (fuzz/validate-request request [200] [] [] "ok" MockTerminal)

           _
           (.stop (:server mock-server))]
     (is (= (:status request) 404))
     (is (= @fuzz/matches [])))))

(deftest exlusion-by-status-code-test
  (testing "OK response at path /ok: exclude status code 200"
    (let  [_
           (reset! fuzz/matches [])
           
           mock-server 
           (start-mock-server ok-handler)

           request
           (fuzz/mk-request (:url-fuzz mock-server) "ok" "GET" {} false)

           _
           (fuzz/validate-request request [200] [200] [] "ok" MockTerminal)

           _
           (.stop (:server mock-server))]
     (is (= (:status request) 200))
     (is (= @fuzz/matches [])))))

(run-tests)
