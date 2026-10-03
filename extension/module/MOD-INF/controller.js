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

    // Register trieshake operation for undo/redo
    var OperationRegistry = Packages.com.google.refine.operations.OperationRegistry;
    var applyClass = java.lang.Class.forName(
      "com.filesext.ApplyTrieshakeOperation", true, jarCL);
    OperationRegistry.registerOperation(module, "trieshake-column", applyClass);

    // Client-side resources — index (import) page
    var CSR = Packages.com.google.refine.ClientSideResourceManager;
    CSR.addPaths("index/scripts", module, [
      "scripts/index/files-importing-controller.js",
      "scripts/index/import-from-local-dir.js"
    ]);
    CSR.addPaths("index/styles", module, [
      "styles/files-ext.css"
    ]);

    // Client-side resources — project page (column menu)
    CSR.addPaths("project/scripts", module, [
      "scripts/trieshake-dialog.js",
      "scripts/menu.js"
    ]);
    CSR.addPaths("project/styles", module, [
      "styles/files-ext.css"
    ]);
  } catch (e) {
    java.lang.System.err.println("files-ext: init failed: " + e);
  }
}
