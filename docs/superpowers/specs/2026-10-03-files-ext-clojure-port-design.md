# FilesExtension Clojure Port — Design Spec

**Date:** 2026-10-03
**Branch:** `clojure-port-attempt1` in FilesExtension-clj repo
**Target:** OpenRefine 3.10.x

## Purpose

Port the FilesExtension OpenRefine plugin from Java/Maven to Clojure/Leiningen, following the same architectural pattern as sankofa-fu-refine. Add a trieshake column action (ported from the trieshake repo) for radix-trie path reorganization within OpenRefine projects.

## Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Architecture | Sankofa-fu pattern: thin Java interop + Clojure core + uberjar classloader | Proven in sankofa-fu-refine. Same classloader strategy, same build pipeline. |
| Build | Leiningen + Makefile (jar → extension → install → zip) | Copied from sankofa-fu-refine. Fast dev loop. |
| OR version | 3.10.x | Matches sankofa-fu-refine. Both extensions run on same OR install. |
| Repo | Existing FilesExtension-clj repo, `clojure-port-attempt1` branch | Java code stays on master as reference. |
| Directory browser | Replaced with text input + browse button | Simpler. Less UI code. |
| Metadata columns | Configurable via checkboxes (default: core subset) | User picks which columns. Expensive ops (SHA-256) opt-in. |
| Scan depth | Depth 1 default, recursive opt-in with depth limit | Matches current behaviour, adds recursive capability. |
| Trieshake UX | Column menu action with preview dialog | Sankofa-fu dialog pattern. User sets prefix-length, sees preview, applies. |

## Repository Structure

```
FilesExtension-clj/                    (clojure-port-attempt1 branch)
├── project.clj                        # Leiningen config
├── Makefile                           # Build pipeline
├── src/
│   ├── files_ext/                     # Clojure namespaces
│   │   ├── scanner.clj                # File walking + metadata extraction
│   │   ├── trieshake.clj              # Radix-trie chunking (from trieshake repo)
│   │   └── engine.clj                 # Orchestration
│   └── java/com/filesext/             # Java interop
│       ├── FilesImportingController.java
│       ├── TrieshakeCommand.java
│       └── ApplyTrieshakeOperation.java
├── test/files_ext/
│   ├── scanner_test.clj
│   ├── trieshake_test.clj
│   └── engine_test.clj
├── extension/module/                  # OpenRefine module (deployed as-is)
│   ├── MOD-INF/
│   │   ├── module.properties
│   │   ├── controller.js
│   │   └── lib/                       # uberjar lands here via Makefile
│   ├── scripts/
│   │   ├── index/
│   │   │   └── files-importing-controller.js
│   │   ├── trieshake-dialog.js
│   │   └── menu.js
│   ├── styles/
│   │   └── files-ext.css
│   └── langs/
│       └── translation-en.json
├── docs/
└── README.md
```

## project.clj

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

## Makefile

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

## Clojure Core

### scanner.clj

File walking and metadata extraction. Borrows scanning pattern from trieshake's scanner.

```clojure
(ns files-ext.scanner
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.nio.file Files LinkOption Path]
           [java.nio.file.attribute BasicFileAttributes]
           [java.security MessageDigest]
           [java.io FileInputStream]))
```

**`scan-files`** — Walk directory, collect file metadata maps.

Parameters:
- `dir-path` — string, absolute directory path
- `opts` — map:
  - `:recursive?` — boolean, default false
  - `:max-depth` — integer, default 1 (only used when recursive)
  - `:columns` — set of keywords, which metadata to extract

Default columns: `#{:filename :extension :size-kb :created :modified :path :mime-type}`

Optional columns: `#{:checksum :permissions :owner}`

Returns: vector of metadata maps, sorted by path.

**Metadata extraction per file:**

