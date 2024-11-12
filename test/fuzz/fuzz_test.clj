(ns fuzz.fuzz-test
  (:require [clojure.test :refer :all]
            [fuzz.core :as fuzz]
            [ring.adapter.jetty :as jetty]
            [clojure.string :as str]))

(defn ok-handler [request]
  (if (str/starts-with? (:uri request) "/ok")
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

           validation
           (fuzz/validate request [200] "ok")

           _
           (.stop (:server mock-server))]
     (is (= (:status request) 200))
     (is (= validation [{:status 200 :word "ok"}])))))

(deftest not-found-test
  (testing "Test NOT FOUND response at path /guess"
    (let  [_
           (reset! fuzz/matches [])

           mock-server 
           (start-mock-server ok-handler)

           request
           (fuzz/mk-request (:url-fuzz mock-server) "guess" {} false)

           _
           (fuzz/validate request [200] "guess")

           _
           (.stop (:server mock-server))]
      (is (= (:status request) 404))
      (is (= @fuzz/matches [])))))

