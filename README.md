# cljs-yagni

ClojureScript dead code tool for finding unused vars (`def`s + `defn`s) and vars that should be private.

Uses dynamic analysis courtesy of ClojureScript analyzer.api and build.api.

Easy to use with both cli and lein (haven't tested with boot yet).

## Running for cli users

in your `deps.edn`

1) add to deps:

``` clojure
:deps {stacksideflow/cljs-yagni {:mvn/version "0.9.1"}}

```


2) add a new alias:

``` clojure
:cljs-yagni {:main-opts ["-m" "cljs-yagni.main" "-r" "YOUR_ROOT_NS" ]}

```

-r is the only option you should specify for reasonable analysis.

where `YOUR_ROOT_NS` - prefix for all your project namespaces, e.g. if all your namespaces
are like "my-web-app.animations.views" then you should run with "-r" "my-web-app", if you also
would like to include ns like "my-other-web-app.some-stuff", then you should run with
"-r" "my-web-app,my-other-web-app"

3) run: 

``` clojure
clj -A:cljs-yagni

```

## Running for lein users

in your `project.clj`

1) add to deps:

``` clojure
:dependencies [[stacksideflow/cljs-yagni "0.9.1"]]

```

2) add a new alias:

``` clojure
:aliases {"cljs-yagni" ["run" "-m" "cljs-yagni.main" "-r" "YOUR_ROOT_NS"]}

```

-r is the only option you should specify for reasonable analysis.

where `YOUR_ROOT_NS` - prefix for all your project namespaces, e.g. if all your namespaces 
are like "my-web-app.animations.views" then you should run with "-r" "my-web-app", if you also
would like to include ns like "my-other-web-app.some-stuff", then you should run with
"-r" "my-web-app,my-other-web-app"
(options are the same for both cli and lein users)

3) run: 

``` clojure
lein cljs-yagni

```

## Full list of options

``` clojure
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

```
## Tests

As you can see, there are none. I've tested on some of my biggish projects, works nicely!
Currently collecting feedback to optimize away unnecesary actions/discover possible edge cases.

## Sample output


``` clojure
Howdy, cljs-yagni started

build started with path... [/home/v/proj/cljs-toolbox/flappy-bird-demo/src]
build ended
analyzing usage...
usage analysis ended



 ______________________________ 



Dead code analysis:
(public vars declared but not used anywhere)



 ______________________________ 



()



 ______________________________ 



Should-be-private vars analysis:
(public vars declared but used in just its own ns)



 ______________________________ 



(:flappy-bird-demo.core/horiz-vel
 :flappy-bird-demo.core/starting-state
 :flappy-bird-demo.core/pillar-offsets)


```

## Misc.

I worked on this project mainly on Christmas week; will be happy if other people 
find it useful! Eventually I'd like to grow this a little more to implement other
refactoring tools as cljs-toolbox, hence the tool is packaged as a (short-lived) REPL. It would be easy to
add more granularity to analysis with say non-zero output on dead-code (e.g. for use in CI).

##TODO

- Add node-or-browser opt to allow browser repl instead of node repl

## License

Copyright © 2019

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
