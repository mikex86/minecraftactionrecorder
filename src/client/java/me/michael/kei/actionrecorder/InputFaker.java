package me.michael.kei.actionrecorder;

import net.minecraft.client.Minecraft;

import java.util.Random;

public class InputFaker {

    enum Direction {
        NONE(false, false, false, false),
        FORWARD(true, false, false, false),
        BACKWARD(false, true, false, false),
        LEFT(true, false, true, false),
        LEFT_FORWARD(true, false, true, false),
        BACKWARD_LEFT(false, true, true, false),
        RIGHT(true, false, false, true),
        RIGHT_FORWARD(true, false, false, true),
        LEFT_BACKWARD(false, true, true, false);

        final boolean moveForward;
        final boolean moveBackward;
        final boolean moveLeft;
        final boolean moveRight;

        Direction(boolean moveForward, boolean moveBackward, boolean moveLeft, boolean moveRight) {
            this.moveForward = moveForward;
            this.moveBackward = moveBackward;
            this.moveLeft = moveLeft;
            this.moveRight = moveRight;
        }
    }

    private static final Random random = new Random();
    private static int nextMovementTicks = randomMovementDuration();
    private static int tickCtr = 0;
    private static Direction currentDirection = Direction.NONE;
    private static final Timer timer = new Timer(20.0f);


    private static int randomMovementDuration() {
        return random.nextInt(30);
    }

    public static void doRandomInput() {
        timer.advanceTime();
        for (int i = 0; i < timer.ticks; i++) {
            doTick();
        }
    }

    private static void doTick() {
        if (tickCtr >= nextMovementTicks) {
            tickCtr = 0;
            nextMovementTicks = randomMovementDuration();
            currentDirection = Direction.values()[random.nextInt(Direction.values().length)];
        }
        Minecraft mc = Minecraft.getInstance();
        mc.options.keyLeft.setDown(currentDirection.moveLeft);
        mc.options.keyRight.setDown(currentDirection.moveRight);
        mc.options.keyUp.setDown(currentDirection.moveForward);
        mc.options.keyDown.setDown(currentDirection.moveBackward);
        mc.options.pauseOnLostFocus = false; // Prevent game from pausing when focus is lost
        mc.mouseHandler.releaseMouse(); // release mouse
        tickCtr++;
    }
}
