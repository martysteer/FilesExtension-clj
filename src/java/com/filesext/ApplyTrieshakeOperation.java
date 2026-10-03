package com.filesext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.google.refine.history.Change;
import com.google.refine.history.HistoryEntry;
import com.google.refine.model.Cell;
import com.google.refine.model.Column;
import com.google.refine.model.Project;
import com.google.refine.model.Row;
import com.google.refine.model.changes.CellAtRow;
import com.google.refine.model.changes.ColumnAdditionChange;
import com.google.refine.model.changes.MassChange;
import com.google.refine.model.AbstractOperation;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;

public class ApplyTrieshakeOperation extends AbstractOperation {

    final protected String _columnName;
    final protected int _prefixLength;
    final protected String _mode;
    final protected Map<String, String> _params;

    @JsonCreator
    public ApplyTrieshakeOperation(
            @JsonProperty("columnName") String columnName,
            @JsonProperty("prefixLength") int prefixLength,
            @JsonProperty("mode") String mode,
            @JsonProperty("params") Map<String, String> params) {
        _columnName = columnName;
        _prefixLength = prefixLength;
        _mode = mode;
        _params = params != null ? params : new HashMap<>();
    }

    @JsonProperty("columnName")
    public String getColumnName() { return _columnName; }
    @JsonProperty("prefixLength")
    public int getPrefixLength() { return _prefixLength; }
    @JsonProperty("mode")
    public String getMode() { return _mode; }
    @JsonProperty("params")
    public Map<String, String> getParams() { return _params; }

    @Override
    protected String getBriefDescription(Project project) {
        return "Trieshake column " + _columnName + " (" + _mode + ", p=" + _prefixLength + ")";
    }

    @Override
    @SuppressWarnings("unchecked")
    protected HistoryEntry createHistoryEntry(Project project, long historyEntryID)
            throws Exception {
        Column column = project.columnModel.getColumnByName(_columnName);
        if (column == null) {
            throw new Exception("No column named " + _columnName);
        }
        final int cellIndex = column.getCellIndex();

        // Collect all values from the column
        List<String> values = new ArrayList<>();
        for (Row row : project.rows) {
            Object v = row.getCellValue(cellIndex);
            values.add(v != null ? v.toString() : "");
        }

        // Call Clojure engine
        IFn applyFn = Clojure.var("files-ext.engine", "trieshake-apply");
        List<IPersistentMap> results = (List<IPersistentMap>) applyFn.invoke(
            values, _prefixLength, _mode, _params);

        // Determine max chunk count across all results
        Keyword chunksKey = Keyword.intern("chunks");
        Keyword targetFilenameKey = Keyword.intern("target-filename");
        int maxChunks = 0;
        for (Object r : results) {
            IPersistentMap m = (IPersistentMap) r;
            List<?> chunks = (List<?>) m.valAt(chunksKey);
            if (chunks != null && chunks.size() > maxChunks) {
                maxChunks = chunks.size();
            }
        }

        // Build column additions
        List<Change> changes = new ArrayList<>();
        int insertAt = project.columnModel.columns.size();

        for (int c = 0; c < maxChunks; c++) {
            List<CellAtRow> cells = new ArrayList<>();
            for (int row = 0; row < results.size(); row++) {
                IPersistentMap m = (IPersistentMap) results.get(row);
                List<?> chunks = (List<?>) m.valAt(chunksKey);
                if (chunks != null && c < chunks.size()) {
                    cells.add(new CellAtRow(row,
                        new Cell(chunks.get(c).toString(), null)));
                }
            }
            changes.add(new ColumnAdditionChange(
                "chunk_" + (c + 1), insertAt++, cells));
        }

        // Add encoded_filename column
        List<CellAtRow> filenameCells = new ArrayList<>();
        for (int row = 0; row < results.size(); row++) {
            IPersistentMap m = (IPersistentMap) results.get(row);
            Object fn = m.valAt(targetFilenameKey);
            if (fn != null) {
                filenameCells.add(new CellAtRow(row,
                    new Cell(fn.toString(), null)));
            }
        }
        changes.add(new ColumnAdditionChange(
            "encoded_filename", insertAt, filenameCells));

        return new HistoryEntry(historyEntryID, project,
            getBriefDescription(project), this, new MassChange(changes, false));
    }
}
