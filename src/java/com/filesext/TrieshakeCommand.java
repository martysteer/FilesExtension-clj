package com.filesext;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.refine.browsing.Engine;
import com.google.refine.browsing.FilteredRows;
import com.google.refine.browsing.RowVisitor;
import com.google.refine.commands.Command;
import com.google.refine.model.Column;
import com.google.refine.model.Project;
import com.google.refine.model.Row;

import clojure.java.api.Clojure;
import clojure.lang.IFn;

public class TrieshakeCommand extends Command {

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        try {
            Project project = getProject(request);
            Engine engine = getEngine(request, project);
            String columnName = request.getParameter("column");
            Column column = project.columnModel.getColumnByName(columnName);
            if (column == null) {
                respondJSON(response, errorJson("Column not found: " + columnName));
                return;
            }

            int prefixLength = Integer.parseInt(request.getParameter("prefixLength"));
            String mode = request.getParameter("mode");  // "forward" or "reverse"
            boolean isPreview = "true".equals(request.getParameter("preview"));

            Map<String, String> params = new HashMap<>();
            params.put("extension", request.getParameter("extension") != null ?
                request.getParameter("extension") : "");
            if (request.getParameter("newPrefixLength") != null) {
                params.put("newPrefixLength", request.getParameter("newPrefixLength"));
            }

            // Collect distinct values from column
            final int cellIndex = column.getCellIndex();
            final List<String> values = new ArrayList<>();
            FilteredRows fr = engine.getAllFilteredRows();
            fr.accept(project, new RowVisitor() {
                @Override public void start(Project p) {}
                @Override public boolean visit(Project p, int rowIndex, Row row) {
                    Object v = row.getCellValue(cellIndex);
                    if (v != null && !v.toString().isEmpty()) {
                        values.add(v.toString());
                    }
                    return false;
                }
                @Override public void end(Project p) {}
            });

            if (isPreview) {
                IFn previewFn = Clojure.var("files-ext.engine", "trieshake-preview");
                Object result = previewFn.invoke(values, prefixLength, mode, params);
                respondJSON(response, result);
            } else {
                // Queue the operation
                ApplyTrieshakeOperation op = new ApplyTrieshakeOperation(
                    columnName, prefixLength, mode, params);
                performOperation(project, op, request, response);
            }
        } catch (Exception e) {
            respondException(response, e);
        }
    }

    private static Map<String, String> errorJson(String msg) {
        Map<String, String> err = new HashMap<>();
        err.put("code", "error");
        err.put("message", msg);
        return err;
    }

    private void performOperation(Project project,
            ApplyTrieshakeOperation op,
            HttpServletRequest request, HttpServletResponse response)
            throws Exception {
        com.google.refine.process.Process process = op.createProcess(project,
            new java.util.Properties());
        project.processManager.queueProcess(process);
        Map<String, String> ok = new HashMap<>();
        ok.put("code", "ok");
        respondJSON(response, ok);
    }
}
