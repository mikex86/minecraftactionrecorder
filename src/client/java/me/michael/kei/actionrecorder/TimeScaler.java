package me.michael.kei.actionrecorder;

public class TimeScaler {

    private static final int REAL_TIME_FACTOR = 1;

    public static long scaledNanoTime() {
        return System.nanoTime() * REAL_TIME_FACTOR;
    }

}
