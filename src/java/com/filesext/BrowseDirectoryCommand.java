package com.filesext;

import java.io.IOException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;

import com.google.refine.commands.Command;

public class BrowseDirectoryCommand extends Command {

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        try {
            final String[] result = { null };
            SwingUtilities.invokeAndWait(() -> {
                JFileChooser chooser = new JFileChooser();
                chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                chooser.setDialogTitle("Select directory");
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    result[0] = chooser.getSelectedFile().getAbsolutePath();
                }
            });
            if (result[0] != null) {
                respondJSON(response, java.util.Map.of("code", "ok", "path", result[0]));
            } else {
                respondJSON(response, java.util.Map.of("code", "cancel"));
            }
        } catch (Exception e) {
            respondException(response, e);
        }
    }
}
