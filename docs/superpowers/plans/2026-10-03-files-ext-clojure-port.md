# FilesExtension Clojure Port — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port FilesExtension from Java/Maven to Clojure/Leiningen following sankofa-fu-refine patterns, adding trieshake column operations.

**Architecture:** Thin Java interop layer (ImportingController + Command + Operation) calls AOT-compiled Clojure core (scanner, trieshake, engine) via `clojure.java.api.Clojure`. Uberjar loaded at runtime through a custom URLClassLoader in controller.js, identical to sankofa-fu-refine.

**Tech Stack:** Clojure 1.12.3, Leiningen, OpenRefine 3.10.0 (provided), Java 11 target, jQuery/DOM (frontend)

---

## File Map

| File | Responsibility |
|------|---------------|
| `project.clj` | Leiningen build config: deps, AOT, uberjar |
| `Makefile` | jar → extension → install → zip pipeline |
| `src/files_ext/scanner.clj` | File walking + metadata extraction |
| `src/files_ext/trieshake.clj` | Radix-trie chunking (ported from trieshake repo) |
| `src/files_ext/engine.clj` | Orchestration: scan→CSV, trieshake preview/apply |
| `src/java/com/filesext/FilesImportingController.java` | ImportingController impl, dispatches subcommands |
| `src/java/com/filesext/TrieshakeCommand.java` | Column menu HTTP handler for trieshake |
| `src/java/com/filesext/ApplyTrieshakeOperation.java` | Undoable operation: adds chunk columns |
| `test/files_ext/scanner_test.clj` | Scanner unit tests |
| `test/files_ext/trieshake_test.clj` | Trieshake unit tests (ported from trieshake repo) |
| `test/files_ext/engine_test.clj` | Engine integration tests |
| `extension/module/MOD-INF/module.properties` | Module metadata |
| `extension/module/MOD-INF/controller.js` | Classloader + registration |
| `extension/module/scripts/index/files-importing-controller.js` | Import source UI |
| `extension/module/scripts/index/import-from-local-dir.js` | Directory input UI |
| `extension/module/scripts/index/import-from-local-dir-form.html` | Directory input form HTML |
| `extension/module/scripts/index/parsing-panel.html` | Preview/create panel HTML |
| `extension/module/scripts/trieshake-dialog.js` | Trieshake preview dialog |
| `extension/module/scripts/trieshake-dialog.html` | Trieshake dialog HTML template |
| `extension/module/scripts/menu.js` | Column menu registration |
| `extension/module/styles/files-ext.css` | Styles for import UI + trieshake dialog |
| `extension/module/langs/translation-en.json` | i18n strings |

---

### Task 1: Project scaffolding — build files and module skeleton

**Files:**
- Create: `project.clj`
- Create: `Makefile`
- Create: `extension/module/MOD-INF/module.properties`
- Create: `.gitignore`

- [ ] **Step 1: Remove old Java/Maven build files from this branch**

Delete files that belong to the Java build. The old code remains on master for reference.

```bash
cd "/Users/marty/Desktop/Pipeline scripts/FilesExtension-clj"
rm -f pom.xml
rm -rf src/main src/test src/assembly
rm -rf module
rm -rf cypress
rm -rf .github
```

- [ ] **Step 2: Create project.clj**

```clojure
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
```

- [ ] **Step 3: Create Makefile**

```makefile
EXTENSION_DIR = extension/module/MOD-INF/lib
INSTALL_DIR = $(HOME)/Library/Application Support/OpenRefine/extensions/files-ext

.PHONY: jar test extension install clean zip

jar:
	lein uberjar

test:
	lein test

extension: jar
	mkdir -p $(EXTENSION_DIR)
	cp target/files-ext.jar $(EXTENSION_DIR)/files-ext.jar

install: extension
	rm -rf "$(INSTALL_DIR)"
	mkdir -p "$(INSTALL_DIR)"
	cp -R extension/module/ "$(INSTALL_DIR)/"

clean:
	lein clean
	rm -f $(EXTENSION_DIR)/files-ext.jar

zip: extension
	mkdir -p dist/files-ext
	cp -R extension/module/ dist/files-ext/
	cd dist && zip -r files-ext.zip files-ext
	rm -rf dist/files-ext
```

- [ ] **Step 4: Create module.properties**

Create `extension/module/MOD-INF/module.properties`:

```properties
name = files-ext
requires = core
```

- [ ] **Step 5: Create .gitignore**

```gitignore
target/
extension/module/MOD-INF/lib/
dist/
.lein-*
*.jar
.nrepl-port
.DS_Store
```

- [ ] **Step 6: Create directory structure**

```bash
mkdir -p src/files_ext
mkdir -p src/java/com/filesext
mkdir -p test/files_ext
mkdir -p extension/module/MOD-INF/lib
mkdir -p extension/module/scripts/index
mkdir -p extension/module/styles
mkdir -p extension/module/langs
```

- [ ] **Step 7: Verify lein resolves dependencies**

Run: `lein deps`
Expected: Dependencies download, no errors.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "chore: scaffold Clojure project, remove Java/Maven build"
```

---

### Task 2: trieshake.clj — port chunking logic from trieshake repo

**Files:**
- Create: `src/files_ext/trieshake.clj`
- Create: `test/files_ext/trieshake_test.clj`

- [ ] **Step 1: Write trieshake tests**

Port the test suite from `trieshake-clj/test/trieshake/planner_test.clj`, changing namespace references. Create `test/files_ext/trieshake_test.clj`:

```clojure
(ns files-ext.trieshake-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [files-ext.trieshake :as ts]))

;; === chunk-string tests ===

(deftest test-chunk-string-spec-example-prefix4
  (testing "BL0000010600001 at p=4 -> [BL00, 0001, 0600, 001]"
    (is (= ["BL00" "0001" "0600" "001"]
           (ts/chunk-string "BL0000010600001" 4)))))

(deftest test-chunk-string-spec-example-prefix3
  (testing "BL0001 at p=3 -> [BL0, 001]"
    (is (= ["BL0" "001"]
           (ts/chunk-string "BL0001" 3)))))

(deftest test-chunk-string-prefix4-with-remainder
  (testing "BL0001 at p=4 -> [BL00, 01]"
    (is (= ["BL00" "01"]
           (ts/chunk-string "BL0001" 4)))))

(deftest test-chunk-string-clean-modulo
  (testing "ABCD at p=2 -> [AB, CD]"
    (is (= ["AB" "CD"]
           (ts/chunk-string "ABCD" 2)))))

(deftest test-chunk-string-single-chunk-shorter
  (is (= ["AB"] (ts/chunk-string "AB" 4))))

(deftest test-chunk-string-single-chunk-exact
  (is (= ["ABCD"] (ts/chunk-string "ABCD" 4))))

(deftest test-chunk-string-empty
  (is (= [] (ts/chunk-string "" 4))))

(deftest test-chunk-string-single-character
  (is (= ["A"] (ts/chunk-string "A" 3))))

;; === compute-target tests ===

(deftest test-compute-target-spec-forward-prefix4
  (testing "BL/00/00/01/06/00001/report.txt at p=4"
    (let [result (ts/compute-target "BL/00/00/01/06/00001/report.txt" 4 ".txt")]
      (is (= "BL00/0001/0600/001" (:target-dir result)))
      (is (= "BL00_0001_0600_001_report.txt" (:target-filename result)))
      (is (= ["BL00" "0001" "0600" "001"] (:chunks result)))
      (is (= "BL0000010600001" (:concat-string result)))
      (is (= "report.txt" (:leafname result))))))

(deftest test-compute-target-spec-forward-prefix3
  (testing "BL/00/01/file.txt at p=3"
    (let [result (ts/compute-target "BL/00/01/file.txt" 3 ".txt")]
      (is (= "BL0/001" (:target-dir result)))
      (is (= "BL0_001_file.txt" (:target-filename result)))
      (is (= ["BL0" "001"] (:chunks result)))
      (is (= "BL0001" (:concat-string result)))
      (is (= "file.txt" (:leafname result))))))

(deftest test-compute-target-root-level-uses-stem
  (testing "File at root with no parent dirs uses filename stem as concat"
    (let [result (ts/compute-target "mydata.txt" 3 ".txt")]
      (is (= "mydata" (:concat-string result)))
      (is (= ["myd" "ata"] (:chunks result)))
      (is (= "mydata.txt" (:leafname result))))))

;; === detect-collisions tests ===

