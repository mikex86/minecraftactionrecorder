package me.michael.kei.actionrecorder;

import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

public class ActionLogWriter {

    private final DataOutputStream dos;

    private static final int MAGIC = 0xFAFA;
    private static final int VERSION = 4;

    public ActionLogWriter(Path logFile) throws IOException {
        this.dos = new DataOutputStream(new FileOutputStream(logFile.toFile()));
        this.dos.writeInt(MAGIC);
        this.dos.write(VERSION);
    }

    public void logStates(boolean[] states, float[] floats, double[] doubles, List<String> pressedCharacters) throws IOException {
        for (boolean state : states) {
            dos.writeBoolean(state);
        }

        for (float state : floats) {
            dos.writeFloat(state);
        }

        for (double delta : doubles) {
            dos.writeDouble(delta);
        }

        // Write the number of pressed characters
        dos.writeInt(pressedCharacters.size());

        // Write each pressed character
        for (String c : pressedCharacters) {
            byte[] strBytes = c.getBytes(StandardCharsets.UTF_8);
            dos.writeInt(strBytes.length); // Write length of the string
            dos.write(strBytes); // Write string bytes
        }
    }

    public void close() throws IOException {
        this.dos.close();
    }
}
