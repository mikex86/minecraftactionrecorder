package me.michael.kei.actionrecorder;

import java.io.DataOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;

public class ActionLogWriter {

    private final DataOutputStream dos;

    public ActionLogWriter(Path logFile) throws FileNotFoundException {
        this.dos = new DataOutputStream(new FileOutputStream(logFile.toFile()));
    }

    public void logStates(boolean[] states, float[] rotationDeltas, double[] cursorDeltas) throws IOException {
        for (boolean state : states) {
            dos.writeBoolean(state);
        }

        dos.writeFloat(rotationDeltas[0]); // deltaYaw
        dos.writeFloat(rotationDeltas[1]); // deltaPitch

        dos.writeDouble(cursorDeltas[0]); // deltaX
        dos.writeDouble(cursorDeltas[1]); // deltaY
    }

    public void close() throws IOException {
        this.dos.close();
    }
}
