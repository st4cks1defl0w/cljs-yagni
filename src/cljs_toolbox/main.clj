(ns cljs-toolbox.main
  (:require [cljs.repl]
            [clojure.walk :as w]
            [cljs.repl.browser]
            [cljs.repl.node]
            [cljs.closure :as closure]
            [clojure.pprint]
            [cljs.cli :as cli]
            [cljs.env]
            [clojure.java.shell :as shell]
            [cljs.repl :as repl]
            [cljs.build.api :as bapi]
            [cljs.analyzer :as ana]
            [clojure.tools.namespace.repl :refer [refresh]]
            [clojure.tools.cli :refer [parse-opts]]
            [cljs.analyzer.api :as ana-api]))

;;shoud be extensible, varies a lot per prject (vec)
(def ^:private path (.getCanonicalPath (clojure.java.io/file "src")))

(def ^:private repl-options {:analyze-path path})

(def ^:private compiler-options {:main          'flappy-bird-demo.core
                                 :output-to     ".cljs-toolbox-tmp/cljs-out/build-main.js"
                                 :output-dir    ".cljs-toolbox-tmp/cljs-out/build"
                                 :asset-path    "cljs-out/build"
                                 :source-map    true
                                 :optimizations :none
                                 :aot-cache     false})

(defonce cli-options* (atom {}))

;;NOTE Global state for easy inspection from repl
(defonce var-usage-graph (atom {}))

(defn log [& xs]
  (when (and (pos? (count xs)) :verbose?)
    (apply println xs)))

(defmacro analyzer []
  (println (keys (ana-api/get-js-index))))

(defn- repl-env [path]
  (cljs.repl.node/repl-env* {:src path}))

(defn cenv* []
  cljs.env/*compiler*)

(defn- clean-build []
  (shell/sh "rm" "-rf" (.getCanonicalPath (clojure.java.io/file ".cljs-toolbox-tmp"))))

(defn- build []
  (log "build started with path..." path)
  (clean-build)
  (log "build ended")
  (bapi/build (bapi/inputs path #_["src"]) compiler-options))

(defn- analyzed-ns? [ns-meta]
  (= (-> @cli-options* :options :root-ns)
     (first (clojure.string/split (first (:provides ns-meta)) #"\."))))

(defn- analyzed-sb? [node]
  (= (-> @cli-options* :options :root-ns)
     (first (clojure.string/split (second node) #"\."))))

(defn- ns->analysis [ns-meta]
  (when (analyzed-ns? ns-meta)
    (let [{:keys [source-map requires provides]} ns-meta
         vars-in-ns                             (atom {})]
     (clojure.walk/prewalk
      (fn [node]
        (when (and (= (type node)
                      clojure.lang.MapEntry)
                   (= (first node) :name)
                   (analyzed-sb? node))
          ;;aka (namespace) from core
          (let [[var-ns var-sb] (clojure.string/split (second node) #"\/")]
            (swap! vars-in-ns update (keyword var-ns) conj (keyword var-sb))))
        node)
      source-map)
     {(keyword (first provides)) {:requires (map keyword requires)
                                  :vars     @vars-in-ns}})))

(defn- ns-graph []
  (let [compiled (:cljs.closure/compiled-cljs @(cenv*))]
    (apply merge (remove nil? (map ns->analysis (vals compiled))))))

(defmacro cenv []
  (build)
  (clojure.pprint/pprint (ns-graph)))

(defmacro reload []
  (refresh))

(def cli-options-scheme
  [["-r" "--root-ns root"
    "Namespaces to analyze are matched against this root ns up to first dot
     e.g.:
     to analyze \"my-proj.views.some-file\" you should run with -root my-proj"
    :id :root-ns
    :parse-fn str
    :validate [#(and (string? %) (not-empty %)) "Must be a non-empty string"]]
   ["-h" "--help"]])


(defn -main
  "Start toolbox REPL"
  [& args]
  (println "Howdy, cljs-toolbox started")
  (reset! cli-options* (parse-opts args cli-options-scheme))
  (cljs.repl/repl* (repl-env path) repl-options)
  (println "Bye!"))
