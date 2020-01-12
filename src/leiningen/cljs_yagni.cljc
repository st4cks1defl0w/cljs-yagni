(ns leiningen.cljs-yagni
  (:require [cljs-yagni.main :as main]))

(defn cljs-yagni
  "Entry for lein users"
  [project & args]
  (apply main/-main args))
