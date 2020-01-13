(ns cljs-yagni.main
  (:require [cljs.repl]
            [clojure.walk :as w]
            [cljs.repl.node]
            [clojure.pprint]
            [cljs.analyzer :as ana]
            [cljs.env]
            [clojure.java.shell :as shell]
            [cljs.build.api :as bapi]
            [clojure.tools.namespace.repl :refer [refresh]]
            [clojure.tools.cli :refer [parse-opts]]
            [cljs.analyzer.api :as ana-api]))

;;NOTE Global state for easy inspection from repl
;;{:my-namespace {::a-public-var {:seen?              true
;;                                :should-be-private? false
;;                                :seen-in           [:added-in-verbose-mode]}}}
(defonce publics-usage-graph (atom {}))

;;TODO should be passed through opts, varies a lot per project (vec)
(def ^:private repl-options {})

(def ^:private compiler-options {:main          'cljs.user
                                 :output-to     ".cljs-toolbox-tmp/cljs-out/build-main.js"
                                 :output-dir    ".cljs-toolbox-tmp/cljs-out/build"
                                 :asset-path    "cljs-out/build"
                                 :source-map    true
                                 :optimizations :none
                                 :aot-cache     false})
(def cli-options (atom {}))

(defn- path []
  (mapv #(.getCanonicalPath (clojure.java.io/file %))
        (-> @cli-options :options :dirs)))

(defn log [& xs]
  (when (and (pos? (count xs)) :verbose?)
    (apply println xs)))

(defn log-spacer []
  (log "\n\n\n" (apply str (repeat 30 "_")) "\n\n\n"))

(defn- repl-env [path]
  (cljs.repl.node/repl-env* {:src path}))

(defn cenv* []
  cljs.env/*compiler*)

(defn- analyzed-ns? [ns-meta]
  ((-> @cli-options :options :root-ns)
   (first (clojure.string/split (first (:provides ns-meta)) #"\."))))

(defn- analyzed-sb? [node]
  ((-> @cli-options :options :root-ns)
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

(defn- unused-or-should-be-private-vars [badness-type]
  (let [badness-case     (case badness-type
                           :unused            #(not (:seen? %))
                           :should-be-private (fn [m] (:should-be-private? m))
                           (constantly nil))
        all-vars         (vals @publics-usage-graph)
        filter-bad-vars  #(filter (fn [[_ v]]
                                    (badness-case v)) %)
        ;;NOTE quickly coerce nicely-structured publics-usage-graph map to seq
        non-verbose-vars (flatten (map #(->> % (filter-bad-vars) (map first)) all-vars))]
    non-verbose-vars))

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
                                   ((-> @cli-options :options :root-ns)
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

(defn- clean-build []
  (shell/sh "rm" "-rf" (.getCanonicalPath (clojure.java.io/file ".cljs-toolbox-tmp"))))

(defn- build* []
  (log "build started with path..." (path))
  (clean-build)
  ;;NOTE bapi/build is too noisy
  (with-out-str
    (binding [*err* *out*]
      (bapi/build (apply bapi/inputs (path)) compiler-options) ))
  (log "build ended")
  (log "analyzing usage...")
  (analyze-usage!)
  (log "usage analysis ended"))

(defn dead-code* []
  (log-spacer)
  (log "Dead code analysis:")
  (log "(public vars declared but not used anywhere)")
  (log-spacer)
  (clojure.pprint/pprint (unused-or-should-be-private-vars :unused)))

(defn privates* []
  (log-spacer)
  (log "Should-be-private vars analysis:")
  (log "(public vars declared but used in just its own ns)")
  (log-spacer)
  (clojure.pprint/pprint (unused-or-should-be-private-vars :should-be-private)))

(defmacro ^:repl-api build []
  (build*))

(defmacro ^:repl-api dead-code []
  (dead-code*))

(defmacro ^:repl-api privates []
  (privates*))

(def cli-options-scheme
  [["-r" "--root-ns root-ns"
    "
    *Only required option
     Namespaces to analyze - specify namespace part(s) from start to first dot
     e.g.:
     to analyze \"my-proj.views.some-file\" and \"my-other-proj.views.some-file\"
     you should run with -r my-proj,my-other-proj"
    :parse-fn #(set (map clojure.string/trim (clojure.string/split % #"\,")))
    :validate [seq "Must be a string that goes like my-proj,my-proj-2"]]
   ["-d" "--dirs dirs"
    "
     Directories to include in analysis, reasonable default is just src "
    :parse-fn #(mapv clojure.string/trim (clojure.string/split % #"\,"))
    :default  ["src"]
    :validate [seq "Must be a non-empty string"]]
   ["-t" "--task task"
    "
    repl - start a repl session, where you should first run
               (cljs-yagni.main/build) for all other api function calls to work.
               Then you can invoke api calls directly:
               (cljs-yagni.main/dead-code) - print dead-code
               (cljs-yagni.main/privates) - print should-be-privates
               (cljs-yagni.main/all) - print should-be-privates and dead-code
               Also at all times you can inspect the var
               (cljs-yagni.main/publics-usage-graph), which holds meaningful
               map of all analyzed vars, may be useful for debugging
    all - print dead-code, print should-be-privates, exit
    dead-code - print dead-code, exit
    privates - print should-be-privates, exit"
    :parse-fn keyword
    :default :all
    :validate [#{:repl :all :privates :dead-code}
               "Must be one of:
                                repl,
                                all (dead-code + privates),
                                dead-code,
                                privates"]]
   ["-h" "--help"]])

(defn- ^:dev remember-cli-options!
  ([]
   (reset! cli-options @cli-options))
  ([args]
   (reset! cli-options (parse-opts args cli-options-scheme))))

(defmacro ^:dev reload []
  (refresh)
  (remember-cli-options!))

(defmacro clean-exit []
  (System/exit 0))

(defn- print-usage []
  (log "Usage:\n")
  (log (:summary @cli-options))
  (System/exit 0))

(defn- print-errors []
  (log (:errors @cli-options))
  (System/exit 1))

(defn- start-repl!
  ([] (start-repl! []))
  ([forms] (cljs.repl/repl* (repl-env (path))
                            (assoc-in repl-options [:inits]
                                      [{:type :init-forms,
                                        :forms [forms]}]))))

(defn -main
  "Can be used to analyze compiled cljs to find unused or needlessly public vars"
  [& args]
  (println "Howdy, cljs-yagni started\n")
  (remember-cli-options! args)
  (let [opts (:options @cli-options)]
    (println "opts are" opts)
    (cond
      (:help opts)
      (print-usage)

      (:errors @cli-options)
      (print-errors)

      (= (:task opts) :all)
      (start-repl! ['(cljs-yagni.main/build)
                    '(cljs-yagni.main/dead-code)
                    '(cljs-yagni.main/privates)
                    '(cljs-yagni.main/clean-exit)])

      (= (:task opts) :dead-code)
      (start-repl! ['(cljs-yagni.main/build)
                    '(cljs-yagni.main/dead-code)
                    '(cljs-yagni.main/clean-exit)])

      (= (:task opts) :privates)
      (start-repl! ['(cljs-yagni.main/build)
                    '(cljs-yagni.main/privates)
                    '(cljs-yagni.main/clean-exit)])

      (= (:task opts) :repl)
      (start-repl!))
    (clean-build)))
