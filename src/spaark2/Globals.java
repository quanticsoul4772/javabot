package spaark2;

import battlecode.common.*;
import java.util.Random;

/**
 * All constants and shared state for spaark2 bot.
 * Copied from SPAARK's winning values.
 */
public class Globals {

    // Random number generator
    public static Random rng;

    // Spawn weights (from SPAARK)
    public static final double SOLDIER_WEIGHT = 1.5;
    public static final double SPLASHER_WEIGHT = 0.2;
    public static final double MOPPER_WEIGHT = 1.2;

    // Retreat thresholds - ALL must be true to retreat (from SPAARK)
    public static final int RETREAT_PAINT = 150;
    public static final int RETREAT_CHIPS = 6000;
    public static final int RETREAT_ALLIES = 9;

    // Unit costs
    public static final int SOLDIER_PAINT_COST = 200;
    public static final int MOPPER_PAINT_COST = 100;
    public static final int SPLASHER_PAINT_COST = 200;

    // Money buffer - spawn only if we'll have this much left
    public static final int SPAWN_MONEY_BUFFER = 100;

    // Early game: first N units are always soldiers
    public static final int EARLY_SOLDIERS_COUNT = 3;

    // Global spawn tracking (shared across all towers)
    public static int spawnedSoldiers = 0;
    public static int spawnedSplashers = 0;
    public static int spawnedMoppers = 0;
    public static int spawnedTotal = 0;

    // Fractional accumulators for ratio maintenance
    public static double fracSoldiers = 0;
    public static double fracSplashers = 0;
    public static double fracMoppers = 0;

    // Direction arrays
    public static final Direction[] DIRECTIONS = {
        Direction.NORTH, Direction.NORTHEAST, Direction.EAST, Direction.SOUTHEAST,
        Direction.SOUTH, Direction.SOUTHWEST, Direction.WEST, Direction.NORTHWEST
    };

    // All directions including CENTER (for micro scoring)
    public static final Direction[] ALL_DIRECTIONS = {
        Direction.NORTH, Direction.NORTHEAST, Direction.EAST, Direction.SOUTHEAST,
        Direction.SOUTH, Direction.SOUTHWEST, Direction.WEST, Direction.NORTHWEST,
        Direction.CENTER
    };

    private static boolean initialized = false;

    /**
     * Initialize globals for a robot (once only).
     * Delayed RNG init to save bytecode on early rounds.
     */
    public static void init(RobotController rc) {
        if (!initialized) {
            initialized = true;
        }
    }

    /**
     * Get RNG, initializing if needed.
     */
    public static Random getRng(RobotController rc) {
        if (rng == null) {
            rng = new Random(rc.getID());
        }
        return rng;
    }
}
