# Files Extension for OpenRefine (Clojure Port)

OpenRefine extension to import file metadata from local directories and apply trieshake path reorganisation to columns.

## Features

- **Import file metadata** — create an OpenRefine project from one or more local directories
  - Configurable columns: filename, extension, size, dates, path, MIME type, SHA-256 checksum, permissions, owner
  - Depth-1 default scan with recursive opt-in
  - Hidden files (dot-prefix) excluded automatically
- **Trieshake column action** — radix-trie chunking of path columns
  - Forward mode: split path into prefix chunks + encoded filename
  - Reverse mode: undo/regroup trieshaked paths with optional new prefix length
  - Preview dialog before applying
  - Undoable (Ctrl+Z) via OpenRefine history

Works with **OpenRefine 3.10.x**.

## Install

### From release zip

Download the `.zip` from [releases](https://github.com/OpenRefine/FilesExtension/releases), unzip into your OpenRefine extensions folder.

### From source

```
make install
```

This builds the uberjar, assembles the extension, and copies it to `~/Library/Application Support/OpenRefine/extensions/files-ext/`.

Restart OpenRefine after installing.

## Usage

### Import from local directory

1. **Create Project** → select **Files from local directory**
2. Enter one or more directory paths (comma or newline separated)
3. Choose metadata columns via checkboxes
4. Optionally enable recursive scanning
5. Click **Next** → preview data → **Create project**

### Trieshake

1. Open a project with a path column
2. Column header → **Trieshake** → **Trieshake path column...** (or **Reverse trieshake...**)
3. Set prefix length and extension filter
4. Preview shows chunk breakdown
5. Click **Apply** → new columns added (chunk_1, chunk_2, ..., encoded_filename)

## Development

### Prerequisites

- JDK 11+
- [Leiningen](https://leiningen.org/)
- OpenRefine 3.10.x (for runtime testing)

### Commands

| Command | Description |
|---------|-------------|
| `make test` | Run all Clojure tests |
| `make jar` | Build uberjar |
| `make extension` | Build extension (jar → extension dir) |
| `make install` | Install to OpenRefine extensions folder |
| `make clean` | Clean build artifacts |
| `make zip` | Package for distribution |

### Architecture

Thin Java interop layer + Clojure core, loaded via URLClassLoader (same pattern as [sankofa-fu-refine](https://github.com/OpenRefine/sankofa-fu-refine)):

- `src/files_ext/scanner.clj` — file walking and metadata extraction
- `src/files_ext/trieshake.clj` — radix-trie path chunking (pure functions)
- `src/files_ext/engine.clj` — orchestration layer bridging scanner + trieshake
- `src/java/com/filesext/` — Java interop (ImportingController, TrieshakeCommand, ApplyTrieshakeOperation)
- `extension/module/` — frontend (controller.js, import UI, trieshake dialog)

Design spec: `docs/superpowers/specs/2026-10-03-files-ext-clojure-port-design.md`

## License

CC-BY
