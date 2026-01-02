package mybot;

import battlecode.common.*;
import java.util.Random;

/**
 * Shared utilities and constants for all units.
 */
public class Utils {

    // Shared RNG - seeded for reproducible debugging
    public static final Random rng = new Random(6147);

    // All movement directions
    public static final Direction[] DIRECTIONS = {
        Direction.NORTH,
        Direction.NORTHEAST,
        Direction.EAST,
        Direction.SOUTHEAST,
        Direction.SOUTH,
        Direction.SOUTHWEST,
        Direction.WEST,
        Direction.NORTHWEST,
    };

    // Cardinal directions only (for certain operations)
    public static final Direction[] CARDINALS = {
        Direction.NORTH,
        Direction.EAST,
        Direction.SOUTH,
        Direction.WEST,
    };

    /**
     * Get a random direction.
     */
    public static Direction randomDirection() {
        return DIRECTIONS[rng.nextInt(DIRECTIONS.length)];
    }

    /**
     * Try to move in a direction, return true if successful.
     */
    public static boolean tryMove(RobotController rc, Direction dir) throws GameActionException {
        if (rc.canMove(dir)) {
            rc.move(dir);
            return true;
        }
        return false;
    }

    /**
     * Try to move randomly in any valid direction.
     */
    public static boolean tryMoveRandom(RobotController rc) throws GameActionException {
        Direction dir = randomDirection();
        // Try the random direction first
        if (tryMove(rc, dir)) return true;
        // Try all other directions
        for (Direction d : DIRECTIONS) {
            if (tryMove(rc, d)) return true;
        }
        return false;
    }

    /**
     * Paint the current tile if it's not already our color.
     */
    public static boolean tryPaintCurrent(RobotController rc) throws GameActionException {
        MapLocation loc = rc.getLocation();
        MapInfo tile = rc.senseMapInfo(loc);
        if (!tile.getPaint().isAlly() && rc.canAttack(loc)) {
            rc.attack(loc);
            return true;
        }
        return false;
    }

    /**
     * Find the closest location from an array.
     */
    public static MapLocation closest(MapLocation from, MapLocation[] locations) {
        if (locations == null || locations.length == 0) return null;
        MapLocation best = null;
        int bestDist = Integer.MAX_VALUE;
        for (MapLocation loc : locations) {
            int dist = from.distanceSquaredTo(loc);
            if (dist < bestDist) {
                bestDist = dist;
                best = loc;
            }
        }
        return best;
    }

    /**
     * Find the closest robot from an array.
     */
    public static RobotInfo closestRobot(MapLocation from, RobotInfo[] robots) {
        if (robots == null || robots.length == 0) return null;
        RobotInfo best = null;
        int bestDist = Integer.MAX_VALUE;
        for (RobotInfo robot : robots) {
            int dist = from.distanceSquaredTo(robot.getLocation());
            if (dist < bestDist) {
                bestDist = dist;
                best = robot;
            }
        }
        return best;
    }

    // ========== PHASE 1: Tower Tracking ==========

