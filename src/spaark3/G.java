package spaark3;

import battlecode.common.*;

/**
 * Global state singleton - SPAARK pattern with bytecode optimizations.
 * All frequently accessed data is static to minimize access cost.
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

    // Map info
    public static int mapWidth;
    public static int mapHeight;
    public static int mapArea;
    public static MapLocation mapCenter;

    // Sensing cache - all lazy-loaded to save bytecode
    public static RobotInfo[] allies;
    public static RobotInfo[] enemies;
    public static MapInfo[] nearbyTiles;
    public static MapLocation[] nearbyRuins;

    // Lazy-load flags
    private static boolean alliesLoaded = false;
    private static boolean enemiesLoaded = false;
    private static boolean tilesLoaded = false;
    private static boolean ruinsLoaded = false;

    // Paint state
    public static int paint;
    public static int chips;

    // Direction constants - pre-allocated
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

    // Spawn tracking for debt-based spawning
    public static int spawnedSoldiers = 0;
    public static int spawnedSplashers = 0;
    public static int spawnedMoppers = 0;
    public static double fracSoldiers = 0;
    public static double fracSplashers = 0;
    public static double fracMoppers = 0;

    // Controlled chaos factor (15% randomization)
    public static final double CHAOS_FACTOR = 0.15;

    // Retreat thresholds - conservative to keep units in fight
    public static final int RETREAT_PAINT = 50;
    public static final int RETREAT_CHIPS = 6000;
    public static final int RETREAT_ALLY_THRESHOLD = 9;

    // Tower counts for spawn weight adjustment
    public static int numTowers = 0;
    public static int allyPaintTowers = 0;
    public static int allyMoneyTowers = 0;
    public static int allyDefenseTowers = 0;

    /**
     * Initialize global state at start of each turn.
     * Must be called first in every robot's run loop.
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

        // Cache map info (only first time for this robot)
        if (mapCenter == null) {
            mapWidth = rc.getMapWidth();
            mapHeight = rc.getMapHeight();
            mapArea = mapWidth * mapHeight;
            mapCenter = new MapLocation(mapWidth / 2, mapHeight / 2);
        }

        // Reset all lazy-load flags (don't sense anything until needed)
        alliesLoaded = false;
        enemiesLoaded = false;
        tilesLoaded = false;
        ruinsLoaded = false;
        allies = null;
        enemies = null;
        nearbyTiles = null;
        nearbyRuins = null;
    }

    /**
     * Get nearby allies (lazy-loaded).
     */
    public static RobotInfo[] getAllies() throws GameActionException {
        if (!alliesLoaded) {
            allies = rc.senseNearbyRobots(-1, team);
            alliesLoaded = true;
        }
        return allies;
    }

    /**
     * Get nearby enemies (lazy-loaded).
     */
    public static RobotInfo[] getEnemies() throws GameActionException {
        if (!enemiesLoaded) {
            enemies = rc.senseNearbyRobots(-1, opponent);
            enemiesLoaded = true;
        }
        return enemies;
    }

    /**
     * Check if there are enemies nearby (senses if needed).
     */
    public static boolean hasEnemies() throws GameActionException {
        return getEnemies().length > 0;
    }

    /**
     * Get enemy count without full array (for bytecode efficiency).
     */
    public static int enemyCount() throws GameActionException {
        return getEnemies().length;
    }

    /**
     * Check if there are allies nearby (senses if needed).
     */
    public static boolean hasAllies() throws GameActionException {
        return getAllies().length > 0;
    }

    /**
     * Get nearby tiles (lazy-loaded to save bytecode).
     * Only call when actually needed.
     */
    public static MapInfo[] getNearbyTiles() throws GameActionException {
        if (!tilesLoaded) {
            nearbyTiles = rc.senseNearbyMapInfos();
            tilesLoaded = true;
        }
        return nearbyTiles;
    }

    /**
     * Get nearby ruins (lazy-loaded to save bytecode).
     * Only call when actually needed.
     */
    public static MapLocation[] getNearbyRuins() throws GameActionException {
        if (!ruinsLoaded) {
            nearbyRuins = rc.senseNearbyRuins(-1);
            ruinsLoaded = true;
        }
        return nearbyRuins;
    }

    /**
     * Check if we should apply chaos randomization.
     * Returns true 15% of the time.
     */
    public static boolean shouldRandomize() {
        return Random.nextDouble() < CHAOS_FACTOR;
    }

    /**
     * Check if robot should retreat based on paint, chips, and ally count.
     * SPAARK style: only retreat when ALL conditions are met (very conservative).
     */
    public static boolean shouldRetreat() throws GameActionException {
        if (paint >= RETREAT_PAINT) return false;
        if (chips >= RETREAT_CHIPS) return false;
        // Only retreat if few allies nearby too
        return getAllies().length < RETREAT_ALLY_THRESHOLD;
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
            case 0: return Direction.SOUTHWEST;  // (-1, -1)
            case 1: return Direction.WEST;       // (-1, 0)
            case 2: return Direction.NORTHWEST;  // (-1, 1)
            case 3: return Direction.SOUTH;      // (0, -1)
            case 4: return Direction.CENTER;     // (0, 0)
            case 5: return Direction.NORTH;      // (0, 1)
            case 6: return Direction.SOUTHEAST;  // (1, -1)
            case 7: return Direction.EAST;       // (1, 0)
            case 8: return Direction.NORTHEAST;  // (1, 1)
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
     * Find index of maximum value in double array.
     */
    public static int maxIndex(double[] arr) {
        int maxIdx = 0;
        double maxVal = arr[0];
        for (int i = arr.length; --i > 0;) {
            if (arr[i] > maxVal) {
                maxVal = arr[i];
                maxIdx = i;
            }
        }
        return maxIdx;
    }
}
