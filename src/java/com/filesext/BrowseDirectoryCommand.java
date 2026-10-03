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
            final Object lock = new Object();

            SwingUtilities.invokeLater(() -> {
                try {
                    JFileChooser chooser = new JFileChooser();
                    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                    chooser.setDialogTitle("Select directory");

                    // Create a simple frame to act as parent and ensure dialog is visible
                    javax.swing.JFrame frame = new javax.swing.JFrame();
                    frame.setAlwaysOnTop(true);
                    frame.setVisible(true);
                    frame.setLocationRelativeTo(null);

                    int returnVal = chooser.showOpenDialog(frame);

                    frame.dispose();

                    synchronized (lock) {
                        if (returnVal == JFileChooser.APPROVE_OPTION) {
                            result[0] = chooser.getSelectedFile().getAbsolutePath();
                        }
                        lock.notify();
                    }
                } catch (Exception e) {
                    synchronized (lock) {
                        lock.notify();
                    }
                }
            });

            // Wait for dialog to complete
            synchronized (lock) {
                lock.wait(60000); // 60 second timeout
            }

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
