(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'clofuzz)
(defn total-commits [head]
  (b/git-process {:git-args (str "rev-list --count " head)}))
(def tag 
  (b/git-process {:git-args "describe --tags --abbrev=0"}))
(def diff-commits
  (- (Integer/parseInt (total-commits "HEAD")) (Integer/parseInt (total-commits tag))))
(def version (str 
               (subs tag 1) 
               (if (> diff-commits 0) 
                 (str "-" diff-commits) 
                 "")))
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def uber-file (format "target/%s-%s.jar" (name lib) version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src"]
               :target-dir class-dir})
  (b/compile-clj {:basis basis
                  :src-dirs ["src"]
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis basis
           :main 'fuzz.core}))
