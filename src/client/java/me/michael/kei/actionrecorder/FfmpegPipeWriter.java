package me.michael.kei.actionrecorder;

import java.io.*;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public final class FfmpegPipeWriter implements AutoCloseable {
    private final int width, height, fps, bytesPerFrame;
    private final Process process;
    private final BlockingQueue<byte[]> queue;
    private final Thread writerThread;
    private final Thread stderrGobbler;
    private volatile boolean running = true;

    /**
     * @param ffmpegPath path to ffmpeg binary ("ffmpeg" if on PATH)
     */
    public FfmpegPipeWriter(String ffmpegPath, Path outputFile,
                            int width, int height, int fps,
                            int queueCapacity) throws IOException {
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.bytesPerFrame = width * height * 3;
        this.queue = new ArrayBlockingQueue<>(Math.max(2, queueCapacity));

        List<String> cmd = FfmpegCmdBuilder.buildCmd(ffmpegPath, width, height, fps, outputFile);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD); // discard stdout
        this.process = pb.start();

        // Drain/stash ffmpeg stderr so the process doesn’t block on full pipes
        this.stderrGobbler = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    // optionally log it somewhere:
                    // System.out.println("[ffmpeg] " + line);
                }
            } catch (IOException ignored) {
            }
        }, "ffmpeg-stderr-gobbler");
        stderrGobbler.setDaemon(true);
        stderrGobbler.start();

        OutputStream ffIn = new BufferedOutputStream(process.getOutputStream(), bytesPerFrame * 2);
        this.writerThread = new Thread(() -> {
            try {
                while (running) {
                    byte[] frame = queue.take();   // waits for frames
                    if (frame == POISON) break;
                    if (frame.length != bytesPerFrame) continue; // or throw
                    ffIn.write(frame);
                    ffIn.flush();

                    // Return byte[] to pool
                    synchronized (bytePool) {
                        bytePool.offer(frame);
                    }
                }
            } catch (InterruptedException ignored) {
            } catch (IOException e) {
                // If ffmpeg exits early, writes will throw. You can log this.
            } finally {
                try {
                    ffIn.flush();
                } catch (IOException ignored) {
                }
                try {
                    ffIn.close();
                } catch (IOException ignored) {
                }
            }
        }, "ffmpeg-writer");
        writerThread.start();
    }

    private static final byte[] POISON = new byte[0];

    public void pushFrame(byte[] rgb) {
        if (!running) return;
        if (rgb.length != bytesPerFrame) return;
        try {
            // Block until there's room
            queue.put(copyPooled(rgb));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private byte[] copyPooled(byte[] src) {
        byte[] dst = obtainedBytes(src.length);
        System.arraycopy(src, 0, dst, 0, src.length);
        return dst;
    }

    private static final Queue<byte[]> bytePool = new ArrayDeque<>();

    private byte[] obtainedBytes(int length) {
        synchronized (bytePool) {
            Iterator<byte[]> it = bytePool.iterator();
            while (it.hasNext()) {
                byte[] b = it.next();
                if (b.length == length) {
                    it.remove();
                    return b;
                }
            }
        }
        return new byte[length];
    }

    /**
     * Close gracefully; waits for the writer to finish and ffmpeg to exit.
     */
    @Override
    public void close() {
        if (!running) return;
        running = false;
        queue.offer(POISON);
        try {
            writerThread.join(2000);
        } catch (InterruptedException ignored) {
        }

        // Tell ffmpeg no more input; it will finalize the file
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
        }

        try {
            // Give ffmpeg a moment to write the moov atom, etc.
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }

        // If it's still alive, destroy
        process.destroy();
        try {
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        process.destroyForcibly();
    }
}
