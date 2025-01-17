(ns leiningen.doo
  "Provides a command line wrapper around doo.core/run-script.
   See the main fn: doo"
  (:require [clojure.java.io :as io] 
            [doo.core :as doo]
            [leiningen.core.main :as lmain]
            [leiningen.cljsbuild.config :as config]
            [leiningen.cljsbuild.subproject :as subproject]
            [leiningen.core.eval :as leval]
            [clojure.pprint :refer [pprint]]))

;; Assumes the project is packaged in the same jar
(defn get-lib-version [proj-name]
  {:pre [(string? proj-name)]}
  (let [[_ coords version]
        (-> (io/resource (str "META-INF/leiningen/" proj-name 
                           "/" proj-name "/project.clj"))
          slurp
          read-string)]
    (assert (= coords (symbol proj-name))
      (str "Something very wrong, could not find " proj-name
        "'s project.clj, actually found: " coords))
    (assert (string? version)
      (str "Something went wrong, version of " proj-name
        " is not a string: " version))
    version))

;; Needed to ensure cljsbuild compatibility
(defn make-subproject [project builds]
  (with-meta
    (merge
      (select-keys project [:checkout-deps-shares
                            :eval-in
                            :jvm-opts
                            :local-repo
                            :repositories
                            :resource-paths])
      {:local-repo-classpath true
       :dependencies (subproject/merge-dependencies (:dependencies project))
       :source-paths (concat
                       (:source-paths project)
                       (mapcat :source-paths builds))})
    (meta project)))

(defn add-dep
  "Adds one dependency (needs to be a vector with a quoted symbol)
  to the project's dependencies.
  Ex: (add-dep project ['doo \"0.1.0-SNAPSHOT\"])"
  [project dep]
  (update-in project [:dependencies] #(conj % dep)))

;; well this is private in the leiningen.cljsbuild ns & figwheel!
(defn run-local-project
  "Runs both forms (requires and form) in the context of the project"
  [project builds requires form]
  (let [project' (-> project
                   (make-subproject builds)
                   ;; just for use inside the plugin
                   (dissoc :eval-in))]
    (leval/eval-in-project project'
      `(try
         (do
           ~form
           (System/exit 0))
         (catch Exception e#
           (do
             (.printStackTrace e#)
             (System/exit 1))))
      requires)))

;; TODO: generate the js-env opts & build-ids dynamically
(def help-string
"\ndoo - run cljs.test in any JS environment.\n
Usage:\n
  lein doo {js-env} {build-id}\n
Where - js-env: slimer, phantom, or node
      - build-id: any of the ids under the :cljsbuild map in your project.clj\n")

(defn find-by-id
  "Out of a seq of builds, returns the one with the given id"
  [builds id]
  (first (filter #(= id (:id %)) builds)))

(defn doo 
  "Command line API for doo, which compiles a cljsbuild
   and runs it in a js enviroment:

  lein doo {js-env} {build-id}

  lein doo {js-env} {build-id} {watch-mode}

  - js-env: any of slimer, phantom, rhinno
  - build-id: the build-id from your cljsbuild configuration
  - watch-mode (optional): either auto (default) or once which exits with 0 if the tests were successful and 1 if they failed."
  ([project] (lmain/info help-string))
  ([project js-env]
   (lmain/info
     (str "We have the js-env (" js-env
       ") but we are missing the build-id. See `lein doo` for help.")))

  ([project js-env build-id] (doo project js-env build-id "auto"))
  ([project js-env build-id watch-mode]
   (assert (contains? #{"auto" "once"} watch-mode)
     (str "Possible watch-modes are auto or once, " watch-mode " was given."))
   (doo/assert-js-env (keyword js-env))
   ;; FIX: execute in a try catch like the one in run-local-project
   ;; FIX: get the version dynamically
   (let [auto? (= "auto" watch-mode)
         project' (add-dep project ['doo "0.1.1-SNAPSHOT"])
         builds (-> project' config/extract-options :builds)
         {:keys [source-paths compiler] :as build} (find-by-id builds build-id)]
     (assert (not (empty? build))
       (str "The given build (" build-id ") was not found in these options: "
         (clojure.string/join ", " (map :id builds))))
     (doo/assert-compiler-opts compiler)
     ;; FIX: there is probably a bug regarding the incorrect use of builds
     (run-local-project project' [builds]
       '(require 'cljs.build.api 'doo.core)
       (if auto?
         `(cljs.build.api/watch
            (apply cljs.build.api/inputs ~source-paths)
            (assoc ~compiler
              :watch-fn (fn []
                          (doo.core/run-script (keyword ~js-env) ~compiler))))
         `(do (cljs.build.api/build
                (apply cljs.build.api/inputs ~source-paths) ~compiler)
              (let [results# (doo.core/run-script (keyword ~js-env) ~compiler)]
                (System/exit (:exit results#)))))))))