| Column | Source | Notes |
|--------|--------|-------|
| `:filename` | `.getName` | Always included |
| `:extension` | Suffix after last `.` | Empty string if no dot |
| `:size-kb` | `Files/size` / 1024.0 | Rounded to 2 decimal places |
| `:created` | `BasicFileAttributes/creationTime` | ISO-8601 string |
| `:modified` | `BasicFileAttributes/lastModifiedTime` | ISO-8601 string |
| `:path` | `.getAbsolutePath` | Full path string |
| `:mime-type` | `Files/probeContentType` | null → "unknown" |
| `:permissions` | `PosixFilePermissions/toString` | Try/catch for non-POSIX; "N/A" on Windows |
| `:checksum` | SHA-256 hex digest | Optional. Computed with FileInputStream. |
| `:owner` | `Files/getOwner` | Optional. Try/catch; "N/A" on failure. |

**Depth control:**
- Depth 1 (default): `(.listFiles (io/file dir-path))`, filter to files only
- Recursive: `(file-seq (io/file dir-path))`, with depth check via path component count relative to base
- Hidden file filtering: skip any file whose path contains a component starting with `.` (same as trieshake)

### trieshake.clj

Direct port of `trieshake-clj/src/trieshake/planner.clj`. Pure functions, no filesystem side effects.

```clojure
(ns files-ext.trieshake
  (:require [clojure.string :as str]))
```

**Functions (ported from trieshake):**
- `chunk-string [s n]` — split string into prefix-length chunks
- `compute-target [rel-path prefix-length & opts]` — forward mode: path → chunked target
- `compute-reverse-target [rel-path & opts]` — reverse mode: decode chunked path
- `detect-collisions [plans]` — resolve duplicate targets with `--collisionN` suffixes

**Additions for OpenRefine column context:**
- `trieshake-column [values prefix-length opts]` — applies forward/reverse to a seq of path strings, returns vector of result maps with chunk columns
- `preview [values prefix-length opts]` — returns first 20 results as JSON-friendly maps for dialog preview

### engine.clj

Orchestration namespace. Bridges scanner and trieshake for the Java interop layer.

```clojure
(ns files-ext.engine
  (:require [files-ext.scanner :as scanner]
            [files-ext.trieshake :as trieshake]))
```

**For import:**
- `scan-directories [dir-paths opts]` — scans multiple directories, merges results, returns vector of metadata maps
- `metadata-to-csv [metadata-maps]` — converts to CSV string for OpenRefine's SeparatorBasedImporter
- `generate-project-name [dir-paths]` — auto-name from folder names (up to 2, then "and_more")

**For trieshake:**
- `trieshake-preview [values prefix-length mode opts]` — preview for dialog
- `trieshake-apply [values prefix-length mode opts]` — full computation for operation

## Java Interop Layer

### FilesImportingController.java

Implements `com.google.refine.importing.ImportingController`.

**Subcommands:**
- `initialize-parser-ui` — returns project name suggestion + options JSON
- `scan-preview` — calls `engine/scan-directories`, generates CSV, loads into import job via `SeparatorBasedImporter`, returns preview
- `create-project` — finalises project with name, encoding, tags

Drops old subcommands: `filesystem-details`, `directory-hierarchy` (no tree browser).

Calls Clojure via:
```java
IFn require = Clojure.var("clojure.core", "require");
require.invoke(Clojure.read("files-ext.engine"));
IFn scanDirs = Clojure.var("files-ext.engine", "scan-directories");
```

### TrieshakeCommand.java

Extends `com.google.refine.commands.Command`.

**HTTP parameters:**
- `project` — project ID
- `column` — column name containing paths
- `prefixLength` — integer
- `mode` — "forward" or "reverse"
- `newPrefixLength` — integer (only for reverse+regroup)
- `preview` — boolean (if true, return preview JSON; if false, apply operation)

Preview mode: reads column values, calls `engine/trieshake-preview`, returns JSON.
Apply mode: creates `ApplyTrieshakeOperation`, queues via `AbstractOperation`.

### ApplyTrieshakeOperation.java

Extends `com.google.refine.operations.AbstractOperation`. Uses `createProcess()` to return a `QuickHistoryEntryProcess` that adds the new columns in one pass.

