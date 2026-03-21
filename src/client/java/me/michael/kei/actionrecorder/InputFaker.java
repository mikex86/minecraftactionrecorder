package me.michael.kei.actionrecorder;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class InputFaker {

    enum WeaponType {
        NONE,
        SWORD,
        AXE,
        BOW
    }

    private record WeaponChoice(int slot, WeaponType weaponType) {
    }

    private record LookAngles(double pitch) {
    }

    private record TargetMetrics(double distance, double yawError, double pitchError, boolean inView) {
    }

    private record HostileSpacingMetrics(double nearestDistance,
                                         double nearestPredictedDistance,
                                         boolean anyAdvancingThreat,
                                         int closeThreatCount) {
    }

    private record ArrowShieldThreat(boolean hasBlockableThreat, boolean hasUnblockableThreat) {
    }

    private record CreeperThreat(boolean shouldBlock, boolean shouldRetreat) {
    }

    private record ArrowEvasionPlan(boolean shouldEvade, int strafeDirection, boolean retreat, boolean urgent) {
    }

    private record SkeletonJukePlan(boolean shouldJuke, int strafeDirection, boolean retreat, boolean urgent) {
    }

    private record MouseDelta(double yaw, double pitch) {
    }

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
    private static final double MIN_TARGET_PITCH_DEGREES = -30.0;
    private static final double MAX_TARGET_PITCH_DEGREES = 70.0;
    private static final double OUT_OF_RANGE_RECOVERY_BIAS = 0.75;
    private static final double OUT_OF_RANGE_STEP_BOOST = 1.8;
    private static final double GROUND_STARE_PITCH_THRESHOLD = 55.0;
    private static final int GROUND_STARE_MAX_FRAMES = 70;
    private static final double GROUND_STARE_RECOVERY_TARGET_PITCH = 22.0;
    private static final double GROUND_STARE_RECOVERY_BIAS = 0.82;
    private static final double GROUND_STARE_RECOVERY_STEP_BOOST = 2.2;
    private static final double GROUND_STARE_RECOVERY_MAX_TARGET_PITCH = 40.0;
    private static final double COMBAT_SEARCH_RADIUS = 12.0;
    private static final double COMBAT_MAX_TRACK_RANGE = 14.0;
    private static final double TARGET_VIEW_YAW_LIMIT = 68.0;
    private static final double TARGET_VIEW_PITCH_LIMIT = 50.0;
    private static final int MAX_TARGET_OUT_OF_VIEW_TICKS = 30;
    private static final int TARGET_MIN_LOCK_TICKS = 26;
    private static final int TARGET_REEVALUATE_TICKS = 6;
    private static final double TARGET_SWITCH_SCORE_MARGIN = 2.4;
    private static final double TARGET_SWITCH_FAST_YAW_THRESHOLD = 18.0;
    private static final double TARGET_SWITCH_FAST_SPACING_THRESHOLD = 3.2;
    private static final double TARGET_SWITCH_SLOW_YAW_THRESHOLD = 34.0;
    private static final double TARGET_SWITCH_SLOW_SPACING_THRESHOLD = 5.2;
    private static final int SKELETON_PRIORITY_REQUIRED_HITS = 2;
    private static final int SKELETON_HIT_MEMORY_TICKS = 480;
    private static final double SKELETON_PRIORITY_DEFER_CURRENT_DISTANCE = 1.95;
    private static final double SKELETON_PRIORITY_STRONGER_CLOSENESS_MARGIN = 0.45;
    private static final double SHIELD_ARROW_BLOCKABLE_FACING_DOT_MAX = -0.06;
    private static final double SHIELD_ARROW_MIN_CLOSING_SPEED = 0.12;
    private static final double SHIELD_ARROW_MAX_TIME_TO_IMPACT_TICKS = 8.0;
    private static final int CREEPER_BLOCK_COMMIT_MIN_TICKS = 9;
    private static final int CREEPER_BLOCK_COMMIT_MAX_TICKS = 16;
    private static final int NO_SHIELD_CREEPER_FLEE_MIN_TICKS = 32;
    private static final int NO_SHIELD_CREEPER_FLEE_MAX_TICKS = 56;
    private static final int NO_SHIELD_CREEPER_FLEE_COMMIT_MIN_TICKS = 22;
    private static final int NO_SHIELD_CREEPER_FLEE_COMMIT_MAX_TICKS = 40;
    private static final int CREEPER_RETREAT_STUCK_TRIGGER_TICKS = 8;
    private static final int CREEPER_RETREAT_OBSERVE_MIN_TICKS = 6;
    private static final int CREEPER_RETREAT_BURST_MIN_TICKS = 18;
    private static final int CREEPER_RETREAT_BURST_MAX_TICKS = 34;
    private static final int HOTBAR_SWITCH_PULSE_INTERVAL_TICKS = 1;
    private static final int MAX_FOOD_LEVEL = 20;
    private static final double ARROW_EVADE_SEARCH_RADIUS = 6.0;
    private static final double ARROW_EVADE_MIN_CLOSING_SPEED = 0.11;
    private static final double ARROW_EVADE_MAX_TIME_TO_IMPACT_TICKS = 13.0;
    private static final double SKELETON_JUKE_SEARCH_RADIUS = 12.0;
    private static final double SWORD_SAFE_MIN_DISTANCE = 1.55;
    private static final double SWORD_SAFE_MAX_DISTANCE = 2.45;
    private static final double AXE_SAFE_MIN_DISTANCE = 1.75;
    private static final double AXE_SAFE_MAX_DISTANCE = 2.75;
    private static final double SWORD_ATTACK_RANGE = 3.1;
    private static final double AXE_ATTACK_RANGE = 3.25;
    private static final double BOW_ENGAGE_MIN_DISTANCE = 6.2;
    private static final double BOW_ENGAGE_HYSTERESIS_DISTANCE = 5.2;
    private static final double BOW_SKELETON_MELEE_SWITCH_DISTANCE = 6.8;
    private static final double BOW_SKELETON_ONLY_AT_DISTANCE = 7.0;
    private static final double BOW_SAFE_MIN_DISTANCE = 4.5;
    private static final double BOW_SAFE_MAX_DISTANCE = 10.4;
    private static final double BOW_ATTACK_RANGE = 14.0;
    private static final int BOW_DRAW_MIN_TICKS = 13;
    private static final int BOW_DRAW_MAX_TICKS = 22;
    private static final int BOW_RELEASE_COOLDOWN_MIN_TICKS = 3;
    private static final int BOW_RELEASE_COOLDOWN_MAX_TICKS = 6;
    private static final int TARGET_MOVEMENT_CHECK_MIN_TICKS = 2;
    private static final int TARGET_MOVEMENT_CHECK_MAX_TICKS = 6;
    private static final int CORRECTION_WINDOW_MIN_TICKS = 2;
    private static final int CORRECTION_WINDOW_MAX_TICKS = 8;

    private static final int SKELETON_KILL_COMMIT_MIN_TICKS = 34;
    private static final int SKELETON_KILL_COMMIT_MAX_TICKS = 62;
    private static int nextMovementTicks = randomMovementDuration();

    private static int tickCtr = 0;
    private static Direction currentDirection = Direction.NONE;
    private static final Timer timer = new Timer(20.0f);
    private static final double FRAME_SIMULATION_RATE = 120.0F;
    private static final double FRAME_SIMULATION_STEP_SECONDS = 1.0 / FRAME_SIMULATION_RATE;
    private static final int MAX_FRAME_SIMULATION_STEPS = 8;
    private static double frameSimulationAccumulatorSeconds = 0.0;

    private static boolean jump = false;
    private static boolean useItem = false;

    private static double startCameraYaw = 0;
    private static double startCameraPitch = 0;

    private static double nextYawChangeDegrees = 0;
    private static double nextPitchChangeDegrees = 0;

    private static int animationFrame = 0;
    private static int animationDurationFrames = 1;
    private static double animationEasePower = 1.0;
    private static double yawStepMin = 0.6;
    private static double yawStepMax = 4.5;
    private static double pitchStepMin = 0.4;
    private static double pitchStepMax = 3.0;

    private static int oscillationCycles = 0;
    private static double oscillationDamping = 0;
    private static double yawOvershootAmplitude = 0;
    private static double pitchOvershootAmplitude = 0;

    private static int groundLookFrameStreak = 0;
    private static LivingEntity combatTarget = null;
    private static WeaponType selectedWeaponType = WeaponType.NONE;
    private static int targetOutOfViewTicks = 0;
    private static int combatTargetLockTicks = 0;
    private static int targetReevaluateTicks = 0;
    private static int targetSwitchSlowTicks = 0;
    private static double targetSwitchYawDelta = 0;
    private static int closestThreatMobId = -1;
    private static int closestThreatTicks = 0;
    private static int attackPressTicks = 0;
    private static int bowDrawTicks = 0;
    private static int bowDrawTargetTicks = 0;
    private static int bowReleaseCooldownTicks = 0;
    private static int meleePursuitStallTicks = 0;
    private static int ticksInRangeNoAttack = 0;
    private static double nextAttackCooldownTarget = 0.0;
    private static double attackEarlySigma = 0.08;
    private static boolean hasAttackCadence = false;
    private static WeaponType attackCadenceWeaponType = WeaponType.NONE;
    private static int postHitPauseTicks = 0;
    private static int arrowEvadeTicks = 0;
    private static int arrowEvadeStrafeDirection = 0;
    private static boolean arrowEvadeRetreat = false;
    private static int skeletonKillCommitTicks = 0;
    private static int skeletonKillCommitTargetId = -1;
    private static int skeletonJukeTicks = 0;
    private static int skeletonJukeDirection = 0;
    private static boolean skeletonJukeRetreat = false;
    private static int skeletonJukeFlipCooldown = 0;
    private static int creeperShieldCommitTicks = 0;
    private static int creeperRetreatBurstTicks = 0;
    private static int creeperRetreatStuckTicks = 0;
    private static int creeperRetreatObserveTicks = 0;
    private static int creeperRetreatTargetId = -1;
    private static double creeperRetreatLastDistance = -1.0;
    private static int noShieldCreeperFleeTicks = 0;
    private static int noShieldCreeperFleeCommitTicks = 0;
    private static int noShieldCreeperFleeTargetId = -1;
    private static boolean noShieldFleeLookLocked = false;
    private static int noShieldFleeLookTargetId = -1;
    private static float noShieldFleeLockedPitch = 0.0f;
    private static int shieldHoldTicks = 0;
    private static int shieldPushbackTicks = 0;
    private static int shieldPushbackCooldownTicks = 0;
    private static int critPrepTicks = 0;
    private static int strafeTicksRemaining = 0;
    private static int strafeDirection = 1;
    private static int aimBiasTicks = 0;
    private static int deliberateMissTicks = 0;
    private static double deliberateMissYawHold = 0;
    private static double deliberateMissPitchHold = 0;
    private static int targetMovementCheckTicks = 0;
    private static int pendingCorrectionDelayTicks = 0;
    private static int pendingCorrectionWindowTicks = 0;
    private static int correctionTicksRemaining = 0;
    private static int aimBehindTicks = 0;
    private static int catchupTicksRemaining = 0;
    private static double aimBiasYaw = 0;
    private static double aimBiasPitch = 0;
    private static int combatLookOffsetTicks = 0;
    private static double combatLookOffsetYaw = 0;
    private static double combatLookOffsetPitch = 0;
    private static double combatLookOffsetTargetYaw = 0;
    private static double combatLookOffsetTargetPitch = 0;
    private static double observedYawError = 0;
    private static double observedPitchError = 0;
    private static double correctionTargetYawError = 0;
    private static double correctionTargetPitchError = 0;
    private static double combatAimYawVelocity = 0;
    private static double combatAimPitchVelocity = 0;
    private static double aimHumanReactionScale = 1.0;
    private static double aimHumanCorrectionScale = 1.0;
    private static double aimHumanJitterScale = 1.0;
    private static double aimHumanMicroSaccadeBias = 0.0;
    private static double lastTargetYawError = 0;
    private static double lastTargetPitchError = 0;
    private static double lastTargetAngularSpeed = 0;
    private static boolean hasTargetErrorHistory = false;
    private static boolean combatMoveForward = false;
    private static boolean combatMoveBackward = false;
    private static boolean combatMoveLeft = false;
    private static boolean combatMoveRight = false;
    private static boolean combatJump = false;
    private static boolean combatSprint = false;
    private static boolean combatAttack = false;
    private static boolean combatUseItem = false;
    private static boolean combatActive = false;
    private static boolean hasObservedTargetError = false;
    private static boolean foodConsumptionObjectiveActive = false;
    private static int foodConsumptionObjectiveSlot = -1;
    private static int pendingHotbarSlot = -1;
    private static int hotbarSwitchPulseCooldownTicks = 0;
    private static int hotbarSwitchPulseAttempts = 0;
    private static int pendingAttackClicks = 0;
    private static int lastProcessedHurtTimestamp = -1;
    private static final Map<Integer, Integer> skeletonHitCounts = new HashMap<>();
    private static final Map<Integer, Integer> skeletonLastHitTicks = new HashMap<>();
    private static double naturalMouseNoiseYaw = 0.0;
    private static double naturalMouseNoisePitch = 0.0;
    private static double naturalMouseNoisePhase = random.nextDouble() * Math.PI * 2.0;
    private static int naturalMouseBurstTicks = 0;
    private static double naturalMouseBurstYaw = 0.0;
    private static double naturalMouseBurstPitch = 0.0;

    private static boolean shouldPerformAnimation = false;

    private static int randomMovementDuration() {
        return random.nextInt(30);
    }

    public static void doRandomInput() {
        timer.advanceTime();
        for (int i = 0; i < timer.ticks; i++) {
            doTick();
        }
        advanceFrameSimulation();
    }

    private static void advanceFrameSimulation() {
        double deltaSeconds = Math.max(0.0, timer.deltaSeconds);
        if (deltaSeconds <= 0.0) {
            deltaSeconds = FRAME_SIMULATION_STEP_SECONDS;
        }

        frameSimulationAccumulatorSeconds = Math.min(
                frameSimulationAccumulatorSeconds + deltaSeconds,
                FRAME_SIMULATION_STEP_SECONDS * MAX_FRAME_SIMULATION_STEPS
        );

        int simulatedSteps = 0;
        while (frameSimulationAccumulatorSeconds >= FRAME_SIMULATION_STEP_SECONDS
                && simulatedSteps < MAX_FRAME_SIMULATION_STEPS) {
            doFrame();
            frameSimulationAccumulatorSeconds -= FRAME_SIMULATION_STEP_SECONDS;
            simulatedSteps++;
        }
    }

    private static void doTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (tickCtr >= nextMovementTicks) {
            tickCtr = 0;
            nextMovementTicks = randomMovementDuration();
            currentDirection = Direction.values()[random.nextInt(Direction.values().length)];

            jump = random.nextBoolean();
            useItem = random.nextBoolean();

            // select random item in hotbar
            int slot = random.nextInt(Inventory.getSelectionSize()); // 0..8
            if (player != null && !combatActive && !foodConsumptionObjectiveActive) {
                queueHotbarSlot(slot);
                Camera mainCamera = mc.gameRenderer.getMainCamera();
                startLookAnimation(mainCamera);
            }
        }

        if (player != null && mc.screen == null) {
            updateCombatBehaviorTick(player);
        } else {
            clearCombatState();
        }

        boolean moveLeft = currentDirection.moveLeft;
        boolean moveRight = currentDirection.moveRight;
        boolean moveForward = currentDirection.moveForward;
        boolean moveBackward = currentDirection.moveBackward;
        boolean jumpDown = jump && player != null && player.onGround();
        boolean useDown = player != null && useItem;
        boolean attackDown = false;
        boolean sprintDown = false;
        boolean shouldShield = false;
        boolean foodObjectiveRunning = false;

        if (combatActive) {
            moveLeft = combatMoveLeft;
            moveRight = combatMoveRight;
            moveForward = combatMoveForward;
            moveBackward = combatMoveBackward;
            jumpDown = combatJump;
            useDown = combatUseItem;
            attackDown = combatAttack;
            sprintDown = combatSprint;
        }
        if (player != null) {
            if (combatActive && foodConsumptionObjectiveActive) {
                abortFoodConsumptionObjective();
            }
            if (!combatActive) {
                shouldShield = shouldRaiseShield(player, combatTarget);
                if (foodConsumptionObjectiveActive) {
                    foodObjectiveRunning = updateFoodConsumptionObjective(player, shouldShield || mc.screen != null);
                } else if (!shouldShield && player.getFoodData().needsFood() && mc.screen == null) {
                    int foodSlot = findBestHotbarFoodSlot(player);
                    if (foodSlot >= 0) {
                        startFoodConsumptionObjective(foodSlot);
                        foodObjectiveRunning = updateFoodConsumptionObjective(player, false);
                    }
                }
            }
            if (combatActive) {
                if (combatUseItem) {
                    useDown = true;
                    attackDown = false;
                }
            } else if (foodObjectiveRunning) {
                useDown = true;
                attackDown = false;
            } else if (shouldShield) {
                useDown = true;
                attackDown = false;
            }
        }

        mc.options.keyLeft.setDown(moveLeft);
        mc.options.keyRight.setDown(moveRight);
        mc.options.keyUp.setDown(moveForward);
        mc.options.keyDown.setDown(moveBackward);
        mc.options.keyJump.setDown(jumpDown);
        mc.options.keyAttack.setDown(attackDown);
        mc.options.keySprint.setDown(sprintDown);
        mc.options.pauseOnLostFocus = false; // Prevent game from pausing when focus is lost
        mc.options.keyUse.setDown(useDown);
        if (combatActive && pendingAttackClicks > 0) {
            emitAttackClick(mc);
            pendingAttackClicks--;
        }
        int hotbarKeyDownSlot = resolveHotbarSlotKeyToPress(player);
        if (hotbarKeyDownSlot >= 0) {
            emitHotbarKeyClick(mc, hotbarKeyDownSlot);
        }
        for (int i = 0; i < Math.min(Inventory.getSelectionSize(), mc.options.keyHotbarSlots.length); i++) {
            mc.options.keyHotbarSlots[i].setDown(i == hotbarKeyDownSlot);
        }
        mc.mouseHandler.releaseMouse(); // release mouse
        tickCtr++;
    }

    private static void doFrame() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        if (mc.screen != null) {
            return; // Don't perform camera animations while a screen is open
        }

        if (combatActive && combatTarget != null && isValidCombatTarget(player, combatTarget)) {
            if (!hasOffhandShield(player)
                    && combatTarget instanceof Creeper creeper
                    && creeper.getId() == noShieldCreeperFleeTargetId) {
                doNoShieldCreeperFleeAimFrame(player, creeper);
                return;
            }
            if (creeperRetreatBurstTicks > 0 && creeperRetreatTargetId >= 0) {
                var fleeEntity = player.level().getEntity(creeperRetreatTargetId);
                if (fleeEntity instanceof Creeper retreatCreeper && retreatCreeper.isAlive() && !retreatCreeper.isSpectator()) {
                    doCommittedCreeperRetreatAimFrame(player, retreatCreeper);
                    return;
                }
            }
            resetNoShieldFleeLookLock();
            doCombatAimFrame(player, combatTarget);
            return;
        }
        resetNoShieldFleeLookLock();

        boolean isGroundStareFrame = player.getXRot() >= GROUND_STARE_PITCH_THRESHOLD;
        if (isGroundStareFrame) {
            groundLookFrameStreak++;
        } else {
            groundLookFrameStreak = Math.max(0, groundLookFrameStreak - 2);
        }
        boolean shouldAvoidGroundStare = groundLookFrameStreak > GROUND_STARE_MAX_FRAMES;

        if (!shouldPerformAnimation) {
            if (shouldAvoidGroundStare) {
                double recoveryPitchDelta = Math.clamp(
                        GROUND_STARE_RECOVERY_TARGET_PITCH - player.getXRot(),
                        -2.4,
                        2.4
                );
                player.turn(0.0, recoveryPitchDelta);
                player.setXRot((float) Math.clamp(player.getXRot(), MIN_TARGET_PITCH_DEGREES, MAX_TARGET_PITCH_DEGREES));
            }
            return;
        }

        double animationProgress = Math.min(1.0, (animationFrame + 1.0) / Math.max(1.0, animationDurationFrames));
        double easedProgress = easeProgress(animationProgress);

        double targetYaw = startCameraYaw + nextYawChangeDegrees;
        double targetPitch = Math.clamp(
                startCameraPitch + nextPitchChangeDegrees,
                MIN_TARGET_PITCH_DEGREES,
                MAX_TARGET_PITCH_DEGREES
        );

        double oscillation = 0;
        if (oscillationCycles > 0) {
            oscillation = Math.sin(animationProgress * Math.PI * oscillationCycles)
                    * Math.exp(-oscillationDamping * animationProgress);
        }

        double totalYawTravel = targetYaw - startCameraYaw;
        double desiredYaw = startCameraYaw + (totalYawTravel * easedProgress)
                + (Math.signum(totalYawTravel) * yawOvershootAmplitude * oscillation);
        double desiredPitch = lerp(easedProgress, startCameraPitch, targetPitch)
                + (Math.signum(targetPitch - startCameraPitch) * pitchOvershootAmplitude * oscillation);
        desiredPitch = Math.clamp(desiredPitch, MIN_TARGET_PITCH_DEGREES, MAX_TARGET_PITCH_DEGREES);

        double currentYaw = player.getYRot();
        double currentPitch = player.getXRot();

        if (isOutsidePreferredPitchRange(currentPitch)) {
            double recoveryPitchTarget = Math.clamp(currentPitch, MIN_TARGET_PITCH_DEGREES + 1.0, MAX_TARGET_PITCH_DEGREES - 1.0);
            desiredPitch = lerp(OUT_OF_RANGE_RECOVERY_BIAS, desiredPitch, recoveryPitchTarget);
        }
        if (shouldAvoidGroundStare) {
            desiredPitch = lerp(
                    GROUND_STARE_RECOVERY_BIAS,
                    desiredPitch,
                    GROUND_STARE_RECOVERY_TARGET_PITCH
            );
        }

        double deltaYaw = desiredYaw - currentYaw;
        double deltaPitch = desiredPitch - currentPitch;

        double speedEnvelope = 0.35 + (Math.sin(animationProgress * Math.PI) * 1.35);
        double yawStepLimit = randomRange(yawStepMin, yawStepMax) * speedEnvelope;
        double pitchStepLimit = randomRange(pitchStepMin, pitchStepMax) * speedEnvelope;
        if (isOutsidePreferredPitchRange(currentPitch)) {
            pitchStepLimit *= OUT_OF_RANGE_STEP_BOOST;
        }
        if (shouldAvoidGroundStare) {
            pitchStepLimit *= GROUND_STARE_RECOVERY_STEP_BOOST;
        }

        double randomYawChange = Math.clamp(deltaYaw, -yawStepLimit, yawStepLimit);
        double randomPitchChange = Math.clamp(deltaPitch, -pitchStepLimit, pitchStepLimit);

        randomYawChange *= randomRange(0.9, 1.1);
        randomPitchChange *= randomRange(0.9, 1.1);

        if (random.nextDouble() < 0.28) {
            randomYawChange += randomRange(-0.04, 0.04);
            randomPitchChange += randomRange(-0.03, 0.03);
        }

        double motionMagnitude = Math.hypot(deltaYaw, deltaPitch);
        double noiseIntensity = 0.42 + Math.min(0.88, motionMagnitude / 6.0);
        MouseDelta noisyInput = applyNaturalMouseNoise(
                randomYawChange,
                randomPitchChange,
                yawStepLimit,
                pitchStepLimit,
                noiseIntensity
        );
        randomYawChange = noisyInput.yaw();
        randomPitchChange = noisyInput.pitch();

        // turn(yawDelta, pitchDelta)
        player.turn(randomYawChange, randomPitchChange);
        player.setXRot((float) Math.clamp(player.getXRot(), MIN_TARGET_PITCH_DEGREES, MAX_TARGET_PITCH_DEGREES));
        animationFrame++;

        boolean animationFinished = animationProgress >= 1.0
                && Math.abs(deltaYaw) < 0.12
                && Math.abs(deltaPitch) < 0.12;
        boolean animationTimedOut = animationFrame >= animationDurationFrames + 24;
        if (animationFinished || animationTimedOut) {
            shouldPerformAnimation = false;
        }
    }

    private static void updateCombatBehaviorTick(LocalPlayer player) {
        updateCombatTarget(player);

        combatMoveForward = false;
        combatMoveBackward = false;
        combatMoveLeft = false;
        combatMoveRight = false;
        combatJump = false;
        combatAttack = false;
        combatUseItem = false;
        combatSprint = false;
        shieldPushbackTicks = Math.max(0, shieldPushbackTicks - 1);
        shieldPushbackCooldownTicks = Math.max(0, shieldPushbackCooldownTicks - 1);

        if (combatTarget == null) {
            combatActive = false;
            selectedWeaponType = WeaponType.NONE;
            attackPressTicks = 0;
            bowDrawTicks = 0;
            bowDrawTargetTicks = 0;
            bowReleaseCooldownTicks = 0;
            meleePursuitStallTicks = 0;
            ticksInRangeNoAttack = 0;
            hasAttackCadence = false;
            skeletonKillCommitTicks = 0;
            skeletonKillCommitTargetId = -1;
            arrowEvadeTicks = 0;
            arrowEvadeStrafeDirection = 0;
            arrowEvadeRetreat = false;
            skeletonJukeTicks = 0;
            skeletonJukeDirection = 0;
            skeletonJukeRetreat = false;
            skeletonJukeFlipCooldown = 0;
            creeperShieldCommitTicks = 0;
            shieldHoldTicks = 0;
            shieldPushbackTicks = 0;
            shieldPushbackCooldownTicks = 0;
            critPrepTicks = 0;
            resetCreeperRetreatBurstState();
            noShieldCreeperFleeTicks = 0;
            noShieldCreeperFleeCommitTicks = 0;
            noShieldCreeperFleeTargetId = -1;
            resetNoShieldFleeLookLock();
            return;
        }

        WeaponChoice weaponChoice = chooseCombatWeapon(player, combatTarget);
        if (weaponChoice.slot < 0) {
            clearCombatState();
            return;
        }

        selectedWeaponType = weaponChoice.weaponType;
        if (player.getInventory().getSelectedSlot() != weaponChoice.slot) {
            queueHotbarSlot(weaponChoice.slot);
            ItemStack equippedStack = player.getInventory().getItem(player.getInventory().getSelectedSlot());
            WeaponType equippedWeaponType = detectWeaponType(player, equippedStack);
            if (equippedWeaponType != WeaponType.NONE) {
                // Keep fighting with what is currently equipped while hotbar switch is in progress.
                selectedWeaponType = equippedWeaponType;
            }
        }
        if (selectedWeaponType == WeaponType.BOW) {
            hasAttackCadence = false;
            attackCadenceWeaponType = WeaponType.NONE;
            nextAttackCooldownTarget = 0.0;
            attackPressTicks = 0;
            pendingAttackClicks = 0;
        } else {
            bowDrawTicks = 0;
            bowDrawTargetTicks = 0;
            bowReleaseCooldownTicks = 0;
            if (!hasAttackCadence || attackCadenceWeaponType != selectedWeaponType) {
                scheduleNextAttackCadence(selectedWeaponType);
            }
        }
        combatActive = true;

        if (strafeTicksRemaining <= 0) {
            strafeTicksRemaining = 8 + random.nextInt(24);
            if (random.nextDouble() < 0.38) {
                strafeDirection *= -1;
            }
        }
        strafeTicksRemaining--;

        double distance = player.distanceTo(combatTarget);
        boolean hasShield = hasOffhandShield(player);
        CreeperThreat creeperThreat = evaluateCreeperThreat(player);
        Creeper retreatCreeper = creeperThreat.shouldRetreat() ? findCriticalCreeperThreat(player) : null;
        boolean committedCreeperRetreat = updateCreeperRetreatBurstState(
                player,
                retreatCreeper,
                creeperThreat.shouldRetreat(),
                hasShield
        );
        if (committedCreeperRetreat && retreatCreeper == null && creeperRetreatTargetId >= 0) {
            var stickyRetreatEntity = player.level().getEntity(creeperRetreatTargetId);
            if (stickyRetreatEntity instanceof Creeper stickyRetreatCreeper
                    && stickyRetreatCreeper.isAlive()
                    && !stickyRetreatCreeper.isSpectator()) {
                retreatCreeper = stickyRetreatCreeper;
            }
        }
        Creeper noShieldCriticalCreeper = !hasShield
                && combatTarget instanceof Creeper creeper
                && creeper.getId() == noShieldCreeperFleeTargetId
                ? creeper
                : null;
        boolean emergencyNoShieldCreeperFlee = noShieldCriticalCreeper != null;
        ArrowEvasionPlan arrowEvasion = evaluateArrowEvasionPlan(player);
        if (selectedWeaponType == WeaponType.BOW) {
            meleePursuitStallTicks = 0;
            updateBowCombatTick(
                    player,
                    combatTarget,
                    distance,
                    creeperThreat,
                    retreatCreeper,
                    committedCreeperRetreat,
                    noShieldCriticalCreeper,
                    emergencyNoShieldCreeperFlee,
                    arrowEvasion
            );
            return;
        }
        SkeletonJukePlan skeletonJuke = evaluateSkeletonJukePlan(player);
        boolean isSkeletonTarget = combatTarget instanceof AbstractSkeleton;
        if (isSkeletonTarget) {
            int nearbySkeletonPressure = countVisibleSkeletonPressure(player);
            if (nearbySkeletonPressure >= 2 && distance > 2.1) {
                int commitTicks = SKELETON_KILL_COMMIT_MIN_TICKS + random.nextInt(
                        (SKELETON_KILL_COMMIT_MAX_TICKS - SKELETON_KILL_COMMIT_MIN_TICKS) + 1
                );
                skeletonKillCommitTicks = Math.max(skeletonKillCommitTicks, commitTicks);
                skeletonKillCommitTargetId = combatTarget.getId();
            } else if (skeletonKillCommitTicks > 0) {
                int decay = combatTarget.getId() == skeletonKillCommitTargetId ? 1 : 2;
                skeletonKillCommitTicks = Math.max(0, skeletonKillCommitTicks - decay);
            }
        } else if (skeletonKillCommitTicks > 0) {
            skeletonKillCommitTicks = Math.max(0, skeletonKillCommitTicks - 2);
        }
        if (skeletonKillCommitTicks <= 0) {
            skeletonKillCommitTargetId = -1;
        }
        boolean skeletonKillCommitActive = isSkeletonTarget
                && skeletonKillCommitTicks > 0
                && combatTarget.getId() == skeletonKillCommitTargetId;
        if (creeperThreat.shouldBlock()) {
            int commitTicks = CREEPER_BLOCK_COMMIT_MIN_TICKS + random.nextInt(
                    (CREEPER_BLOCK_COMMIT_MAX_TICKS - CREEPER_BLOCK_COMMIT_MIN_TICKS) + 1
            );
            if (creeperThreat.shouldRetreat()) {
                commitTicks += 4;
            }
            creeperShieldCommitTicks = Math.max(creeperShieldCommitTicks, commitTicks);
        } else {
            creeperShieldCommitTicks = Math.max(0, creeperShieldCommitTicks - 1);
        }
        boolean creeperBlockCommitted = creeperShieldCommitTicks > 0;
        double safeMinDistance;
        double safeMaxDistance;
        double attackRange;
        if (selectedWeaponType == WeaponType.AXE) {
            safeMinDistance = AXE_SAFE_MIN_DISTANCE;
            safeMaxDistance = AXE_SAFE_MAX_DISTANCE;
            attackRange = AXE_ATTACK_RANGE;
        } else if (selectedWeaponType == WeaponType.SWORD) {
            safeMinDistance = SWORD_SAFE_MIN_DISTANCE;
            safeMaxDistance = SWORD_SAFE_MAX_DISTANCE;
            attackRange = SWORD_ATTACK_RANGE;
        } else {
            // Fallback combat profile if no sword/axe was found.
            safeMinDistance = 1.6;
            safeMaxDistance = 2.6;
            attackRange = 3.0;
        }
        boolean closeSkeletonEngage = isSkeletonTarget
                && distance <= attackRange + 0.95
                && !creeperThreat.shouldRetreat();
        double cooldown = player.getAttackStrengthScale(0.0f);
        // While cooldown is rebuilding, bias movement toward safer spacing instead of face-trading.
        double cooldownGapFromReset = Math.clamp((0.97 - cooldown) / 0.45, 0.0, 1.0);
        safeMinDistance += cooldownGapFromReset * 0.20;
        safeMaxDistance += cooldownGapFromReset * 0.32;

        Vec3 toTarget = combatTarget.position().subtract(player.position());
        Vec3 toTargetDir = toTarget.lengthSqr() > 1.0E-6 ? toTarget.normalize() : new Vec3(0.0, 0.0, 0.0);
        double relativeAlongLine = combatTarget.getDeltaMovement()
                .subtract(player.getDeltaMovement())
                .dot(toTargetDir);
        double predictedDistance = distance + (relativeAlongLine * 5.0);
        boolean targetRecedingFromKnockback = postHitPauseTicks > 0
                && relativeAlongLine > 0.055
                && distance <= attackRange + 1.5;
        HostileSpacingMetrics hostileSpacing = evaluateHostileSpacing(player, safeMinDistance + 0.38);
        double nearestHostileDistance = Math.min(distance, hostileSpacing.nearestDistance());
        double nearestHostilePredictedDistance = Math.min(predictedDistance, hostileSpacing.nearestPredictedDistance());
        boolean hostileAdvancingPressure = hostileSpacing.anyAdvancingThreat();
        boolean crowdedCloseThreat = hostileSpacing.closeThreatCount() >= 2;

        double desiredDistance = (safeMinDistance + safeMaxDistance) * 0.5;
        boolean emergencyCloseRange = nearestHostileDistance <= Math.min(2.1, safeMinDistance + 0.22);
        boolean attackRecovering = cooldown < Math.min(0.92, nextAttackCooldownTarget + 0.04) && attackPressTicks <= 0;
        boolean targetAdvancing = relativeAlongLine < -0.022 || predictedDistance < distance - 0.07;
        boolean shouldKiteBackward = attackRecovering
                && (targetAdvancing || hostileAdvancingPressure)
                && nearestHostileDistance <= safeMaxDistance + 0.95
                && !targetRecedingFromKnockback;
        boolean shouldMaintainSpace = (nearestHostileDistance <= safeMinDistance + 0.34 && cooldown < 0.92)
                || shouldKiteBackward
                || (crowdedCloseThreat && nearestHostileDistance <= safeMaxDistance + 0.42);
        boolean safeToAdvance = predictedDistance > safeMinDistance + 0.10
                && nearestHostilePredictedDistance > safeMinDistance + 0.12
                && (postHitPauseTicks <= 0 || distance > desiredDistance + 0.30)
                && !targetRecedingFromKnockback
                && (cooldown >= 0.90 || nearestHostileDistance > safeMaxDistance + 0.55)
                && (!hostileAdvancingPressure || nearestHostileDistance > safeMaxDistance + 0.22 || cooldown >= 0.96);
        if (distance > desiredDistance + 0.16 && safeToAdvance) {
            combatMoveForward = true;
        }
        if (nearestHostileDistance < safeMinDistance + 0.02
                || nearestHostilePredictedDistance < safeMinDistance + 0.03
                || shouldMaintainSpace) {
            combatMoveBackward = true;
            combatMoveForward = false;
        }

        if (distance > safeMaxDistance + 1.2) {
            combatMoveLeft = false;
            combatMoveRight = false;
            combatMoveForward = combatMoveBackward ? false : safeToAdvance && nearestHostileDistance > safeMinDistance + 0.12;
        } else {
            combatMoveLeft = strafeDirection < 0;
            combatMoveRight = strafeDirection > 0;
        }
        if (targetRecedingFromKnockback) {
            // Preserve gained spacing after a likely hit-knockback event.
            combatMoveForward = false;
        }
        if (creeperThreat.shouldRetreat() || (committedCreeperRetreat && retreatCreeper != null)) {
            if (committedCreeperRetreat && retreatCreeper != null) {
                applyEmergencyCreeperFleeMovement(player, retreatCreeper, true);
            } else {
                // During creeper fuse danger, create space first instead of face-trading movement.
                combatMoveForward = false;
                combatMoveBackward = true;
                combatMoveLeft = false;
                combatMoveRight = false;
            }
        }
        boolean skeletonPressureAdvance = combatTarget instanceof AbstractSkeleton
                && distance > attackRange + 0.14
                && predictedDistance > safeMinDistance + 0.12
                && !creeperThreat.shouldRetreat();
        boolean strictSkeletonCharge = combatTarget instanceof AbstractSkeleton
                && distance > attackRange + 0.22
                && !creeperThreat.shouldRetreat();
        int skeletonChargeBypassDirection = strictSkeletonCharge
                ? findMeleeBypassDirection(player, combatTarget)
                : 0;
        if (skeletonKillCommitActive && distance > attackRange + 0.10 && !creeperThreat.shouldRetreat()) {
            skeletonPressureAdvance = true;
        }
        boolean shouldRetreatForArrowEvade = arrowEvasion.retreat()
                && !closeSkeletonEngage
                && !(skeletonPressureAdvance && !arrowEvasion.urgent())
                && !(skeletonKillCommitActive && !arrowEvasion.urgent());
        boolean shouldRetreatForSkeletonJuke = skeletonJuke.retreat()
                && !closeSkeletonEngage
                && !(skeletonPressureAdvance && !skeletonJuke.urgent())
                && !(skeletonKillCommitActive && !skeletonJuke.urgent());
        if (arrowEvasion.shouldEvade() && !creeperThreat.shouldRetreat() && !strictSkeletonCharge) {
            boolean shouldAdvanceDuringArrowEvade = closeSkeletonEngage
                    || skeletonPressureAdvance
                    || !shouldRetreatForArrowEvade
                    && distance > Math.max(attackRange + 0.24, safeMaxDistance + 0.16)
                    && predictedDistance > safeMinDistance + 0.20
                    && (combatTarget instanceof AbstractSkeleton || random.nextDouble() < 0.74);
            combatMoveForward = shouldAdvanceDuringArrowEvade;
            if (shouldAdvanceDuringArrowEvade) {
                combatMoveBackward = false;
            }
            if (shouldRetreatForArrowEvade) {
                combatMoveBackward = true;
                combatMoveForward = false;
            }
            if (arrowEvasion.strafeDirection() < 0) {
                combatMoveLeft = true;
                combatMoveRight = false;
            } else if (arrowEvasion.strafeDirection() > 0) {
                combatMoveLeft = false;
                combatMoveRight = true;
            }
            if (arrowEvasion.urgent() && player.onGround() && random.nextDouble() < 0.28) {
                combatJump = true;
            }
        }
        if (skeletonJuke.shouldJuke()
                && !creeperThreat.shouldRetreat()
                && !arrowEvasion.shouldEvade()
                && !strictSkeletonCharge) {
            boolean shouldAdvanceDuringJuke = closeSkeletonEngage
                    || skeletonPressureAdvance
                    || !shouldRetreatForSkeletonJuke
                    && distance > Math.max(attackRange + 0.28, safeMaxDistance + 0.18)
                    && predictedDistance > safeMinDistance + 0.18;
            combatMoveForward = shouldAdvanceDuringJuke;
            if (shouldAdvanceDuringJuke) {
                combatMoveBackward = false;
            }
            if (shouldRetreatForSkeletonJuke) {
                combatMoveBackward = true;
            } else if (!combatMoveBackward && distance > safeMinDistance + 0.36 && random.nextDouble() < 0.12) {
                // Occasionally vary speed profile to break simple leading prediction.
                combatMoveBackward = true;
                combatMoveForward = false;
            }
            if (skeletonJuke.strafeDirection() < 0) {
                combatMoveLeft = true;
                combatMoveRight = false;
            } else if (skeletonJuke.strafeDirection() > 0) {
                combatMoveLeft = false;
                combatMoveRight = true;
            }
            if (skeletonJuke.urgent() && player.onGround() && random.nextDouble() < 0.18) {
                combatJump = true;
            }
        }
        if (skeletonKillCommitActive && !creeperThreat.shouldRetreat()) {
            boolean shouldForceForwardClose = distance > attackRange + 0.08
                    && predictedDistance > safeMinDistance + 0.06;
            if (shouldForceForwardClose) {
                combatMoveForward = true;
                if (!arrowEvasion.urgent() && !skeletonJuke.urgent()) {
                    combatMoveBackward = false;
                }
            }
        }
        if (strictSkeletonCharge) {
            // Against ranged skeletons, prioritise pure gap-close over evasive micro-adjustments.
            combatMoveForward = true;
            combatMoveBackward = false;
            if (skeletonChargeBypassDirection < 0) {
                combatMoveLeft = true;
                combatMoveRight = false;
            } else if (skeletonChargeBypassDirection > 0) {
                combatMoveLeft = false;
                combatMoveRight = true;
            } else {
                combatMoveLeft = false;
                combatMoveRight = false;
            }
        }
        if (emergencyNoShieldCreeperFlee) {
            applyEmergencyCreeperFleeMovement(player, noShieldCriticalCreeper);
            attackPressTicks = 0;
            pendingAttackClicks = 0;
            combatAttack = false;
        }
        if (closeSkeletonEngage && !creeperThreat.shouldRetreat() && !emergencyNoShieldCreeperFlee) {
            if (distance > attackRange - 0.14) {
                combatMoveForward = true;
            }
            combatMoveBackward = false;
        }

        combatSprint = !combatMoveBackward && (arrowEvasion.shouldEvade() || skeletonJuke.shouldJuke() || random.nextDouble() < 0.92);
        if (emergencyNoShieldCreeperFlee || committedCreeperRetreat) {
            combatSprint = true;
        }
        combatUseItem = shouldRaiseShield(player, combatTarget);
        if (closeSkeletonEngage && !creeperBlockCommitted) {
            combatUseItem = false;
        }
        if (creeperBlockCommitted) {
            combatUseItem = true;
            shieldPushbackTicks = 0;
        }
        if (combatUseItem) {
            shieldHoldTicks++;
        } else {
            shieldHoldTicks = Math.max(0, shieldHoldTicks - 2);
        }
        boolean shouldStartShieldPushback = combatUseItem
                && !creeperBlockCommitted
                && !creeperThreat.shouldRetreat()
                && shieldPushbackTicks <= 0
                && shieldPushbackCooldownTicks <= 0
                && shieldHoldTicks >= 18
                && distance <= attackRange + 0.65;
        if (shouldStartShieldPushback) {
            shieldPushbackTicks = 4 + random.nextInt(3);
            shieldPushbackCooldownTicks = 16 + random.nextInt(8);
            shieldHoldTicks = 0;
        }
        boolean forceShieldPushback = !creeperBlockCommitted
                && !creeperThreat.shouldRetreat()
                && shieldPushbackTicks > 0
                && distance <= attackRange + 0.8;
        if (forceShieldPushback) {
            combatUseItem = false;
        }
        if (committedCreeperRetreat) {
            combatUseItem = false;
        }

        TargetMetrics liveMetrics = buildTargetMetrics(player, combatTarget);
        double requiredYaw = selectedWeaponType == WeaponType.AXE ? 6.8 : 7.8;
        double requiredPitch = selectedWeaponType == WeaponType.AXE ? 6.2 : 7.0;
        boolean crosshairCloseEnough = Math.abs(liveMetrics.yawError()) < requiredYaw
                && Math.abs(liveMetrics.pitchError()) < requiredPitch
                && liveMetrics.inView();
        boolean recoveryCrosshair = liveMetrics.inView()
                && Math.abs(liveMetrics.yawError()) < (requiredYaw + 4.2)
                && Math.abs(liveMetrics.pitchError()) < (requiredPitch + 3.4);

        boolean targetLikelyToLeaveCrosshair = lastTargetAngularSpeed > 3.5
                && (Math.abs(liveMetrics.yawError()) > 2.0 || Math.abs(liveMetrics.pitchError()) > 2.0);
        boolean broadMeleeAligned = liveMetrics.inView()
                && Math.abs(liveMetrics.yawError()) < 20.0
                && Math.abs(liveMetrics.pitchError()) < 15.0;
        boolean veryClosePanicRange = emergencyCloseRange;
        boolean closeSkeletonBrawl = closeSkeletonEngage && liveMetrics.inView();

        if (critPrepTicks > 0) {
            critPrepTicks--;
        }
        if (critPrepTicks == 0
                && player.onGround()
                && distance <= attackRange + 0.35
                && distance >= safeMinDistance + 0.15
                && cooldown > 0.88
                && random.nextDouble() < 0.14) {
            critPrepTicks = 7 + random.nextInt(4);
        }
        if (critPrepTicks > 0 && player.onGround()) {
            combatJump = true;
        }

        double adjustedEarlySigma = Math.max(0.028, attackEarlySigma);
        double earlyGap = nextAttackCooldownTarget - cooldown;
        double earlyTimerChance = 0.0;
        boolean allowEarlySwingWindow = veryClosePanicRange
                || forceShieldPushback
                || targetLikelyToLeaveCrosshair
                || catchupTicksRemaining > 0;
        if (earlyGap > 0.0 && allowEarlySwingWindow) {
            double normalizedGap = earlyGap / adjustedEarlySigma;
            earlyTimerChance = Math.exp(-0.5 * normalizedGap * normalizedGap) * 0.38;
            if (veryClosePanicRange || forceShieldPushback) {
                earlyTimerChance *= 1.35;
            } else if (targetLikelyToLeaveCrosshair || catchupTicksRemaining > 0) {
                earlyTimerChance *= 1.12;
            }
            earlyTimerChance = Math.clamp(earlyTimerChance, 0.0, 0.35);
        }
        boolean timerReady = cooldown >= nextAttackCooldownTarget;
        boolean fullyReset = cooldown >= 0.995;
        boolean shouldTakeEarlyTimerShot = cooldown >= (closeSkeletonBrawl ? 0.34 : (veryClosePanicRange ? 0.62 : 0.78))
                && random.nextDouble() < earlyTimerChance;

        boolean tryingForCritical = critPrepTicks > 0;
        boolean shouldAttackFromCriticalJump = tryingForCritical && !player.onGround() && cooldown >= 0.82;
        boolean shouldAttackVeryClose = distance <= Math.max(1.8, safeMinDistance - 0.1)
                && cooldown >= (closeSkeletonBrawl ? 0.34 : 0.58)
                && liveMetrics.inView();
        boolean broadlyAimed = liveMetrics.inView()
                && Math.abs(liveMetrics.yawError()) < 16.0
                && Math.abs(liveMetrics.pitchError()) < 12.0;
        boolean shouldProbeAttack = broadlyAimed
                && cooldown >= 0.80
                && (veryClosePanicRange || targetLikelyToLeaveCrosshair)
                && random.nextDouble() < 0.22;
        boolean shouldRecoveryAttack = recoveryCrosshair
                && cooldown >= 0.76
                && (catchupTicksRemaining > 0
                || correctionTicksRemaining > 0
                || targetLikelyToLeaveCrosshair
                || (veryClosePanicRange && random.nextDouble() < 0.22));
        boolean shouldPressureAttack = broadMeleeAligned
                && cooldown >= 0.82
                && distance <= attackRange - 0.05
                && (veryClosePanicRange || targetLikelyToLeaveCrosshair)
                && random.nextDouble() < 0.22;
        boolean shouldShieldPushbackAttack = forceShieldPushback
                && liveMetrics.inView()
                && Math.abs(liveMetrics.yawError()) < 14.0
                && Math.abs(liveMetrics.pitchError()) < 12.0
                && cooldown >= 0.56;
        boolean shouldForceAttackOnReset = fullyReset && recoveryCrosshair;
        boolean shouldUrgencyAttack = targetLikelyToLeaveCrosshair
                && recoveryCrosshair
                && cooldown >= 0.72;
        boolean isInRange = distance <= attackRange;
        boolean guaranteedSwingWindow = liveMetrics.inView()
                && distance <= attackRange + 0.30
                && Math.abs(liveMetrics.yawError()) < 9.0
                && Math.abs(liveMetrics.pitchError()) < 8.0;
        boolean shouldGuaranteedAttack = guaranteedSwingWindow && cooldown >= 0.97;
        boolean inMeleeViewWindow = isInRange && liveMetrics.inView()
                && (recoveryCrosshair || broadMeleeAligned || veryClosePanicRange);

        boolean pursuitStallCandidate = liveMetrics.inView()
                && !isInRange
                && distance <= attackRange + 3.6
                && !creeperThreat.shouldRetreat()
                && !emergencyNoShieldCreeperFlee;
        if (pursuitStallCandidate) {
            meleePursuitStallTicks++;
        } else {
            meleePursuitStallTicks = Math.max(0, meleePursuitStallTicks - 3);
        }
        boolean forceMeleeCharge = meleePursuitStallTicks >= 10
                && !arrowEvasion.retreat()
                && !shouldRetreatForSkeletonJuke
                && !creeperBlockCommitted;
        if (forceMeleeCharge) {
            combatMoveForward = true;
            combatMoveBackward = false;
            if (distance > attackRange + 1.15) {
                combatMoveLeft = false;
                combatMoveRight = false;
            }
        }
        if (inMeleeViewWindow && attackPressTicks <= 0) {
            ticksInRangeNoAttack++;
        } else {
            ticksInRangeNoAttack = Math.max(0, ticksInRangeNoAttack - 2);
        }

        boolean attackTimerReady = timerReady || shouldTakeEarlyTimerShot;
        boolean shouldTimerDrivenAttack = attackTimerReady && inMeleeViewWindow;
        boolean shouldForceCatchupAttack = ticksInRangeNoAttack >= (closeSkeletonBrawl ? 5 : 10)
                && cooldown >= (closeSkeletonBrawl ? 0.40 : 0.84)
                && inMeleeViewWindow;
        boolean shouldCloseSkeletonChainAttack = closeSkeletonBrawl
                && isInRange
                && recoveryCrosshair
                && cooldown >= 0.32;

        boolean shouldAttackThisTick = shouldGuaranteedAttack
                || (isInRange
                && (shouldTimerDrivenAttack
                || (crosshairCloseEnough && (attackTimerReady || shouldAttackFromCriticalJump))
                || shouldAttackVeryClose
                || shouldProbeAttack
                || shouldRecoveryAttack
                || shouldPressureAttack
                || shouldShieldPushbackAttack
                || shouldUrgencyAttack
                || shouldForceAttackOnReset
                || shouldForceCatchupAttack
                || shouldCloseSkeletonChainAttack
                || (veryClosePanicRange && liveMetrics.inView() && cooldown >= 0.56)));
        if (combatUseItem && !shouldGuaranteedAttack && !shouldShieldPushbackAttack) {
            shouldAttackThisTick = false;
        }
        if (emergencyNoShieldCreeperFlee) {
            shouldAttackThisTick = false;
            attackPressTicks = 0;
            pendingAttackClicks = 0;
        }
        if (committedCreeperRetreat) {
            shouldAttackThisTick = false;
            attackPressTicks = 0;
            pendingAttackClicks = 0;
        }
        if ((creeperThreat.shouldRetreat() || creeperBlockCommitted) && combatUseItem) {
            shouldAttackThisTick = false;
        }
        if (shouldRetreatForSkeletonJuke && !veryClosePanicRange && !closeSkeletonBrawl && !strictSkeletonCharge) {
            shouldAttackThisTick = false;
        }
        if (arrowEvasion.retreat() && combatMoveBackward && !veryClosePanicRange && !closeSkeletonBrawl) {
            shouldAttackThisTick = false;
        }
        boolean shouldPreserveCooldown = !veryClosePanicRange
                && !forceShieldPushback
                && !targetLikelyToLeaveCrosshair
                && distance > safeMinDistance - 0.05;
        if (shouldPreserveCooldown
                && !timerReady
                && cooldown < 0.90
                && !shouldGuaranteedAttack
                && !closeSkeletonBrawl
                && !shouldForceAttackOnReset) {
            shouldAttackThisTick = false;
        }

        if (tryingForCritical
                && player.onGround()
                && !veryClosePanicRange
                && !shouldGuaranteedAttack
                && !timerReady
                && cooldown < 0.95) {
            shouldAttackThisTick = false;
        }

        if (attackPressTicks <= 0 && shouldAttackThisTick) {
            int pressTicks = shouldGuaranteedAttack ? 2 : 1;
            if (!shouldGuaranteedAttack && (veryClosePanicRange || forceShieldPushback) && random.nextDouble() < 0.35) {
                pressTicks += 1;
            }
            attackPressTicks = pressTicks;
            int clickBurst = shouldGuaranteedAttack ? 2 : 1;
            if (!shouldGuaranteedAttack && veryClosePanicRange && random.nextDouble() < 0.16) {
                clickBurst = 2;
            }
            pendingAttackClicks = Math.max(pendingAttackClicks, clickBurst);
            postHitPauseTicks = veryClosePanicRange ? 1 + random.nextInt(2) : 3 + random.nextInt(3);
            ticksInRangeNoAttack = 0;
            if (shouldGuaranteedAttack) {
                critPrepTicks = 0;
            }
            scheduleNextAttackCadence(selectedWeaponType);
            if (random.nextDouble() < 0.25) {
                strafeDirection *= -1;
            }
        }

        if (attackPressTicks > 0) {
            combatAttack = true;
            attackPressTicks--;
        }

        if (postHitPauseTicks > 0) {
            postHitPauseTicks--;
        }
    }

    private static void updateBowCombatTick(LocalPlayer player,
                                            LivingEntity target,
                                            double distance,
                                            CreeperThreat creeperThreat,
                                            Creeper retreatCreeper,
                                            boolean committedCreeperRetreat,
                                            Creeper noShieldCriticalCreeper,
                                            boolean emergencyNoShieldCreeperFlee,
                                            ArrowEvasionPlan arrowEvasion) {
        bowReleaseCooldownTicks = Math.max(0, bowReleaseCooldownTicks - 1);
        combatAttack = false;
        pendingAttackClicks = 0;
        attackPressTicks = 0;
        critPrepTicks = 0;
        postHitPauseTicks = 0;

        HostileSpacingMetrics spacing = evaluateHostileSpacing(player, BOW_SAFE_MIN_DISTANCE + 0.45);
        double nearestHostileDistance = Math.min(distance, spacing.nearestDistance());
        double nearestHostilePredictedDistance = Math.min(distance, spacing.nearestPredictedDistance());
        boolean hostilePressure = spacing.anyAdvancingThreat() || spacing.closeThreatCount() >= 2;
        boolean isSkeletonTarget = target instanceof AbstractSkeleton;
        boolean hasMeleeOption = hasMeleeWeaponInHotbar(player);
        boolean closeGapToSkeleton = isSkeletonTarget
                && distance > BOW_SKELETON_MELEE_SWITCH_DISTANCE - 0.4
                && !creeperThreat.shouldRetreat();

        combatMoveForward = false;
        combatMoveBackward = false;
        combatMoveLeft = strafeDirection < 0;
        combatMoveRight = strafeDirection > 0;
        combatJump = false;

        boolean shouldBackpedal = !closeGapToSkeleton
                && (nearestHostileDistance <= BOW_SAFE_MIN_DISTANCE + 0.25
                || nearestHostilePredictedDistance <= BOW_SAFE_MIN_DISTANCE + 0.16
                || hostilePressure);
        boolean shouldAdvance = (distance >= BOW_SAFE_MAX_DISTANCE + 0.55 || closeGapToSkeleton)
                && !shouldBackpedal
                && !creeperThreat.shouldRetreat();
        if (shouldAdvance) {
            combatMoveForward = true;
        }
        if (shouldBackpedal) {
            combatMoveBackward = true;
            combatMoveForward = false;
        }

        if (arrowEvasion.shouldEvade() && !creeperThreat.shouldRetreat()) {
            if (arrowEvasion.strafeDirection() < 0) {
                combatMoveLeft = true;
                combatMoveRight = false;
            } else if (arrowEvasion.strafeDirection() > 0) {
                combatMoveLeft = false;
                combatMoveRight = true;
            }
            if (arrowEvasion.retreat() && (!closeGapToSkeleton || arrowEvasion.urgent())) {
                combatMoveBackward = true;
                combatMoveForward = false;
            }
            if (arrowEvasion.urgent() && player.onGround() && random.nextDouble() < 0.16) {
                combatJump = true;
            }
        }

        if (creeperThreat.shouldRetreat() || (committedCreeperRetreat && retreatCreeper != null)) {
            if (committedCreeperRetreat && retreatCreeper != null) {
                applyEmergencyCreeperFleeMovement(player, retreatCreeper, true);
            } else {
                combatMoveForward = false;
                combatMoveBackward = true;
                combatMoveLeft = false;
                combatMoveRight = false;
            }
        }

        TargetMetrics liveMetrics = buildTargetMetrics(player, target);
        boolean broadShotWindow = player.hasLineOfSight(target)
                && Math.abs(liveMetrics.yawError()) <= 27.0
                && Math.abs(liveMetrics.pitchError()) <= 19.0;
        boolean alignedForShot = liveMetrics.inView()
                && Math.abs(liveMetrics.yawError()) <= (distance > 10.0 ? 7.5 : 10.5)
                && Math.abs(liveMetrics.pitchError()) <= (distance > 10.0 ? 6.0 : 8.0);
        boolean shouldBowAtSkeletonNow = !isSkeletonTarget
                || !hasMeleeOption
                || distance >= BOW_SKELETON_MELEE_SWITCH_DISTANCE - 0.55;
        double bowMinDrawDistance = isSkeletonTarget && hasMeleeOption ? 4.5 : 3.2;
        boolean shouldDrawBow = !creeperThreat.shouldRetreat()
                && !emergencyNoShieldCreeperFlee
                && distance <= BOW_ATTACK_RANGE
                && distance >= bowMinDrawDistance
                && broadShotWindow
                && shouldBowAtSkeletonNow
                && player.hasLineOfSight(target);

        combatUseItem = false;
        if (bowReleaseCooldownTicks <= 0 && shouldDrawBow) {
            if (bowDrawTicks <= 0) {
                bowDrawTargetTicks = BOW_DRAW_MIN_TICKS + random.nextInt((BOW_DRAW_MAX_TICKS - BOW_DRAW_MIN_TICKS) + 1);
            }
            bowDrawTicks++;
            combatUseItem = true;

            boolean drawTimeout = bowDrawTicks >= bowDrawTargetTicks + 7;
            boolean shouldRelease = (bowDrawTicks >= bowDrawTargetTicks && (alignedForShot || random.nextDouble() < 0.42))
                    || drawTimeout
                    || (bowDrawTicks >= BOW_DRAW_MIN_TICKS + 4 && !liveMetrics.inView());
            if (shouldRelease) {
                combatUseItem = false;
                bowDrawTicks = 0;
                bowDrawTargetTicks = 0;
                bowReleaseCooldownTicks = BOW_RELEASE_COOLDOWN_MIN_TICKS + random.nextInt(
                        (BOW_RELEASE_COOLDOWN_MAX_TICKS - BOW_RELEASE_COOLDOWN_MIN_TICKS) + 1
                );
            }
        } else {
            bowDrawTicks = 0;
            bowDrawTargetTicks = 0;
        }

        if (emergencyNoShieldCreeperFlee) {
            applyEmergencyCreeperFleeMovement(player, noShieldCriticalCreeper);
            combatUseItem = false;
            bowDrawTicks = 0;
            bowDrawTargetTicks = 0;
            bowReleaseCooldownTicks = 0;
            combatSprint = true;
            return;
        }
        if (committedCreeperRetreat) {
            combatUseItem = false;
            bowDrawTicks = 0;
            bowDrawTargetTicks = 0;
            bowReleaseCooldownTicks = 0;
            combatSprint = true;
            return;
        }

        combatSprint = (closeGapToSkeleton && !combatMoveBackward)
                || (!combatUseItem
                && !combatMoveBackward
                && (distance > BOW_SAFE_MAX_DISTANCE || random.nextDouble() < 0.88));
    }

    private static void doCommittedCreeperRetreatAimFrame(LocalPlayer player, Creeper creeper) {
        if (creeper == null || !creeper.isAlive() || creeper.isSpectator()) {
            doOccludedCombatAimFrame(player);
            return;
        }

        Vec3 away = player.position().subtract(creeper.position());
        if ((away.x * away.x) + (away.z * away.z) < 1.0E-6) {
            doOccludedCombatAimFrame(player);
            return;
        }

        double yawError = horizontalYawErrorFromView(player.getViewVector(1.0f), away);
        double yawErrorAbs = Math.abs(yawError);
        double yawStepLimit = Math.clamp((yawErrorAbs * 0.92) + 12.0, 12.0, 86.0);
        double yawStep = (yawError * randomRange(0.82, 1.10))
                + randomRange(-3.2, 3.2)
                + (random.nextGaussian() * 1.6);
        if (yawErrorAbs > 20.0 && random.nextDouble() < 0.34) {
            yawStep += Math.signum(yawError) * randomRange(5.0, 13.0);
        }
        yawStep = Math.clamp(yawStep, -yawStepLimit, yawStepLimit);
        player.turn(yawStep, 0.0);

        float fleePitch = (float) Math.clamp(player.getXRot(), MIN_TARGET_PITCH_DEGREES, MAX_TARGET_PITCH_DEGREES);
        player.setXRot(fleePitch);

        // Prevent regular tracking animation from fighting this emergency turn-away behavior.
        shouldPerformAnimation = false;
        hasObservedTargetError = false;
        hasTargetErrorHistory = false;
    }

    private static void doNoShieldCreeperFleeAimFrame(LocalPlayer player, Creeper creeper) {
        if (creeper == null || !creeper.isAlive() || creeper.isSpectator()) {
            resetNoShieldFleeLookLock();
            doOccludedCombatAimFrame(player);
            return;
        }

        Vec3 toCreeper = creeper.position().subtract(player.position());
        Vec3 away = player.position().subtract(creeper.position());
        if ((away.x * away.x) + (away.z * away.z) < 1.0E-6 || (toCreeper.x * toCreeper.x) + (toCreeper.z * toCreeper.z) < 1.0E-6) {
            resetNoShieldFleeLookLock();
            doOccludedCombatAimFrame(player);
            return;
        }

        boolean newLock = !noShieldFleeLookLocked || noShieldFleeLookTargetId != creeper.getId();
        if (newLock) {
            noShieldFleeLookLocked = true;
            noShieldFleeLookTargetId = creeper.getId();
            noShieldFleeLockedPitch = (float) Math.clamp(player.getXRot(), MIN_TARGET_PITCH_DEGREES, MAX_TARGET_PITCH_DEGREES);
        }

        boolean hardFleeRequired = shouldHardFleeFromNoShieldCreeper(player, creeper);
        double desiredYawError = hardFleeRequired
                ? horizontalYawErrorFromView(player.getViewVector(1.0f), away)
                : horizontalYawErrorFromView(player.getViewVector(1.0f), toCreeper);
        double yawErrorAbs = Math.abs(desiredYawError);
        double yawStepLimit = hardFleeRequired
                ? Math.clamp((yawErrorAbs * 0.95) + 14.0, 14.0, 82.0)
                : Math.clamp((yawErrorAbs * 0.55) + 3.0, 3.0, 20.0);
        double trackingScale = hardFleeRequired ? randomRange(0.78, 1.08) : randomRange(0.55, 0.90);
        double randomContribution = hardFleeRequired
                ? randomRange(-4.5, 4.5) + (random.nextGaussian() * 2.2)
                : randomRange(-1.4, 1.4);
        if (hardFleeRequired && yawErrorAbs > 18.0 && random.nextDouble() < 0.42) {
            randomContribution += Math.signum(desiredYawError) * randomRange(6.0, 16.0);
        }
        if (!hardFleeRequired && yawErrorAbs < 1.8) {
            randomContribution *= 0.25;
        }

        double yawStep = (desiredYawError * trackingScale) + randomContribution;
        yawStep = Math.clamp(yawStep, -yawStepLimit, yawStepLimit);
        if (hardFleeRequired && yawErrorAbs > 40.0 && random.nextDouble() < 0.20) {
            yawStep += Math.signum(desiredYawError) * randomRange(8.0, 22.0);
            yawStep = Math.clamp(yawStep, -90.0, 90.0);
        }
        player.turn(yawStep, 0.0);
        player.setXRot((float) Math.clamp(noShieldFleeLockedPitch, MIN_TARGET_PITCH_DEGREES, MAX_TARGET_PITCH_DEGREES));

        // Prevent stale combat tracking state from fighting the flee-orientation behavior.
        shouldPerformAnimation = false;
        hasObservedTargetError = false;
        hasTargetErrorHistory = false;
    }

    private static void doCombatAimFrame(LocalPlayer player, LivingEntity target) {
        if (!isValidCombatTarget(player, target)) {
            clearCombatState();
            return;
        }
        if (!player.hasLineOfSight(target)) {
            doOccludedCombatAimFrame(player);
            return;
        }

        double distance = player.distanceTo(target);
        double cooldown = player.getAttackStrengthScale(0.0f);
        double attackRange = selectedWeaponType == WeaponType.AXE
                ? AXE_ATTACK_RANGE
                : (selectedWeaponType == WeaponType.SWORD
                ? SWORD_ATTACK_RANGE
                : (selectedWeaponType == WeaponType.BOW ? BOW_ATTACK_RANGE : 3.0));
        boolean closeForSwing = selectedWeaponType != WeaponType.BOW && distance <= attackRange + 0.35;
        Vec3 eyePosition = player.getEyePosition();
        Vec3 targetVelocity = target.getDeltaMovement();
        Vec3 relativeVelocity = targetVelocity.subtract(player.getDeltaMovement());
        double relativeHorizontalSpeed = Math.hypot(relativeVelocity.x, relativeVelocity.z);
        double leadScale = Math.clamp(distance / 6.0, 0.45, 1.25);
        Vec3 targetCenter = target.position().add(0.0, target.getBbHeight() * 0.68, 0.0);
        double horizontalLeadTicks = 6.0 * leadScale;
        double verticalLead = targetVelocity.y * (2.0 * leadScale);
        if (targetVelocity.y > 0.08 && distance <= attackRange + 0.8) {
            // Do not chase sudden knockback pop-up aggressively with vertical camera motion.
            verticalLead *= postHitPauseTicks > 0 ? 0.08 : 0.22;
        }
        verticalLead = Math.clamp(verticalLead, -0.16, 0.14);
        Vec3 baseAimPoint = targetCenter.add(
                relativeVelocity.x * horizontalLeadTicks,
                verticalLead,
                relativeVelocity.z * horizontalLeadTicks
        );

        LookAngles rawAngles = calculateLookAngles(eyePosition, baseAimPoint);
        double liveYawError = horizontalYawErrorFromView(
                player.getViewVector(1.0f),
                baseAimPoint.subtract(eyePosition)
        );
        double livePitchError = rawAngles.pitch() - player.getXRot();

        if (!hasObservedTargetError) {
            observedYawError = liveYawError;
            observedPitchError = livePitchError;
            correctionTargetYawError = observedYawError;
            correctionTargetPitchError = observedPitchError;
            correctionTicksRemaining = 0;
            catchupTicksRemaining = 0;
            targetMovementCheckTicks = sampleCombatTrackingInterval(distance, closeForSwing, relativeHorizontalSpeed);
            hasObservedTargetError = true;
        }

        double angularSpeed = 0;
        if (hasTargetErrorHistory) {
            angularSpeed = Math.hypot(liveYawError - lastTargetYawError, livePitchError - lastTargetPitchError);
        }
        lastTargetAngularSpeed = angularSpeed;

        boolean movementSampleDue = targetMovementCheckTicks <= 0;
        if (movementSampleDue) {
            targetMovementCheckTicks = sampleCombatTrackingInterval(distance, closeForSwing, relativeHorizontalSpeed);
        } else {
            targetMovementCheckTicks--;
        }

        double screenSpaceMovement = Math.hypot(liveYawError - observedYawError, livePitchError - observedPitchError);
        double surpriseLevel = 0.0;
        if (targetSwitchSlowTicks > 0) {
            surpriseLevel += 0.28;
        }
        if (player.hurtTime > 0) {
            surpriseLevel += 0.30;
        }
        surpriseLevel += Math.clamp(screenSpaceMovement / 9.0, 0.0, 0.80);
        surpriseLevel += Math.clamp(angularSpeed / 8.5, 0.0, 0.48);
        surpriseLevel = Math.clamp(surpriseLevel, 0.0, 1.65);
        if (surpriseLevel > 0.72) {
            int surpriseRecoveryTicks = sampleCatchupTicks(distance, Math.max(angularSpeed, screenSpaceMovement));
            aimBehindTicks = Math.max(aimBehindTicks, surpriseRecoveryTicks);
        }
        if (aimBehindTicks > 0) {
            aimBehindTicks--;
        }

        boolean forcePerceptionResample = screenSpaceMovement > (closeForSwing ? 2.6 : 4.3) || surpriseLevel > 0.80;
        if (pendingCorrectionDelayTicks <= 0 && (movementSampleDue || forcePerceptionResample)) {
            pendingCorrectionDelayTicks = sampleReactionDelayTicks(distance, surpriseLevel, closeForSwing, relativeHorizontalSpeed);
            pendingCorrectionWindowTicks = sampleCorrectionWindowTicks(distance, Math.max(screenSpaceMovement, angularSpeed));
        }

        if (pendingCorrectionDelayTicks > 0) {
            pendingCorrectionDelayTicks--;
        } else {
            if (pendingCorrectionWindowTicks <= 0) {
                pendingCorrectionWindowTicks = sampleCorrectionWindowTicks(distance, Math.max(screenSpaceMovement, angularSpeed));
            }
            double perceptionNoiseScale = (0.08 + (surpriseLevel * 0.16) + Math.clamp(distance / 24.0, 0.0, 0.16))
                    * aimHumanJitterScale;
            double perceivedYaw = liveYawError
                    + (random.nextGaussian() * perceptionNoiseScale)
                    + (aimHumanMicroSaccadeBias * (0.35 + random.nextDouble() * 0.65));
            double perceivedPitch = livePitchError + (random.nextGaussian() * perceptionNoiseScale * 0.72);

            double correctionGain = (0.18
                    + (1.0 / Math.max(1.0, pendingCorrectionWindowTicks))
                    + Math.clamp(relativeHorizontalSpeed * 1.5, 0.0, 0.22)
                    + (correctionTicksRemaining > 0 ? 0.16 : 0.0))
                    * aimHumanCorrectionScale;
            correctionGain = Math.clamp(correctionGain, 0.12, 0.86);

            correctionTargetYawError = lerp(correctionGain, correctionTargetYawError, perceivedYaw);
            correctionTargetPitchError = lerp(correctionGain, correctionTargetPitchError, perceivedPitch);

            double memoryBlend = Math.clamp(0.30 + (0.16 * (1.0 - surpriseLevel)), 0.18, 0.52);
            observedYawError = lerp(memoryBlend, observedYawError, correctionTargetYawError);
            observedPitchError = lerp(memoryBlend, observedPitchError, correctionTargetPitchError);
            pendingCorrectionWindowTicks--;
        }

        if (aimBiasTicks <= 0) {
            double aimWindowScale = distance <= attackRange + 0.45 ? 0.42 : 1.0;
            aimBiasYaw = randomRange(-0.7, 0.7) * aimWindowScale;
            aimBiasPitch = randomRange(-0.45, 0.45) * aimWindowScale;
            aimBiasTicks = 6 + random.nextInt(8);
        }
        aimBiasTicks--;

        double observedErrorMagnitude = Math.hypot(observedYawError, observedPitchError);
        boolean slowSwitchActive = targetSwitchSlowTicks > 0;
        if (!slowSwitchActive
                && (observedErrorMagnitude > 7.5 || screenSpaceMovement > (closeForSwing ? 2.8 : 4.8))) {
            int catchupTicks = sampleCatchupTicks(distance, Math.max(angularSpeed, screenSpaceMovement));
            catchupTicksRemaining = Math.max(catchupTicksRemaining, catchupTicks);
            correctionTicksRemaining = Math.max(
                    correctionTicksRemaining,
                    sampleCorrectionWindowTicks(distance, Math.max(angularSpeed, screenSpaceMovement))
            );
        } else {
            catchupTicksRemaining = Math.max(0, catchupTicksRemaining - 1);
            correctionTicksRemaining = Math.max(0, correctionTicksRemaining - 1);
        }
        boolean catchupActive = catchupTicksRemaining > 0;

        if (combatLookOffsetTicks <= 0) {
            double yawBase = distance > 3.6 ? 5.0 : (distance > 2.6 ? 2.8 : 0.8);
            double pitchBase = distance > 3.2 ? 1.4 : 0.7;
            combatLookOffsetTargetYaw = randomRange(-yawBase, yawBase) + (strafeDirection * randomRange(-0.9, 0.9));
            combatLookOffsetTargetPitch = randomRange(-pitchBase, pitchBase);
            combatLookOffsetTicks = 10 + random.nextInt(14);
        } else {
            combatLookOffsetTicks--;
        }
        combatLookOffsetYaw = lerp(0.09, combatLookOffsetYaw, combatLookOffsetTargetYaw);
        combatLookOffsetPitch = lerp(0.09, combatLookOffsetPitch, combatLookOffsetTargetPitch);

        double precision = closeForSwing ? Math.clamp((cooldown - 0.55) / 0.35, 0.0, 1.0) : 0.0;
        double lookOffsetScale = closeForSwing ? (0.01 + ((1.0 - precision) * 0.22)) : 0.28;
        if (relativeHorizontalSpeed > 0.05) {
            lookOffsetScale *= 0.72;
        }
        if (relativeHorizontalSpeed > 0.10) {
            lookOffsetScale *= 0.58;
        }
        if (catchupActive) {
            lookOffsetScale *= 0.35;
        }

        double liveTrackingWeight = closeForSwing ? 0.70 : 0.26;
        liveTrackingWeight += Math.clamp((relativeHorizontalSpeed - 0.04) * 2.8, 0.0, 0.24);
        if (observedErrorMagnitude > 4.5) {
            liveTrackingWeight += 0.12;
        }
        liveTrackingWeight -= Math.clamp((surpriseLevel * 0.14) + (aimBehindTicks > 0 ? 0.06 : 0.0), 0.0, 0.18);
        if (catchupActive) {
            liveTrackingWeight = Math.max(liveTrackingWeight, 0.84);
        }
        liveTrackingWeight = Math.clamp(liveTrackingWeight, 0.36, 0.90);

        double trackedYawForAim = lerp(liveTrackingWeight, observedYawError, liveYawError);
        double trackedPitchForAim = lerp(liveTrackingWeight, observedPitchError, livePitchError);
        double biasScale = closeForSwing ? 0.45 : 1.0;

        double targetYawDelta = trackedYawForAim + (aimBiasYaw * biasScale) + (combatLookOffsetYaw * lookOffsetScale);
        double targetPitchDelta = trackedPitchForAim + (aimBiasPitch * biasScale) + (combatLookOffsetPitch * lookOffsetScale);

        // Keep human noise realistic without allowing aim to drift categorically behind the target.
        double yawDivergenceLimit = closeForSwing
                ? 2.2
                : (distance > attackRange + 3.0 ? 6.2 : 4.4);
        double pitchDivergenceLimit = closeForSwing
                ? 1.7
                : (distance > attackRange + 3.0 ? 4.4 : 3.2);
        targetYawDelta = liveYawError + Math.clamp(targetYawDelta - liveYawError, -yawDivergenceLimit, yawDivergenceLimit);
        targetPitchDelta = livePitchError + Math.clamp(targetPitchDelta - livePitchError, -pitchDivergenceLimit, pitchDivergenceLimit);

        if (liveYawError * targetYawDelta < 0.0 && Math.abs(liveYawError) < 26.0) {
            targetYawDelta = liveYawError * randomRange(0.62, 0.94);
        }
        if (livePitchError * targetPitchDelta < 0.0 && Math.abs(livePitchError) < 22.0) {
            targetPitchDelta = livePitchError * randomRange(0.60, 0.92);
        }
        if (Math.abs(liveYawError) <= 3.0) {
            targetYawDelta = Math.clamp(targetYawDelta, liveYawError - 1.4, liveYawError + 1.4);
        }
        if (Math.abs(livePitchError) <= 2.6) {
            targetPitchDelta = Math.clamp(targetPitchDelta, livePitchError - 1.1, livePitchError + 1.1);
        }

        boolean upwardPopMotion = targetVelocity.y > 0.08;
        boolean closeHorizontalControlWindow = distance <= attackRange + 0.45 && Math.abs(liveYawError) < 10.5;
        if (closeHorizontalControlWindow && upwardPopMotion) {
            targetPitchDelta = Math.max(targetPitchDelta, postHitPauseTicks > 0 ? -0.45 : -0.75);
        }

        targetYawDelta = Math.clamp(targetYawDelta, -70.0, 70.0);
        targetPitchDelta = Math.clamp(targetPitchDelta, -40.0, 40.0);
        double targetDeltaMagnitude = Math.hypot(targetYawDelta, targetPitchDelta);

        double yawRetargetThreshold = catchupActive ? 2.2 : 0.9;
        double pitchRetargetThreshold = catchupActive ? 1.8 : 0.7;
        if (closeForSwing && relativeHorizontalSpeed > 0.05) {
            yawRetargetThreshold = Math.min(yawRetargetThreshold, 0.45);
            pitchRetargetThreshold = Math.min(pitchRetargetThreshold, 0.35);
        }
        boolean shouldRestartAnimation = !shouldPerformAnimation
                || animationFrame >= animationDurationFrames
                || Math.abs(nextYawChangeDegrees - targetYawDelta) > (slowSwitchActive ? 3.8 : yawRetargetThreshold)
                || Math.abs(nextPitchChangeDegrees - targetPitchDelta) > (slowSwitchActive ? 2.6 : pitchRetargetThreshold);
        if (shouldRestartAnimation) {
            startCameraYaw = player.getYRot();
            startCameraPitch = player.getXRot();
            animationFrame = 0;
            nextYawChangeDegrees = targetYawDelta;
            nextPitchChangeDegrees = targetPitchDelta;
            configureCombatTrackingAnimationProfile(targetDeltaMagnitude, distance, catchupActive, closeForSwing);
            shouldPerformAnimation = true;
        }

        double animationProgress = Math.min(1.0, (animationFrame + 1.0) / Math.max(1.0, animationDurationFrames));
        double easedProgress = easeProgress(animationProgress);

        double targetYaw = startCameraYaw + nextYawChangeDegrees;
        double targetPitch = Math.clamp(
                startCameraPitch + nextPitchChangeDegrees,
                MIN_TARGET_PITCH_DEGREES,
                MAX_TARGET_PITCH_DEGREES
        );

        double oscillation = 0;
        if (oscillationCycles > 0) {
            oscillation = Math.sin(animationProgress * Math.PI * oscillationCycles)
                    * Math.exp(-oscillationDamping * animationProgress);
        }

        double totalYawTravel = targetYaw - startCameraYaw;
        double desiredYaw = startCameraYaw + (totalYawTravel * easedProgress)
                + (Math.signum(totalYawTravel) * yawOvershootAmplitude * oscillation);
        double desiredPitch = lerp(easedProgress, startCameraPitch, targetPitch)
                + (Math.signum(targetPitch - startCameraPitch) * pitchOvershootAmplitude * oscillation);
        desiredPitch = Math.clamp(desiredPitch, MIN_TARGET_PITCH_DEGREES, MAX_TARGET_PITCH_DEGREES);

        double currentYaw = player.getYRot();
        double currentPitch = player.getXRot();
        double deltaYaw = desiredYaw - currentYaw;
        double deltaPitch = desiredPitch - currentPitch;
        double deltaMagnitude = Math.hypot(deltaYaw, deltaPitch);

        double speedEnvelope = 0.45 + (Math.sin(animationProgress * Math.PI) * 1.10);
        double yawStepLimit = randomRange(yawStepMin, yawStepMax) * speedEnvelope;
        double pitchStepLimit = randomRange(pitchStepMin, pitchStepMax) * speedEnvelope;
        if (deltaMagnitude < 1.2) {
            yawStepLimit *= 0.38;
            pitchStepLimit *= 0.34;
        }
        if (deltaMagnitude < 0.45) {
            yawStepLimit *= 0.18;
            pitchStepLimit *= 0.14;
        }

        double yawStep = Math.clamp(deltaYaw, -yawStepLimit, yawStepLimit);
        double pitchStep = Math.clamp(deltaPitch, -pitchStepLimit, pitchStepLimit);
        yawStep *= randomRange(0.96, 1.04);
        pitchStep *= randomRange(0.96, 1.04);
        if (slowSwitchActive) {
            double switchYawNoise = randomRange(-0.9, 0.9);
            double switchPitchNoise = randomRange(-0.35, 0.35);
            if (Math.abs(deltaYaw) > 8.0 && random.nextDouble() < 0.34) {
                switchYawNoise += Math.signum(deltaYaw) * randomRange(0.9, 3.0);
            }
            yawStep += switchYawNoise;
            pitchStep += switchPitchNoise;
            yawStep = Math.clamp(yawStep, -(yawStepLimit * 1.35), yawStepLimit * 1.35);
            pitchStep = Math.clamp(pitchStep, -(pitchStepLimit * 1.25), pitchStepLimit * 1.25);
        }
        if (random.nextDouble() < 0.16) {
            yawStep += randomRange(-0.18, 0.18);
            pitchStep += randomRange(-0.12, 0.12);
        }

        // Human-like motor layer: velocity-limited corrections with small tremor/micro-saccade noise.
        double reactionLagScale = Math.clamp(
                (aimHumanReactionScale * (1.0 + (surpriseLevel * 0.22))) + (aimBehindTicks > 0 ? 0.06 : 0.0),
                0.65,
                1.95
        );
        double accelerationGain = Math.clamp((aimHumanCorrectionScale / reactionLagScale), 0.42, 1.55);
        double yawAccelerationLimit = Math.max(0.06, yawStepLimit * 0.34 * accelerationGain * (catchupActive ? 1.20 : 1.0));
        double pitchAccelerationLimit = Math.max(0.05, pitchStepLimit * 0.32 * accelerationGain * (catchupActive ? 1.14 : 1.0));

        combatAimYawVelocity += Math.clamp(yawStep - combatAimYawVelocity, -yawAccelerationLimit, yawAccelerationLimit);
        combatAimPitchVelocity += Math.clamp(pitchStep - combatAimPitchVelocity, -pitchAccelerationLimit, pitchAccelerationLimit);

        double tremorBase = (0.018 + (Math.min(deltaMagnitude, 16.0) * 0.0034) + (surpriseLevel * 0.028)) * aimHumanJitterScale;
        double tremorYaw = (random.nextGaussian() * tremorBase)
                + (Math.sin((player.tickCount * 0.34) + 1.6) * tremorBase * 0.55);
        double tremorPitch = (random.nextGaussian() * tremorBase * 0.78)
                + (Math.cos((player.tickCount * 0.31) + 0.7) * tremorBase * 0.45);

        yawStep = combatAimYawVelocity + tremorYaw;
        pitchStep = combatAimPitchVelocity + tremorPitch;

        double yawCap = yawStepLimit * (slowSwitchActive ? 1.35 : 1.18);
        double pitchCap = pitchStepLimit * (slowSwitchActive ? 1.30 : 1.15);
        yawStep = Math.clamp(yawStep, -yawCap, yawCap);
        pitchStep = Math.clamp(pitchStep, -pitchCap, pitchCap);

        double combatNoiseIntensity = (closeForSwing ? 0.54 : 0.82)
                + (surpriseLevel * 0.32)
                + Math.min(0.38, deltaMagnitude / 18.0);
        MouseDelta noisyCombatInput = applyNaturalMouseNoise(
                yawStep,
                pitchStep,
                yawCap,
                pitchCap,
                combatNoiseIntensity
        );
        yawStep = noisyCombatInput.yaw();
        pitchStep = noisyCombatInput.pitch();

        player.turn(yawStep, pitchStep);
        player.setXRot((float) Math.clamp(player.getXRot(), MIN_TARGET_PITCH_DEGREES, MAX_TARGET_PITCH_DEGREES));
        animationFrame++;
        if (targetSwitchSlowTicks > 0) {
            targetSwitchSlowTicks--;
        }

        boolean animationFinished = animationProgress >= 1.0
                && Math.abs(deltaYaw) < 0.22
                && Math.abs(deltaPitch) < 0.22;
        boolean animationTimedOut = animationFrame >= animationDurationFrames + 18;
        if (animationFinished || animationTimedOut) {
            shouldPerformAnimation = false;
        }

        LookAngles residualAngles = calculateLookAngles(player.getEyePosition(), baseAimPoint);
        lastTargetYawError = horizontalYawErrorFromView(
                player.getViewVector(1.0f),
                baseAimPoint.subtract(player.getEyePosition())
        );
        lastTargetPitchError = residualAngles.pitch() - player.getXRot();
        hasTargetErrorHistory = true;
    }

    private static void doOccludedCombatAimFrame(LocalPlayer player) {
        // While LOS is blocked, keep combat target state but avoid direct target-facing corrections.
        hasObservedTargetError = false;
        hasTargetErrorHistory = false;
        observedYawError = 0;
        observedPitchError = 0;
        correctionTargetYawError = 0;
        correctionTargetPitchError = 0;
        targetMovementCheckTicks = 0;
        correctionTicksRemaining = 0;
        catchupTicksRemaining = 0;

        if (!shouldPerformAnimation) {
            startCameraYaw = player.getYRot();
            startCameraPitch = player.getXRot();
            animationFrame = 0;
            nextYawChangeDegrees = sampleMouseLikeDelta(0.6, 1.4, 3.2, 0.32, 0.05, 8.0);
            nextPitchChangeDegrees = sampleMouseLikeDelta(0.35, 0.8, 1.8, 0.26, 0.03, 4.0);
            animationDurationFrames = 6 + random.nextInt(12);
            animationEasePower = randomRange(1.05, 1.9);
            yawStepMin = 0.18;
            yawStepMax = 1.6;
            pitchStepMin = 0.12;
            pitchStepMax = 1.1;
            oscillationCycles = 0;
            oscillationDamping = 0;
            yawOvershootAmplitude = 0;
            pitchOvershootAmplitude = 0;
            shouldPerformAnimation = true;
        }

        double animationProgress = Math.min(1.0, (animationFrame + 1.0) / Math.max(1.0, animationDurationFrames));
        double easedProgress = easeProgress(animationProgress);

        double targetYaw = startCameraYaw + nextYawChangeDegrees;
        double targetPitch = Math.clamp(
                startCameraPitch + nextPitchChangeDegrees,
                MIN_TARGET_PITCH_DEGREES,
                MAX_TARGET_PITCH_DEGREES
        );

        double currentYaw = player.getYRot();
        double currentPitch = player.getXRot();
        double desiredYaw = lerp(easedProgress, startCameraYaw, targetYaw);
        double desiredPitch = lerp(easedProgress, startCameraPitch, targetPitch);

        double deltaYaw = desiredYaw - currentYaw;
        double deltaPitch = desiredPitch - currentPitch;
        double speedEnvelope = 0.40 + (Math.sin(animationProgress * Math.PI) * 1.0);
        double yawStepLimit = randomRange(yawStepMin, yawStepMax) * speedEnvelope;
        double pitchStepLimit = randomRange(pitchStepMin, pitchStepMax) * speedEnvelope;
        double yawStep = Math.clamp(deltaYaw, -yawStepLimit, yawStepLimit);
        double pitchStep = Math.clamp(deltaPitch, -pitchStepLimit, pitchStepLimit);

        double occludedNoiseIntensity = 0.34 + Math.min(0.40, Math.hypot(deltaYaw, deltaPitch) / 8.0);
        MouseDelta noisyOccludedInput = applyNaturalMouseNoise(
                yawStep,
                pitchStep,
                yawStepLimit,
                pitchStepLimit,
                occludedNoiseIntensity
        );
        yawStep = noisyOccludedInput.yaw();
        pitchStep = noisyOccludedInput.pitch();

        player.turn(yawStep, pitchStep);
        player.setXRot((float) Math.clamp(player.getXRot(), MIN_TARGET_PITCH_DEGREES, MAX_TARGET_PITCH_DEGREES));
        animationFrame++;

        boolean animationFinished = animationProgress >= 1.0
                && Math.abs(deltaYaw) < 0.16
                && Math.abs(deltaPitch) < 0.16;
        boolean animationTimedOut = animationFrame >= animationDurationFrames + 14;
        if (animationFinished || animationTimedOut) {
            shouldPerformAnimation = false;
        }
    }

    private static void updateCombatTarget(LocalPlayer player) {
        updateAttackerHitMemory(player);

        Creeper noShieldCriticalCreeper = resolveNoShieldCriticalCreeperThreat(player);
        if (noShieldCriticalCreeper != null) {
            if (combatTarget != noShieldCriticalCreeper) {
                forceSwitchToRecentAttacker(noShieldCriticalCreeper);
            } else {
                targetOutOfViewTicks = 0;
            }
            return;
        }

        Creeper criticalCreeper = findCriticalCreeperThreat(player);
        if (criticalCreeper != null) {
            if (combatTarget != criticalCreeper) {
                forceSwitchToRecentAttacker(criticalCreeper);
            } else {
                targetOutOfViewTicks = 0;
            }
            return;
        }

        LivingEntity rangedPriorityTarget = findPriorityRangedAttackerTarget(player, combatTarget);
        if (rangedPriorityTarget != null) {
            if (combatTarget != rangedPriorityTarget) {
                forceSwitchToRecentAttacker(rangedPriorityTarget);
            } else if (isValidCombatTarget(player, combatTarget)) {
                targetOutOfViewTicks = 0;
            }
            return;
        }

        if (combatTarget != null
                && skeletonKillCommitTicks > 0
                && combatTarget.getId() == skeletonKillCommitTargetId
                && combatTarget instanceof AbstractSkeleton
                && isValidCombatTarget(player, combatTarget)) {
            targetOutOfViewTicks = 0;
            return;
        }

        if (combatTarget == null) {
            LivingEntity immediateRetaliation = findImmediateRetaliationTarget(player);
            if (immediateRetaliation != null) {
                forceSwitchToRecentAttacker(immediateRetaliation);
                return;
            }
        }

        LivingEntity recentAttacker = findRecentAttackerTarget(player);
        if (recentAttacker != null && recentAttacker != combatTarget) {
            if (shouldKeepCurrentTargetAgainstSkeletonAttacker(player, combatTarget, recentAttacker)) {
                targetOutOfViewTicks = 0;
                return;
            }
            forceSwitchToRecentAttacker(recentAttacker);
            return;
        }

        if (combatTarget != null && isValidCombatTarget(player, combatTarget)) {
            LivingEntity threatOverrideTarget = chooseThreatOverrideTarget(player, combatTarget);
            if (threatOverrideTarget != null) {
                registerTargetSwitch(player, combatTarget, threatOverrideTarget);
                combatTarget = threatOverrideTarget;
                combatTargetLockTicks = 0;
                targetReevaluateTicks = TARGET_REEVALUATE_TICKS + random.nextInt(3);
                resetThreatTracking();
                resetAimTrackingForNewTarget();
                return;
            }

            TargetMetrics currentMetrics = buildTargetMetrics(player, combatTarget);
            if (currentMetrics.inView()) {
                targetOutOfViewTicks = 0;
                combatTargetLockTicks++;

                if (combatTargetLockTicks < TARGET_MIN_LOCK_TICKS) {
                    return;
                }
                if (targetReevaluateTicks > 0) {
                    targetReevaluateTicks--;
                    return;
                }

                targetReevaluateTicks = TARGET_REEVALUATE_TICKS + random.nextInt(5);
                Mob bestVisibleTarget = findBestVisibleTarget(player);
                if (bestVisibleTarget == null || bestVisibleTarget == combatTarget) {
                    return;
                }

                double currentScore = scoreTarget(player, combatTarget);
                double candidateScore = scoreTarget(player, bestVisibleTarget);
                if (candidateScore + TARGET_SWITCH_SCORE_MARGIN < currentScore) {
                    registerTargetSwitch(player, combatTarget, bestVisibleTarget);
                    combatTarget = bestVisibleTarget;
                    combatTargetLockTicks = 0;
                    targetReevaluateTicks = TARGET_REEVALUATE_TICKS + random.nextInt(4);
                    resetThreatTracking();
                    resetAimTrackingForNewTarget();
                }
                return;
            }

            if (!player.hasLineOfSight(combatTarget)) {
                // Keep current target while it's occluded by blocks; do not force retarget/drop.
                targetOutOfViewTicks = 0;
                return;
            }

            targetOutOfViewTicks++;
            if (targetOutOfViewTicks <= MAX_TARGET_OUT_OF_VIEW_TICKS) {
                return;
            }
        }

        if (combatTarget == null) {
            recentAttacker = findRecentAttackerTarget(player);
            if (recentAttacker != null) {
                forceSwitchToRecentAttacker(recentAttacker);
                return;
            }
        }

        Mob bestVisibleTarget = findBestVisibleTarget(player);
        if (bestVisibleTarget != combatTarget) {
            registerTargetSwitch(player, combatTarget, bestVisibleTarget);
            resetThreatTracking();
            resetAimTrackingForNewTarget();
            combatTargetLockTicks = 0;
            targetReevaluateTicks = TARGET_REEVALUATE_TICKS + random.nextInt(4);
        }

        combatTarget = bestVisibleTarget;
        targetOutOfViewTicks = 0;
        if (combatTarget == null) {
            combatActive = false;
            combatTargetLockTicks = 0;
            targetReevaluateTicks = 0;
            targetSwitchSlowTicks = 0;
            targetSwitchYawDelta = 0;
            skeletonKillCommitTicks = 0;
            skeletonKillCommitTargetId = -1;
            resetThreatTracking();
        }
    }

    private static void forceSwitchToRecentAttacker(LivingEntity attacker) {
        combatTarget = attacker;
        combatTargetLockTicks = 0;
        targetReevaluateTicks = TARGET_REEVALUATE_TICKS;
        targetOutOfViewTicks = 0;
        targetSwitchSlowTicks = 0;
        targetSwitchYawDelta = 0;
        resetThreatTracking();
        resetAimTrackingForNewTarget();
        catchupTicksRemaining = Math.max(catchupTicksRemaining, 5);
        correctionTicksRemaining = Math.max(correctionTicksRemaining, 4);
    }

    private static Mob findBestVisibleTarget(LocalPlayer player) {
        List<Mob> nearbyMobs = player.level().getEntitiesOfClass(
                Mob.class,
                player.getBoundingBox().inflate(COMBAT_SEARCH_RADIUS),
                mob -> isHostileMob(mob)
                        && mob.isAlive()
                        && mob.isPickable()
                        && !mob.isSpectator()
                        && player.hasLineOfSight(mob)
        );

        Mob bestTarget = null;
        double bestScore = Double.MAX_VALUE;
        for (Mob mob : nearbyMobs) {
            double score = scoreTarget(player, mob);
            if (score == Double.MAX_VALUE) {
                continue;
            }

            score += randomRange(0.0, 0.18);
            if (score < bestScore) {
                bestScore = score;
                bestTarget = mob;
            }
        }
        return bestTarget;
    }

    private static LivingEntity findPriorityRangedAttackerTarget(LocalPlayer player, LivingEntity currentTarget) {
        if (!hasCloseRangeMeleePressure(player)) {
            return null;
        }

        List<AbstractSkeleton> nearbySkeletons = player.level().getEntitiesOfClass(
                AbstractSkeleton.class,
                player.getBoundingBox().inflate(COMBAT_SEARCH_RADIUS + 2.0),
                skeleton -> skeleton.isAlive()
                        && skeleton.isPickable()
                        && !skeleton.isSpectator()
                        && player.hasLineOfSight(skeleton)
        );

        LivingEntity bestTarget = null;
        double bestScore = Double.MAX_VALUE;
        Vec3 playerCenter = player.position().add(0.0, player.getBbHeight() * 0.5, 0.0);
        for (AbstractSkeleton skeleton : nearbySkeletons) {
            if (!hasSkeletonPriorityHitHistory(skeleton)) {
                continue;
            }

            double distance = player.distanceTo(skeleton);
            if (distance > COMBAT_MAX_TRACK_RANGE + 1.2) {
                continue;
            }

            Vec3 toPlayer = playerCenter.subtract(skeleton.getEyePosition());
            if (toPlayer.lengthSqr() < 1.0E-6) {
                continue;
            }
            Vec3 toPlayerDir = toPlayer.normalize();
            double aimDot = skeleton.getViewVector(1.0f).dot(toPlayerDir);
            boolean focusingPlayer = skeleton.getTarget() == player;
            if (!focusingPlayer && aimDot < 0.78) {
                continue;
            }

            Vec3 toSkeleton = skeleton.position().subtract(player.position());
            double yawError = Math.abs(horizontalYawErrorFromView(player.getViewVector(1.0f), toSkeleton));
            double score = (distance * 1.15) + (yawError * 0.03) - (focusingPlayer ? 1.2 : 0.0);
            if (score < bestScore) {
                bestScore = score;
                bestTarget = skeleton;
            }
        }

        if (bestTarget != null && shouldDeferSkeletonPrioritySwitch(player, currentTarget, bestTarget)) {
            return null;
        }

        return bestTarget;
    }

    private static boolean hasCloseRangeMeleePressure(LocalPlayer player) {
        List<LivingEntity> nearbyMeleeHostiles = player.level().getEntitiesOfClass(
                LivingEntity.class,
                player.getBoundingBox().inflate(6.2),
                mob -> isHostileMob(mob)
                        && !(mob instanceof AbstractSkeleton)
                        && mob.isAlive()
                        && mob.isPickable()
                        && !mob.isSpectator()
        );

        for (LivingEntity hostile : nearbyMeleeHostiles) {
            double distance = player.distanceTo(hostile);
            boolean closeBy = distance <= 4.4;
            boolean hardAggro = hostile instanceof Mob mob && mob.getTarget() == player && distance <= 6.0;
            if (closeBy || hardAggro) {
                return true;
            }
        }
        return false;
    }

    private static void updateAttackerHitMemory(LocalPlayer player) {
        int hurtTimestamp = player.getLastHurtByMobTimestamp();
        if (hurtTimestamp != lastProcessedHurtTimestamp) {
            lastProcessedHurtTimestamp = hurtTimestamp;
            LivingEntity attacker = player.getLastHurtByMob();
            if (attacker instanceof AbstractSkeleton skeleton
                    && skeleton.isAlive()
                    && skeleton.isPickable()
                    && !skeleton.isSpectator()) {
                int id = skeleton.getId();
                int hits = skeletonHitCounts.getOrDefault(id, 0) + 1;
                skeletonHitCounts.put(id, Math.min(8, hits));
                skeletonLastHitTicks.put(id, player.tickCount);
            }
        }

        int staleTickCutoff = player.tickCount - SKELETON_HIT_MEMORY_TICKS;
        skeletonLastHitTicks.entrySet().removeIf(entry -> {
            int id = entry.getKey();
            boolean stale = entry.getValue() < staleTickCutoff;
            boolean gone = player.level().getEntity(id) == null;
            if (stale || gone) {
                skeletonHitCounts.remove(id);
                return true;
            }
            return false;
        });
    }

    private static boolean hasSkeletonPriorityHitHistory(AbstractSkeleton skeleton) {
        if (skeleton == null) {
            return false;
        }
        return skeletonHitCounts.getOrDefault(skeleton.getId(), 0) >= SKELETON_PRIORITY_REQUIRED_HITS;
    }

    private static boolean shouldDeferSkeletonPrioritySwitch(LocalPlayer player,
                                                             LivingEntity currentTarget,
                                                             LivingEntity skeletonTarget) {
        if (currentTarget == null || skeletonTarget == null || currentTarget == skeletonTarget) {
            return false;
        }
        if (currentTarget instanceof AbstractSkeleton) {
            return false;
        }
        if (!isValidCombatTarget(player, currentTarget)) {
            return false;
        }

        double currentDistance = player.distanceTo(currentTarget);
        if (currentDistance > SKELETON_PRIORITY_DEFER_CURRENT_DISTANCE) {
            return false;
        }

        double skeletonDistance = player.distanceTo(skeletonTarget);
        return skeletonDistance >= currentDistance - SKELETON_PRIORITY_STRONGER_CLOSENESS_MARGIN;
    }

    private static boolean shouldKeepCurrentTargetAgainstSkeletonAttacker(LocalPlayer player,
                                                                           LivingEntity currentTarget,
                                                                           LivingEntity candidateAttacker) {
        if (!(candidateAttacker instanceof AbstractSkeleton skeleton)) {
            return false;
        }
        if (currentTarget == null || currentTarget instanceof AbstractSkeleton) {
            return false;
        }
        if (!isValidCombatTarget(player, currentTarget)) {
            return false;
        }
        if (!hasSkeletonPriorityHitHistory(skeleton)) {
            return true;
        }
        return shouldDeferSkeletonPrioritySwitch(player, currentTarget, skeleton);
    }

    private static LivingEntity findRecentAttackerTarget(LocalPlayer player) {
        LivingEntity attacker = player.getLastHurtByMob();
        if (attacker == null) {
            return null;
        }
        if (!isHostileMob(attacker)
                || !attacker.isAlive()
                || attacker.isSpectator()
                || !attacker.isPickable()) {
            return null;
        }

        int ticksSinceAttacked = player.tickCount - player.getLastHurtByMobTimestamp();
        boolean immediateHurtWindow = player.hurtTime > 0;
        if (!immediateHurtWindow && ticksSinceAttacked > 40) {
            return null;
        }

        double attackerDistance = player.distanceTo(attacker);
        if (attackerDistance > COMBAT_MAX_TRACK_RANGE + 4.0) {
            return null;
        }
        boolean hasLineOfSight = player.hasLineOfSight(attacker);
        boolean immediateCloseContact = immediateHurtWindow && attackerDistance <= 3.2;
        if (!hasLineOfSight && !immediateCloseContact) {
            return null;
        }

        return attacker;
    }

    private static LivingEntity findImmediateRetaliationTarget(LocalPlayer player) {
        if (player.hurtTime <= 0) {
            return null;
        }

        LivingEntity recentAttacker = findRecentAttackerTarget(player);
        if (recentAttacker != null) {
            return recentAttacker;
        }

        List<LivingEntity> nearbyHostiles = player.level().getEntitiesOfClass(
                LivingEntity.class,
                player.getBoundingBox().inflate(COMBAT_SEARCH_RADIUS + 2.0),
                mob -> isHostileMob(mob)
                        && mob.isAlive()
                        && mob.isPickable()
                        && !mob.isSpectator()
                        && player.hasLineOfSight(mob)
        );

        LivingEntity bestTarget = null;
        double bestScore = Double.MAX_VALUE;
        for (LivingEntity mob : nearbyHostiles) {
            double distance = player.distanceTo(mob);
            if (distance > COMBAT_MAX_TRACK_RANGE + 2.0) {
                continue;
            }

            Vec3 toMob = mob.position().subtract(player.position());
            double yawError = Math.abs(horizontalYawErrorFromView(player.getViewVector(1.0f), toMob));
            double score = (distance * 1.2) + (yawError * 0.028);
            if (score < bestScore) {
                bestScore = score;
                bestTarget = mob;
            }
        }

        return bestTarget;
    }

    private static LivingEntity chooseThreatOverrideTarget(LocalPlayer player, LivingEntity currentTarget) {
        LivingEntity rangedPriorityTarget = findPriorityRangedAttackerTarget(player, currentTarget);
        if (rangedPriorityTarget != null) {
            if (rangedPriorityTarget == currentTarget) {
                resetThreatTracking();
                return null;
            }
            return rangedPriorityTarget;
        }

        double currentDistance = player.distanceTo(currentTarget);
        LivingEntity closestThreat = null;
        double closestThreatDistance = Double.MAX_VALUE;

        List<LivingEntity> nearbyMobs = player.level().getEntitiesOfClass(
                LivingEntity.class,
                player.getBoundingBox().inflate(COMBAT_SEARCH_RADIUS),
                mob -> mob != currentTarget
                        && isHostileMob(mob)
                        && mob.isAlive()
                        && mob.isPickable()
                        && !mob.isSpectator()
                        && player.hasLineOfSight(mob)
        );

        for (LivingEntity mob : nearbyMobs) {
            double distance = player.distanceTo(mob);
            if (distance < closestThreatDistance) {
                closestThreatDistance = distance;
                closestThreat = mob;
            }
        }

        if (closestThreat == null || closestThreatDistance >= currentDistance - 0.08) {
            resetThreatTracking();
            return null;
        }

        int closestId = closestThreat.getId();
        if (closestThreatMobId == closestId) {
            closestThreatTicks++;
        } else {
            closestThreatMobId = closestId;
            closestThreatTicks = 1;
        }

        // High immediate switch chance that converges to certainty after sustained closest-threat pressure.
        int certaintyTicks = 22;
        double progress = Math.clamp((closestThreatTicks - 1.0) / certaintyTicks, 0.0, 1.0);
        double switchChance = 0.74 + (0.26 * progress);
        if (closestThreatTicks >= certaintyTicks || random.nextDouble() < switchChance) {
            return closestThreat;
        }

        return null;
    }

    private static void resetThreatTracking() {
        closestThreatMobId = -1;
        closestThreatTicks = 0;
    }

    private static void registerTargetSwitch(LocalPlayer player, LivingEntity previousTarget, LivingEntity newTarget) {
        if (previousTarget == null || newTarget == null) {
            targetSwitchSlowTicks = 0;
            targetSwitchYawDelta = 0;
            return;
        }

        Vec3 eyePosition = player.getEyePosition();
        Vec3 previousPoint = previousTarget.position().add(0.0, previousTarget.getBbHeight() * 0.65, 0.0);
        Vec3 newPoint = newTarget.position().add(0.0, newTarget.getBbHeight() * 0.65, 0.0);
        Vec3 toPrevious = previousPoint.subtract(eyePosition);
        Vec3 toNew = newPoint.subtract(eyePosition);

        double yawDelta = Math.abs(horizontalYawErrorFromView(toPrevious, toNew));
        double targetSpacing = previousTarget.position().distanceTo(newTarget.position());

        targetSwitchYawDelta = yawDelta;

        boolean isFastHandoff = yawDelta <= TARGET_SWITCH_FAST_YAW_THRESHOLD
                && targetSpacing <= TARGET_SWITCH_FAST_SPACING_THRESHOLD;
        if (isFastHandoff) {
            targetSwitchSlowTicks = 0;
            return;
        }

        if (yawDelta >= TARGET_SWITCH_SLOW_YAW_THRESHOLD
                || targetSpacing >= TARGET_SWITCH_SLOW_SPACING_THRESHOLD) {
            double yawSeverity = Math.clamp((yawDelta - 24.0) / 56.0, 0.0, 1.0);
            double spacingSeverity = Math.clamp((targetSpacing - 3.0) / 8.0, 0.0, 1.0);
            double severity = Math.max(yawSeverity, spacingSeverity);
            targetSwitchSlowTicks = 10 + (int) Math.round(severity * 14.0) + random.nextInt(4);
            return;
        }

        targetSwitchSlowTicks = 5 + random.nextInt(4);
    }

    private static double scoreTarget(LocalPlayer player, LivingEntity target) {
        TargetMetrics metrics = buildTargetMetrics(player, target);
        if (!metrics.inView()) {
            return Double.MAX_VALUE;
        }

        return (Math.abs(metrics.yawError()) * 1.9)
                + (Math.abs(metrics.pitchError()) * 1.25)
                + (metrics.distance() * 1.45);
    }

    private static boolean shouldRaiseShield(LocalPlayer player, LivingEntity currentTarget) {
        if (!hasOffhandShield(player)) {
            return false;
        }
        ArrowShieldThreat arrowThreat = evaluateDangerousArrowThreat(player);
        CreeperThreat creeperThreat = evaluateCreeperThreat(player);
        if (creeperThreat.shouldBlock()) {
            return true;
        }
        if (arrowThreat.hasUnblockableThreat()) {
            return false;
        }
        return arrowThreat.hasBlockableThreat()
                || hasDangerouslyCloseHostileThreat(player, currentTarget);
    }

    private static boolean hasOffhandShield(LocalPlayer player) {
        ItemStack offhand = player.getOffhandItem();
        return !offhand.isEmpty() && offhand.getItem() instanceof ShieldItem;
    }

    private static ArrowShieldThreat evaluateDangerousArrowThreat(LocalPlayer player) {
        Vec3 playerCenter = player.position().add(0.0, player.getBbHeight() * 0.5, 0.0);
        Vec3 viewVector = player.getViewVector(1.0f);
        double viewLenSq = (viewVector.x * viewVector.x) + (viewVector.z * viewVector.z);
        if (viewLenSq < 1.0E-6) {
            return new ArrowShieldThreat(false, false);
        }
        double invViewLen = 1.0 / Math.sqrt(viewLenSq);
        double viewX = viewVector.x * invViewLen;
        double viewZ = viewVector.z * invViewLen;

        List<AbstractArrow> nearbyArrows = player.level().getEntitiesOfClass(
                AbstractArrow.class,
                player.getBoundingBox().inflate(3.8),
                arrow -> arrow.isAlive() && !arrow.isRemoved()
        );

        boolean blockableThreat = false;
        boolean unblockableThreat = false;
        for (AbstractArrow arrow : nearbyArrows) {
            Vec3 toPlayer = playerCenter.subtract(arrow.position());
            double distance = toPlayer.length();
            if (distance > 3.2) {
                continue;
            }
            if (toPlayer.lengthSqr() < 1.0E-6) {
                continue;
            }

            Vec3 toPlayerDir = toPlayer.normalize();
            double closingSpeed = arrow.getDeltaMovement().dot(toPlayerDir);
            if (closingSpeed <= SHIELD_ARROW_MIN_CLOSING_SPEED) {
                continue;
            }

            double timeToImpactTicks = distance / Math.max(closingSpeed, 1.0E-6);
            boolean imminentThreat = distance < 1.7
                    || (distance < 3.0 && timeToImpactTicks <= SHIELD_ARROW_MAX_TIME_TO_IMPACT_TICKS);
            if (!imminentThreat) {
                continue;
            }

            double incomingLenSq = (toPlayerDir.x * toPlayerDir.x) + (toPlayerDir.z * toPlayerDir.z);
            if (incomingLenSq < 1.0E-6) {
                continue;
            }
            double invIncomingLen = 1.0 / Math.sqrt(incomingLenSq);
            double incomingX = toPlayerDir.x * invIncomingLen;
            double incomingZ = toPlayerDir.z * invIncomingLen;
            double facingDotIncoming = (viewX * incomingX) + (viewZ * incomingZ);
            if (facingDotIncoming <= SHIELD_ARROW_BLOCKABLE_FACING_DOT_MAX) {
                blockableThreat = true;
            } else {
                unblockableThreat = true;
            }

            if (unblockableThreat && blockableThreat) {
                break;
            }
        }

        return new ArrowShieldThreat(blockableThreat, unblockableThreat);
    }

    private static ArrowEvasionPlan evaluateArrowEvasionPlan(LocalPlayer player) {
        Vec3 playerCenter = player.position().add(0.0, player.getBbHeight() * 0.5, 0.0);
        List<AbstractArrow> nearbyArrows = player.level().getEntitiesOfClass(
                AbstractArrow.class,
                player.getBoundingBox().inflate(ARROW_EVADE_SEARCH_RADIUS),
                arrow -> arrow.isAlive() && !arrow.isRemoved()
        );

        double bestThreatScore = 0.0;
        double bestYawError = 0.0;
        boolean bestRetreat = false;
        boolean bestUrgent = false;
        for (AbstractArrow arrow : nearbyArrows) {
            Vec3 velocity = arrow.getDeltaMovement();
            if (velocity.lengthSqr() < 0.0049) {
                continue;
            }

            Vec3 toPlayer = playerCenter.subtract(arrow.position());
            double distance = toPlayer.length();
            if (distance > ARROW_EVADE_SEARCH_RADIUS || toPlayer.lengthSqr() < 1.0E-6) {
                continue;
            }

            Vec3 toPlayerDir = toPlayer.normalize();
            double closingSpeed = velocity.dot(toPlayerDir);
            if (closingSpeed <= ARROW_EVADE_MIN_CLOSING_SPEED) {
                continue;
            }

            double timeToImpactTicks = distance / Math.max(closingSpeed, 1.0E-6);
            if (timeToImpactTicks > ARROW_EVADE_MAX_TIME_TO_IMPACT_TICKS && distance > 3.8) {
                continue;
            }

            double distanceUrgency = Math.clamp((ARROW_EVADE_SEARCH_RADIUS - distance) / ARROW_EVADE_SEARCH_RADIUS, 0.0, 1.0);
            double impactUrgency = Math.clamp((8.0 - timeToImpactTicks) / 8.0, 0.0, 1.0);
            double speedUrgency = Math.clamp(closingSpeed / 0.9, 0.0, 1.0);
            double threatScore = (distanceUrgency * 0.95) + (impactUrgency * 1.25) + (speedUrgency * 0.55);
            if (threatScore <= bestThreatScore) {
                continue;
            }

            Vec3 fromPlayerToArrow = arrow.position().subtract(playerCenter);
            bestYawError = horizontalYawErrorFromView(player.getViewVector(1.0f), fromPlayerToArrow);
            bestThreatScore = threatScore;
            bestRetreat = distance <= 2.45 || timeToImpactTicks <= 5.0;
            bestUrgent = distance <= 1.95 || timeToImpactTicks <= 3.4;
        }

        if (bestThreatScore <= 0.0) {
            if (arrowEvadeTicks > 0 && arrowEvadeStrafeDirection != 0) {
                arrowEvadeTicks--;
                boolean urgent = arrowEvadeTicks > 4 && arrowEvadeRetreat;
                return new ArrowEvasionPlan(true, arrowEvadeStrafeDirection, arrowEvadeRetreat, urgent);
            }
            arrowEvadeStrafeDirection = 0;
            arrowEvadeRetreat = false;
            return new ArrowEvasionPlan(false, 0, false, false);
        }

        int strafeDirection;
        if (Math.abs(bestYawError) <= 5.5) {
            boolean keepCurrent = arrowEvadeStrafeDirection != 0
                    && arrowEvadeTicks > 0
                    && random.nextDouble() < 0.72;
            strafeDirection = keepCurrent ? arrowEvadeStrafeDirection : (random.nextBoolean() ? 1 : -1);
        } else {
            strafeDirection = bestYawError > 0.0 ? -1 : 1;
            if (arrowEvadeStrafeDirection != 0 && arrowEvadeTicks > 0 && random.nextDouble() < 0.56) {
                strafeDirection = arrowEvadeStrafeDirection;
            }
        }

        arrowEvadeStrafeDirection = strafeDirection;
        arrowEvadeRetreat = bestRetreat;
        arrowEvadeTicks = bestUrgent ? (7 + random.nextInt(5)) : (4 + random.nextInt(4));
        return new ArrowEvasionPlan(true, strafeDirection, bestRetreat, bestUrgent);
    }

    private static int countVisibleSkeletonPressure(LocalPlayer player) {
        List<AbstractSkeleton> nearbySkeletons = player.level().getEntitiesOfClass(
                AbstractSkeleton.class,
                player.getBoundingBox().inflate(SKELETON_JUKE_SEARCH_RADIUS),
                skeleton -> skeleton.isAlive()
                        && skeleton.isPickable()
                        && !skeleton.isSpectator()
                        && player.hasLineOfSight(skeleton)
        );

        int pressureCount = 0;
        Vec3 playerCenter = player.position().add(0.0, player.getBbHeight() * 0.5, 0.0);
        for (AbstractSkeleton skeleton : nearbySkeletons) {
            Vec3 toPlayer = playerCenter.subtract(skeleton.getEyePosition());
            if (toPlayer.lengthSqr() < 1.0E-6) {
                continue;
            }
            Vec3 toPlayerDir = toPlayer.normalize();
            double aimDot = skeleton.getViewVector(1.0f).dot(toPlayerDir);
            boolean focusingPlayer = skeleton.getTarget() == player;
            double distance = player.distanceTo(skeleton);
            if (distance <= 11.5 && (focusingPlayer || aimDot > 0.68)) {
                pressureCount++;
            }
        }
        return pressureCount;
    }

    private static int findMeleeBypassDirection(LocalPlayer player, LivingEntity target) {
        if (!(target instanceof AbstractSkeleton)) {
            return 0;
        }

        Vec3 toTarget = target.position().subtract(player.position());
        double targetHorizontalDist = Math.hypot(toTarget.x, toTarget.z);
        if (targetHorizontalDist < 1.0E-6) {
            return 0;
        }
        double tx = toTarget.x / targetHorizontalDist;
        double tz = toTarget.z / targetHorizontalDist;

        List<LivingEntity> nearbyMeleeHostiles = player.level().getEntitiesOfClass(
                LivingEntity.class,
                player.getBoundingBox().inflate(4.2),
                mob -> isHostileMob(mob)
                        && !(mob instanceof AbstractSkeleton)
                        && mob.isAlive()
                        && mob.isPickable()
                        && !mob.isSpectator()
        );

        LivingEntity closestBlocker = null;
        double closestBlockerDistance = Double.MAX_VALUE;
        for (LivingEntity hostile : nearbyMeleeHostiles) {
            Vec3 toHostile = hostile.position().subtract(player.position());
            double hostileHorizontalDist = Math.hypot(toHostile.x, toHostile.z);
            if (hostileHorizontalDist < 1.0E-6 || hostileHorizontalDist > 3.6) {
                continue;
            }
            if (hostileHorizontalDist >= targetHorizontalDist - 0.12) {
                continue;
            }

            double hx = toHostile.x / hostileHorizontalDist;
            double hz = toHostile.z / hostileHorizontalDist;
            double aheadDot = (hx * tx) + (hz * tz);
            if (aheadDot < 0.52) {
                continue;
            }

            double lateralDist = Math.abs((tx * hz) - (tz * hx)) * hostileHorizontalDist;
            double laneWidth = 0.65 + (hostile.getBbWidth() * 0.55);
            if (lateralDist > laneWidth) {
                continue;
            }

            if (hostileHorizontalDist < closestBlockerDistance) {
                closestBlockerDistance = hostileHorizontalDist;
                closestBlocker = hostile;
            }
        }

        if (closestBlocker == null) {
            return 0;
        }

        Vec3 toBlocker = closestBlocker.position().subtract(player.position());
        double bx = toBlocker.x;
        double bz = toBlocker.z;
        double cross = (tx * bz) - (tz * bx);
        return cross >= 0.0 ? 1 : -1;
    }

    private static Creeper resolveNoShieldCriticalCreeperThreat(LocalPlayer player) {
        if (hasOffhandShield(player)) {
            noShieldCreeperFleeTicks = 0;
            noShieldCreeperFleeCommitTicks = 0;
            noShieldCreeperFleeTargetId = -1;
            return null;
        }

        Creeper criticalCreeper = findNoShieldEmergencyCreeperThreat(player);
        if (criticalCreeper != null) {
            noShieldCreeperFleeTargetId = criticalCreeper.getId();
            noShieldCreeperFleeTicks = NO_SHIELD_CREEPER_FLEE_MIN_TICKS + random.nextInt(
                    (NO_SHIELD_CREEPER_FLEE_MAX_TICKS - NO_SHIELD_CREEPER_FLEE_MIN_TICKS) + 1
            );
            noShieldCreeperFleeCommitTicks = Math.max(
                    noShieldCreeperFleeCommitTicks,
                    NO_SHIELD_CREEPER_FLEE_COMMIT_MIN_TICKS + random.nextInt(
                            (NO_SHIELD_CREEPER_FLEE_COMMIT_MAX_TICKS - NO_SHIELD_CREEPER_FLEE_COMMIT_MIN_TICKS) + 1
                    )
            );
            return criticalCreeper;
        }

        if (noShieldCreeperFleeTargetId < 0 || (noShieldCreeperFleeTicks <= 0 && noShieldCreeperFleeCommitTicks <= 0)) {
            noShieldCreeperFleeTicks = 0;
            noShieldCreeperFleeCommitTicks = 0;
            noShieldCreeperFleeTargetId = -1;
            return null;
        }

        noShieldCreeperFleeTicks = Math.max(0, noShieldCreeperFleeTicks - 1);
        noShieldCreeperFleeCommitTicks = Math.max(0, noShieldCreeperFleeCommitTicks - 1);

        var stickyEntity = player.level().getEntity(noShieldCreeperFleeTargetId);
        if (!(stickyEntity instanceof Creeper sticky) || !sticky.isAlive() || sticky.isSpectator()) {
            if (noShieldCreeperFleeTicks <= 0 && noShieldCreeperFleeCommitTicks <= 0) {
                noShieldCreeperFleeTargetId = -1;
            }
            return null;
        }

        double distance = player.distanceTo(sticky);
        boolean primed = sticky.isIgnited()
                || sticky.getSwellDir() > 0
                || sticky.getSwelling(1.0f) > 0.04f
                || distance <= 2.85;
        boolean stickyCommitActive = noShieldCreeperFleeCommitTicks > 0;
        boolean stickyFleeCondition = primed
                || stickyCommitActive
                || distance <= 3.35
                || noShieldCreeperFleeTicks > 0;
        if (stickyFleeCondition && distance <= (stickyCommitActive ? 9.4 : 7.2)) {
            return sticky;
        }

        if (noShieldCreeperFleeTicks <= 0 && noShieldCreeperFleeCommitTicks <= 0) {
            noShieldCreeperFleeTargetId = -1;
        }
        return null;
    }

    private static Creeper findNoShieldEmergencyCreeperThreat(LocalPlayer player) {
        List<Creeper> nearbyCreepers = player.level().getEntitiesOfClass(
                Creeper.class,
                player.getBoundingBox().inflate(5.4),
                creeper -> creeper.isAlive() && !creeper.isSpectator()
        );

        Creeper bestCreeper = null;
        double bestRisk = Double.NEGATIVE_INFINITY;
        for (Creeper creeper : nearbyCreepers) {
            double distance = player.distanceTo(creeper);
            if (distance > 4.8) {
                continue;
            }
            if (!player.hasLineOfSight(creeper)) {
                continue;
            }

            boolean ignited = creeper.isIgnited();
            int swellDir = creeper.getSwellDir();
            float swelling = creeper.getSwelling(1.0f);
            boolean primed = ignited || swellDir > 0 || swelling > 0.06f;
            if (!primed) {
                continue;
            }

            boolean mustFlee = (swelling >= 0.22f && distance <= 4.2)
                    || (swelling >= 0.14f && distance <= 3.5)
                    || distance <= 2.95;
            if (!mustFlee) {
                continue;
            }

            double riskScore = (swelling * 2.5)
                    + ((4.9 - distance) * 0.85)
                    + (ignited ? 0.65 : 0.0)
                    + (swellDir > 0 ? 0.32 : 0.0);
            if (riskScore > bestRisk) {
                bestRisk = riskScore;
                bestCreeper = creeper;
            }
        }

        return bestCreeper;
    }

    private static boolean updateCreeperRetreatBurstState(LocalPlayer player,
                                                          Creeper retreatCreeper,
                                                          boolean retreatActive,
                                                          boolean hasShield) {
        if (!retreatActive || retreatCreeper == null || !retreatCreeper.isAlive() || retreatCreeper.isSpectator()) {
            if (creeperRetreatBurstTicks > 0 && creeperRetreatTargetId >= 0) {
                var stickyEntity = player.level().getEntity(creeperRetreatTargetId);
                if (stickyEntity instanceof Creeper stickyCreeper
                        && stickyCreeper.isAlive()
                        && !stickyCreeper.isSpectator()) {
                    double stickyDistance = player.distanceTo(stickyCreeper);
                    float stickySwelling = stickyCreeper.getSwelling(1.0f);
                    boolean stickyPrimed = stickyCreeper.isIgnited()
                            || stickyCreeper.getSwellDir() > 0
                            || stickySwelling > 0.06f;
                    if (stickyDistance <= 7.0 && (stickyPrimed || stickyDistance <= 3.7)) {
                        creeperRetreatBurstTicks = Math.max(0, creeperRetreatBurstTicks - 1);
                        creeperRetreatLastDistance = stickyDistance;
                        return true;
                    }
                }
            }
            resetCreeperRetreatBurstState();
            return false;
        }

        int creeperId = retreatCreeper.getId();
        double distance = player.distanceTo(retreatCreeper);
        if (creeperRetreatTargetId != creeperId) {
            creeperRetreatTargetId = creeperId;
            creeperRetreatObserveTicks = 0;
            creeperRetreatStuckTicks = 0;
            creeperRetreatLastDistance = distance;
        }

        creeperRetreatObserveTicks++;
        if (creeperRetreatLastDistance >= 0.0) {
            double distanceDelta = distance - creeperRetreatLastDistance;
            boolean openingGap = distanceDelta >= 0.055;
            boolean closingGap = distanceDelta <= -0.02;
            if (openingGap) {
                creeperRetreatStuckTicks = Math.max(0, creeperRetreatStuckTicks - 2);
            } else {
                creeperRetreatStuckTicks++;
            }
            if (closingGap) {
                creeperRetreatStuckTicks++;
            }
            if (distance <= 3.25) {
                creeperRetreatStuckTicks++;
            }
        }
        creeperRetreatLastDistance = distance;

        boolean immediateBlastRisk = hasShield && isImmediateShieldCreeperBlastRisk(player, retreatCreeper);
        boolean shouldStartBurst = creeperRetreatBurstTicks <= 0
                && !immediateBlastRisk
                && creeperRetreatObserveTicks >= CREEPER_RETREAT_OBSERVE_MIN_TICKS
                && (creeperRetreatStuckTicks >= CREEPER_RETREAT_STUCK_TRIGGER_TICKS
                || (distance <= 3.5 && creeperRetreatObserveTicks >= CREEPER_RETREAT_OBSERVE_MIN_TICKS + 6));
        if (shouldStartBurst) {
            creeperRetreatBurstTicks = CREEPER_RETREAT_BURST_MIN_TICKS + random.nextInt(
                    (CREEPER_RETREAT_BURST_MAX_TICKS - CREEPER_RETREAT_BURST_MIN_TICKS) + 1
            );
        }

        if (creeperRetreatBurstTicks > 0) {
            creeperRetreatBurstTicks--;
            return true;
        }
        return false;
    }

    private static boolean isImmediateShieldCreeperBlastRisk(LocalPlayer player, Creeper creeper) {
        if (creeper == null || !creeper.isAlive() || creeper.isSpectator()) {
            return false;
        }
        double distance = player.distanceTo(creeper);
        float swelling = creeper.getSwelling(1.0f);
        boolean primed = creeper.isIgnited() || creeper.getSwellDir() > 0 || swelling > 0.08f;
        if (!primed) {
            return false;
        }
        return (swelling >= 0.58f && distance <= 3.9)
                || (swelling >= 0.45f && distance <= 3.15)
                || distance <= 2.5;
    }

    private static void applyEmergencyCreeperFleeMovement(LocalPlayer player, Creeper creeper) {
        applyEmergencyCreeperFleeMovement(player, creeper, false);
    }

    private static void applyEmergencyCreeperFleeMovement(LocalPlayer player, Creeper creeper, boolean forceHardFlee) {
        if (creeper == null) {
            return;
        }

        boolean hardFleeRequired = forceHardFlee || shouldHardFleeFromNoShieldCreeper(player, creeper);
        Vec3 away = player.position().subtract(creeper.position());
        double awayLenSq = (away.x * away.x) + (away.z * away.z);
        if (awayLenSq < 1.0E-6) {
            combatMoveForward = false;
            combatMoveBackward = true;
            combatMoveLeft = false;
            combatMoveRight = false;
            return;
        }

        double invAwayLen = 1.0 / Math.sqrt(awayLenSq);
        double awayX = away.x * invAwayLen;
        double awayZ = away.z * invAwayLen;

        Vec3 view = player.getViewVector(1.0f);
        double viewLenSq = (view.x * view.x) + (view.z * view.z);
        if (viewLenSq < 1.0E-6) {
            combatMoveForward = false;
            combatMoveBackward = true;
            combatMoveLeft = false;
            combatMoveRight = false;
            return;
        }

        double invViewLen = 1.0 / Math.sqrt(viewLenSq);
        double forwardX = view.x * invViewLen;
        double forwardZ = view.z * invViewLen;
        double rightX = -forwardZ;
        double rightZ = forwardX;

        if (!hardFleeRequired) {
            // Prefer stable backpedal while keeping view on creeper until situation is truly critical.
            combatMoveForward = false;
            combatMoveBackward = true;
            combatMoveLeft = false;
            combatMoveRight = false;
            return;
        }

        double forwardDot = (awayX * forwardX) + (awayZ * forwardZ);
        double rightDot = (awayX * rightX) + (awayZ * rightZ);
        if (forwardDot < 0.20) {
            // Still pivoting camera: keep creating gap with backpedal to avoid stepping into blast.
            combatMoveForward = false;
            combatMoveBackward = true;
        } else {
            combatMoveForward = true;
            combatMoveBackward = false;
        }
        combatMoveLeft = rightDot < -0.24;
        combatMoveRight = rightDot > 0.24;
        if (!combatMoveLeft && !combatMoveRight && Math.abs(rightDot) > 0.12) {
            combatMoveLeft = rightDot < 0.0;
            combatMoveRight = rightDot > 0.0;
        }
    }

    private static boolean shouldHardFleeFromNoShieldCreeper(LocalPlayer player, Creeper creeper) {
        double distance = player.distanceTo(creeper);
        float swelling = creeper.getSwelling(1.0f);
        boolean primed = creeper.isIgnited() || creeper.getSwellDir() > 0 || swelling > 0.06f;
        if (!primed) {
            return false;
        }

        Vec3 toCreeper = creeper.position().subtract(player.position());
        double predictedDistance = distance;
        if (toCreeper.lengthSqr() > 1.0E-6) {
            Vec3 toCreeperDir = toCreeper.normalize();
            double relativeAlongLine = creeper.getDeltaMovement()
                    .subtract(player.getDeltaMovement())
                    .dot(toCreeperDir);
            predictedDistance = distance + (relativeAlongLine * 4.8);
        }

        boolean pointBlank = distance <= 2.30;
        boolean criticalFuseClose = swelling >= 0.56f && distance <= 4.0;
        boolean highFuseClose = swelling >= 0.42f && distance <= 3.25;
        boolean mediumFuseVeryClose = swelling >= 0.30f && distance <= 2.80;
        boolean rapidlyCollapsingGap = predictedDistance <= 2.45 && distance <= 3.7 && swelling >= 0.20f;
        return pointBlank || criticalFuseClose || highFuseClose || mediumFuseVeryClose || rapidlyCollapsingGap;
    }

    private static Creeper findCriticalCreeperThreat(LocalPlayer player) {
        List<Creeper> nearbyCreepers = player.level().getEntitiesOfClass(
                Creeper.class,
                player.getBoundingBox().inflate(5.2),
                creeper -> creeper.isAlive() && !creeper.isSpectator()
        );

        Creeper bestCreeper = null;
        double bestRisk = Double.NEGATIVE_INFINITY;
        for (Creeper creeper : nearbyCreepers) {
            double distance = player.distanceTo(creeper);
            if (distance > 4.3) {
                continue;
            }
            if (!player.hasLineOfSight(creeper)) {
                continue;
            }

            boolean ignited = creeper.isIgnited();
            int swellDir = creeper.getSwellDir();
            float swelling = creeper.getSwelling(1.0f);
            boolean primed = ignited || swellDir > 0 || swelling > 0.10f;
            if (!primed) {
                continue;
            }

            boolean criticalBlastRisk = (swelling >= 0.42f && distance <= 3.9)
                    || (swelling >= 0.30f && distance <= 3.2)
                    || distance <= 2.65;
            if (!criticalBlastRisk) {
                continue;
            }

            double riskScore = (swelling * 2.25)
                    + ((4.2 - distance) * 0.72)
                    + (ignited ? 0.55 : 0.0)
                    + (swellDir > 0 ? 0.28 : 0.0);
            if (riskScore > bestRisk) {
                bestRisk = riskScore;
                bestCreeper = creeper;
            }
        }

        return bestCreeper;
    }

    private static SkeletonJukePlan evaluateSkeletonJukePlan(LocalPlayer player) {
        Vec3 playerCenter = player.position().add(0.0, player.getBbHeight() * 0.5, 0.0);
        List<AbstractSkeleton> nearbySkeletons = player.level().getEntitiesOfClass(
                AbstractSkeleton.class,
                player.getBoundingBox().inflate(SKELETON_JUKE_SEARCH_RADIUS),
                skeleton -> skeleton.isAlive()
                        && skeleton.isPickable()
                        && !skeleton.isSpectator()
                        && player.hasLineOfSight(skeleton)
        );

        double bestThreatScore = 0.0;
        double bestYawError = 0.0;
        boolean bestRetreat = false;
        boolean bestUrgent = false;
        for (AbstractSkeleton skeleton : nearbySkeletons) {
            double distance = player.distanceTo(skeleton);
            if (distance > SKELETON_JUKE_SEARCH_RADIUS) {
                continue;
            }

            Vec3 skeletonEyes = skeleton.getEyePosition();
            Vec3 toPlayer = playerCenter.subtract(skeletonEyes);
            if (toPlayer.lengthSqr() < 1.0E-6) {
                continue;
            }
            Vec3 toPlayerDir = toPlayer.normalize();
            Vec3 skeletonView = skeleton.getViewVector(1.0f);
            double aimDot = skeletonView.dot(toPlayerDir);
            double aimThreat = Math.clamp((aimDot - 0.56) / 0.40, 0.0, 1.0);
            boolean focusingPlayer = skeleton.getTarget() == player;
            double focusThreat = focusingPlayer ? 1.0 : 0.0;
            double distanceThreat = Math.clamp((SKELETON_JUKE_SEARCH_RADIUS - distance) / SKELETON_JUKE_SEARCH_RADIUS, 0.0, 1.0);

            double threatScore = (distanceThreat * 0.86) + (aimThreat * 1.15) + (focusThreat * 0.92);
            if (threatScore <= bestThreatScore) {
                continue;
            }

            bestThreatScore = threatScore;
            bestYawError = horizontalYawErrorFromView(
                    player.getViewVector(1.0f),
                    skeleton.position().subtract(player.position())
            );
            bestRetreat = distance <= 4.1 && (focusingPlayer || aimThreat > 0.76);
            bestUrgent = distance <= 3.2 && (focusingPlayer || aimThreat > 0.87);
        }

        if (bestThreatScore < 0.82) {
            if (skeletonJukeTicks > 0 && skeletonJukeDirection != 0) {
                skeletonJukeTicks--;
                skeletonJukeFlipCooldown = Math.max(0, skeletonJukeFlipCooldown - 1);
                return new SkeletonJukePlan(true, skeletonJukeDirection, skeletonJukeRetreat, false);
            }
            skeletonJukeDirection = 0;
            skeletonJukeRetreat = false;
            return new SkeletonJukePlan(false, 0, false, false);
        }

        skeletonJukeFlipCooldown = Math.max(0, skeletonJukeFlipCooldown - 1);
        if (skeletonJukeDirection != 0 && skeletonJukeTicks > 0) {
            skeletonJukeTicks--;
            boolean shouldFlipDirection = skeletonJukeFlipCooldown <= 0
                    && (bestUrgent || random.nextDouble() < 0.26);
            if (shouldFlipDirection) {
                skeletonJukeDirection *= -1;
                skeletonJukeFlipCooldown = 4 + random.nextInt(4);
                skeletonJukeTicks = 4 + random.nextInt(5);
            }
            skeletonJukeRetreat = bestRetreat;
            return new SkeletonJukePlan(true, skeletonJukeDirection, bestRetreat, bestUrgent);
        }

        int preferredDirection;
        if (Math.abs(bestYawError) < 6.0) {
            preferredDirection = random.nextBoolean() ? 1 : -1;
        } else {
            preferredDirection = bestYawError > 0.0 ? -1 : 1;
            if (random.nextDouble() < 0.35) {
                preferredDirection *= -1;
            }
        }

        skeletonJukeDirection = preferredDirection;
        skeletonJukeRetreat = bestRetreat;
        skeletonJukeTicks = bestUrgent ? (7 + random.nextInt(5)) : (5 + random.nextInt(5));
        skeletonJukeFlipCooldown = 3 + random.nextInt(4);
        return new SkeletonJukePlan(true, skeletonJukeDirection, bestRetreat, bestUrgent);
    }

    private static CreeperThreat evaluateCreeperThreat(LocalPlayer player) {
        List<Creeper> nearbyCreepers = player.level().getEntitiesOfClass(
                Creeper.class,
                player.getBoundingBox().inflate(5.0),
                creeper -> creeper.isAlive() && !creeper.isSpectator()
        );

        boolean shouldBlock = false;
        boolean shouldRetreat = false;
        for (Creeper creeper : nearbyCreepers) {
            double distance = player.distanceTo(creeper);
            if (distance > 4.4) {
                continue;
            }
            if (!player.hasLineOfSight(creeper)) {
                continue;
            }

            boolean ignited = creeper.isIgnited();
            int swellDir = creeper.getSwellDir();
            float swelling = creeper.getSwelling(1.0f);
            boolean primed = ignited || swellDir > 0 || swelling > 0.08f;
            if (!primed) {
                continue;
            }

            boolean earlyBlock = (swelling >= 0.34f && distance <= 3.9)
                    || (swelling >= 0.22f && distance <= 3.2)
                    || distance <= 2.75;
            boolean urgentRetreat = (swelling >= 0.46f && distance <= 3.8)
                    || (swelling >= 0.30f && distance <= 3.1)
                    || distance <= 2.55;

            if (earlyBlock) {
                shouldBlock = true;
            }
            if (urgentRetreat) {
                shouldRetreat = true;
            }
            if (shouldBlock && shouldRetreat) {
                break;
            }
        }

        return new CreeperThreat(shouldBlock, shouldRetreat);
    }

    private static HostileSpacingMetrics evaluateHostileSpacing(LocalPlayer player, double closeThreshold) {
        double scanRadius = Math.clamp(closeThreshold + 2.9, 4.6, 8.2);
        List<LivingEntity> nearbyHostiles = player.level().getEntitiesOfClass(
                LivingEntity.class,
                player.getBoundingBox().inflate(scanRadius),
                mob -> isHostileMob(mob)
                        && mob.isAlive()
                        && mob.isPickable()
                        && !mob.isSpectator()
        );

        double nearestDistance = Double.MAX_VALUE;
        double nearestPredictedDistance = Double.MAX_VALUE;
        boolean anyAdvancingThreat = false;
        int closeThreatCount = 0;
        for (LivingEntity hostile : nearbyHostiles) {
            double distance = player.distanceTo(hostile);
            if (distance > COMBAT_MAX_TRACK_RANGE) {
                continue;
            }
            if (!player.hasLineOfSight(hostile) && distance > 2.45) {
                continue;
            }

            Vec3 toHostile = hostile.position().subtract(player.position());
            if (toHostile.lengthSqr() < 1.0E-6) {
                continue;
            }

            Vec3 toHostileDir = toHostile.normalize();
            double relativeAlongLine = hostile.getDeltaMovement()
                    .subtract(player.getDeltaMovement())
                    .dot(toHostileDir);
            double predictedDistance = distance + (relativeAlongLine * 4.2);

            nearestDistance = Math.min(nearestDistance, distance);
            nearestPredictedDistance = Math.min(nearestPredictedDistance, predictedDistance);

            if (distance <= closeThreshold) {
                closeThreatCount++;
            }

            boolean hostileAdvancing = relativeAlongLine < -0.018 || predictedDistance < distance - 0.05;
            if (hostileAdvancing && distance <= closeThreshold + 0.9) {
                anyAdvancingThreat = true;
            }
        }

        return new HostileSpacingMetrics(nearestDistance, nearestPredictedDistance, anyAdvancingThreat, closeThreatCount);
    }

    private static boolean hasDangerouslyCloseHostileThreat(LocalPlayer player, LivingEntity currentTarget) {
        double currentTargetDistance = currentTarget != null ? player.distanceTo(currentTarget) : Double.MAX_VALUE;
        List<Mob> nearbyHostiles = player.level().getEntitiesOfClass(
                Mob.class,
                player.getBoundingBox().inflate(3.3),
                mob -> isHostileMob(mob)
                        && mob.isAlive()
                        && mob.isPickable()
                        && !mob.isSpectator()
        );

        for (Mob hostile : nearbyHostiles) {
            double distance = player.distanceTo(hostile);
            if (distance <= 2.0) {
                return true;
            }
            if (distance <= 2.45 && distance + 0.18 < currentTargetDistance) {
                return true;
            }

            Vec3 toPlayer = player.position().subtract(hostile.position());
            if (toPlayer.lengthSqr() < 1.0E-6) {
                continue;
            }
            double approach = hostile.getDeltaMovement().dot(toPlayer.normalize());
            if (distance <= 2.8 && approach > 0.07) {
                return true;
            }
        }
        return false;
    }

    private static TargetMetrics buildTargetMetrics(LocalPlayer player, LivingEntity target) {
        Vec3 eyePosition = player.getEyePosition();
        Vec3 targetPoint = target.position().add(0.0, target.getBbHeight() * 0.65, 0.0);
        LookAngles lookAngles = calculateLookAngles(eyePosition, targetPoint);
        double yawError = horizontalYawErrorFromView(
                player.getViewVector(1.0f),
                targetPoint.subtract(eyePosition)
        );
        double pitchError = lookAngles.pitch() - player.getXRot();
        boolean inView = Math.abs(yawError) <= TARGET_VIEW_YAW_LIMIT
                && Math.abs(pitchError) <= TARGET_VIEW_PITCH_LIMIT
                && player.hasLineOfSight(target);
        return new TargetMetrics(player.distanceTo(target), yawError, pitchError, inView);
    }

    private static boolean isValidCombatTarget(LocalPlayer player, LivingEntity target) {
        return target != null
                && isHostileMob(target)
                && target.isAlive()
                && target.isPickable()
                && !target.isSpectator()
                && player.distanceTo(target) <= COMBAT_MAX_TRACK_RANGE;
    }

    private static boolean isHostileMob(LivingEntity mob) {
        return mob instanceof Enemy;
    }

    private static WeaponChoice chooseCombatWeapon(LocalPlayer player, LivingEntity target) {
        int swordSlot = -1;
        int axeSlot = -1;
        int bowSlot = -1;

        for (int i = 0; i < Inventory.getSelectionSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.is(ItemTags.SWORDS) && swordSlot == -1) {
                swordSlot = i;
            } else if (stack.is(ItemTags.AXES) && axeSlot == -1) {
                axeSlot = i;
            } else if (stack.getItem() instanceof BowItem && bowSlot == -1 && hasUsableBowAmmo(player, stack)) {
                bowSlot = i;
            }
        }

        if (swordSlot == -1 && axeSlot == -1 && bowSlot == -1) {
            int currentSlot = player.getInventory().getSelectedSlot();
            ItemStack currentStack = player.getInventory().getItem(currentSlot);
            if (!currentStack.isEmpty()) {
                return new WeaponChoice(currentSlot, WeaponType.NONE);
            }

            int fallbackSlot = findFirstNonEmptyHotbarSlot(player);
            if (fallbackSlot >= 0) {
                return new WeaponChoice(fallbackSlot, WeaponType.NONE);
            }

            return new WeaponChoice(currentSlot, WeaponType.NONE);
        }
        if (swordSlot == -1 && axeSlot == -1 && bowSlot != -1) {
            return new WeaponChoice(bowSlot, WeaponType.BOW);
        }

        double distance = player.distanceTo(target);
        boolean hasMeleeOption = swordSlot != -1 || axeSlot != -1;
        boolean targetIsSkeleton = target instanceof AbstractSkeleton;
        if (bowSlot != -1) {
            boolean currentlyUsingBowSlot = player.getInventory().getSelectedSlot() == bowSlot;
            boolean skeletonTooCloseForBow = hasMeleeOption
                    && targetIsSkeleton
                    && distance <= BOW_SKELETON_MELEE_SWITCH_DISTANCE;
            boolean bowSwitchRollPassed = currentlyUsingBowSlot || random.nextDouble() < 0.24;
            if (currentlyUsingBowSlot) {
                double keepBowDistanceFloor = targetIsSkeleton && hasMeleeOption
                        ? (BOW_SKELETON_MELEE_SWITCH_DISTANCE - 0.95)
                        : Math.max(2.9, BOW_ENGAGE_HYSTERESIS_DISTANCE - 0.35);
                if (distance >= keepBowDistanceFloor) {
                    return new WeaponChoice(bowSlot, WeaponType.BOW);
                }
            }

            boolean hasLineOfSight = player.hasLineOfSight(target);
            double bowMinDistance = targetIsSkeleton ? BOW_SKELETON_ONLY_AT_DISTANCE : BOW_ENGAGE_MIN_DISTANCE;
            double bowHysteresisDistance = targetIsSkeleton
                    ? Math.max(BOW_SKELETON_MELEE_SWITCH_DISTANCE, BOW_ENGAGE_HYSTERESIS_DISTANCE + 1.4)
                    : BOW_ENGAGE_HYSTERESIS_DISTANCE;
            boolean bowRangePreference = hasLineOfSight
                    && (distance >= bowMinDistance + (hasMeleeOption ? 1.2 : 0.0)
                    || (currentlyUsingBowSlot && distance >= bowHysteresisDistance));
            boolean looseBowPreference = !targetIsSkeleton
                    && hasLineOfSight
                    && distance >= Math.max(7.0, BOW_ENGAGE_MIN_DISTANCE + 0.7);
            boolean rangedSkeletonPressure = targetIsSkeleton
                    && hasLineOfSight
                    && distance >= Math.max(7.2, BOW_SKELETON_MELEE_SWITCH_DISTANCE + 0.3);
            boolean keepBowMomentum = currentlyUsingBowSlot
                    && hasLineOfSight
                    && distance >= Math.max(3.3, BOW_ENGAGE_HYSTERESIS_DISTANCE);
            if (bowRangePreference) {
                if (!skeletonTooCloseForBow && bowSwitchRollPassed) {
                    return new WeaponChoice(bowSlot, WeaponType.BOW);
                }
            }
            if (rangedSkeletonPressure
                    && !hasDangerouslyCloseHostileThreat(player, target)
                    && bowSwitchRollPassed) {
                return new WeaponChoice(bowSlot, WeaponType.BOW);
            }
            if (keepBowMomentum && !skeletonTooCloseForBow) {
                return new WeaponChoice(bowSlot, WeaponType.BOW);
            }
            if (looseBowPreference
                    && !hasDangerouslyCloseHostileThreat(player, target)
                    && bowSwitchRollPassed) {
                return new WeaponChoice(bowSlot, WeaponType.BOW);
            }
        }

        if (swordSlot != -1 && axeSlot == -1) {
            return new WeaponChoice(swordSlot, WeaponType.SWORD);
        }
        if (axeSlot != -1 && swordSlot == -1) {
            return new WeaponChoice(axeSlot, WeaponType.AXE);
        }

        double cooldown = player.getAttackStrengthScale(0.0f);
        boolean preferAxeNow = distance >= 2.6 && cooldown > 0.9 && random.nextDouble() < 0.42;
        if (preferAxeNow) {
            return new WeaponChoice(axeSlot, WeaponType.AXE);
        }
        return new WeaponChoice(swordSlot, WeaponType.SWORD);
    }

    private static boolean hasUsableBowAmmo(LocalPlayer player, ItemStack bowStack) {
        return !player.getProjectile(bowStack).isEmpty();
    }

    private static boolean hasMeleeWeaponInHotbar(LocalPlayer player) {
        for (int i = 0; i < Inventory.getSelectionSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES)) {
                return true;
            }
        }
        return false;
    }

    private static int findFirstNonEmptyHotbarSlot(LocalPlayer player) {
        for (int i = 0; i < Inventory.getSelectionSize(); i++) {
            if (!player.getInventory().getItem(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    private static LookAngles calculateLookAngles(Vec3 from, Vec3 to) {
        Vec3 delta = to.subtract(from);
        double horizontalDistance = Math.sqrt((delta.x * delta.x) + (delta.z * delta.z));
        double pitch = -Math.toDegrees(Math.atan2(delta.y, horizontalDistance));
        return new LookAngles(pitch);
    }

    private static double horizontalYawErrorFromView(Vec3 viewVector, Vec3 toTargetVector) {
        double vx = viewVector.x;
        double vz = viewVector.z;
        double tx = toTargetVector.x;
        double tz = toTargetVector.z;

        double viewLenSq = (vx * vx) + (vz * vz);
        double targetLenSq = (tx * tx) + (tz * tz);
        if (viewLenSq < 1.0E-6 || targetLenSq < 1.0E-6) {
            return 0.0;
        }

        double invViewLen = 1.0 / Math.sqrt(viewLenSq);
        double invTargetLen = 1.0 / Math.sqrt(targetLenSq);
        vx *= invViewLen;
        vz *= invViewLen;
        tx *= invTargetLen;
        tz *= invTargetLen;

        double crossY = (vx * tz) - (vz * tx);
        double dot = (vx * tx) + (vz * tz);
        return Math.toDegrees(Math.atan2(crossY, dot));
    }

    private static void configureCombatTrackingAnimationProfile(double deltaMagnitude,
                                                                double distance,
                                                                boolean catchupActive,
                                                                boolean closeForSwing) {
        boolean slowSwitchActive = targetSwitchSlowTicks > 0;
        if (catchupActive || deltaMagnitude > 12.0) {
            animationDurationFrames = 2 + random.nextInt(3);
            yawStepMin = 2.2;
            yawStepMax = 11.0;
            pitchStepMin = 1.5;
            pitchStepMax = 7.2;
            animationEasePower = randomRange(0.72, 1.05);
        } else if (deltaMagnitude > 4.8) {
            animationDurationFrames = 3 + random.nextInt(4);
            yawStepMin = 1.0;
            yawStepMax = 5.8;
            pitchStepMin = 0.8;
            pitchStepMax = 4.2;
            animationEasePower = randomRange(0.9, 1.35);
        } else {
            animationDurationFrames = 4 + random.nextInt(5);
            yawStepMin = 0.22;
            yawStepMax = 2.1;
            pitchStepMin = 0.16;
            pitchStepMax = 1.6;
            animationEasePower = randomRange(1.05, 1.85);
        }

        if (slowSwitchActive) {
            double yawSeverity = Math.clamp(targetSwitchYawDelta / 90.0, 0.0, 1.0);
            int burstFrames = 3 + (int) Math.round(4.0 * (1.0 - yawSeverity)) + random.nextInt(3);
            animationDurationFrames = Math.min(animationDurationFrames, burstFrames);
            yawStepMin *= 1.25;
            yawStepMax *= 2.20;
            pitchStepMin *= 1.05;
            pitchStepMax *= 1.60;
            animationEasePower = randomRange(0.62, 1.02);
        }

        if (closeForSwing) {
            yawStepMax *= 0.82;
            pitchStepMax *= 0.78;
        }
        if (distance < 2.2) {
            yawStepMax *= 0.86;
            pitchStepMax *= 0.82;
        }

        boolean allowOvershoot = !closeForSwing
                && deltaMagnitude > 4.6
                && random.nextDouble() < (slowSwitchActive ? 0.72 : 0.20);
        if (allowOvershoot) {
            oscillationCycles = slowSwitchActive ? (2 + random.nextInt(2)) : (1 + random.nextInt(2));
            oscillationDamping = slowSwitchActive ? randomRange(1.4, 2.8) : randomRange(2.0, 4.0);
            double yawAmpMin = slowSwitchActive ? 0.10 : 0.04;
            double yawAmpMax = slowSwitchActive ? 0.28 : 0.14;
            double pitchAmpMin = slowSwitchActive ? 0.07 : 0.03;
            double pitchAmpMax = slowSwitchActive ? 0.18 : 0.10;
            yawOvershootAmplitude = randomRange(yawAmpMin, yawAmpMax) * Math.max(0.8, Math.abs(nextYawChangeDegrees));
            pitchOvershootAmplitude = randomRange(pitchAmpMin, pitchAmpMax) * Math.max(0.5, Math.abs(nextPitchChangeDegrees));
        } else {
            oscillationCycles = 0;
            oscillationDamping = 0;
            yawOvershootAmplitude = 0;
            pitchOvershootAmplitude = 0;
        }
    }

    private static int sampleTargetMovementCheckInterval(double distance) {
        int minTicks = TARGET_MOVEMENT_CHECK_MIN_TICKS;
        int maxTicks = TARGET_MOVEMENT_CHECK_MAX_TICKS;

        if (distance < 2.8) {
            // Close targets move more on screen, so sometimes reaction sampling lags behind.
            minTicks += 1;
            maxTicks += 2;
        }
        if (lastTargetAngularSpeed > 5.0) {
            minTicks = Math.max(1, minTicks - 1);
            maxTicks = Math.max(minTicks, maxTicks - 1);
        }

        return minTicks + random.nextInt((maxTicks - minTicks) + 1);
    }

    private static int sampleCombatTrackingInterval(double distance, boolean closeForSwing, double relativeHorizontalSpeed) {
        int interval = sampleTargetMovementCheckInterval(distance);
        if (closeForSwing) {
            interval = Math.min(interval, 2);
        }
        if (relativeHorizontalSpeed > 0.05) {
            interval = Math.min(interval, 2);
        }
        if (relativeHorizontalSpeed > 0.10) {
            interval = 1;
        }
        return Math.max(1, interval - 1);
    }

    private static int sampleCorrectionWindowTicks(double distance, double movementDelta) {
        int minTicks = CORRECTION_WINDOW_MIN_TICKS;
        int maxTicks = CORRECTION_WINDOW_MAX_TICKS;

        if (distance < 2.8) {
            // Nearby targets require larger camera correction; longer windows create realistic misses.
            minTicks += 1;
            maxTicks += 3;
        }
        if (movementDelta > 7.0) {
            minTicks = Math.max(1, minTicks - 1);
        }
        if (movementDelta < 1.2 && random.nextDouble() < 0.45) {
            maxTicks += 1;
        }

        maxTicks = Math.max(minTicks, maxTicks);
        return minTicks + random.nextInt((maxTicks - minTicks) + 1);
    }

    private static int sampleCatchupTicks(double distance, double angularSpeed) {
        int minTicks = 3;
        int maxTicks = 7;

        if (distance < 2.8) {
            minTicks += 1;
            maxTicks += 2;
        }
        if (angularSpeed > 4.0) {
            minTicks += 1;
            maxTicks += 1;
        }

        maxTicks = Math.max(minTicks, maxTicks);
        return minTicks + random.nextInt((maxTicks - minTicks) + 1);
    }

    private static int sampleReactionDelayTicks(double distance,
                                                double surpriseLevel,
                                                boolean closeForSwing,
                                                double relativeHorizontalSpeed) {
        double base = closeForSwing ? 1.9 : 2.7;
        base += Math.clamp(distance / 10.0, 0.0, 0.9);
        base += surpriseLevel * 2.2;
        base -= Math.clamp(relativeHorizontalSpeed * 2.2, 0.0, 0.8);
        base *= aimHumanReactionScale;
        base += random.nextGaussian() * 0.65;

        int sampledTicks = (int) Math.round(base);
        if (closeForSwing) {
            sampledTicks = Math.max(1, sampledTicks - 1);
        }
        return Math.max(1, Math.min(12, sampledTicks));
    }

    private static MouseDelta applyNaturalMouseNoise(double yawStep,
                                                     double pitchStep,
                                                     double yawLimit,
                                                     double pitchLimit,
                                                     double intensity) {
        double clampedIntensity = Math.clamp(intensity, 0.12, 1.8);

        double lowFreqSigma = (0.006 + (0.020 * clampedIntensity)) * aimHumanJitterScale;
        naturalMouseNoiseYaw = lerp(0.18, naturalMouseNoiseYaw, random.nextGaussian() * lowFreqSigma);
        naturalMouseNoisePitch = lerp(0.20, naturalMouseNoisePitch, random.nextGaussian() * lowFreqSigma * 0.80);

        naturalMouseNoisePhase += 0.13 + randomRange(-0.03, 0.03) + (clampedIntensity * 0.015);
        double waveMagnitude = (0.004 + (0.011 * clampedIntensity)) * aimHumanJitterScale;
        double waveYaw = (Math.sin(naturalMouseNoisePhase) * waveMagnitude)
                + (Math.sin((naturalMouseNoisePhase * 0.47) + 1.2) * waveMagnitude * 0.58);
        double wavePitch = (Math.cos((naturalMouseNoisePhase * 0.93) + 0.5) * waveMagnitude * 0.74)
                + (Math.sin((naturalMouseNoisePhase * 0.41) - 0.9) * waveMagnitude * 0.42);

        if (naturalMouseBurstTicks <= 0 && random.nextDouble() < (0.006 + (0.018 * clampedIntensity))) {
            naturalMouseBurstTicks = 2 + random.nextInt(4);
            naturalMouseBurstYaw = randomRange(-1.0, 1.0) * (0.018 + (0.055 * clampedIntensity));
            naturalMouseBurstPitch = randomRange(-1.0, 1.0) * (0.012 + (0.040 * clampedIntensity));
        }
        if (naturalMouseBurstTicks > 0) {
            double burstDecay = naturalMouseBurstTicks / 6.0;
            waveYaw += naturalMouseBurstYaw * burstDecay;
            wavePitch += naturalMouseBurstPitch * burstDecay;
            naturalMouseBurstTicks--;
            if (naturalMouseBurstTicks <= 0) {
                naturalMouseBurstYaw = 0.0;
                naturalMouseBurstPitch = 0.0;
            }
        }

        double noisyYaw = yawStep + naturalMouseNoiseYaw + waveYaw;
        double noisyPitch = pitchStep + naturalMouseNoisePitch + wavePitch;
        double yawCap = Math.max(0.08, Math.abs(yawLimit) * 1.12);
        double pitchCap = Math.max(0.08, Math.abs(pitchLimit) * 1.12);
        noisyYaw = Math.clamp(noisyYaw, -yawCap, yawCap);
        noisyPitch = Math.clamp(noisyPitch, -pitchCap, pitchCap);
        return new MouseDelta(noisyYaw, noisyPitch);
    }

    private static void scheduleNextAttackCadence(WeaponType weaponType) {
        attackCadenceWeaponType = weaponType;
        hasAttackCadence = true;

        double meanTarget;
        double spread;
        double minTarget;
        if (weaponType == WeaponType.AXE) {
            meanTarget = 0.93;
            spread = 0.04;
            minTarget = 0.74;
        } else if (weaponType == WeaponType.SWORD) {
            meanTarget = 0.91;
            spread = 0.05;
            minTarget = 0.70;
        } else if (weaponType == WeaponType.BOW) {
            meanTarget = 1.0;
            spread = 0.01;
            minTarget = 0.98;
        } else {
            meanTarget = 0.89;
            spread = 0.06;
            minTarget = 0.68;
        }

        double sampledTarget = meanTarget + (random.nextGaussian() * spread);
        if (random.nextDouble() < 0.22) {
            // Intentional occasional early swing tendency with normal-distributed falloff.
            sampledTarget -= Math.abs(random.nextGaussian()) * spread * randomRange(0.5, 1.2);
        }

        nextAttackCooldownTarget = Math.clamp(sampledTarget, minTarget, 0.995);
        attackEarlySigma = Math.clamp(spread * randomRange(0.8, 1.25), 0.035, 0.12);
    }

    private static void resetAimTrackingForNewTarget() {
        hasTargetErrorHistory = false;
        combatAimYawVelocity = 0;
        combatAimPitchVelocity = 0;
        naturalMouseNoiseYaw = 0;
        naturalMouseNoisePitch = 0;
        naturalMouseBurstTicks = 0;
        naturalMouseBurstYaw = 0;
        naturalMouseBurstPitch = 0;
        aimHumanReactionScale = Math.clamp(1.0 + (random.nextGaussian() * 0.16), 0.72, 1.42);
        aimHumanCorrectionScale = Math.clamp(1.0 + (random.nextGaussian() * 0.17), 0.68, 1.45);
        aimHumanJitterScale = Math.clamp(1.0 + (random.nextGaussian() * 0.24), 0.55, 1.85);
        aimHumanMicroSaccadeBias = randomRange(-0.22, 0.22);
        lastTargetAngularSpeed = 0;
        aimBiasTicks = 0;
        deliberateMissTicks = 0;
        deliberateMissYawHold = 0;
        deliberateMissPitchHold = 0;
        critPrepTicks = 0;
        targetMovementCheckTicks = 0;
        pendingCorrectionDelayTicks = 0;
        pendingCorrectionWindowTicks = 0;
        correctionTicksRemaining = 0;
        aimBehindTicks = 0;
        catchupTicksRemaining = 0;
        combatLookOffsetTicks = 0;
        combatLookOffsetYaw = 0;
        combatLookOffsetPitch = 0;
        combatLookOffsetTargetYaw = 0;
        combatLookOffsetTargetPitch = 0;
        observedYawError = 0;
        observedPitchError = 0;
        correctionTargetYawError = 0;
        correctionTargetPitchError = 0;
        hasObservedTargetError = false;
    }

    private static void resetNoShieldFleeLookLock() {
        noShieldFleeLookLocked = false;
        noShieldFleeLookTargetId = -1;
    }

    private static void resetCreeperRetreatBurstState() {
        creeperRetreatBurstTicks = 0;
        creeperRetreatStuckTicks = 0;
        creeperRetreatObserveTicks = 0;
        creeperRetreatTargetId = -1;
        creeperRetreatLastDistance = -1.0;
    }

    private static void clearCombatState() {
        combatTarget = null;
        combatActive = false;
        selectedWeaponType = WeaponType.NONE;
        targetOutOfViewTicks = 0;
        combatTargetLockTicks = 0;
        targetReevaluateTicks = 0;
        targetSwitchSlowTicks = 0;
        targetSwitchYawDelta = 0;
        resetThreatTracking();
        attackPressTicks = 0;
        bowDrawTicks = 0;
        bowDrawTargetTicks = 0;
        bowReleaseCooldownTicks = 0;
        meleePursuitStallTicks = 0;
        ticksInRangeNoAttack = 0;
        hasAttackCadence = false;
        attackCadenceWeaponType = WeaponType.NONE;
        nextAttackCooldownTarget = 0.0;
        attackEarlySigma = 0.08;
        pendingAttackClicks = 0;
        postHitPauseTicks = 0;
        skeletonKillCommitTicks = 0;
        skeletonKillCommitTargetId = -1;
        arrowEvadeTicks = 0;
        arrowEvadeStrafeDirection = 0;
        arrowEvadeRetreat = false;
        skeletonJukeTicks = 0;
        skeletonJukeDirection = 0;
        skeletonJukeRetreat = false;
        skeletonJukeFlipCooldown = 0;
        creeperShieldCommitTicks = 0;
        resetCreeperRetreatBurstState();
        noShieldCreeperFleeTicks = 0;
        noShieldCreeperFleeCommitTicks = 0;
        noShieldCreeperFleeTargetId = -1;
        resetNoShieldFleeLookLock();
        shieldHoldTicks = 0;
        shieldPushbackTicks = 0;
        shieldPushbackCooldownTicks = 0;
        critPrepTicks = 0;
        combatMoveForward = false;
        combatMoveBackward = false;
        combatMoveLeft = false;
        combatMoveRight = false;
        combatJump = false;
        combatAttack = false;
        combatUseItem = false;
        combatSprint = false;
        pendingHotbarSlot = -1;
        hotbarSwitchPulseCooldownTicks = 0;
        hotbarSwitchPulseAttempts = 0;
        resetAimTrackingForNewTarget();
    }

    private static void emitAttackClick(Minecraft mc) {
        InputConstants.Key attackKey = InputConstants.getKey(mc.options.keyAttack.saveString());
        KeyMapping.click(attackKey);
    }

    private static void emitHotbarKeyClick(Minecraft mc, int hotbarSlot) {
        if (hotbarSlot < 0 || hotbarSlot >= Math.min(Inventory.getSelectionSize(), mc.options.keyHotbarSlots.length)) {
            return;
        }
        InputConstants.Key hotbarKey = InputConstants.getKey(mc.options.keyHotbarSlots[hotbarSlot].saveString());
        KeyMapping.click(hotbarKey);
    }

    private static WeaponType detectWeaponType(LocalPlayer player, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return WeaponType.NONE;
        }
        if (stack.is(ItemTags.SWORDS)) {
            return WeaponType.SWORD;
        }
        if (stack.is(ItemTags.AXES)) {
            return WeaponType.AXE;
        }
        if (stack.getItem() instanceof BowItem && hasUsableBowAmmo(player, stack)) {
            return WeaponType.BOW;
        }
        return WeaponType.NONE;
    }

    private static void startFoodConsumptionObjective(int hotbarSlot) {
        if (hotbarSlot < 0 || hotbarSlot >= Inventory.getSelectionSize()) {
            return;
        }
        foodConsumptionObjectiveActive = true;
        foodConsumptionObjectiveSlot = hotbarSlot;
        // Preempt any previous wandering slot pulse so food objective takes ownership.
        pendingHotbarSlot = -1;
        hotbarSwitchPulseCooldownTicks = 0;
        hotbarSwitchPulseAttempts = 0;
        queueHotbarSlot(hotbarSlot);
    }

    private static void abortFoodConsumptionObjective() {
        foodConsumptionObjectiveActive = false;
        foodConsumptionObjectiveSlot = -1;
    }

    private static boolean updateFoodConsumptionObjective(LocalPlayer player, boolean shouldAbort) {
        if (!foodConsumptionObjectiveActive) {
            return false;
        }
        if (player == null || shouldAbort || combatActive || !player.isAlive() || player.isSpectator()) {
            abortFoodConsumptionObjective();
            return false;
        }
        if (!player.getFoodData().needsFood()) {
            abortFoodConsumptionObjective();
            return false;
        }

        int objectiveSlot = resolveFoodConsumptionObjectiveSlot(player);
        if (objectiveSlot < 0) {
            abortFoodConsumptionObjective();
            return false;
        }
        foodConsumptionObjectiveSlot = objectiveSlot;

        if (player.getInventory().getSelectedSlot() != objectiveSlot) {
            queueHotbarSlot(objectiveSlot);
        }
        return true;
    }

    private static int resolveFoodConsumptionObjectiveSlot(LocalPlayer player) {
        if (isValidFoodObjectiveSlot(player, foodConsumptionObjectiveSlot)) {
            return foodConsumptionObjectiveSlot;
        }
        return findBestHotbarFoodSlot(player);
    }

    private static boolean isValidFoodObjectiveSlot(LocalPlayer player, int hotbarSlot) {
        if (player == null || hotbarSlot < 0 || hotbarSlot >= Inventory.getSelectionSize()) {
            return false;
        }
        ItemStack stack = player.getInventory().getItem(hotbarSlot);
        FoodProperties food = getFoodProperties(stack);
        if (food == null) {
            return false;
        }
        return player.canEat(food.canAlwaysEat());
    }

    private static int findBestHotbarFoodSlot(LocalPlayer player) {
        if (player == null) {
            return -1;
        }
        int missingFood = Math.max(0, MAX_FOOD_LEVEL - player.getFoodData().getFoodLevel());
        if (missingFood <= 0) {
            return -1;
        }

        Inventory inventory = player.getInventory();
        int bestSlot = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        int fallbackSlot = -1;
        double fallbackScore = Double.NEGATIVE_INFINITY;
        int hotbarSize = Inventory.getSelectionSize();
        for (int slot = 0; slot < hotbarSize; slot++) {
            ItemStack stack = inventory.getItem(slot);
            FoodProperties food = getFoodProperties(stack);
            if (food == null) {
                continue;
            }

            double score = scoreFoodForAutoEat(stack, food, missingFood);
            if (isUnsuitableFoodForAutoEat(stack)) {
                if (score > fallbackScore) {
                    fallbackScore = score;
                    fallbackSlot = slot;
                }
                continue;
            }
            if (score > bestScore) {
                bestScore = score;
                bestSlot = slot;
            }
        }

        return bestSlot >= 0 ? bestSlot : fallbackSlot;
    }

    private static FoodProperties getFoodProperties(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return stack.get(DataComponents.FOOD);
    }

    private static double scoreFoodForAutoEat(ItemStack stack, FoodProperties food, int missingFood) {
        int nutrition = Math.max(1, food.nutrition());
        double waste = Math.max(0.0, nutrition - missingFood);
        double score = 100.0;
        score -= waste * 4.2;
        score -= Math.abs(nutrition - missingFood) * 0.35;
        score += food.saturation() * 0.25;
        if (food.canAlwaysEat()) {
            score -= 1.8;
        }
        if (isPremiumFood(stack)) {
            score -= 8.0;
        }
        return score;
    }

    private static boolean isUnsuitableFoodForAutoEat(ItemStack stack) {
        return stack.is(Items.ROTTEN_FLESH)
                || stack.is(Items.SPIDER_EYE)
                || stack.is(Items.POISONOUS_POTATO)
                || stack.is(Items.PUFFERFISH)
                || stack.is(Items.CHICKEN);
    }

    private static boolean isPremiumFood(ItemStack stack) {
        return stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE);
    }

    private static int resolveHotbarSlotKeyToPress(LocalPlayer player) {
        if (pendingHotbarSlot < 0) {
            hotbarSwitchPulseCooldownTicks = 0;
            hotbarSwitchPulseAttempts = 0;
            return -1;
        }
        if (player == null) {
            return -1;
        }

        int selectedSlot = player.getInventory().getSelectedSlot();
        if (selectedSlot == pendingHotbarSlot) {
            pendingHotbarSlot = -1;
            hotbarSwitchPulseCooldownTicks = 0;
            hotbarSwitchPulseAttempts = 0;
            return -1;
        }

        if (hotbarSwitchPulseCooldownTicks > 0) {
            hotbarSwitchPulseCooldownTicks--;
            return -1;
        }

        hotbarSwitchPulseCooldownTicks = HOTBAR_SWITCH_PULSE_INTERVAL_TICKS;
        hotbarSwitchPulseAttempts++;
        return pendingHotbarSlot;
    }

    private static void queueHotbarSlot(int slot) {
        if (slot >= 0 && slot < Inventory.getSelectionSize()) {
            if (pendingHotbarSlot >= 0
                    && pendingHotbarSlot != slot
                    && hotbarSwitchPulseAttempts < 3) {
                return;
            }
            if (pendingHotbarSlot != slot) {
                pendingHotbarSlot = slot;
                hotbarSwitchPulseCooldownTicks = 0;
                hotbarSwitchPulseAttempts = 0;
            }
        }
    }

    private static void startLookAnimation(Camera mainCamera) {
        startCameraYaw = mainCamera.getYRot();
        startCameraPitch = mainCamera.getXRot();
        animationFrame = 0;

        nextYawChangeDegrees = sampleMouseLikeDelta(2.5, 5.0, 12.0, 0.35, 0.08, 45.0);
        double sampledPitchChangeDegrees = sampleMouseLikeDelta(1.3, 3.2, 7.5, 0.30, 0.06, 20.0);

        double clampedTargetPitch = Math.clamp(
                startCameraPitch + sampledPitchChangeDegrees,
                MIN_TARGET_PITCH_DEGREES,
                MAX_TARGET_PITCH_DEGREES
        );
        if (isOutsidePreferredPitchRange(startCameraPitch)) {
            double recoveryPitchTarget = Math.clamp(startCameraPitch, MIN_TARGET_PITCH_DEGREES + 2.0, MAX_TARGET_PITCH_DEGREES - 2.0);
            clampedTargetPitch = lerp(OUT_OF_RANGE_RECOVERY_BIAS, clampedTargetPitch, recoveryPitchTarget);
        }
        if (groundLookFrameStreak > GROUND_STARE_MAX_FRAMES / 2) {
            clampedTargetPitch = Math.min(clampedTargetPitch, GROUND_STARE_RECOVERY_MAX_TARGET_PITCH);
        }
        nextPitchChangeDegrees = clampedTargetPitch - startCameraPitch;

        configureAnimationProfile();
        shouldPerformAnimation = true;
    }

    private static void configureAnimationProfile() {
        double profileRoll = random.nextDouble();

        if (profileRoll < 0.18) {
            // Fast flicks that can occasionally look twitchy and very quick.
            animationDurationFrames = 2 + random.nextInt(5);
            yawStepMin = 3.0;
            yawStepMax = 14.0;
            pitchStepMin = 2.0;
            pitchStepMax = 9.0;
            animationEasePower = randomRange(0.7, 1.1);
        } else if (profileRoll < 0.43) {
            // Slower drags for long, smooth camera motion.
            animationDurationFrames = 20 + random.nextInt(36);
            yawStepMin = 0.15;
            yawStepMax = 1.1;
            pitchStepMin = 0.10;
            pitchStepMax = 0.9;
            animationEasePower = randomRange(1.3, 2.1);
        } else {
            // Typical movement profile.
            animationDurationFrames = 7 + random.nextInt(14);
            yawStepMin = 0.6;
            yawStepMax = 4.5;
            pitchStepMin = 0.4;
            pitchStepMax = 3.0;
            animationEasePower = randomRange(0.9, 1.6);
        }

        if (isOutsidePreferredPitchRange(startCameraPitch)) {
            animationDurationFrames = Math.max(2, animationDurationFrames / 2);
            pitchStepMin *= 1.5;
            pitchStepMax *= 2.0;
        }

        boolean shouldOvershoot = random.nextDouble() < 0.45;
        if (shouldOvershoot) {
            oscillationCycles = 1 + random.nextInt(3);
            oscillationDamping = randomRange(1.8, 4.4);

            yawOvershootAmplitude = randomRange(0.12, 0.35) * Math.max(1.0, Math.abs(nextYawChangeDegrees));
            pitchOvershootAmplitude = randomRange(0.08, 0.26) * Math.max(0.4, Math.abs(nextPitchChangeDegrees));
        } else {
            oscillationCycles = 0;
            oscillationDamping = 0;
            yawOvershootAmplitude = 0;
            pitchOvershootAmplitude = 0;
        }
    }

    private static double sampleMouseLikeDelta(double fineStdDev, double mediumStdDev, double burstStdDev,
                                               double mediumChance, double burstChance, double maxAbsDelta) {
        double delta = random.nextGaussian() * fineStdDev;
        if (random.nextDouble() < mediumChance) {
            delta += random.nextGaussian() * mediumStdDev;
        }
        if (random.nextDouble() < burstChance) {
            delta += random.nextGaussian() * burstStdDev;
        }
        return Math.clamp(delta, -maxAbsDelta, maxAbsDelta);
    }

    private static boolean isOutsidePreferredPitchRange(double pitch) {
        return pitch < MIN_TARGET_PITCH_DEGREES || pitch > MAX_TARGET_PITCH_DEGREES;
    }

    private static double randomRange(double min, double max) {
        return min + ((max - min) * random.nextDouble());
    }

    private static double lerp(double t, double start, double end) {
        return start + ((end - start) * t);
    }

    private static double easeProgress(double t) {
        // Base smoothstep with a variable power gives more human-like acceleration curves.
        double smoothstep = t * t * (3.0 - (2.0 * t));
        return Math.pow(smoothstep, animationEasePower);
    }
}
