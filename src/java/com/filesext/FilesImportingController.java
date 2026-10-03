package com.filesext;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.refine.ProjectManager;
import com.google.refine.RefineServlet;
import com.google.refine.commands.HttpUtilities;
import com.google.refine.importers.SeparatorBasedImporter;
import com.google.refine.importing.ImportingController;
import com.google.refine.importing.ImportingJob;
import com.google.refine.importing.ImportingManager;
import com.google.refine.model.Project;
import com.google.refine.util.JSONUtilities;
import com.google.refine.util.ParsingUtilities;

import static com.google.refine.commands.Command.respondJSON;
import static com.google.refine.importing.ImportingUtilities.*;

import clojure.java.api.Clojure;
import clojure.lang.IFn;

public class FilesImportingController implements ImportingController {

    static {
        ClassLoader moduleCL = FilesImportingController.class.getClassLoader();
        Thread t = Thread.currentThread();
        ClassLoader old = t.getContextClassLoader();
        try {
            t.setContextClassLoader(moduleCL);
            Class.forName("files_ext.engine__init", true, moduleCL);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("files-ext: engine namespace not loadable", e);
        } finally {
            t.setContextClassLoader(old);
        }
    }

    protected RefineServlet servlet;

    @Override
    public void init(RefineServlet servlet) {
        this.servlet = servlet;
    }

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        HttpUtilities.respond(response, "error", "GET not implemented");
    }

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        response.setCharacterEncoding("UTF-8");
        Properties parameters = ParsingUtilities.parseUrlParameters(request);
        String subCommand = parameters.getProperty("subCommand");

        if ("initialize-parser-ui".equals(subCommand)) {
            doInitializeParserUI(request, response, parameters);
        } else if ("scan-preview".equals(subCommand)) {
            try {
                doScanPreview(request, response, parameters);
            } catch (Exception e) {
                e.printStackTrace();
                HttpUtilities.respond(response, "error",
                    "Unable to scan directory: " + e.getMessage());
            }
        } else if ("create-project".equals(subCommand)) {
            doCreateProject(request, response, parameters);
        } else {
            HttpUtilities.respond(response, "error", "No such sub command");
        }
    }

    @SuppressWarnings("unchecked")
    private void doInitializeParserUI(HttpServletRequest request,
            HttpServletResponse response, Properties parameters)
            throws ServletException, IOException {
        String dirPathsJson = parameters.getProperty("directoryPaths");
        List<String> dirPaths = ParsingUtilities.mapper.readValue(
            dirPathsJson, List.class);

        IFn genName = Clojure.var("files-ext.engine", "generate-project-name");
        String projectName = (String) genName.invoke(dirPaths);

        ObjectNode result = ParsingUtilities.mapper.createObjectNode();
        JSONUtilities.safePut(result, "status", "ok");
        JSONUtilities.safePut(result, "projectName", projectName);
        HttpUtilities.respond(response, result.toString());
    }

    @SuppressWarnings("unchecked")
    private void doScanPreview(HttpServletRequest request,
            HttpServletResponse response, Properties parameters) throws Exception {
        long jobID = Long.parseLong(parameters.getProperty("jobID"));
        ImportingJob job = ImportingManager.getJob(jobID);
        if (job == null) {
            HttpUtilities.respond(response, "error", "No such import job");
            return;
        }

        job.prepareNewProject();
        ObjectNode config = job.getOrCreateDefaultConfig();
        ObjectNode retrievalRecord = ParsingUtilities.mapper.createObjectNode();
        JSONUtilities.safePut(config, "retrievalRecord", retrievalRecord);
        ArrayNode fileRecords = ParsingUtilities.mapper.createArrayNode();
        JSONUtilities.safePut(retrievalRecord, "files", fileRecords);

        job.updating = true;

        ObjectNode optionObj = ParsingUtilities.evaluateJsonStringToObjectNode(
            request.getParameter("options"));

        // Parse options from frontend
        ArrayNode dirArray = (ArrayNode) optionObj.get("directoryPaths");
        List<String> dirPaths = new ArrayList<>();
        for (int i = 0; i < dirArray.size(); i++) {
            dirPaths.add(dirArray.get(i).asText());
        }

        boolean recursive = optionObj.has("recursive") &&
            optionObj.get("recursive").asBoolean(false);
        int maxDepth = optionObj.has("maxDepth") ?
            optionObj.get("maxDepth").asInt(Integer.MAX_VALUE) : Integer.MAX_VALUE;

        // Build columns set from frontend checkboxes
        ArrayNode colArray = (ArrayNode) optionObj.get("columns");
        List<String> columnNames = new ArrayList<>();
        if (colArray != null) {
            for (int i = 0; i < colArray.size(); i++) {
                columnNames.add(colArray.get(i).asText());
            }
        }

        // Call Clojure engine
        IFn scanDirs = Clojure.var("files-ext.engine", "scan-directories");
        IFn toCsv = Clojure.var("files-ext.engine", "metadata-to-csv");

        // Build opts map for Clojure
        IFn keyword = Clojure.var("clojure.core", "keyword");
        IFn hashMap = Clojure.var("clojure.core", "hash-map");
        Object[] kwArgs = columnNames.stream()
            .map(c -> keyword.invoke(c))
            .toArray();
        Object cols = clojure.lang.PersistentHashSet.create(
            java.util.Arrays.asList(kwArgs));
        Object opts = hashMap.invoke(
            keyword.invoke("recursive?"), recursive,
            keyword.invoke("max-depth"), maxDepth,
            keyword.invoke("columns"), cols);

        Object metadata = scanDirs.invoke(dirPaths, opts);
        String csv = (String) toCsv.invoke(metadata);

        // Debug logging
        System.err.println("files-ext: scanned " + dirPaths + " with " + columnNames.size() + " columns");
        System.err.println("files-ext: metadata result count = " +
            (metadata instanceof java.util.List ? ((java.util.List)metadata).size() : "unknown"));
        System.err.println("files-ext: CSV length = " + csv.length());

        // Write CSV to temp file for SeparatorBasedImporter
        File csvFile = allocateFile(job.getRawDataDir(), "filesList.csv");
        java.nio.file.Files.writeString(csvFile.toPath(), csv);

        ObjectNode fileRecord = ParsingUtilities.mapper.createObjectNode();
        JSONUtilities.safePut(fileRecord, "origin", "directoryScan");
        JSONUtilities.safePut(fileRecord, "declaredEncoding", "UTF-8");
        JSONUtilities.safePut(fileRecord, "declaredMimeType", (String) null);
        JSONUtilities.safePut(fileRecord, "fileName", "filelist.csv");
        JSONUtilities.safePut(fileRecord, "location",
            getRelativePath(csvFile, job.getRawDataDir()));
        JSONUtilities.safePut(fileRecord, "size", csvFile.length());
        JSONUtilities.safePut(fileRecord, "format", "text/line-based/*sv");
        JSONUtilities.append(fileRecords, fileRecord);

        // Parse CSV into project
        ObjectNode parseOpts = ParsingUtilities.mapper.createObjectNode();
        JSONUtilities.safePut(parseOpts, "separator", ",");
        SeparatorBasedImporter parser = new SeparatorBasedImporter();
        List<Exception> exceptions = new LinkedList<>();
        parser.parse(job.project, job.metadata, job,
            JSONUtilities.getObjectList(fileRecords), "csv", -1,
            parseOpts, exceptions);
        if (!exceptions.isEmpty()) {
            throw exceptions.get(0);
        }
        job.project.update();
        job.touch();
        job.updating = false;

        ObjectNode result = ParsingUtilities.mapper.createObjectNode();
        ArrayNode rankedFormats = ParsingUtilities.mapper.createArrayNode();
        rankedFormats.add("text/line-based/*sv");
        JSONUtilities.safePut(config, "rankedFormats", rankedFormats);
        JSONUtilities.safePut(config, "hasData", true);
        JSONUtilities.safePut(result, "job", job.getJsonConfig());
        JSONUtilities.safePut(result, "status", "ok");
        respondJSON(response, result);
    }

    private void doCreateProject(HttpServletRequest request,
            HttpServletResponse response, Properties parameters)
            throws ServletException, IOException {
        long jobID = Long.parseLong(parameters.getProperty("jobID"));
        ImportingJob job = ImportingManager.getJob(jobID);
        if (job == null) {
            HttpUtilities.respond(response, "error", "No such import job");
            return;
        }

        job.updating = true;
        ObjectNode optionObj = ParsingUtilities.evaluateJsonStringToObjectNode(
            request.getParameter("options"));

        job.setState("creating-project");
        Project project = job.project;
        job.metadata.setName(
            JSONUtilities.getString(optionObj, "projectName", "Untitled"));
        job.metadata.setEncoding(
            JSONUtilities.getString(optionObj, "encoding", "UTF-8"));
        job.metadata.setTags(
            JSONUtilities.getStringArray(optionObj, "projectTags"));
        project.update();

        ProjectManager.singleton.registerProject(project, job.metadata);
        job.setProjectID(project.id);
        job.setState("created-project");
        job.touch();
        job.updating = false;

        HttpUtilities.respond(response, "ok", "done");
    }
}
