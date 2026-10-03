(defproject com.filesext/files-ext "0.1.0"
  :description "OpenRefine extension: import file metadata + trieshake paths"
  :license {:name "CC-BY"}
  :dependencies [[org.clojure/clojure "1.12.3"]]
  :profiles {:provided {:dependencies
                        [[org.openrefine/main "3.10.0"]
                         [javax.servlet/javax.servlet-api "3.1.0"]]}}
  :java-source-paths ["src/java"]
  :javac-options ["--release" "11"]
  :source-paths ["src"]
  :test-paths ["test"]
  :target-path "target"
  :aot [files-ext.scanner
        files-ext.trieshake
        files-ext.engine]
  :uberjar-name "files-ext.jar")
