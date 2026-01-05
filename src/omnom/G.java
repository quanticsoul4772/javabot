package omnom;

import battlecode.common.*;

/**
 * Global state singleton - all static for bytecode efficiency.
 * Source: SPAARK G.java, bot_spec.md Part 7.3, 14.16
 */
public class G {
    // Robot controller - set once per turn
    public static RobotController rc;

    // Robot state - updated each turn
    public static MapLocation me;
    public static int round;
    public static Team team;
    public static Team opponent;
    public static UnitType type;
    public static int id;

    // Map info (set once)
    public static int mapWidth;
    public static int mapHeight;
    public static int mapArea;
    public static MapLocation mapCenter;

    // Current state
    public static int paint;
    public static int chips;

    // Sensing cache (lazy-loaded)
    private static RobotInfo[] allies;
    private static RobotInfo[] enemies;
    private static boolean alliesLoaded = false;
    private static boolean enemiesLoaded = false;

    // StringBuilder for O(1) position lookup (SPAARK optimization)
    public static StringBuilder allyRobotsString;

    // Paint economy constants (from spec Part 11.1)
    public static final double PAINT_PER_CHIP = 0.25;
    public static final double PAINT_PER_COOLDOWN = 0.1;

    // Pre-computed offset tables (from spec Part 14.15)
    public static final int[] range20X = {
        0, -1, 0, 0, 1, -1, -1, 1, 1, -2, 0, 0, 2, -2, -2, -1, -1, 1, 1, 2, 2,
        -2, -2, 2, 2, -3, 0, 0, 3, -3, -3, -1, -1, 1, 1, 3, 3, -3, -3, -2, -2,
        2, 2, 3, 3, -4, 0, 0, 4, -4, -4, -1, -1, 1, 1, 4, 4, -3, -3, 3, 3, -4,
        -4, -2, -2, 2, 2, 4, 4
    };
    public static final int[] range20Y = {
        0, 0, -1, 1, 0, -1, 1, -1, 1, 0, -2, 2, 0, -1, 1, -2, 2, -2, 2, -1, 1,
        -2, 2, -2, 2, 0, -3, 3, 0, -1, 1, -3, 3, -3, 3, -1, 1, -2, 2, -3, 3,
        -3, 3, -2, 2, 0, -4, 4, 0, -1, 1, -4, 4, -4, 4, -1, 1, -3, 3, -3, 3,
        -2, 2, -4, 4, -4, 4, -2, 2
    };

    // Retreat thresholds (baseline)
    public static final int RETREAT_PAINT = 50;
    public static final int RETREAT_CHIPS = 6000;
    public static final int RETREAT_ALLY_THRESHOLD = 9;

    // Direction constants
    public static final Direction[] DIRECTIONS = {
        Direction.NORTH,
        Direction.NORTHEAST,
        Direction.EAST,
        Direction.SOUTHEAST,
        Direction.SOUTH,
        Direction.SOUTHWEST,
        Direction.WEST,
        Direction.NORTHWEST
    };

    public static final Direction[] ALL_DIRECTIONS = {
        Direction.NORTH,
        Direction.NORTHEAST,
        Direction.EAST,
        Direction.SOUTHEAST,
        Direction.SOUTH,
        Direction.SOUTHWEST,
        Direction.WEST,
        Direction.NORTHWEST,
        Direction.CENTER
    };

    // lastVisited grid (30x30, coordinates / 2, +2000 offset)
    public static int[][] lastVisited = new int[30][30];

    // Tower counts (updated by POI)
    public static int numTowers = 0;
    public static int allyPaintTowers = 0;
    public static int allyMoneyTowers = 0;
    public static int lastTowerCount = 0;

    // Bytecode profiling (Week 6)
    private static int profileStartBytecode = 0;

    public static void profileStart() {
        profileStartBytecode = Clock.getBytecodeNum();
    }

    public static void profileEnd(String section) {
        int cost = Clock.getBytecodeNum() - profileStartBytecode;
        if (G.round % 20 == 0) {  // Sample profiling
            System.out.println("PROFILE:" + G.round + ":" + G.id + ":" + section + ":" + cost);
        }
    }

