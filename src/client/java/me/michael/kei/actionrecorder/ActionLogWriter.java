package me.michael.kei.actionrecorder;

import java.io.DataOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

public class ActionLogWriter {

    private final DataOutputStream dos;

    public ActionLogWriter(Path logFile) throws FileNotFoundException {
        this.dos = new DataOutputStream(new FileOutputStream(logFile.toFile()));
    }

    public void logStates(boolean[] states, float[] rotationDeltas, double[] cursorDeltas, List<String> pressedCharacters) throws IOException {
        for (boolean state : states) {
            dos.writeBoolean(state);
        }

        dos.writeFloat(rotationDeltas[0]); // deltaYaw
        dos.writeFloat(rotationDeltas[1]); // deltaPitch

        dos.writeDouble(cursorDeltas[0]); // deltaX
        dos.writeDouble(cursorDeltas[1]); // deltaY

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
