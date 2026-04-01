package me.michael.kei.actionrecorder;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.InvocationTargetException;

final class RecordingUploadWindow {
    private static final int MAX_CONSOLE_CHARS = 200_000;

    private final JFrame frame;
    private final JProgressBar progressBar;
    private final JLabel summaryLabel;
    private final JLabel phaseLabel;
    private final JLabel fileLabel;
    private final JTextArea console;

    private RecordingUploadWindow() {
        frame = new JFrame("Upload Progress");
        frame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        frame.setSize(860, 480);
        frame.setLocationByPlatform(true);
        frame.setLayout(new BorderLayout(8, 8));

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBorder(BorderFactory.createEmptyBorder(10, 10, 0, 10));

        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setValue(0);
        progressBar.setString("0% (0/0 chunks)");

        summaryLabel = new JLabel("Chunks uploaded: 0 / 0  |  Pending: 0");
        phaseLabel = new JLabel("Phase: Idle");
        fileLabel = new JLabel("File: -");

        top.add(progressBar);
        top.add(summaryLabel);
        top.add(phaseLabel);
        top.add(fileLabel);
        frame.add(top, BorderLayout.NORTH);

        console = new JTextArea();
        console.setEditable(false);
        console.setLineWrap(false);
        console.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scrollPane = new JScrollPane(console);
        scrollPane.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(0, 10, 10, 10),
                BorderFactory.createTitledBorder("Upload Console")
        ));
        frame.add(scrollPane, BorderLayout.CENTER);
    }

    static RecordingUploadWindow createIfSupported() {
        if (GraphicsEnvironment.isHeadless()) {
            return null;
        }
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                return new RecordingUploadWindow();
            }
            final RecordingUploadWindow[] holder = new RecordingUploadWindow[1];
            SwingUtilities.invokeAndWait(() -> holder[0] = new RecordingUploadWindow());
            return holder[0];
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (InvocationTargetException e) {
            return null;
        }
    }

    void showWindow() {
        SwingUtilities.invokeLater(() -> {
            if (!frame.isVisible()) {
                frame.setVisible(true);
            }
            frame.toFront();
        });
    }

    void appendLog(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            console.append(line);
            if (!line.endsWith("\n")) {
                console.append("\n");
            }
            trimConsole();
            console.setCaretPosition(console.getDocument().getLength());
        });
    }

    void updateProgress(RecordingUploadDaemon.UploadProgress progress) {
        if (progress == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            int percent = progress.completionPercent();
            progressBar.setValue(percent);
            progressBar.setString(percent + "% (" + progress.uploadedChunks + "/" + progress.totalChunks + " chunks)");
            summaryLabel.setText("Chunks uploaded: " + progress.uploadedChunks + " / " + progress.totalChunks
                    + "  |  Pending: " + progress.pendingChunks);
            phaseLabel.setText("Phase: " + fallback(progress.phase, "Idle"));
            fileLabel.setText("File: " + fallback(progress.currentFile, "-"));
        });
    }

    private void trimConsole() {
        int extra = console.getDocument().getLength() - MAX_CONSOLE_CHARS;
        if (extra <= 0) {
            return;
        }
        console.replaceRange("", 0, extra);
    }

    private static String fallback(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }
}