(defn- make-plan [source target & [extension]]
  (let [parts (str/split target #"/")
        target-dir (str/join "/" (butlast parts))
        target-filename (last parts)]
    {:source-path source
     :target-dir target-dir
     :target-filename target-filename
     :chunks []
     :concat-string ""
     :leafname ""
     :extension (or extension "")
     :is-collision false
     :collision-of nil}))

(deftest test-detect-collisions-none
  (testing "Distinct targets produce no collisions"
    (let [plans [(make-plan "a/data.txt" "AB/AB_data.txt")
                 (make-plan "b/other.txt" "CD/CD_other.txt")]
          resolved (ts/detect-collisions plans)]
      (is (every? (complement :is-collision) resolved)))))

(deftest test-detect-collisions-adds-suffix
  (testing "Two files mapping to same target: second gets --collision1"
    (let [plans [(make-plan "AB/CD/data.txt" "ABCD/ABCD_data.txt")
                 (make-plan "A/BCD/data.txt" "ABCD/ABCD_data.txt")]
          resolved (ts/detect-collisions plans)]
      (is (= "ABCD_data.txt" (:target-filename (first resolved))))
      (is (not (:is-collision (first resolved))))
      (is (= "ABCD_data--collision1.txt" (:target-filename (second resolved))))
      (is (:is-collision (second resolved))))))

(deftest test-detect-collisions-triple
  (testing "Three files to same target: collision1, collision2"
    (let [plans [(make-plan "x/data.txt" "XX/XX_data.txt")
                 (make-plan "y/data.txt" "XX/XX_data.txt")
                 (make-plan "z/data.txt" "XX/XX_data.txt")]
          resolved (ts/detect-collisions plans)]
      (is (= "XX_data.txt" (:target-filename (nth resolved 0))))
      (is (= "XX_data--collision1.txt" (:target-filename (nth resolved 1))))
      (is (= "XX_data--collision2.txt" (:target-filename (nth resolved 2)))))))

;; === compute-reverse-target tests ===

(deftest test-reverse-strip-prefix
  (testing "Reverse without regroup strips prefix, keeps dir structure"
    (let [result (ts/compute-reverse-target
                   "BL00/0001/0600/001/BL00_0001_0600_001_report.txt" ".txt")]
      (is (= "report.txt" (:leafname result)))
      (is (= "BL00/0001/0600/001" (:target-dir result)))
      (is (= "report.txt" (:target-filename result))))))

(deftest test-reverse-with-regroup
  (testing "Reverse with new-prefix-length 3 regroups"
    (let [result (ts/compute-reverse-target
                   "BL00/0001/0600/001/BL00_0001_0600_001_report.txt" ".txt"
                   :new-prefix-length 3)]
      (is (= "report.txt" (:leafname result)))
      (is (= "BL0000010600001" (:concat-string result)))
      (is (= ["BL0" "000" "010" "600" "001"] (:chunks result)))
      (is (= "BL0/000/010/600/001" (:target-dir result)))
      (is (= "BL0_000_010_600_001_report.txt" (:target-filename result))))))

(deftest test-reverse-skips-non-encoded
  (testing "File whose name doesn't match dir prefix is skipped"
    (is (nil? (ts/compute-reverse-target "BL00/0001/random_file.txt" ".txt")))))

(deftest test-reverse-strips-collision-suffix
  (testing "--collisionN suffix stripped during reverse"
    (let [result (ts/compute-reverse-target "ABCD/ABCD_data--collision1.txt" ".txt")]
      (is (= "data.txt" (:leafname result))))))

(deftest test-reverse-root-level-skipped
  (testing "File at root level is skipped in reverse"
    (is (nil? (ts/compute-reverse-target "report.txt" ".txt")))))

;; === trieshake-column tests ===

(deftest test-trieshake-column-forward
  (testing "Forward mode on a seq of path strings"
    (let [values ["BL/00/01/file.txt" "CD/02/data.csv"]
          result (ts/trieshake-column values 3
                   {:mode :forward :extension ".txt"})]
      (is (= 2 (count result)))
      (is (contains? (first result) :chunks))
      (is (contains? (first result) :target-filename)))))

(deftest test-trieshake-column-reverse
  (testing "Reverse mode on trieshaked paths"
    (let [values ["BL0/001/BL0_001_file.txt"]
          result (ts/trieshake-column values 3
                   {:mode :reverse :extension ".txt"})]
      (is (= 1 (count result)))
      (is (= "file.txt" (:leafname (first result)))))))

(deftest test-preview-limits-to-20
  (testing "Preview returns at most 20 results"
    (let [values (mapv #(str "dir" % "/file.txt") (range 50))
          result (ts/preview values 4 {:mode :forward :extension ".txt"})]
      (is (<= (count result) 20)))))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `lein test`
Expected: Compilation error — `files-ext.trieshake` namespace not found.

- [ ] **Step 3: Write trieshake.clj**

Create `src/files_ext/trieshake.clj` — direct port from `trieshake-clj/src/trieshake/planner.clj` plus `trieshake-column` and `preview` wrappers:

```clojure
(ns files-ext.trieshake
  "Radix-trie path chunking. Ported from trieshake-clj/planner.clj.
   Pure functions, no filesystem side effects."
  (:require [clojure.string :as str]))

(defn chunk-string
  "Split s into chunks of length n, with a shorter remainder."
  [s n]
  (if (empty? s)
    []
    (let [len (count s)
          full (quot len n)
          chunks (mapv #(subs s (* % n) (* (inc %) n)) (range full))
          remainder (subs s (* full n))]
      (if (seq remainder)
        (conj chunks remainder)
        chunks))))

(defn- strip-extension
  "Strip a (possibly multi-part) extension from a filename.
   Returns [stem ext] where ext includes the leading dot."
  [filename extension]
  (let [lower (str/lower-case filename)
        ext-lower (str/lower-case extension)]
    (if (and (seq extension) (str/ends-with? lower ext-lower))
      [(subs filename 0 (- (count filename) (count extension)))
       (subs filename (- (count filename) (count extension)))]
      [filename ""])))

(defn- strip-collision-suffix
  "Remove --collisionN suffix from a leafname."
  [leafname]
  (str/replace leafname #"--collision\d+" ""))

(defn- collision-filename
  "Insert --collisionN before the extension."
  [filename extension n]
  (let [[stem ext] (strip-extension filename extension)
        [stem ext] (if (empty? ext)
                     (let [dot-pos (str/last-index-of filename ".")]
                       (if (and dot-pos (pos? dot-pos))
                         [(subs filename 0 dot-pos) (subs filename dot-pos)]
                         [filename ""]))
                     [stem ext])]
    (str stem "--collision" n ext)))

(defn- path-parts
  "Split a path string into its components."
  [path-str]
  (filterv (complement empty?) (str/split path-str #"/")))

(defn- join-path
  "Join path components with /."
  [& parts]
  (str/join "/" (flatten parts)))

(defn compute-target
  "Compute the forward-mode target for a file at rel-path.
   Returns a plan entry map."
  [rel-path prefix-length extension & {:keys [encode-leafname] :or {encode-leafname true}}]
  (let [parts (path-parts rel-path)
        filename (last parts)
        parent-segments (butlast parts)
        [concat-string leafname]
        (if (seq parent-segments)
          [(apply str parent-segments) filename]
          (let [[stem _] (strip-extension filename extension)]
            [stem filename]))
        chunks (chunk-string concat-string prefix-length)
        target-dir (if (seq chunks) (join-path chunks) ".")
        target-filename (if (and encode-leafname (seq chunks))
                          (str (str/join "_" chunks) "_" leafname)
                          leafname)]
    {:source-path rel-path
     :target-dir target-dir
     :target-filename target-filename
     :chunks chunks
     :concat-string concat-string
     :leafname leafname
     :extension extension
     :is-collision false
     :collision-of nil}))

(defn compute-reverse-target
  "Compute the reverse-mode target for a file at rel-path.
   Returns a plan entry map, or nil if the file should be skipped."
  [rel-path extension & {:keys [new-prefix-length encode-leafname]
                         :or {new-prefix-length nil encode-leafname true}}]
  (let [parts (path-parts rel-path)
        filename (last parts)
        dir-parts (butlast parts)]
    (when (seq dir-parts)
      (let [prefix-groups (filterv #(not= % "collisions") dir-parts)]
        (when (seq prefix-groups)
          (let [expected-prefix (str (str/join "_" prefix-groups) "_")]
            (when (str/starts-with? filename expected-prefix)
              (let [leafname (subs filename (count expected-prefix))
                    leafname (strip-collision-suffix leafname)
                    concat-string (apply str prefix-groups)]
                (if new-prefix-length
                  (let [chunks (chunk-string concat-string new-prefix-length)
                        target-dir (if (seq chunks) (join-path chunks) ".")
                        target-filename (if (and encode-leafname (seq chunks))
                                          (str (str/join "_" chunks) "_" leafname)
                                          leafname)]
                    {:source-path rel-path
                     :target-dir target-dir
                     :target-filename target-filename
                     :chunks chunks
                     :concat-string concat-string
                     :leafname leafname
                     :extension extension
                     :is-collision false
                     :collision-of nil})
                  {:source-path rel-path
                   :target-dir (join-path prefix-groups)
                   :target-filename leafname
                   :chunks (vec prefix-groups)
                   :concat-string concat-string
                   :leafname leafname
                   :extension extension
                   :is-collision false
                   :collision-of nil})))))))))

(defn detect-collisions
  "Detect and resolve collisions in a list of plan entries.
   First occurrence keeps its target; subsequent get --collisionN suffixes."
  [plans]
  (let [seen (atom {})]
    (mapv (fn [plan]
            (let [target-key (str (:target-dir plan) "/" (:target-filename plan))]
              (if-not (contains? @seen target-key)
                (do (swap! seen assoc target-key 0)
                    plan)
                (let [n (inc (get @seen target-key))
                      _ (swap! seen assoc target-key n)
                      new-filename (collision-filename
                                     (:target-filename plan) (:extension plan) n)]
                  (assoc plan
                         :target-filename new-filename
                         :is-collision true
                         :collision-of target-key)))))
          plans)))

(defn trieshake-column
  "Apply trieshake to a seq of path strings.
   opts: {:mode :forward|:reverse, :extension str,
          :new-prefix-length int (reverse regroup only)}
   Returns vector of plan entry maps."
  [values prefix-length opts]
  (let [mode (:mode opts :forward)
        ext (:extension opts "")
        plans (if (= mode :reverse)
                (let [new-pl (:new-prefix-length opts)]
                  (->> values
                       (map #(compute-reverse-target % ext
                               :new-prefix-length new-pl))
                       (remove nil?)
                       vec))
                (->> values
                     (mapv #(compute-target % prefix-length ext))))]
    (if (= mode :forward)
      (detect-collisions plans)
      plans)))

(defn preview
  "Return at most 20 trieshake results for dialog preview."
  [values prefix-length opts]
  (let [sample (take 20 values)]
    (trieshake-column (vec sample) prefix-length opts)))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `lein test`
Expected: All trieshake tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/files_ext/trieshake.clj test/files_ext/trieshake_test.clj
git commit -m "feat: port trieshake chunking logic from trieshake repo"
```

---

### Task 3: scanner.clj — file walking and metadata extraction

**Files:**
- Create: `src/files_ext/scanner.clj`
- Create: `test/files_ext/scanner_test.clj`

- [ ] **Step 1: Write scanner tests**

Create `test/files_ext/scanner_test.clj`:

```clojure
(ns files-ext.scanner-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [files-ext.scanner :as scanner])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:dynamic *test-dir* nil)

(defn temp-dir-fixture [f]
  (let [dir (Files/createTempDirectory "files-ext-test"
              (into-array FileAttribute []))]
    (binding [*test-dir* (.toString dir)]
      ;; Create test files
      (spit (str *test-dir* "/hello.txt") "hello world")
      (spit (str *test-dir* "/data.csv") "a,b,c\n1,2,3")
      (spit (str *test-dir* "/image.png") "fake png content")
      (.mkdirs (io/file *test-dir* "subdir"))
      (spit (str *test-dir* "/subdir/nested.txt") "nested content")
      (spit (str *test-dir* "/.hidden") "hidden file")
      (try (f)
           (finally
             ;; Cleanup
             (doseq [f (reverse (file-seq (io/file *test-dir*)))]
               (.delete f)))))))

(use-fixtures :each temp-dir-fixture)

(deftest test-scan-depth-1-default
  (testing "Default depth=1 scans only immediate children"
    (let [result (scanner/scan-files *test-dir* {})]
      ;; Should find hello.txt, data.csv, image.png but NOT subdir/nested.txt or .hidden
      (is (= 3 (count result)))
      (is (every? #(contains? % :filename) result))
      (is (every? #(contains? % :path) result))
      (is (not (some #(= ".hidden" (:filename %)) result))))))

(deftest test-scan-recursive
  (testing "Recursive scan finds nested files"
    (let [result (scanner/scan-files *test-dir* {:recursive? true})]
      ;; hello.txt, data.csv, image.png, subdir/nested.txt (not .hidden)
      (is (= 4 (count result)))
      (is (some #(= "nested.txt" (:filename %)) result)))))

(deftest test-scan-hidden-excluded
  (testing "Hidden files (dot-prefix) are excluded"
    (let [result (scanner/scan-files *test-dir* {:recursive? true})]
      (is (not (some #(str/starts-with? (:filename %) ".") result))))))

(deftest test-scan-default-columns
  (testing "Default columns present in result"
    (let [result (scanner/scan-files *test-dir* {})
          first-file (first result)]
      (is (contains? first-file :filename))
      (is (contains? first-file :extension))
      (is (contains? first-file :size-kb))
      (is (contains? first-file :created))
      (is (contains? first-file :modified))
      (is (contains? first-file :path))
      (is (contains? first-file :mime-type)))))

(deftest test-scan-optional-columns
  (testing "Optional columns only present when requested"
    (let [result-default (scanner/scan-files *test-dir* {})
          result-with (scanner/scan-files *test-dir*
                        {:columns #{:filename :path :checksum}})]
      ;; Default result should not have :checksum
      (is (not (contains? (first result-default) :checksum)))
      ;; Explicit request should have :checksum
      (is (contains? (first result-with) :checksum))
      ;; And should have hex string
      (is (string? (:checksum (first result-with))))
      (is (= 64 (count (:checksum (first result-with))))))))

(deftest test-scan-column-selection
  (testing "Only requested columns appear in result"
    (let [result (scanner/scan-files *test-dir*
                   {:columns #{:filename :path}})]
      (is (contains? (first result) :filename))
      (is (contains? (first result) :path))
      (is (not (contains? (first result) :size-kb)))
      (is (not (contains? (first result) :extension))))))

(deftest test-scan-extension-extraction
  (testing "Extension extracted correctly"
    (let [result (scanner/scan-files *test-dir* {})
          txt-file (first (filter #(= "hello.txt" (:filename %)) result))]
      (is (= "txt" (:extension txt-file))))))

(deftest test-scan-size-numeric
  (testing "Size is a number"
    (let [result (scanner/scan-files *test-dir* {})
          first-file (first result)]
      (is (number? (:size-kb first-file))))))

(deftest test-scan-sorted-by-path
  (testing "Results sorted by path"
    (let [result (scanner/scan-files *test-dir* {:recursive? true})
          paths (mapv :path result)]
      (is (= paths (sort paths))))))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `lein test`
Expected: Compilation error — `files-ext.scanner` namespace not found.

- [ ] **Step 3: Write scanner.clj**

Create `src/files_ext/scanner.clj`:

```clojure
(ns files-ext.scanner
  "File walking and metadata extraction."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.nio.file Files LinkOption Path]
           [java.nio.file.attribute BasicFileAttributes
                                    PosixFileAttributeView
                                    PosixFilePermissions]
           [java.security MessageDigest]
           [java.io FileInputStream]))

