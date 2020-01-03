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
;;{:my-namespace {::a-public-var {:seen?              true
;;                                :should-be-private? false
;;                                :seen-in           [:added-in-verbose-mode]}}}
;;NOTE should-be-private is still crude (meaning too conservative, but safe to apply)
(defonce publics-usage-graph (atom {}))

(defn log [& xs]
  (when (and (pos? (count xs)) :verbose?)
    (apply println xs)))

(defn log-spacer []
  (log (apply str (repeat 30 "_"))))

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

(defn- analyze-var-status!
  "this fn comprises core logic of when a var is considered seen
  let's be as conservative as possible here. my assumptions of possible cases are

  a) spotted at least twice anywhere - seen.
    additionally, if var is not spotted in any ns where it's not def'd (external usage),
  var should have a gentle warning that the var should be private.

  b) spotted at least one external usage - seen.

  c) no external usage and no internal usage - unseen, prompt dead code error.
  "
  [var-ns ns-kw provides]
  (let [curr-ns (keyword (first provides))
        seen-in (get-in @publics-usage-graph [var-ns ns-kw :seen-in])
        should-be-private? (and (seq seen-in)
                                (= var-ns curr-ns)
                                (every? #(= var-ns %) seen-in))]
    (when (or (seq seen-in) (not= curr-ns var-ns))
      (swap! publics-usage-graph
             assoc-in
             [var-ns ns-kw :seen?]
             true))
    (swap! publics-usage-graph
           assoc-in
           [var-ns ns-kw :should-be-private?]
           should-be-private?)))

(defn- ns->analysis! [ns-meta]
  (when (analyzed-ns? ns-meta)
    (let [{:keys [source-map provides]} ns-meta]
     (clojure.walk/prewalk
      (fn [node]
        (when (and (= (type node)
                      clojure.lang.MapEntry)
                   (= (first node) :name)
                   (analyzed-sb? node))
          ;;aka (namespace) from core
          (let [ns-kw  (keyword (second node))
                var-ns (keyword (namespace ns-kw))]
            (analyze-var-status! var-ns ns-kw provides)
            (when :verbose?
              (swap! publics-usage-graph
                     update-in
                     [var-ns ns-kw :seen-in]
                     conj
                     (keyword (first provides))))))
        node)
      source-map))))

(defn- analyze-usage! []
  (let [compiled    (:cljs.closure/compiled-cljs @(cenv*))
        ns->publics (->> @(cenv*)
                         :cljs.analyzer/namespaces
                         keys
                         ;;assuming lazy map+filter are optimized by compiler
                         ;;to prevent walking twice; this is more readable
                         (filter (fn [k]
                                   (= (-> @cli-options* :options :root-ns)
                                      (first (clojure.string/split (str k) #"\.")))))
                         ;;leave ns->:ns/var scheme for now to stay flexible
                         ;;as query by ns seems imminent at some point
                         (map
                          (fn [n]
                            {(keyword n)
                             (apply merge
                                    (map (fn [pub-var] {(-> pub-var second :name keyword)
                                                       {:seen?              false
                                                        :should-be-private? false
                                                        :seen-in            []}})
                                         (ana-api/ns-publics (symbol n))))}))
                         (apply merge))]
    (reset! publics-usage-graph ns->publics)
    (doseq [compiled-meta (vals compiled)]
      (ns->analysis! compiled-meta))))


(defn- unused-or-should-be-private-vars [badness-type]
  (let [badness-case    (case badness-type
                          :unused            #(not (:seen? %))
                          :should-be-private (fn [m] (:should-be-private? m))
                          (constantly nil))
        all-vars        (vals @publics-usage-graph)
        filter-bad-vars #(apply merge
                                (filter (fn [[_ v]]
                                          (badness-case v)) %))]
    (remove nil? (map filter-bad-vars all-vars))))

(defmacro print-dead-code []
  (build)
  (analyze-usage!)
  (log-spacer)
  (log "Dead code analysis:")
  (clojure.pprint/pprint (unused-or-should-be-private-vars :unused)))

(defmacro print-should-be-private []
  (build)
  (analyze-usage!)
  (log-spacer)
  (log "Should-be-private vars analysis:")
  (clojure.pprint/pprint (unused-or-should-be-private-vars :should-be-private)))

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
