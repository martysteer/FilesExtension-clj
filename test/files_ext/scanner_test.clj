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
