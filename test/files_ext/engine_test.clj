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