    /**
     * Count visible ally towers of any type.
     */
    public static int countAllyTowers(RobotController rc) throws GameActionException {
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        int count = 0;
        for (RobotInfo ally : allies) {
            if (ally.getType().isTowerType()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Count visible ally Paint Towers specifically.
     */
    public static int countPaintTowers(RobotController rc) throws GameActionException {
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        int count = 0;
        for (RobotInfo ally : allies) {
            UnitType type = ally.getType();
            if (type == UnitType.LEVEL_ONE_PAINT_TOWER ||
                type == UnitType.LEVEL_TWO_PAINT_TOWER ||
                type == UnitType.LEVEL_THREE_PAINT_TOWER) {
                count++;
            }
        }
        return count;
    }

    /**
     * Find the nearest ally Paint Tower.
     */
    public static RobotInfo findNearestPaintTower(RobotController rc) throws GameActionException {
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        MapLocation myLoc = rc.getLocation();
        RobotInfo best = null;
        int bestDist = Integer.MAX_VALUE;
        for (RobotInfo ally : allies) {
            UnitType type = ally.getType();
            if (type == UnitType.LEVEL_ONE_PAINT_TOWER ||
                type == UnitType.LEVEL_TWO_PAINT_TOWER ||
                type == UnitType.LEVEL_THREE_PAINT_TOWER) {
                int dist = myLoc.distanceSquaredTo(ally.getLocation());
                if (dist < bestDist) {
                    bestDist = dist;
                    best = ally;
                }
            }
        }
        return best;
    }

    /**
     * Check if any visible Paint Tower is under attack (enemies nearby).
     */
    public static RobotInfo findPaintTowerUnderAttack(RobotController rc) throws GameActionException {
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());

        for (RobotInfo ally : allies) {
            UnitType type = ally.getType();
            if (type == UnitType.LEVEL_ONE_PAINT_TOWER ||
                type == UnitType.LEVEL_TWO_PAINT_TOWER ||
                type == UnitType.LEVEL_THREE_PAINT_TOWER) {
                // Check if any enemy is close to this paint tower
                for (RobotInfo enemy : enemies) {
                    if (ally.getLocation().distanceSquaredTo(enemy.getLocation()) <= 20) {
                        return ally; // This paint tower is under threat
                    }
                }
            }
        }
        return null;
    }

    /**
     * Check if this is a Paint Tower type.
     */
    public static boolean isPaintTower(UnitType type) {
        return type == UnitType.LEVEL_ONE_PAINT_TOWER ||
               type == UnitType.LEVEL_TWO_PAINT_TOWER ||
               type == UnitType.LEVEL_THREE_PAINT_TOWER;
    }

    /**
     * Count enemy soldiers visible (for rush detection).
     */
    public static int countEnemySoldiers(RobotController rc) throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        int count = 0;
        for (RobotInfo enemy : enemies) {
            if (enemy.getType() == UnitType.SOLDIER) {
                count++;
            }
        }
        return count;
    }

    /**
     * Count enemy splashers visible (for adaptive spawn).
     */
    public static int countEnemySplashers(RobotController rc) throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        int count = 0;
        for (RobotInfo enemy : enemies) {
            if (enemy.getType() == UnitType.SPLASHER) {
                count++;
            }
        }
        return count;
    }

    // ========== PHASE 4: Economy Tracking ==========

    private static int[] moneyHistory = new int[10];
    private static int historyIndex = 0;
    private static int lastMoney = 0;
    private static int estimatedIncome = 0;

    /**
     * Update income estimate (call once per turn from any robot).
     */
    public static void updateIncomeEstimate(RobotController rc) {
        int currentMoney = rc.getMoney();
        int delta = currentMoney - lastMoney;

        // Only track positive income (spending makes delta negative)
        if (delta > 0) {
            moneyHistory[historyIndex] = delta;
            historyIndex = (historyIndex + 1) % 10;

            // Find max income in history
            int maxIncome = 0;
            for (int i = 0; i < 10; i++) {
                if (moneyHistory[i] > maxIncome) {
                    maxIncome = moneyHistory[i];
                }
            }
            estimatedIncome = maxIncome;
        }

        lastMoney = currentMoney;
    }

    /**
     * Get estimated income per turn.
     */
    public static int getEstimatedIncome() {
        return estimatedIncome;
    }

    /**
     * Estimate number of Money Towers based on income.
     * Income = (20 + 3 × #SRPs) × #MoneyTowers
     * Simplified: assume 0 SRPs, so MoneyTowers ≈ income / 20
     */
    public static int estimateMoneyTowers() {
        return estimatedIncome / 20;
    }

    /**
     * Check if economy is strong enough for aggressive strategy.
     * Strong = estimated income suggests 2+ money towers or 3+ SRPs.
     */
    public static boolean isEconomyStrong() {
        // Income of 40+ suggests multiple money towers or SRPs
        return estimatedIncome >= 40;
    }

    /**
     * Check if economy is weak, requiring defensive posture.
     */
    public static boolean isEconomyWeak() {
        // Income under 20 means only base tower income
        return estimatedIncome < 20;
    }

    // ========== PHASE 3: Tile Scoring ==========

    /**
     * Score a tile for movement preference (higher = better).
     * Uses centralized weights from Scoring class.
     */
    public static int scoreTile(RobotController rc, MapLocation loc) throws GameActionException {
        if (!rc.canSenseLocation(loc)) return -100;

        MapInfo info = rc.senseMapInfo(loc);
        if (!info.isPassable()) return -100;

        int score = 0;
        PaintType paint = info.getPaint();

        // Use centralized weights
        if (paint.isAlly()) score += Scoring.WEIGHT_ALLY_PAINT;
        else if (paint.isEnemy()) score += Scoring.WEIGHT_ENEMY_PAINT;
        else score += Scoring.WEIGHT_NEUTRAL;

        return score;
    }
}