- Reads path values from source column
- Calls `engine/trieshake-apply`
- Adds new columns: `chunk_1`, `chunk_2`, ..., `chunk_N`, `encoded_filename`
- Column count determined by max chunks across all rows
- Undoable via OpenRefine's standard undo mechanism

## controller.js

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

    // Register trieshake operation for undo
    var OperationRegistry = Packages.com.google.refine.operations.OperationRegistry;
    var applyClass = java.lang.Class.forName(
      "com.filesext.ApplyTrieshakeOperation", true, jarCL);
    OperationRegistry.registerOperation(module, "trieshake-column", applyClass);

    // Client-side resources
    var CSR = Packages.com.google.refine.ClientSideResourceManager;

    // Import page scripts (index)
    CSR.addPaths("index/scripts", module,
      ["scripts/index/files-importing-controller.js"]);
    CSR.addPaths("index/styles", module,
      ["styles/files-ext.css"]);

    // Project page scripts (column menu)
    CSR.addPaths("project/scripts", module,
      ["scripts/trieshake-dialog.js", "scripts/menu.js"]);
    CSR.addPaths("project/styles", module,
      ["styles/files-ext.css"]);

  } catch (e) {
    java.lang.System.err.println("files-ext: init failed: " + e);
  }
}
```

## Frontend

### Import UI (files-importing-controller.js)

Registers "Files from local directory" source on OpenRefine's create project page.

**UI elements:**
- Text input for directory path(s) — comma or newline separated
- "Browse..." button — `<input type="file" webkitdirectory>` for browser directory selection
- Checkboxes for optional columns:
  - [x] Filename (always on, disabled)
  - [x] Extension (always on, disabled)
  - [x] Size (KB) (default on)
  - [x] Created date (default on)
  - [x] Modified date (default on)
  - [x] Full path (default on)
  - [x] MIME type (default on)
  - [ ] SHA-256 checksum (default off — expensive)
  - [ ] Permissions (default off)
  - [ ] Owner (default off)
- Checkbox: "Scan recursively" (default off)
  - When checked: depth spinner appears (default: unlimited, or specific number)
- "Preview" button → triggers scan-preview subcommand

### Trieshake Dialog (trieshake-dialog.js)

Launched from column menu. Follows sankofa-fu match-dialog pattern.

**UI elements:**
- Column name (read-only label)
- Mode toggle: Forward / Reverse
- Prefix length spinner (default 4, range 1-10)
- New prefix length spinner (visible only in Reverse mode, for regroup)
- Preview table: 20 rows
  - Forward: source path | chunk_1 | chunk_2 | ... | encoded_filename
  - Reverse: source path | restored original path
- Apply / Cancel buttons

Preview updates on any parameter change (debounced).

### Column Menu (menu.js)

Adds to column header dropdown:

```
"Trieshake" →
  "Trieshake path column..."      (forward mode)
  "Reverse trieshake..."           (reverse mode)
```

## Testing

### Clojure Tests

**scanner_test.clj:**
- Create temp directory with known files (various extensions, sizes)
- Verify metadata maps contain correct values
- Test depth-1 vs recursive scanning
- Test hidden file skipping (`.hidden` files excluded)
- Test column selection (requesting only `:filename :path` returns only those)
- Test MIME type detection
- Test SHA-256 checksum computation

**trieshake_test.clj:**
- Port assertions from `trieshake-clj/test/trieshake/planner_test.clj`
- Forward mode: known inputs → expected chunks + encoded filenames
- Reverse mode: chunked paths → restored originals
- Regroup: reverse + new prefix length
- Collision detection: duplicate targets get `--collisionN` suffixes
- Edge cases: root-level files, empty segments, single-character chunks

**engine_test.clj:**
- Integration: scan temp dir → CSV → verify CSV structure
- Project name generation: 1 dir, 2 dirs, 3+ dirs ("and_more")
- Trieshake preview: verify JSON shape and content

### Manual Smoke Test

1. `make install`
2. Restart OpenRefine
3. Create project → "Files from local directory" → enter path → preview → create
4. Verify columns match selected options
5. Column menu → Trieshake → set prefix-length → preview → apply
6. Verify new chunk columns added correctly
