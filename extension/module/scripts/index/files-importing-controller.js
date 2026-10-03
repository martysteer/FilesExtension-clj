/* Files Extension — importing controller (Clojure port) */

// Internationalization
var lang = navigator.language.split("-")[0]
    || navigator.userLanguage.split("-")[0];
var dictionary = "";
$.ajax({
  url: "command/core/load-language?",
  type: "POST",
  async: false,
  data: { module: "files-ext" },
  success: function (data) {
    dictionary = data["dictionary"];
    lang = data["lang"];
  }
});
$.i18n().load(dictionary, lang);

Refine.FilesImportingController = function (createProjectUI) {
  this._createProjectUI = createProjectUI;
  this._parsingPanel = createProjectUI.addCustomPanel();
  createProjectUI.addSourceSelectionUI({
    label: $.i18n("files-import/menu-localdirectory"),
    id: "files-local-directory",
    ui: new Refine.LocalDirectorySourceUI(this)
  });
};
Refine.CreateProjectUI.controllers.push(Refine.FilesImportingController);

Refine.FilesImportingController.prototype.startImportingDocument = function (doc) {
  var dismiss = DialogSystem.showBusy($.i18n("files-import/preparing"));
  var self = this;
  self._doc = doc;
  Refine.postCSRF("command/core/create-importing-job", null, function (data) {
    Refine.wrapCSRF(function (token) {
      $.post(
        "command/core/importing-controller?" + $.param({
          controller: "files-ext/files-importing-controller",
          subCommand: "initialize-parser-ui",
          directoryPaths: JSON.stringify(doc.directoryPaths),
          csrf_token: token
        }),
        null,
        function (data2) {
          dismiss();
          if (data2.status == "ok") {
            self._jobID = data.jobID;
            self._projectName = data2.projectName;
            self._showParsingPanel();
          } else {
            alert(data2.message);
          }
        },
        "json"
      );
    });
  }, "json");
};

Refine.FilesImportingController.prototype.getOptions = function () {
  return {
    directoryPaths: this._doc.directoryPaths,
    columns: this._doc.columns,
    recursive: this._doc.recursive || false,
    maxDepth: this._doc.maxDepth || 1,
    parentDirMode: this._doc.parentDirMode || "row"
  };
};

Refine.FilesImportingController.prototype._showParsingPanel = function () {
  var self = this;
  this._parsingPanel.unbind().empty().html(
    DOM.loadHTML("files-ext", "scripts/index/parsing-panel.html"));
  this._parsingPanelElmts = DOM.bind(this._parsingPanel);

  this._parsingPanelElmts.startOverButton.html($.i18n("files-parsing/start-over"));
  this._parsingPanelElmts.commons_proj_name.html($.i18n("files-parsing/proj-name"));
  $("#or-import-projtags").html($.i18n("files-parsing/project-tags"));
  this._parsingPanelElmts.createProjectButton.html($.i18n("files-parsing/create-proj"));

  $("#tagsInput").select2({
    data: Refine.TagsManager._getAllProjectTags(),
    tags: true,
    tokenSeparators: [",", " "]
  });

  if (this._parsingPanelResizer) {
    $(window).unbind("resize", this._parsingPanelResizer);
  }
  this._parsingPanelResizer = function () {
    var elmts = self._parsingPanelElmts;
    var width = self._parsingPanel.width();
    var height = self._parsingPanel.height();
    var headerHeight = elmts.wizardHeader.outerHeight(true);
    elmts.dataPanel
      .css("left", "0px")
      .css("top", headerHeight + "px")
      .css("width", (width - DOM.getHPaddings(elmts.dataPanel)) + "px")
      .css("height", (height - headerHeight - DOM.getVPaddings(elmts.dataPanel)) + "px");
    elmts.progressPanel
      .css("left", "0px")
      .css("top", headerHeight + "px")
      .css("width", (width - DOM.getHPaddings(elmts.progressPanel)) + "px")
      .css("height", (height - headerHeight - 250 - DOM.getVPaddings(elmts.progressPanel)) + "px");
  };
  $(window).resize(this._parsingPanelResizer);
  this._parsingPanelResizer();

  this._parsingPanelElmts.startOverButton.click(function () {
    Refine.CreateProjectUI.cancelImportingJob(self._jobID);
    delete self._doc;
    delete self._jobID;
    delete self._projectName;
    self._createProjectUI.showSourceSelectionPanel();
  });
  this._parsingPanelElmts.createProjectButton.click(function () {
    self._createProject();
  });
  this._parsingPanelElmts.projectNameInput[0].value =
    self._projectName || $.i18n("files-parsing/project-default-name");

  this._createProjectUI.showCustomPanel(this._parsingPanel);
  this._updatePreview();
};

