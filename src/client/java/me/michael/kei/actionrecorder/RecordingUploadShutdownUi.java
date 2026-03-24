package me.michael.kei.actionrecorder;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class RecordingUploadShutdownUi {

    private final Runnable onTerminate;
    private final double uiScale;
    private final boolean initiallyVisible;

    private JFrame frame;
    private JProgressBar progressBar;
    private JLabel label;
    private TrayIcon trayIcon;
    private boolean trayHiddenMessageShown;

    RecordingUploadShutdownUi(Runnable onTerminate, boolean initiallyVisible) {
        this.onTerminate = onTerminate;
        this.initiallyVisible = initiallyVisible;
        this.uiScale = detectUiScale();
        System.out.println("[UploadDaemon] Shutdown UI scale factor=" + uiScale);
        initUi();
    }

    void showWindow() {
        SwingUtilities.invokeLater(() -> {
            if (frame != null) {
                frame.setVisible(true);
                frame.toFront();
                frame.requestFocus();
            }
        });
    }

    void showStartingState() {
        SwingUtilities.invokeLater(() -> {
            if (progressBar == null || label == null) {
                return;
            }
            progressBar.setIndeterminate(true);
            progressBar.setString("Starting...");
            label.setText("Preparing upload status...");
        });
    }

    void showProgress(int completionPercent, long totalChunks, long pendingChunks, int finalizingFiles, int failedFiles, String phase, String lastError) {
        SwingUtilities.invokeLater(() -> {
            if (progressBar == null || label == null) {
                return;
            }
            int pct = Math.max(0, Math.min(100, completionPercent));
            progressBar.setIndeterminate(false);
            progressBar.setValue(pct);
            progressBar.setString(pct + "%");
            StringBuilder text = new StringBuilder();
            if (phase != null && !phase.isBlank()) {
                text.append(phase).append(" | ");
            }
            text.append("Uploaded ")
                    .append(Math.max(0L, totalChunks - pendingChunks))
                    .append(" / ")
                    .append(totalChunks)
                    .append(" chunks (pending ")
                    .append(pendingChunks)
                    .append(")");
            if (finalizingFiles > 0) {
                text.append(" | finalizing files: ").append(finalizingFiles);
            }
            if (failedFiles > 0) {
                text.append(" | failed files: ").append(failedFiles);
            }
            if (lastError != null && !lastError.isBlank()) {
                text.append(" | last error: ").append(lastError);
            }
            label.setText(text.toString());
        });
    }

    void dispose() {
        if (trayIcon != null && SystemTray.isSupported()) {
            try {
                SystemTray.getSystemTray().remove(trayIcon);
            } catch (Exception ignored) {
            }
        }
        SwingUtilities.invokeLater(() -> {
            if (frame != null) {
                frame.dispose();
            }
        });
    }

    private void initUi() {
        Runnable createUi = () -> {
            frame = new JFrame("Upload In Progress");
            int horizontalGap = scale(16);
            int verticalGap = scale(14);
            int padX = scale(24);
            int padY = scale(20);
            frame.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);

            label = new JLabel("Finalizing uploads...", SwingConstants.CENTER);
            progressBar = new JProgressBar(0, 100);
            progressBar.setStringPainted(true);
            progressBar.setValue(0);
            progressBar.setString("0%");
            applyScaledFont(label, 14f);
            applyScaledFont(progressBar, 12f);
            progressBar.setPreferredSize(new Dimension(scale(560), scale(34)));

            JPanel content = new JPanel(new java.awt.BorderLayout(horizontalGap, verticalGap));
            content.setBorder(BorderFactory.createEmptyBorder(padY, padX, padY, padX));
            content.add(label, java.awt.BorderLayout.NORTH);
            content.add(progressBar, java.awt.BorderLayout.CENTER);
            frame.setContentPane(content);
            frame.pack();
            Dimension minimum = new Dimension(scale(640), scale(220));
            frame.setMinimumSize(minimum);
            frame.setSize(minimum);
            frame.setLocationRelativeTo(null);
            frame.setVisible(initiallyVisible);
        };

        try {
            if (SwingUtilities.isEventDispatchThread()) {
                createUi.run();
            } else {
                SwingUtilities.invokeAndWait(createUi);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize shutdown UI", e);
        }

        ensureTrayIcon();
        SwingUtilities.invokeLater(() -> frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                boolean trayReady = ensureTrayIcon();
                if (trayReady) {
                    frame.setVisible(false);
                } else {
                    frame.setState(Frame.ICONIFIED);
                }
                if (trayReady && !trayHiddenMessageShown) {
                    trayHiddenMessageShown = true;
                    System.out.println("[UploadDaemon] Upload window hidden; uploader continues in system tray");
                } else if (!trayReady) {
                    System.err.println("[UploadDaemon] Tray icon unavailable; window minimized to taskbar instead");
                }
            }
        }));
    }

    private synchronized boolean ensureTrayIcon() {
        if (trayIcon != null) {
            return true;
        }
        if (!SystemTray.isSupported()) {
            logTrayEnvironment("SystemTray.isSupported=false");
            return false;
        }

        AtomicBoolean created = new AtomicBoolean(false);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        Runnable create = () -> {
            try {
                PopupMenu menu = new PopupMenu();
                MenuItem showItem = new MenuItem("Show Upload Progress");
                MenuItem terminateItem = new MenuItem("Terminate Upload Daemon");
                showItem.addActionListener(e -> SwingUtilities.invokeLater(() -> {
                    if (frame == null) {
                        return;
                    }
                    frame.setVisible(true);
                    frame.setState(Frame.NORMAL);
                    frame.toFront();
                    frame.requestFocus();
                }));
                terminateItem.addActionListener(e -> onTerminate.run());
                menu.add(showItem);
                menu.addSeparator();
                menu.add(terminateItem);

                Image image = buildTrayImage();
                TrayIcon icon = new TrayIcon(image, "Recording Upload Daemon", menu);
                icon.setImageAutoSize(true);
                icon.addActionListener(e -> SwingUtilities.invokeLater(() -> {
                    if (frame == null) {
                        return;
                    }
                    frame.setVisible(true);
                    frame.setState(Frame.NORMAL);
                    frame.toFront();
                    frame.requestFocus();
                }));
                SystemTray.getSystemTray().add(icon);
                trayIcon = icon;
                created.set(true);
            } catch (Throwable t) {
                errorRef.set(t);
            }
        };

        try {
            if (SwingUtilities.isEventDispatchThread()) {
                create.run();
            } else {
                SwingUtilities.invokeAndWait(create);
            }
        } catch (Exception e) {
            errorRef.set(e);
        }

        if (created.get()) {
            System.out.println("[UploadDaemon] System tray icon started");
            try {
                trayIcon.displayMessage(
                        "Recording Upload Daemon",
                        "Uploads continue in the tray after closing this window.",
                        TrayIcon.MessageType.INFO
                );
            } catch (Throwable ignored) {
            }
            return true;
        }

        trayIcon = null;
        Throwable error = errorRef.get();
        if (error != null) {
            System.err.println("[UploadDaemon] Failed to initialize tray icon: " + throwableSummary(error));
        }
        logTrayEnvironment("Tray icon creation failed");
        return false;
    }

    private int scale(int px) {
        return Math.max(1, (int) Math.round(px * uiScale));
    }

    private void applyScaledFont(java.awt.Component component, float basePt) {
        Font current = component.getFont();
        if (current == null) {
            return;
        }
        float target = Math.max(basePt, current.getSize2D()) * (float) uiScale;
        component.setFont(current.deriveFont(target));
    }

    private static double detectUiScale() {
        double scale = 1.0d;
        scale = Math.max(scale, detectTransformScale());
        scale = Math.max(scale, detectToolkitDpiScale());
        scale = Math.max(scale, detectScaleHint(System.getProperty("mcactionrec.upload.uiScale")));
        scale = Math.max(scale, detectScaleHint(System.getProperty("sun.java2d.uiScale")));
        scale = Math.max(scale, detectScaleHint(System.getenv("GDK_SCALE")));
        scale = Math.max(scale, detectScaleHint(System.getenv("QT_SCALE_FACTOR")));
        return Math.max(1.0d, Math.min(4.0d, scale));
    }

    private static double detectTransformScale() {
        try {
            double sx = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice()
                    .getDefaultConfiguration()
                    .getDefaultTransform()
                    .getScaleX();
            double sy = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice()
                    .getDefaultConfiguration()
                    .getDefaultTransform()
                    .getScaleY();
            return Math.max(sx, sy);
        } catch (Throwable ignored) {
            return 1.0d;
        }
    }

    private static double detectToolkitDpiScale() {
        try {
            int dpi = Toolkit.getDefaultToolkit().getScreenResolution();
            return Math.max(1.0d, dpi / 96.0d);
        } catch (Throwable ignored) {
            return 1.0d;
        }
    }

    private static double detectScaleHint(String rawScale) {
        if (rawScale == null || rawScale.isBlank()) {
            return 1.0d;
        }
        String normalized = rawScale.trim().toLowerCase(Locale.ROOT);
        try {
            if (normalized.endsWith("%")) {
                double pct = Double.parseDouble(normalized.substring(0, normalized.length() - 1));
                return pct > 0d ? pct / 100.0d : 1.0d;
            }
            double value = Double.parseDouble(normalized);
            return value > 0d ? value : 1.0d;
        } catch (NumberFormatException ignored) {
            return 1.0d;
        }
    }

    private static void logTrayEnvironment(String reason) {
        String xdgDesktop = System.getenv("XDG_CURRENT_DESKTOP");
        String sessionType = System.getenv("XDG_SESSION_TYPE");
        String desktopSession = System.getenv("DESKTOP_SESSION");
        boolean headless = java.awt.GraphicsEnvironment.isHeadless();
        System.err.println("[UploadDaemon] " + reason
                + " | headless=" + headless
                + ", xdgDesktop=" + valueOrUnknown(xdgDesktop)
                + ", sessionType=" + valueOrUnknown(sessionType)
                + ", desktopSession=" + valueOrUnknown(desktopSession));
    }

    private static String valueOrUnknown(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value;
    }

    private static Image buildTrayImage() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(new Color(35, 145, 92));
            g.fillOval(1, 1, 14, 14);
            g.setColor(new Color(255, 255, 255));
            g.drawLine(5, 8, 7, 10);
            g.drawLine(7, 10, 11, 5);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static String throwableSummary(Throwable t) {
        if (t == null) {
            return "unknown error";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(t.getClass().getSimpleName());

        String message = t.getMessage();
        if (message != null && !message.isBlank()) {
            sb.append(": ").append(message);
        }

        Throwable cause = t.getCause();
        if (cause != null && cause != t) {
            sb.append(" (cause=").append(cause.getClass().getSimpleName());
            String causeMessage = cause.getMessage();
            if (causeMessage != null && !causeMessage.isBlank()) {
                sb.append(": ").append(causeMessage);
            }
            sb.append(")");
        }
        return sb.toString();
    }
}
