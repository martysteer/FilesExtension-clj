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
