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
