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

    // Tower building limits (from SPAARK)
    public static final int MAX_TOWER_ENEMY_PAINT = 4;
    public static final int MAX_BUILDING_SOLDIERS = 2;
    public static final int TOWER_TIMEOUT = 80;

    // SRP (Special Resource Pattern) timing
    public static final int SRP_MIN_ROUND = 50;
    public static final int SRP_TIMEOUT = 50;

    // Unit costs
    public static final int SOLDIER_PAINT_COST = 200;
    public static final int MOPPER_PAINT_COST = 100;
    public static final int SPLASHER_PAINT_COST = 200;

    // Money buffer - spawn only if we'll have this much left
    public static final int SPAWN_MONEY_BUFFER = 100;

    // Early game: first N units are always soldiers
    public static final int EARLY_SOLDIERS_COUNT = 3;

    // Direction arrays
    public static final Direction[] DIRECTIONS = {
        Direction.NORTH, Direction.NORTHEAST, Direction.EAST, Direction.SOUTHEAST,
        Direction.SOUTH, Direction.SOUTHWEST, Direction.WEST, Direction.NORTHWEST
    };

    private static boolean initialized = false;

    /**
     * Initialize globals for a robot (once only).
     */
    public static void init(RobotController rc) {
        if (!initialized) {
            rng = new Random(rc.getID());
            initialized = true;
        }
    }
}
