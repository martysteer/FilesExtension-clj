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