Refine.FilesImportingController.prototype._updatePreview = function () {
  var self = this;
  this._parsingPanelElmts.dataPanel.hide();
  this._parsingPanelElmts.progressPanel.show();

  Refine.wrapCSRF(function (token) {
    $.post(
      "command/core/importing-controller?" + $.param({
        controller: "files-ext/files-importing-controller",
        jobID: self._jobID,
        subCommand: "scan-preview",
        csrf_token: token
      }),
      { options: JSON.stringify(self.getOptions()) },
      function (result) {
        if (result.status == "ok") {
          self._getPreviewData(function (projectData) {
            self._parsingPanelElmts.progressPanel.hide();
            self._parsingPanelElmts.dataPanel.show();
            new Refine.PreviewTable(
              projectData, self._parsingPanelElmts.dataPanel.unbind().empty());
          });
        } else {
          self._parsingPanelElmts.progressPanel.hide();
          alert(result.message || "Error scanning directory");
        }
      },
      "json"
    );
  });
};

Refine.FilesImportingController.prototype._getPreviewData = function (callback, numRows) {
  var self = this;
  var result = {};
  $.post(
    "command/core/get-models?" + $.param({ importingJobID: this._jobID }),
    null,
    function (data) {
      for (var n in data) {
        if (data.hasOwnProperty(n)) result[n] = data[n];
      }
      $.post(
        "command/core/get-rows?" + $.param({
          importingJobID: self._jobID,
          start: 0,
          limit: numRows || 100
        }),
        null,
        function (data) {
          result.rowModel = data;
          callback(result);
        },
        "json"
      );
    },
    "json"
  );
};

Refine.FilesImportingController.prototype._createProject = function () {
  var projectName = $.trim(this._parsingPanelElmts.projectNameInput[0].value);
  if (projectName.length == 0) {
    window.alert("Please name the project.");
    this._parsingPanelElmts.projectNameInput.focus();
    return;
  }
  var self = this;
  var options = this.getOptions();
  options.projectName = projectName;
  options.projectTags = $("#tagsInput").val();

  Refine.wrapCSRF(function (token) {
    $.post(
      "command/core/importing-controller?" + $.param({
        controller: "files-ext/files-importing-controller",
        jobID: self._jobID,
        subCommand: "create-project",
        csrf_token: token
      }),
      { options: JSON.stringify(options) },
      function (o) {
        if (o.status == "error") {
          alert(o.message);
        } else {
          var start = new Date();
          var timerID = window.setInterval(function () {
            self._createProjectUI.pollImportJob(
              start, self._jobID, timerID,
              function (job) { return "projectID" in job.config; },
              function (jobID, job) {
                window.clearInterval(timerID);
                Refine.CreateProjectUI.cancelImportingJob(jobID);
                document.location = "project?project=" + job.config.projectID;
              },
              function (job) {
                alert(Refine.CreateProjectUI.composeErrorMessage(job));
              }
            );
          }, 1000);
          self._createProjectUI.showImportProgressPanel(
            $.i18n("files-import/preparing"),
            function () {
              window.clearInterval(timerID);
              delete self._jobID;
              self._createProjectUI.showSourceSelectionPanel();
            }
          );
        }
      },
      "json"
    );
  });
};

// TagsManager (shared utility, same as original)
Refine.TagsManager = Refine.TagsManager || {};
Refine.TagsManager.allProjectTags = Refine.TagsManager.allProjectTags || [];
Refine.TagsManager._getAllProjectTags = Refine.TagsManager._getAllProjectTags || function () {
  var self = this;
  if (self.allProjectTags.length === 0) {
    jQuery.ajax({
      url: "command/core/get-all-project-tags",
      success: function (result) {
        self.allProjectTags = result.tags.sort(function (a, b) {
          return a.toLowerCase().localeCompare(b.toLowerCase());
        });
      },
      async: false
    });
  }
  return self.allProjectTags;
};
