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
