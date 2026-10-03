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

  // Browse button - trigger file input
  this._elmts.browseButton.click(function () {
    self._elmts.fileInput.click();
  });

  // File input handler
  this._elmts.fileInput.on("change", function (evt) {
    var files = evt.target.files;
    if (files.length === 0) return;

    // Try to extract directory path from first file
    var firstFile = files[0];
    var path = "";

    // Try webkitRelativePath (shows path relative to selected dir)
    if (firstFile.webkitRelativePath) {
      var parts = firstFile.webkitRelativePath.split("/");
      if (parts.length > 1) {
        // Get the top-level directory name
        var dirName = parts[0];
        path = dirName;

        // If we have a path property (non-standard), try to extract parent
        if (firstFile.path) {
          var fullPath = firstFile.path;
          var idx = fullPath.lastIndexOf("/" + dirName);
          if (idx > 0) {
            path = fullPath.substring(0, idx + dirName.length + 1);
          }
        } else {
          // Prompt user to complete the path
          var userPath = window.prompt(
            "Browser security prevents reading the full path.\n" +
            "Please enter the full path to the '" + dirName + "' directory:",
            dirName
          );
          if (userPath) {
            path = userPath;
          }
        }
      }
    }

    if (path) {
      var current = self._elmts.pathInput.val().trim();
      if (current.length > 0 && !current.endsWith("\n")) {
        current += "\n";
      }
      self._elmts.pathInput.val(current + path);
    }

    // Reset file input for reuse
    evt.target.value = "";
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