    /**
     * Initialize global state at start of each turn.
     */
    public static void init(RobotController controller) throws GameActionException {
        rc = controller;
        me = rc.getLocation();
        round = rc.getRoundNum();
        team = rc.getTeam();
        opponent = team.opponent();
        type = rc.getType();
        id = rc.getID();
        paint = rc.getPaint();
        chips = rc.getChips();

        // Cache map info (only first time)
        if (mapCenter == null) {
            mapWidth = rc.getMapWidth();
            mapHeight = rc.getMapHeight();
            mapArea = mapWidth * mapHeight;
            mapCenter = new MapLocation(mapWidth / 2, mapHeight / 2);
        }

        // Reset lazy-load flags
        alliesLoaded = false;
        enemiesLoaded = false;
        allies = null;
        enemies = null;

        // Build allyRobotsString for O(1) position lookup
        allyRobotsString = new StringBuilder();
        if (RobotPlayer.allAllies != null) {
            for (int i = RobotPlayer.allAllies.length; --i >= 0;) {
                if (RobotPlayer.allAllies[i].type.isRobotType()) {
                    allyRobotsString.append(RobotPlayer.allAllies[i].location.toString());
                }
            }
        }

        // Mark current location as visited
        markVisited(me);
    }

    /**
     * Mark location as visited in 30x30 grid.
     * Uses +2000 offset for efficiency.
     */
    public static void markVisited(MapLocation loc) {
        int gx = loc.x >> 1;  // Divide by 2
        int gy = loc.y >> 1;
        if (gx >= 0 && gx < 30 && gy >= 0 && gy < 30) {
            lastVisited[gx][gy] = round + 2000;
        }
    }

    /**
     * Get when location was last visited.
     */
    public static int getLastVisited(MapLocation loc) {
        int gx = loc.x >> 1;
        int gy = loc.y >> 1;
        if (gx < 0 || gx >= 30 || gy < 0 || gy >= 30) return -2000;
        return lastVisited[gx][gy] - 2000;
    }

    /**
     * Check if location was recently visited.
     */
    public static boolean recentlyVisited(MapLocation loc, int threshold) {
        return round - getLastVisited(loc) < threshold;
    }

    /**
     * Get nearby allies (uses cached data from RobotPlayer).
     */
    public static RobotInfo[] getAllies() throws GameActionException {
        if (!alliesLoaded) {
            // Use cached data from RobotPlayer (sensed once per round)
            allies = RobotPlayer.allAllies;
            alliesLoaded = true;
        }
        return allies;
    }

    /**
     * Get nearby enemies (uses cached data from RobotPlayer).
     */
    public static RobotInfo[] getEnemies() throws GameActionException {
        if (!enemiesLoaded) {
            // Use cached data from RobotPlayer (sensed once per round)
            enemies = RobotPlayer.allEnemies;
            enemiesLoaded = true;
        }
        return enemies;
    }

    /**
     * Find direction from vector components.
     * Uses switch for O(1) lookup.
     */
    public static Direction directionFromVector(int dx, int dy) {
        if (dx == 0 && dy == 0) return Direction.CENTER;

        // Normalize to -1, 0, 1
        int sx = dx > 0 ? 1 : (dx < 0 ? -1 : 0);
        int sy = dy > 0 ? 1 : (dy < 0 ? -1 : 0);

        // Combined lookup
        int key = (sx + 1) * 3 + (sy + 1);
        switch (key) {
            case 0: return Direction.SOUTHWEST;
            case 1: return Direction.WEST;
            case 2: return Direction.NORTHWEST;
            case 3: return Direction.SOUTH;
            case 4: return Direction.CENTER;
            case 5: return Direction.NORTH;
            case 6: return Direction.SOUTHEAST;
            case 7: return Direction.EAST;
            case 8: return Direction.NORTHEAST;
            default: return Direction.CENTER;
        }
    }

    /**
     * Find index of maximum value in array.
     * Uses reversed loop for bytecode efficiency.
     */
    public static int maxIndex(int[] arr) {
        int maxIdx = 0;
        int maxVal = arr[0];
        for (int i = arr.length; --i > 0;) {
            if (arr[i] > maxVal) {
                maxVal = arr[i];
                maxIdx = i;
            }
        }
        return maxIdx;
    }

    /**
     * Cooldown calculation matching engine behavior.
     * Source: SPAARK G.java line 174-187, bot_spec.md Part 14.16
     */
    public static int cooldown(int paintAmount, int cooldownToAdd, int paintCapacity) {
        if (paintAmount * 2 > paintCapacity) {
            return cooldownToAdd;  // > 50% paint = no penalty
        }

        int paintPercentage = (int) Math.round(paintAmount * 100.0 / paintCapacity);

        return cooldownToAdd + (int) Math.round(
            cooldownToAdd * (GameConstants.INCREASED_COOLDOWN_INTERCEPT
                           + GameConstants.INCREASED_COOLDOWN_SLOPE * paintPercentage) / 100.0
        );
    }

    /**
     * Paint value per chip (trade-off constant).
     */
    public static double paintPerChips() {
        return PAINT_PER_CHIP;
    }

    /**
     * Paint value per cooldown (trade-off constant).
     */
    public static double paintPerCooldown() {
        return PAINT_PER_COOLDOWN;
    }
}
