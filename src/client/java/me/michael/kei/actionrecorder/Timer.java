package me.michael.kei.actionrecorder;

/**
 * Game timing system that manages ticks and frame interpolation.
 * Responsible for determining how many ticks should occur per frame.
 */
public class Timer {
    /**
     * Number of nanoseconds in one second
     */
    private static final long NS_PER_SECOND = 1000000000L;
    /**
     * Maximum time that can pass between updates
     */
    private static final long MAX_NS_PER_UPDATE = 1000000000L;
    /**
     * Maximum tick count that can occur in a single update
     */
    private static final int MAX_TICKS_PER_UPDATE = 100;

    /**
     * Target number of ticks per second
     */
    private final float ticksPerSecond;
    /**
     * Time of last update in nanoseconds
     */
    private long lastTime;

    /**
     * Number of ticks to be processed in current frame
     */
    public int ticks;
    /**
     * Partial tick fraction for smooth rendering (0.0-1.0)
     */
    public float partialTick;
    /**
     * Time scale factor (speeds up or slows down game)
     */
    public float timeScale = 1.0F;
    /**
     * Current frames per second
     */
    public float fps = 0.0F;
    /**
     * Accumulated time not yet converted to ticks
     */
    public float passedTime = 0.0F;

    /**
     * Creates a new timer with specified tick rate.
     *
     * @param ticksPerSecond Target number of game ticks per second
     */
    public Timer(float ticksPerSecond) {
        this.ticksPerSecond = ticksPerSecond;
        this.lastTime = System.nanoTime();
    }

    /**
     * Updates the timer, calculating how many ticks should be processed
     * and the partial tick for rendering interpolation.
     */
    public void advanceTime() {
        // Get current time and calculate elapsed time
        long now = System.nanoTime();
        long passedNs = now - this.lastTime;
        this.lastTime = now;

        // Clamp elapsed time to valid range
        if (passedNs < 0L) {
            passedNs = 0L;
        }
        if (passedNs > MAX_NS_PER_UPDATE) {
            passedNs = MAX_NS_PER_UPDATE;
        }

        // Calculate current FPS
        this.fps = (float) (NS_PER_SECOND / passedNs);

        // Calculate elapsed time in ticks
        this.passedTime += (float) passedNs * this.timeScale * this.ticksPerSecond / NS_PER_SECOND;

        // Extract whole ticks for processing
        this.ticks = (int) this.passedTime;

        // Limit maximum tick count to prevent spiral of death
        if (this.ticks > MAX_TICKS_PER_UPDATE) {
            this.ticks = MAX_TICKS_PER_UPDATE;
        }

        // Store remaining partial tick for rendering interpolation
        this.passedTime -= (float) this.ticks;
        this.partialTick = this.passedTime;
    }
}