(def default-columns
  "Columns included by default."
  #{:filename :extension :size-kb :created :modified :path :mime-type})

(defn- hidden?
  "True if any path component starts with a dot."
  [^java.io.File f ^java.io.File base]
  (let [base-path (.toPath base)
        rel (.relativize base-path (.toPath f))
        parts (iterator-seq (.iterator rel))]
    (some #(str/starts-with? (str %) ".") parts)))

(defn- file-extension [^String filename]
  (let [dot (.lastIndexOf filename ".")]
    (if (and (pos? dot) (< dot (dec (count filename))))
      (subs filename (inc dot))
      "")))

(defn- sha256 [^java.io.File f]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (with-open [is (FileInputStream. f)]
      (let [buf (byte-array 8192)]
        (loop []
          (let [n (.read is buf)]
            (when (pos? n)
              (.update digest buf 0 n)
              (recur))))))
    (apply str (map #(format "%02x" %) (.digest digest)))))

(defn- file-owner [^Path path]
  (try (str (Files/getOwner path (into-array LinkOption [])))
       (catch Exception _ "N/A")))

(defn- file-permissions [^Path path]
  (try
    (let [store (Files/getFileStore path)]
      (if (.supportsFileAttributeView store PosixFileAttributeView)
        (PosixFilePermissions/toString
          (Files/getPosixFilePermissions path (into-array LinkOption [])))
        "N/A"))
    (catch Exception _ "N/A")))

(defn- extract-metadata
  "Extract metadata for a single file. columns determines which fields."
  [^java.io.File f columns]
  (let [path (.toPath f)
        attrs (Files/readAttributes path BasicFileAttributes
                (into-array LinkOption []))
        filename (.getName f)
        m (transient {})]
    (when (columns :filename)
      (assoc! m :filename filename))
    (when (columns :extension)
      (assoc! m :extension (file-extension filename)))
    (when (columns :size-kb)
      (assoc! m :size-kb (Double/parseDouble
                           (format "%.2f" (/ (double (.size attrs)) 1024.0)))))
    (when (columns :created)
      (assoc! m :created (str (.creationTime attrs))))
    (when (columns :modified)
      (assoc! m :modified (str (.lastModifiedTime attrs))))
    (when (columns :path)
      (assoc! m :path (.getAbsolutePath f)))
    (when (columns :mime-type)
      (assoc! m :mime-type (or (Files/probeContentType path) "unknown")))
    (when (columns :checksum)
      (assoc! m :checksum (sha256 f)))
    (when (columns :permissions)
      (assoc! m :permissions (file-permissions path)))
    (when (columns :owner)
      (assoc! m :owner (file-owner path)))
    (persistent! m)))

(defn- within-depth?
  "True if file is within max-depth of base directory."
  [^java.io.File f ^java.io.File base max-depth]
  (let [base-path (.toPath base)
        rel (.relativize base-path (.toPath f))]
    (<= (.getNameCount rel) max-depth)))

(defn scan-files
  "Walk directory, collect file metadata maps.
   opts: {:recursive? false, :max-depth Integer/MAX_VALUE,
          :columns #{:filename :extension ...}}"
  [dir-path opts]
  (let [base (io/file dir-path)
        recursive? (:recursive? opts false)
        max-depth (:max-depth opts Integer/MAX_VALUE)
        columns (or (:columns opts) default-columns)
        files (if recursive?
                (->> (file-seq base)
                     (filter #(.isFile ^java.io.File %))
                     (remove #(hidden? % base))
                     (filter #(within-depth? % base max-depth)))
                (->> (.listFiles base)
                     (filter #(.isFile ^java.io.File %))
                     (remove #(hidden? % base))))]
    (->> files
         (map #(extract-metadata % columns))
         (sort-by :path)
         vec)))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `lein test`
Expected: All scanner and trieshake tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/files_ext/scanner.clj test/files_ext/scanner_test.clj
git commit -m "feat: file scanner with configurable metadata columns"
```

---

### Task 4: engine.clj — orchestration layer

**Files:**
- Create: `src/files_ext/engine.clj`
- Create: `test/files_ext/engine_test.clj`

- [ ] **Step 1: Write engine tests**

Create `test/files_ext/engine_test.clj`:

```clojure
(ns files-ext.engine-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [files-ext.engine :as engine])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:dynamic *test-dir* nil)
(def ^:dynamic *test-dir2* nil)

(defn temp-dir-fixture [f]
  (let [dir1 (Files/createTempDirectory "engine-test1"
               (into-array FileAttribute []))
        dir2 (Files/createTempDirectory "engine-test2"
               (into-array FileAttribute []))]
    (binding [*test-dir* (.toString dir1)
              *test-dir2* (.toString dir2)]
      (spit (str *test-dir* "/hello.txt") "hello")
      (spit (str *test-dir* "/data.csv") "a,b")
      (spit (str *test-dir2* "/report.pdf") "fake pdf")
      (try (f)
           (finally
             (doseq [d [dir1 dir2]]
               (doseq [f (reverse (file-seq (io/file (.toString d))))]
                 (.delete f))))))))

(use-fixtures :each temp-dir-fixture)

(deftest test-scan-directories-single
  (testing "Scan single directory"
    (let [result (engine/scan-directories [*test-dir*] {})]
      (is (= 2 (count result)))
      (is (every? #(contains? % :filename) result)))))

(deftest test-scan-directories-multiple
  (testing "Scan multiple directories merges results"
    (let [result (engine/scan-directories [*test-dir* *test-dir2*] {})]
      (is (= 3 (count result))))))

(deftest test-metadata-to-csv
  (testing "CSV output has header row and data rows"
    (let [maps [{:filename "a.txt" :extension "txt" :size-kb 1.0
                 :created "2026-01-01" :modified "2026-01-02"
                 :path "/tmp/a.txt" :mime-type "text/plain"}]
          csv (engine/metadata-to-csv maps)]
      (is (str/includes? csv "filename"))
      (is (str/includes? csv "a.txt"))
      (is (str/includes? csv "text/plain")))))

(deftest test-generate-project-name-single
  (is (= "folder-details_mydir"
         (engine/generate-project-name ["/some/path/mydir"]))))

(deftest test-generate-project-name-two
  (is (= "folder-details_dir1_dir2"
         (engine/generate-project-name ["/a/dir1" "/b/dir2"]))))

(deftest test-generate-project-name-three-plus
  (is (= "folder-details_dir1_dir2_and_more"
         (engine/generate-project-name ["/a/dir1" "/b/dir2" "/c/dir3"]))))

(deftest test-generate-project-name-empty
  (is (= "folder-details" (engine/generate-project-name []))))

(deftest test-trieshake-preview
  (testing "Preview returns map with results"
    (let [values ["BL/00/01/file.txt" "CD/02/data.csv"]
          result (engine/trieshake-preview values 3 "forward"
                   {"extension" ".txt"})]
      (is (vector? result))
      (is (= 2 (count result))))))

(deftest test-trieshake-apply
  (testing "Apply returns full results"
    (let [values ["BL/00/01/file.txt"]
          result (engine/trieshake-apply values 3 "forward"
                   {"extension" ".txt"})]
      (is (= 1 (count result)))
      (is (contains? (first result) :chunks)))))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `lein test`
Expected: Compilation error — `files-ext.engine` namespace not found.

- [ ] **Step 3: Write engine.clj**

Create `src/files_ext/engine.clj`:

```clojure
(ns files-ext.engine
  "Orchestration: bridges scanner and trieshake for Java interop layer."
  (:require [files-ext.scanner :as scanner]
            [files-ext.trieshake :as trieshake]
            [clojure.string :as str]))

(defn scan-directories
  "Scan multiple directories, merge results.
   dir-paths: seq of absolute path strings.
   opts: passed through to scanner/scan-files."
  [dir-paths opts]
  (->> dir-paths
       (mapcat #(scanner/scan-files % opts))
       (sort-by :path)
       vec))

(defn metadata-to-csv
  "Convert metadata maps to CSV string.
   Column order is determined by keys of first map."
  [metadata-maps]
  (if (empty? metadata-maps)
    ""
    (let [columns (keys (first metadata-maps))
          header (str/join "," (map name columns))
          rows (map (fn [m]
                      (str/join ","
                        (map (fn [k]
                               (let [v (get m k "")]
                                 ;; Quote values containing commas or quotes
                                 (if (or (str/includes? (str v) ",")
                                         (str/includes? (str v) "\""))
                                   (str "\"" (str/replace (str v) "\"" "\"\"") "\"")
                                   (str v))))
                             columns)))
                    metadata-maps)]
      (str/join "\n" (cons header rows)))))

(defn generate-project-name
  "Auto-generate project name from directory paths."
  [dir-paths]
  (if (empty? dir-paths)
    "folder-details"
    (let [names (mapv #(.getName (java.io.File. ^String %)) dir-paths)]
      (cond
        (= 1 (count names))
        (str "folder-details_" (first names))

        (= 2 (count names))
        (str "folder-details_" (first names) "_" (second names))

        :else
        (str "folder-details_" (first names) "_" (second names) "_and_more")))))

(defn- parse-opts
  "Convert string-keyed params map to trieshake opts."
  [mode params]
  (let [ext (get params "extension" "")]
    (cond-> {:mode (keyword mode) :extension ext}
      (get params "newPrefixLength")
      (assoc :new-prefix-length (Integer/parseInt
                                  (str (get params "newPrefixLength")))))))

(defn trieshake-preview
  "Preview trieshake for dialog. Returns vector of result maps."
  [values prefix-length mode params]
  (trieshake/preview values prefix-length (parse-opts mode params)))

(defn trieshake-apply
  "Full trieshake computation. Returns vector of result maps."
  [values prefix-length mode params]
  (trieshake/trieshake-column values prefix-length (parse-opts mode params)))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `lein test`
Expected: All tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/files_ext/engine.clj test/files_ext/engine_test.clj
git commit -m "feat: engine orchestration layer for scan and trieshake"
```

---

### Task 5: Java interop — FilesImportingController

**Files:**
- Create: `src/java/com/filesext/FilesImportingController.java`

- [ ] **Step 1: Write FilesImportingController.java**

```java
package com.filesext;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.refine.ProjectManager;
import com.google.refine.RefineServlet;
import com.google.refine.commands.HttpUtilities;
import com.google.refine.importers.SeparatorBasedImporter;
import com.google.refine.importing.ImportingController;
import com.google.refine.importing.ImportingJob;
import com.google.refine.importing.ImportingManager;
import com.google.refine.model.Project;
import com.google.refine.util.JSONUtilities;
import com.google.refine.util.ParsingUtilities;

import static com.google.refine.commands.Command.respondJSON;
import static com.google.refine.importing.ImportingUtilities.*;

import clojure.java.api.Clojure;
import clojure.lang.IFn;

public class FilesImportingController implements ImportingController {

    static {
        ClassLoader moduleCL = FilesImportingController.class.getClassLoader();
        Thread t = Thread.currentThread();
        ClassLoader old = t.getContextClassLoader();
        try {
            t.setContextClassLoader(moduleCL);
            Class.forName("files_ext.engine__init", true, moduleCL);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("files-ext: engine namespace not loadable", e);
        } finally {
            t.setContextClassLoader(old);
        }
    }

    protected RefineServlet servlet;

    @Override
    public void init(RefineServlet servlet) {
        this.servlet = servlet;
    }

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        HttpUtilities.respond(response, "error", "GET not implemented");
    }

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        response.setCharacterEncoding("UTF-8");
        Properties parameters = ParsingUtilities.parseUrlParameters(request);
        String subCommand = parameters.getProperty("subCommand");

        if ("initialize-parser-ui".equals(subCommand)) {
            doInitializeParserUI(request, response, parameters);
        } else if ("scan-preview".equals(subCommand)) {
            try {
                doScanPreview(request, response, parameters);
            } catch (Exception e) {
                HttpUtilities.respond(response, "error",
                    "Unable to scan directory: " + e.getMessage());
            }
        } else if ("create-project".equals(subCommand)) {
            doCreateProject(request, response, parameters);
        } else {
            HttpUtilities.respond(response, "error", "No such sub command");
        }
    }

    @SuppressWarnings("unchecked")
    private void doInitializeParserUI(HttpServletRequest request,
            HttpServletResponse response, Properties parameters)
            throws ServletException, IOException {
        String dirPathsJson = parameters.getProperty("directoryPaths");
        List<String> dirPaths = ParsingUtilities.mapper.readValue(
            dirPathsJson, List.class);

        IFn genName = Clojure.var("files-ext.engine", "generate-project-name");
        String projectName = (String) genName.invoke(dirPaths);

        ObjectNode result = ParsingUtilities.mapper.createObjectNode();
        JSONUtilities.safePut(result, "status", "ok");
        JSONUtilities.safePut(result, "projectName", projectName);
        HttpUtilities.respond(response, result.toString());
    }

    @SuppressWarnings("unchecked")
    private void doScanPreview(HttpServletRequest request,
            HttpServletResponse response, Properties parameters) throws Exception {
        long jobID = Long.parseLong(parameters.getProperty("jobID"));
        ImportingJob job = ImportingManager.getJob(jobID);
        if (job == null) {
            HttpUtilities.respond(response, "error", "No such import job");
            return;
        }

        job.prepareNewProject();
        ObjectNode config = job.getOrCreateDefaultConfig();
        ObjectNode retrievalRecord = ParsingUtilities.mapper.createObjectNode();
        JSONUtilities.safePut(config, "retrievalRecord", retrievalRecord);
        ArrayNode fileRecords = ParsingUtilities.mapper.createArrayNode();
        JSONUtilities.safePut(retrievalRecord, "files", fileRecords);

        job.updating = true;

        ObjectNode optionObj = ParsingUtilities.evaluateJsonStringToObjectNode(
            request.getParameter("options"));

        // Parse options from frontend
        ArrayNode dirArray = (ArrayNode) optionObj.get("directoryPaths");
        List<String> dirPaths = new ArrayList<>();
        for (int i = 0; i < dirArray.size(); i++) {
            dirPaths.add(dirArray.get(i).asText());
        }

        boolean recursive = optionObj.has("recursive") &&
            optionObj.get("recursive").asBoolean(false);
        int maxDepth = optionObj.has("maxDepth") ?
            optionObj.get("maxDepth").asInt(Integer.MAX_VALUE) : Integer.MAX_VALUE;

        // Build columns set from frontend checkboxes
        ArrayNode colArray = (ArrayNode) optionObj.get("columns");
        List<String> columnNames = new ArrayList<>();
        if (colArray != null) {
            for (int i = 0; i < colArray.size(); i++) {
                columnNames.add(colArray.get(i).asText());
            }
        }

        // Call Clojure engine
        IFn scanDirs = Clojure.var("files-ext.engine", "scan-directories");
        IFn toCsv = Clojure.var("files-ext.engine", "metadata-to-csv");

        // Build opts map for Clojure
        IFn keyword = Clojure.var("clojure.core", "keyword");
        IFn hashSet = Clojure.var("clojure.core", "hash-set");
        IFn hashMap = Clojure.var("clojure.core", "hash-map");
        Object[] kwArgs = columnNames.stream()
            .map(c -> keyword.invoke(c))
            .toArray();
        Object cols = clojure.lang.PersistentHashSet.create(
            java.util.Arrays.asList(kwArgs));
        Object opts = hashMap.invoke(
            keyword.invoke("recursive?"), recursive,
            keyword.invoke("max-depth"), maxDepth,
            keyword.invoke("columns"), cols);

        Object metadata = scanDirs.invoke(dirPaths, opts);
        String csv = (String) toCsv.invoke(metadata);

        // Write CSV to temp file for SeparatorBasedImporter
        File csvFile = allocateFile(job.getRawDataDir(), "filesList.csv");
        java.nio.file.Files.writeString(csvFile.toPath(), csv);

        ObjectNode fileRecord = ParsingUtilities.mapper.createObjectNode();
        JSONUtilities.safePut(fileRecord, "origin", "directoryScan");
        JSONUtilities.safePut(fileRecord, "declaredEncoding", "UTF-8");
        JSONUtilities.safePut(fileRecord, "declaredMimeType", (String) null);
        JSONUtilities.safePut(fileRecord, "fileName", "filelist.csv");
        JSONUtilities.safePut(fileRecord, "location",
            getRelativePath(csvFile, job.getRawDataDir()));
        JSONUtilities.safePut(fileRecord, "size", csvFile.length());
        JSONUtilities.safePut(fileRecord, "format", "text/line-based/*sv");
        JSONUtilities.append(fileRecords, fileRecord);

        // Parse CSV into project
        ObjectNode parseOpts = ParsingUtilities.mapper.createObjectNode();
        JSONUtilities.safePut(parseOpts, "separator", ",");
        SeparatorBasedImporter parser = new SeparatorBasedImporter();
        List<Exception> exceptions = new LinkedList<>();
        parser.parse(job.project, job.metadata, job,
            JSONUtilities.getObjectList(fileRecords), "csv", -1,
            parseOpts, exceptions);
        if (!exceptions.isEmpty()) {
            throw exceptions.get(0);
        }
        job.project.update();
        job.touch();
        job.updating = false;

        ObjectNode result = ParsingUtilities.mapper.createObjectNode();
        ArrayNode rankedFormats = ParsingUtilities.mapper.createArrayNode();
        rankedFormats.add("text/line-based/*sv");
        JSONUtilities.safePut(config, "rankedFormats", rankedFormats);
        JSONUtilities.safePut(config, "hasData", true);
        JSONUtilities.safePut(result, "job", job.getJsonConfig());
        JSONUtilities.safePut(result, "status", "ok");
        respondJSON(response, result);
    }

    private void doCreateProject(HttpServletRequest request,
            HttpServletResponse response, Properties parameters)
            throws ServletException, IOException {
        long jobID = Long.parseLong(parameters.getProperty("jobID"));
        ImportingJob job = ImportingManager.getJob(jobID);
        if (job == null) {
            HttpUtilities.respond(response, "error", "No such import job");
            return;
        }

        job.updating = true;
        ObjectNode optionObj = ParsingUtilities.evaluateJsonStringToObjectNode(
            request.getParameter("options"));

        job.setState("creating-project");
        Project project = job.project;
        job.metadata.setName(
            JSONUtilities.getString(optionObj, "projectName", "Untitled"));
        job.metadata.setEncoding(
            JSONUtilities.getString(optionObj, "encoding", "UTF-8"));
        job.metadata.setTags(
            JSONUtilities.getStringArray(optionObj, "projectTags"));
        project.update();

        ProjectManager.singleton.registerProject(project, job.metadata);
        job.setProjectID(project.id);
        job.setState("created-project");
        job.touch();
        job.updating = false;

        HttpUtilities.respond(response, "ok", "done");
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `lein compile`
Expected: Compiles without errors (Clojure namespaces AOT'd, Java compiled against them).

- [ ] **Step 3: Commit**

```bash
git add src/java/com/filesext/FilesImportingController.java
git commit -m "feat: FilesImportingController Java interop for import source"
```

---

### Task 6: Java interop — TrieshakeCommand and ApplyTrieshakeOperation

**Files:**
- Create: `src/java/com/filesext/TrieshakeCommand.java`
- Create: `src/java/com/filesext/ApplyTrieshakeOperation.java`

- [ ] **Step 1: Write TrieshakeCommand.java**

```java
package com.filesext;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.refine.browsing.Engine;
import com.google.refine.browsing.FilteredRows;
import com.google.refine.browsing.RowVisitor;
import com.google.refine.commands.Command;
import com.google.refine.model.Column;
import com.google.refine.model.Project;
import com.google.refine.model.Row;
import com.google.refine.util.ParsingUtilities;

import clojure.java.api.Clojure;
import clojure.lang.IFn;

public class TrieshakeCommand extends Command {

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        try {
            Project project = getProject(request);
            Engine engine = getEngine(request, project);
            String columnName = request.getParameter("column");
            Column column = project.columnModel.getColumnByName(columnName);
            if (column == null) {
                respondJSON(response, errorJson("Column not found: " + columnName));
                return;
            }

            int prefixLength = Integer.parseInt(request.getParameter("prefixLength"));
            String mode = request.getParameter("mode");  // "forward" or "reverse"
            boolean isPreview = "true".equals(request.getParameter("preview"));

            Map<String, String> params = new HashMap<>();
            params.put("extension", request.getParameter("extension") != null ?
                request.getParameter("extension") : "");
            if (request.getParameter("newPrefixLength") != null) {
                params.put("newPrefixLength", request.getParameter("newPrefixLength"));
            }

            // Collect distinct values from column
            final int cellIndex = column.getCellIndex();
            final List<String> values = new ArrayList<>();
            FilteredRows fr = engine.getAllFilteredRows();
            fr.accept(project, new RowVisitor() {
                @Override public void start(Project p) {}
                @Override public boolean visit(Project p, int rowIndex, Row row) {
                    Object v = row.getCellValue(cellIndex);
                    if (v != null && !v.toString().isEmpty()) {
                        values.add(v.toString());
                    }
                    return false;
                }
                @Override public void end(Project p) {}
            });

            if (isPreview) {
                IFn previewFn = Clojure.var("files-ext.engine", "trieshake-preview");
                Object result = previewFn.invoke(values, prefixLength, mode, params);
                respondJSON(response, result);
            } else {
                // Queue the operation
                ApplyTrieshakeOperation op = new ApplyTrieshakeOperation(
                    columnName, prefixLength, mode, params);
                performOperation(project, op, request, response);
            }
        } catch (Exception e) {
            respondException(response, e);
        }
    }

    private static Map<String, String> errorJson(String msg) {
        Map<String, String> err = new HashMap<>();
        err.put("code", "error");
        err.put("message", msg);
        return err;
    }

    private void performOperation(Project project,
            ApplyTrieshakeOperation op,
            HttpServletRequest request, HttpServletResponse response)
            throws Exception {
        com.google.refine.process.Process process = op.createProcess(project,
            new java.util.Properties());
        project.processManager.queueProcess(process);
        Map<String, String> ok = new HashMap<>();
        ok.put("code", "ok");
        respondJSON(response, ok);
    }
}
```

- [ ] **Step 2: Write ApplyTrieshakeOperation.java**

```java
package com.filesext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.google.refine.history.Change;
import com.google.refine.history.HistoryEntry;
import com.google.refine.model.Cell;
import com.google.refine.model.Column;
import com.google.refine.model.Project;
import com.google.refine.model.Row;
import com.google.refine.model.changes.CellAtRow;
import com.google.refine.model.changes.ColumnAdditionChange;
import com.google.refine.model.changes.MassChange;
import com.google.refine.operations.AbstractOperation;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;

public class ApplyTrieshakeOperation extends AbstractOperation {

    final protected String _columnName;
    final protected int _prefixLength;
    final protected String _mode;
    final protected Map<String, String> _params;

    @JsonCreator
    public ApplyTrieshakeOperation(
            @JsonProperty("columnName") String columnName,
            @JsonProperty("prefixLength") int prefixLength,
            @JsonProperty("mode") String mode,
            @JsonProperty("params") Map<String, String> params) {
        _columnName = columnName;
        _prefixLength = prefixLength;
        _mode = mode;
        _params = params != null ? params : new HashMap<>();
    }

    @JsonProperty("columnName")
    public String getColumnName() { return _columnName; }
    @JsonProperty("prefixLength")
    public int getPrefixLength() { return _prefixLength; }
    @JsonProperty("mode")
    public String getMode() { return _mode; }
    @JsonProperty("params")
    public Map<String, String> getParams() { return _params; }

    @Override
    protected String getBriefDescription(Project project) {
        return "Trieshake column " + _columnName + " (" + _mode + ", p=" + _prefixLength + ")";
    }

    @Override
    @SuppressWarnings("unchecked")
    protected HistoryEntry createHistoryEntry(Project project, long historyEntryID)
            throws Exception {
        Column column = project.columnModel.getColumnByName(_columnName);
        if (column == null) {
            throw new Exception("No column named " + _columnName);
        }
        final int cellIndex = column.getCellIndex();

        // Collect all values from the column
        List<String> values = new ArrayList<>();
        for (Row row : project.rows) {
            Object v = row.getCellValue(cellIndex);
            values.add(v != null ? v.toString() : "");
        }

        // Call Clojure engine
        IFn applyFn = Clojure.var("files-ext.engine", "trieshake-apply");
        List<IPersistentMap> results = (List<IPersistentMap>) applyFn.invoke(
            values, _prefixLength, _mode, _params);

        // Determine max chunk count across all results
        Keyword chunksKey = Keyword.intern("chunks");
        Keyword targetFilenameKey = Keyword.intern("target-filename");
        int maxChunks = 0;
        for (Object r : results) {
            IPersistentMap m = (IPersistentMap) r;
            List<?> chunks = (List<?>) m.valAt(chunksKey);
            if (chunks != null && chunks.size() > maxChunks) {
                maxChunks = chunks.size();
            }
        }

        // Build column additions
        List<Change> changes = new ArrayList<>();
        int insertAt = project.columnModel.columns.size();

        for (int c = 0; c < maxChunks; c++) {
            List<CellAtRow> cells = new ArrayList<>();
            for (int row = 0; row < results.size(); row++) {
                IPersistentMap m = (IPersistentMap) results.get(row);
                List<?> chunks = (List<?>) m.valAt(chunksKey);
                if (chunks != null && c < chunks.size()) {
                    cells.add(new CellAtRow(row,
                        new Cell(chunks.get(c).toString(), null)));
                }
            }
            changes.add(new ColumnAdditionChange(
                "chunk_" + (c + 1), insertAt++, cells));
        }

        // Add encoded_filename column
        List<CellAtRow> filenameCells = new ArrayList<>();
        for (int row = 0; row < results.size(); row++) {
            IPersistentMap m = (IPersistentMap) results.get(row);
            Object fn = m.valAt(targetFilenameKey);
            if (fn != null) {
                filenameCells.add(new CellAtRow(row,
                    new Cell(fn.toString(), null)));
            }
        }
        changes.add(new ColumnAdditionChange(
            "encoded_filename", insertAt, filenameCells));

        return new HistoryEntry(historyEntryID, project,
            getBriefDescription(project), this, new MassChange(changes, false));
    }
}
```

- [ ] **Step 3: Verify compilation**

Run: `lein compile`
Expected: All Java and Clojure compiles without errors.

- [ ] **Step 4: Commit**

```bash
git add src/java/com/filesext/TrieshakeCommand.java \
        src/java/com/filesext/ApplyTrieshakeOperation.java
git commit -m "feat: TrieshakeCommand + ApplyTrieshakeOperation Java interop"
```

---

### Task 7: controller.js and module frontend skeleton

**Files:**
- Create: `extension/module/MOD-INF/controller.js`
- Create: `extension/module/langs/translation-en.json`
- Create: `extension/module/styles/files-ext.css`

- [ ] **Step 1: Write controller.js**

Create `extension/module/MOD-INF/controller.js`:

```javascript
var RefineServlet = Packages.com.google.refine.RefineServlet;
var IM = Packages.com.google.refine.importing.ImportingManager;

function loadJarClassLoader() {
  var jarFile = new java.io.File(module.getPath(), "MOD-INF/lib/files-ext.jar");
  var urls = java.lang.reflect.Array.newInstance(java.net.URL, 1);
  urls[0] = jarFile.toURI().toURL();
  return new java.net.URLClassLoader(
    urls, java.lang.Thread.currentThread().getContextClassLoader());
}

function init() {
  try {
    var jarCL = loadJarClassLoader();

    // Register importing controller
    var controllerClass = java.lang.Class.forName(
      "com.filesext.FilesImportingController", true, jarCL);
    IM.registerController(
      module, "files-importing-controller", controllerClass.newInstance());

    // Register trieshake command
    var trieshakeClass = java.lang.Class.forName(
      "com.filesext.TrieshakeCommand", true, jarCL);
    RefineServlet.registerCommand(
      module, "trieshake-column", trieshakeClass.newInstance());

    // Register trieshake operation for undo/redo
    var OperationRegistry = Packages.com.google.refine.operations.OperationRegistry;
    var applyClass = java.lang.Class.forName(
      "com.filesext.ApplyTrieshakeOperation", true, jarCL);
    OperationRegistry.registerOperation(module, "trieshake-column", applyClass);

    // Client-side resources — index (import) page
    var CSR = Packages.com.google.refine.ClientSideResourceManager;
    CSR.addPaths("index/scripts", module, [
      "scripts/index/files-importing-controller.js",
      "scripts/index/import-from-local-dir.js"
    ]);
    CSR.addPaths("index/styles", module, [
      "styles/files-ext.css"
    ]);

    // Client-side resources — project page (column menu)
    CSR.addPaths("project/scripts", module, [
      "scripts/trieshake-dialog.js",
      "scripts/menu.js"
    ]);
    CSR.addPaths("project/styles", module, [
      "styles/files-ext.css"
    ]);
  } catch (e) {
    java.lang.System.err.println("files-ext: init failed: " + e);
  }
}
```

- [ ] **Step 2: Write translation-en.json**

Create `extension/module/langs/translation-en.json`:

```json
{
  "files-import/menu-localdirectory": "Files from local directory",
  "files-import/preparing": "Preparing...",
  "files-import/enter-path": "Enter directory path(s)",
  "files-import/browse": "Browse...",
  "files-import/scan-recursive": "Scan recursively",
  "files-import/max-depth": "Max depth",
  "files-import/columns-label": "Metadata columns",
  "files-import/next": "Next \u00bb",
  "files-import/no-directory": "Please enter at least one directory path.",
  "files-parsing/start-over": "\u00ab Start over",
  "files-parsing/proj-name": "Project name:",
  "files-parsing/project-tags": "Tags:",
  "files-parsing/create-proj": "Create project \u00bb",
  "files-parsing/project-default-name": "folder-details"
}
```

- [ ] **Step 3: Write files-ext.css**

Create `extension/module/styles/files-ext.css`:

```css
/* Import UI */
.files-ext-source-panel {
  padding: 10px;
}
.files-ext-source-panel input[type="text"] {
  width: 400px;
  padding: 4px;
  font-family: monospace;
}
.files-ext-source-panel .files-ext-columns {
  margin: 10px 0;
  columns: 2;
}
.files-ext-source-panel .files-ext-columns label {
  display: block;
  margin: 2px 0;
}
.files-ext-source-panel .files-ext-depth-row {
  margin-left: 20px;
}

/* Trieshake dialog */
.trieshake-dialog {
  width: 700px;
}
.trieshake-dialog .dialog-header {
  padding: 8px 12px;
  font-weight: bold;
  border-bottom: 1px solid #ccc;
}
.trieshake-dialog .dialog-body {
  padding: 12px;
}
.trieshake-dialog .trieshake-controls label {
  display: block;
  margin: 6px 0;
}
.trieshake-dialog .trieshake-controls select,
.trieshake-dialog .trieshake-controls input[type="number"] {
  margin-left: 8px;
}
.trieshake-dialog .trieshake-preview-table {
  width: 100%;
  border-collapse: collapse;
  margin: 10px 0;
  font-size: 12px;
}
.trieshake-dialog .trieshake-preview-table th,
.trieshake-dialog .trieshake-preview-table td {
  border: 1px solid #ddd;
  padding: 3px 6px;
  text-align: left;
}
.trieshake-dialog .trieshake-preview-table th {
  background: #f5f5f5;
}
.trieshake-dialog .trieshake-footer {
  text-align: right;
  padding-top: 10px;
  border-top: 1px solid #ccc;
}
.trieshake-dialog .trieshake-close {
  float: right;
  cursor: pointer;
  font-size: 18px;
}

/* Import wizard (reused from original) */
.files-importing-wizard-header {
  padding: 5px;
  border-bottom: 1px solid #ccc;
}
.files-importing-parsing-data-panel {
  overflow: auto;
}
.files-importing-progress-data-panel {
  text-align: center;
  padding: 20px;
}
```

- [ ] **Step 4: Commit**

```bash
git add extension/module/MOD-INF/controller.js \
        extension/module/langs/translation-en.json \
        extension/module/styles/files-ext.css
git commit -m "feat: controller.js, i18n strings, and CSS"
```

---

### Task 8: Frontend — Import source UI

**Files:**
- Create: `extension/module/scripts/index/files-importing-controller.js`
- Create: `extension/module/scripts/index/import-from-local-dir.js`
- Create: `extension/module/scripts/index/import-from-local-dir-form.html`
- Create: `extension/module/scripts/index/parsing-panel.html`

- [ ] **Step 1: Write files-importing-controller.js**

Create `extension/module/scripts/index/files-importing-controller.js`:

```javascript
/* Files Extension — importing controller (Clojure port) */

// Internationalization
var lang = navigator.language.split("-")[0]
    || navigator.userLanguage.split("-")[0];
var dictionary = "";
$.ajax({
  url: "command/core/load-language?",
  type: "POST",
  async: false,
  data: { module: "files-ext" },
  success: function (data) {
    dictionary = data["dictionary"];
    lang = data["lang"];
  }
});
$.i18n().load(dictionary, lang);

Refine.FilesImportingController = function (createProjectUI) {
  this._createProjectUI = createProjectUI;
  this._parsingPanel = createProjectUI.addCustomPanel();
  createProjectUI.addSourceSelectionUI({
    label: $.i18n("files-import/menu-localdirectory"),
    id: "files-local-directory",
    ui: new Refine.LocalDirectorySourceUI(this)
  });
};
Refine.CreateProjectUI.controllers.push(Refine.FilesImportingController);

Refine.FilesImportingController.prototype.startImportingDocument = function (doc) {
  var dismiss = DialogSystem.showBusy($.i18n("files-import/preparing"));
  var self = this;
  self._doc = doc;
  Refine.postCSRF("command/core/create-importing-job", null, function (data) {
    Refine.wrapCSRF(function (token) {
      $.post(
        "command/core/importing-controller?" + $.param({
          controller: "files-ext/files-importing-controller",
          subCommand: "initialize-parser-ui",
          directoryPaths: JSON.stringify(doc.directoryPaths),
          csrf_token: token
        }),
        null,
        function (data2) {
          dismiss();
          if (data2.status == "ok") {
            self._jobID = data.jobID;
            self._projectName = data2.projectName;
            self._showParsingPanel();
          } else {
            alert(data2.message);
          }
        },
        "json"
      );
    });
  }, "json");
};

Refine.FilesImportingController.prototype.getOptions = function () {
  return {
    directoryPaths: this._doc.directoryPaths,
    columns: this._doc.columns,
    recursive: this._doc.recursive || false,
    maxDepth: this._doc.maxDepth || 1
  };
};

Refine.FilesImportingController.prototype._showParsingPanel = function () {
  var self = this;
  this._parsingPanel.unbind().empty().html(
    DOM.loadHTML("files-ext", "scripts/index/parsing-panel.html"));
  this._parsingPanelElmts = DOM.bind(this._parsingPanel);

  this._parsingPanelElmts.startOverButton.html($.i18n("files-parsing/start-over"));
  this._parsingPanelElmts.commons_proj_name.html($.i18n("files-parsing/proj-name"));
  $("#or-import-projtags").html($.i18n("files-parsing/project-tags"));
  this._parsingPanelElmts.createProjectButton.html($.i18n("files-parsing/create-proj"));

  $("#tagsInput").select2({
    data: Refine.TagsManager._getAllProjectTags(),
    tags: true,
    tokenSeparators: [",", " "]
  });

  if (this._parsingPanelResizer) {
    $(window).unbind("resize", this._parsingPanelResizer);
  }
  this._parsingPanelResizer = function () {
    var elmts = self._parsingPanelElmts;
    var width = self._parsingPanel.width();
    var height = self._parsingPanel.height();
    var headerHeight = elmts.wizardHeader.outerHeight(true);
    elmts.dataPanel
      .css("left", "0px")
      .css("top", headerHeight + "px")
      .css("width", (width - DOM.getHPaddings(elmts.dataPanel)) + "px")
      .css("height", (height - headerHeight - DOM.getVPaddings(elmts.dataPanel)) + "px");
    elmts.progressPanel
      .css("left", "0px")
      .css("top", headerHeight + "px")
      .css("width", (width - DOM.getHPaddings(elmts.progressPanel)) + "px")
      .css("height", (height - headerHeight - 250 - DOM.getVPaddings(elmts.progressPanel)) + "px");
  };
  $(window).resize(this._parsingPanelResizer);
  this._parsingPanelResizer();

  this._parsingPanelElmts.startOverButton.click(function () {
    Refine.CreateProjectUI.cancelImportingJob(self._jobID);
    delete self._doc;
    delete self._jobID;
    delete self._projectName;
    self._createProjectUI.showSourceSelectionPanel();
  });
  this._parsingPanelElmts.createProjectButton.click(function () {
    self._createProject();
  });
  this._parsingPanelElmts.projectNameInput[0].value =
    self._projectName || $.i18n("files-parsing/project-default-name");

  this._createProjectUI.showCustomPanel(this._parsingPanel);
  this._updatePreview();
};

Refine.FilesImportingController.prototype._updatePreview = function () {
  var self = this;
  this._parsingPanelElmts.dataPanel.hide();
  this._parsingPanelElmts.progressPanel.show();

  Refine.wrapCSRF(function (token) {
    $.post(
      "command/core/importing-controller?" + $.param({
        controller: "files-ext/files-importing-controller",
        jobID: self._jobID,
        subCommand: "scan-preview",
        csrf_token: token
      }),
      { options: JSON.stringify(self.getOptions()) },
      function (result) {
        if (result.status == "ok") {
          self._getPreviewData(function (projectData) {
            self._parsingPanelElmts.progressPanel.hide();
            self._parsingPanelElmts.dataPanel.show();
            new Refine.PreviewTable(
              projectData, self._parsingPanelElmts.dataPanel.unbind().empty());
          });
        } else {
          self._parsingPanelElmts.progressPanel.hide();
          alert(result.message || "Error scanning directory");
        }
      },
      "json"
    );
  });
};

Refine.FilesImportingController.prototype._getPreviewData = function (callback, numRows) {
  var self = this;
  var result = {};
  $.post(
    "command/core/get-models?" + $.param({ importingJobID: this._jobID }),
    null,
    function (data) {
      for (var n in data) {
        if (data.hasOwnProperty(n)) result[n] = data[n];
      }
      $.post(
        "command/core/get-rows?" + $.param({
          importingJobID: self._jobID,
          start: 0,
          limit: numRows || 100
        }),
        null,
        function (data) {
          result.rowModel = data;
          callback(result);
        },
        "json"
      );
    },
    "json"
  );
};

Refine.FilesImportingController.prototype._createProject = function () {
  var projectName = $.trim(this._parsingPanelElmts.projectNameInput[0].value);
  if (projectName.length == 0) {
    window.alert("Please name the project.");
    this._parsingPanelElmts.projectNameInput.focus();
    return;
  }
  var self = this;
  var options = this.getOptions();
  options.projectName = projectName;
  options.projectTags = $("#tagsInput").val();

  Refine.wrapCSRF(function (token) {
    $.post(
      "command/core/importing-controller?" + $.param({
        controller: "files-ext/files-importing-controller",
        jobID: self._jobID,
        subCommand: "create-project",
        csrf_token: token
      }),
      { options: JSON.stringify(options) },
      function (o) {
        if (o.status == "error") {
          alert(o.message);
        } else {
          var start = new Date();
          var timerID = window.setInterval(function () {
            self._createProjectUI.pollImportJob(
              start, self._jobID, timerID,
              function (job) { return "projectID" in job.config; },
              function (jobID, job) {
                window.clearInterval(timerID);
                Refine.CreateProjectUI.cancelImportingJob(jobID);
                document.location = "project?project=" + job.config.projectID;
              },
              function (job) {
                alert(Refine.CreateProjectUI.composeErrorMessage(job));
              }
            );
          }, 1000);
          self._createProjectUI.showImportProgressPanel(
            $.i18n("files-import/preparing"),
            function () {
              window.clearInterval(timerID);
              delete self._jobID;
              self._createProjectUI.showSourceSelectionPanel();
            }
          );
        }
      },
      "json"
    );
  });
};

// TagsManager (shared utility, same as original)
Refine.TagsManager = Refine.TagsManager || {};
Refine.TagsManager.allProjectTags = Refine.TagsManager.allProjectTags || [];
Refine.TagsManager._getAllProjectTags = Refine.TagsManager._getAllProjectTags || function () {
  var self = this;
  if (self.allProjectTags.length === 0) {
    jQuery.ajax({
      url: "command/core/get-all-project-tags",
      success: function (result) {
        self.allProjectTags = result.tags.sort(function (a, b) {
          return a.toLowerCase().localeCompare(b.toLowerCase());
        });
      },
      async: false
    });
  }
  return self.allProjectTags;
};
```

- [ ] **Step 2: Write import-from-local-dir.js**

Create `extension/module/scripts/index/import-from-local-dir.js`:

```javascript
Refine.LocalDirectorySourceUI = function (controller) {
  this._controller = controller;
};

Refine.LocalDirectorySourceUI.prototype.attachUI = function (bodyDiv) {
  var self = this;
  bodyDiv.html(DOM.loadHTML("files-ext", "scripts/index/import-from-local-dir-form.html"));
  this._elmts = DOM.bind(bodyDiv);

  // Default columns
  var defaultCols = ["filename", "extension", "size-kb", "created", "modified", "path", "mime-type"];
  bodyDiv.find(".col-checkbox").each(function () {
    if (defaultCols.indexOf($(this).val()) >= 0) {
      $(this).prop("checked", true);
    }
  });

  // Recursive toggle
  this._elmts.recursiveCheck.change(function () {
    self._elmts.depthRow.toggle($(this).is(":checked"));
  });
  this._elmts.depthRow.hide();

  // Submit
  this._elmts.form.on("submit", function (evt) {
    evt.preventDefault();
    var pathText = self._elmts.pathInput.val().trim();
    if (!pathText) {
      window.alert($.i18n("files-import/no-directory"));
      return;
    }
    // Split on comma or newline
    var paths = pathText.split(/[,\n]+/).map(function (s) { return s.trim(); })
      .filter(function (s) { return s.length > 0; });

    var columns = [];
    bodyDiv.find(".col-checkbox:checked").each(function () {
      columns.push($(this).val());
    });

    var doc = {
      directoryPaths: paths,
      columns: columns,
      recursive: self._elmts.recursiveCheck.is(":checked"),
      maxDepth: parseInt(self._elmts.depthInput.val(), 10) || 999
    };
    self._controller.startImportingDocument(doc);
  });
};

Refine.LocalDirectorySourceUI.prototype.focus = function () {};
```

- [ ] **Step 3: Write import-from-local-dir-form.html**

Create `extension/module/scripts/index/import-from-local-dir-form.html`:

```html
<form bind="form" class="files-ext-source-panel">
  <div class="grid-layout layout-normal">
    <label>Enter directory path(s) — comma or newline separated:</label>
    <textarea bind="pathInput" rows="3" style="width:400px; font-family:monospace;"
              placeholder="/path/to/directory"></textarea>

    <div class="files-ext-columns">
      <strong>Metadata columns:</strong>
      <label><input type="checkbox" class="col-checkbox" value="filename" checked disabled /> Filename</label>
      <label><input type="checkbox" class="col-checkbox" value="extension" checked disabled /> Extension</label>
      <label><input type="checkbox" class="col-checkbox" value="size-kb" checked /> Size (KB)</label>
      <label><input type="checkbox" class="col-checkbox" value="created" checked /> Created date</label>
      <label><input type="checkbox" class="col-checkbox" value="modified" checked /> Modified date</label>
      <label><input type="checkbox" class="col-checkbox" value="path" checked /> Full path</label>
      <label><input type="checkbox" class="col-checkbox" value="mime-type" checked /> MIME type</label>
      <label><input type="checkbox" class="col-checkbox" value="checksum" /> SHA-256 checksum</label>
      <label><input type="checkbox" class="col-checkbox" value="permissions" /> Permissions</label>
      <label><input type="checkbox" class="col-checkbox" value="owner" /> Owner</label>
    </div>

    <label>
      <input type="checkbox" bind="recursiveCheck" /> Scan recursively
    </label>
    <div bind="depthRow" class="files-ext-depth-row">
      Max depth: <input type="number" bind="depthInput" value="999" min="1" max="999" style="width:60px;" />
    </div>

    <hr />
    <button type="submit" bind="nextButton" class="button button-primary">Next &raquo;</button>
  </div>
</form>
```

- [ ] **Step 4: Write parsing-panel.html**

Create `extension/module/scripts/index/parsing-panel.html`:

```html
<div bind="wizardHeader" class="files-importing-wizard-header">
  <div class="grid-layout layout-tightest layout-full">
    <table role="presentation">
      <tr>
        <td width="1px"><button bind="startOverButton" class="button"></button></td>
        <td width="600px" style="text-align: right;" bind="commons_proj_name"></td>
        <td width="200px"><input class="inline" type="text" size="30" bind="projectNameInput" /></td>
        <td width="35px" style="text-align: right;"><label for="tagsInput" id="or-import-projtags"></label></td>
        <td width="200px">
          <div id="project-tags-container" class="inline">
            <select id="tagsInput" style="width: 200px;" multiple="multiple"></select>
          </div>
        </td>
        <td width="1px"><button bind="createProjectButton" class="button button-primary"></button></td>
      </tr>
    </table>
  </div>
</div>
<div bind="dataPanel" class="files-importing-parsing-data-panel"></div>
<div bind="progressPanel" class="files-importing-progress-data-panel">
  <img src="images/large-spinner.gif" />
</div>
```

- [ ] **Step 5: Commit**

```bash
git add extension/module/scripts/
git commit -m "feat: import source UI — directory input, column checkboxes, preview"
```

---

### Task 9: Frontend — Trieshake dialog and column menu

**Files:**
- Create: `extension/module/scripts/trieshake-dialog.html`
- Create: `extension/module/scripts/trieshake-dialog.js`
- Create: `extension/module/scripts/menu.js`

- [ ] **Step 1: Write trieshake-dialog.html**

Create `extension/module/scripts/trieshake-dialog.html`:

```html
<div class="dialog-frame trieshake-dialog">
  <div class="dialog-header">
    Trieshake: <span bind="columnName"></span>
    <span class="trieshake-close" bind="closeButton">&#10005;</span>
  </div>
  <div class="dialog-body">
    <div class="trieshake-controls">
      <label>Mode:
        <select bind="modeSelect">
          <option value="forward">Forward</option>
          <option value="reverse">Reverse</option>
        </select>
      </label>
      <label>Prefix length:
        <input type="number" bind="prefixInput" value="4" min="1" max="10" />
      </label>
      <label bind="newPrefixRow" style="display:none;">New prefix length (regroup):
        <input type="number" bind="newPrefixInput" value="3" min="1" max="10" />
      </label>
      <label>Extension filter:
        <input type="text" bind="extensionInput" value="" placeholder=".txt" size="8" />
      </label>
    </div>

    <div bind="previewContainer">
      <table class="trieshake-preview-table">
        <thead>
          <tr bind="previewHeader"></tr>
        </thead>
        <tbody bind="previewBody"></tbody>
      </table>
    </div>

    <div bind="statusText" style="padding: 4px 0; color: #666;"></div>

    <div class="trieshake-footer">
      <button class="button" bind="cancelButton">Cancel</button>
      <button class="button button-primary" bind="applyButton">Apply</button>
    </div>
  </div>
</div>
```

- [ ] **Step 2: Write trieshake-dialog.js**

Create `extension/module/scripts/trieshake-dialog.js`:

```javascript
function TrieshakeDialog(column, initialMode) {
  this._column = column;
  this._initialMode = initialMode || "forward";
  this._previewTimer = null;
  this._createDialog();
}

TrieshakeDialog.prototype._createDialog = function () {
  var self = this;
  var frame = $(DOM.loadHTML("files-ext", "scripts/trieshake-dialog.html"));
  this._elmts = DOM.bind(frame);
  this._elmts.columnName.text(this._column.name);
  this._elmts.modeSelect.val(this._initialMode);

  this._elmts.modeSelect.change(function () {
    var reverse = $(this).val() === "reverse";
    self._elmts.newPrefixRow.toggle(reverse);
    self._onParamChange();
  });
  this._elmts.prefixInput.on("change keyup", function () { self._onParamChange(); });
  this._elmts.newPrefixInput.on("change keyup", function () { self._onParamChange(); });
  this._elmts.extensionInput.on("change keyup", function () { self._onParamChange(); });

  this._elmts.applyButton.click(function () { self._apply(); });
  this._elmts.cancelButton.click(function () {
    DialogSystem.dismissUntil(self._level - 1);
  });
  this._elmts.closeButton.click(function () {
    DialogSystem.dismissUntil(self._level - 1);
  });

  this._level = DialogSystem.showDialog(frame);
  this._onParamChange();
};

TrieshakeDialog.prototype._onParamChange = function () {
  var self = this;
  clearTimeout(this._previewTimer);
  this._previewTimer = setTimeout(function () { self._fetchPreview(); }, 400);
};

TrieshakeDialog.prototype._fetchPreview = function () {
  var self = this;
  var mode = this._elmts.modeSelect.val();
  var params = {
    project: theProject.id,
    column: this._column.name,
    prefixLength: this._elmts.prefixInput.val(),
    mode: mode,
    extension: this._elmts.extensionInput.val(),
    preview: "true",
    engine: JSON.stringify(ui.browsingEngine.getJSON())
  };
  if (mode === "reverse") {
    params.newPrefixLength = this._elmts.newPrefixInput.val();
  }
  this._elmts.statusText.text("Loading preview...");
  $.post("command/files-ext/trieshake-column", params, function (data) {
    if (data.code === "error") {
      self._elmts.statusText.text(data.message);
      return;
    }
    self._renderPreview(data);
    self._elmts.statusText.text(data.length + " rows previewed");
  }, "json").fail(function () {
    self._elmts.statusText.text("Preview request failed");
  });
};

TrieshakeDialog.prototype._renderPreview = function (results) {
  var mode = this._elmts.modeSelect.val();
  var headerRow = this._elmts.previewHeader.empty();
  var tbody = this._elmts.previewBody.empty();

  if (!results || results.length === 0) {
    headerRow.append($("<th>").text("No results"));
    return;
  }

  if (mode === "forward") {
    headerRow.append($("<th>").text("Source"));
    // Determine max chunks
    var maxChunks = 0;
    results.forEach(function (r) {
      if (r.chunks && r.chunks.length > maxChunks) maxChunks = r.chunks.length;
    });
    for (var i = 0; i < maxChunks; i++) {
      headerRow.append($("<th>").text("chunk_" + (i + 1)));
    }
    headerRow.append($("<th>").text("encoded_filename"));

    results.forEach(function (r) {
      var tr = $("<tr>");
      tr.append($("<td>").text(r["source-path"] || ""));
      for (var i = 0; i < maxChunks; i++) {
        tr.append($("<td>").text(r.chunks && r.chunks[i] ? r.chunks[i] : ""));
      }
      tr.append($("<td>").text(r["target-filename"] || ""));
      tbody.append(tr);
    });
  } else {
    headerRow.append($("<th>").text("Source"));
    headerRow.append($("<th>").text("Target dir"));
    headerRow.append($("<th>").text("Filename"));

    results.forEach(function (r) {
      var tr = $("<tr>");
      tr.append($("<td>").text(r["source-path"] || ""));
      tr.append($("<td>").text(r["target-dir"] || ""));
      tr.append($("<td>").text(r["target-filename"] || ""));
      tbody.append(tr);
    });
  }
};

TrieshakeDialog.prototype._apply = function () {
  var self = this;
  var mode = this._elmts.modeSelect.val();
  var params = {
    column: this._column.name,
    prefixLength: this._elmts.prefixInput.val(),
    mode: mode,
    extension: this._elmts.extensionInput.val(),
    engine: JSON.stringify(ui.browsingEngine.getJSON())
  };
  if (mode === "reverse") {
    params.newPrefixLength = this._elmts.newPrefixInput.val();
  }
  Refine.postProcess("files-ext", "trieshake-column", {}, params,
    { modelsChanged: true },
    {
      onDone: function () {
        DialogSystem.dismissUntil(self._level - 1);
      }
    }
  );
};
```

- [ ] **Step 3: Write menu.js**

Create `extension/module/scripts/menu.js`:

```javascript
DataTableColumnHeaderUI.extendMenu(function (column, columnHeaderUI, menu) {
  menu.push({});   // separator
  menu.push({
    id: "files-ext-trieshake",
    label: "Trieshake",
    submenu: [
      {
        id: "files-ext/trieshake-forward",
        label: "Trieshake path column\u2026",
        click: function () { new TrieshakeDialog(column, "forward"); }
      },
      {
        id: "files-ext/trieshake-reverse",
        label: "Reverse trieshake\u2026",
        click: function () { new TrieshakeDialog(column, "reverse"); }
      }
    ]
  });
});
```

- [ ] **Step 4: Commit**

```bash
git add extension/module/scripts/trieshake-dialog.html \
        extension/module/scripts/trieshake-dialog.js \
        extension/module/scripts/menu.js
git commit -m "feat: trieshake dialog, preview, and column menu registration"
```

---

### Task 10: Build, install, and smoke test

**Files:**
- No new files

- [ ] **Step 1: Run all Clojure tests**

Run: `lein test`
Expected: All tests pass (scanner, trieshake, engine).

- [ ] **Step 2: Build uberjar**

Run: `make jar`
Expected: `target/files-ext.jar` created.

- [ ] **Step 3: Build extension**

Run: `make extension`
Expected: `extension/module/MOD-INF/lib/files-ext.jar` copied.

- [ ] **Step 4: Install to OpenRefine**

Run: `make install`
Expected: Extension installed to `~/Library/Application Support/OpenRefine/extensions/files-ext/`.

- [ ] **Step 5: Smoke test — import**

1. Start/restart OpenRefine
2. "Create Project" → should see "Files from local directory" source
3. Enter a directory path → select columns → click Next
4. Preview should show file metadata table
5. Name project → Create project
6. Verify project has correct columns and data

- [ ] **Step 6: Smoke test — trieshake**

1. Open a project with a path column
2. Column header → Trieshake → "Trieshake path column..."
3. Set prefix length → verify preview table shows chunks
4. Click Apply → verify new columns added (chunk_1, chunk_2, ..., encoded_filename)
5. Undo → verify columns removed

- [ ] **Step 7: Commit any fixes from smoke testing**

```bash
git add -A
git commit -m "fix: smoke test adjustments"
```

(Only if fixes needed. Skip if smoke tests pass clean.)

---

### Task 11: Final cleanup and documentation

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Update README.md**

Replace existing README with Clojure port documentation:
- Project description (what it does)
- Install instructions (`make install` or download zip)
- Usage: import from local directory + trieshake column action
- Development: `lein test`, `make install`, build loop
- Reference to design spec in `docs/`

- [ ] **Step 2: Verify clean build from scratch**

```bash
make clean && make test && make install
```

Expected: Clean build, all tests pass, extension installed.

- [ ] **Step 3: Final commit**

```bash
git add README.md
git commit -m "docs: update README for Clojure port"
```
