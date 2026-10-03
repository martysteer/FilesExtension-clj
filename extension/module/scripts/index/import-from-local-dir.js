Refine.LocalDirectorySourceUI = function (controller) {
  this._controller = controller;
};

Refine.LocalDirectorySourceUI.prototype.attachUI = function (bodyDiv) {
  var self = this;
  bodyDiv.html(DOM.loadHTML("files-ext", "scripts/index/import-from-local-dir-form.html"));
  this._elmts = DOM.bind(bodyDiv);

  // Default columns
  var defaultCols = ["filename", "extension", "size-kb", "created", "modified", "path", "parent-directory", "mime-type"];
  bodyDiv.find(".col-checkbox").each(function () {
    if (defaultCols.indexOf($(this).val()) >= 0) {
      $(this).prop("checked", true);
    }
  });

  // Browse button
  this._elmts.browseButton.click(function () {
    Refine.postCSRF("command/files-ext/browse-directory", {}, function (data) {
      if (data.code === "ok" && data.path) {
        var current = self._elmts.pathInput.val().trim();
        if (current.length > 0 && !current.endsWith("\n")) {
          current += "\n";
        }
        self._elmts.pathInput.val(current + data.path);
      }
    }, "json");
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
      maxDepth: parseInt(self._elmts.depthInput.val(), 10) || 3,
      includeDirs: self._elmts.includeDirsCheck.is(":checked")
    };
    self._controller.startImportingDocument(doc);
  });
};

Refine.LocalDirectorySourceUI.prototype.focus = function () {};
