package mybot.core;

import battlecode.common.*;

/**
 * Points of Interest (POI) tracking system.
 *
 * Implements SPAARK-style grid-based tracking with O(1) lookups:
 * - Grid chunks (5x5 tiles) for tower tracking
 * - Bitwise arrays for ruin and exploration tracking
 * - Message-based synchronization between units
 *
 * Max map size is 60x60, so we use 12x12 grid (60/5=12) and 60-bit longs.
 */
public class POI {

    // Grid dimensions: 60x60 map / 5 = 12x12 chunks
    private static final int CHUNK_SIZE = 5;
    private static final int GRID_SIZE = 12;

    // Tower tracking: towerGrid[chunkY][chunkX] = tower robot ID (0 = none/unknown)
    private static int[][] allyTowerGrid = new int[GRID_SIZE][GRID_SIZE];
    private static int[][] enemyTowerGrid = new int[GRID_SIZE][GRID_SIZE];

    // Ruin tracking: ruinGrid[chunkY][chunkX] = state
    // 0 = unknown, 1 = has unclaimed ruin, 2 = claimed/building, -1 = no ruin
    private static int[][] ruinGrid = new int[GRID_SIZE][GRID_SIZE];

    // Bitwise exploration tracking (1 bit per tile, 60 tiles per row)
    private static long[] explored = new long[60];

    // Bitwise passability tracking for symmetry detection
    private static long[] passable = new long[60];  // 1 = passable
    private static long[] impassable = new long[60]; // 1 = wall/lake

    // Map dimensions (set on init)
    private static int mapWidth = 60;
    private static int mapHeight = 60;

    // Last broadcast round (rate limiting)
    private static int lastBroadcastRound = 0;
    private static final int BROADCAST_COOLDOWN = 10;

    /**
     * Initialize POI system with map dimensions.
     */
    public static void init(RobotController rc) {
        mapWidth = rc.getMapWidth();
        mapHeight = rc.getMapHeight();
    }

    // ========== Tower Tracking ==========

    /**
     * Mark an ally tower at a location.
     */
    public static void markAllyTower(MapLocation loc, int robotId) {
        int cx = loc.x / CHUNK_SIZE;
        int cy = loc.y / CHUNK_SIZE;
        if (cx < GRID_SIZE && cy < GRID_SIZE) {
            allyTowerGrid[cy][cx] = robotId;
        }
    }

    /**
     * Mark an enemy tower at a location.
     */
    public static void markEnemyTower(MapLocation loc, int robotId) {
        int cx = loc.x / CHUNK_SIZE;
        int cy = loc.y / CHUNK_SIZE;
        if (cx < GRID_SIZE && cy < GRID_SIZE) {
            enemyTowerGrid[cy][cx] = robotId;
        }
    }

    /**
     * Check if we know about an ally tower in a chunk.
     */
    public static boolean hasAllyTowerInChunk(MapLocation loc) {
        int cx = loc.x / CHUNK_SIZE;
        int cy = loc.y / CHUNK_SIZE;
        if (cx >= GRID_SIZE || cy >= GRID_SIZE) return false;
        return allyTowerGrid[cy][cx] != 0;
    }

    /**
     * Check if we know about an enemy tower in a chunk.
     */
    public static boolean hasEnemyTowerInChunk(MapLocation loc) {
        int cx = loc.x / CHUNK_SIZE;
        int cy = loc.y / CHUNK_SIZE;
        if (cx >= GRID_SIZE || cy >= GRID_SIZE) return false;
        return enemyTowerGrid[cy][cx] != 0;
    }

    /**
     * Clear a tower location (tower destroyed).
     */
    public static void clearTower(MapLocation loc, boolean isAlly) {
        int cx = loc.x / CHUNK_SIZE;
        int cy = loc.y / CHUNK_SIZE;
        if (cx < GRID_SIZE && cy < GRID_SIZE) {
            if (isAlly) {
                allyTowerGrid[cy][cx] = 0;
            } else {
                enemyTowerGrid[cy][cx] = 0;
            }
        }
    }

    // ========== Ruin Tracking ==========

    /**
     * Mark a ruin at a location.
     * @param state 1 = unclaimed, 2 = claimed/building, -1 = no ruin
     */
    public static void markRuin(MapLocation loc, int state) {
        int cx = loc.x / CHUNK_SIZE;
        int cy = loc.y / CHUNK_SIZE;
        if (cx < GRID_SIZE && cy < GRID_SIZE) {
            ruinGrid[cy][cx] = state;
        }
    }

    /**
     * Get ruin state in a chunk.
     */
    public static int getRuinState(MapLocation loc) {
        int cx = loc.x / CHUNK_SIZE;
        int cy = loc.y / CHUNK_SIZE;
        if (cx >= GRID_SIZE || cy >= GRID_SIZE) return 0;
        return ruinGrid[cy][cx];
    }

    /**
     * Check if there's a known unclaimed ruin in a chunk.
     */
    public static boolean hasUnclaimedRuin(MapLocation loc) {
        return getRuinState(loc) == 1;
    }

    /**
     * Find nearest known unclaimed ruin.
     */
    public static MapLocation findNearestRuin(MapLocation from) {
        int bestDist = Integer.MAX_VALUE;
        MapLocation best = null;

        for (int cy = 0; cy < GRID_SIZE; cy++) {
            for (int cx = 0; cx < GRID_SIZE; cx++) {
                if (ruinGrid[cy][cx] == 1) {  // Unclaimed
                    // Center of chunk
                    int x = cx * CHUNK_SIZE + CHUNK_SIZE / 2;
                    int y = cy * CHUNK_SIZE + CHUNK_SIZE / 2;
                    if (x >= mapWidth) x = mapWidth - 1;
                    if (y >= mapHeight) y = mapHeight - 1;

                    MapLocation ruinLoc = new MapLocation(x, y);
                    int dist = from.distanceSquaredTo(ruinLoc);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = ruinLoc;
                    }
                }
            }
        }
        return best;
    }

    // ========== Exploration Tracking ==========

    /**
     * Mark a tile as explored.
     */
    public static void markExplored(MapLocation loc) {
        if (loc.y < 60 && loc.x < 60) {
            explored[loc.y] |= (1L << loc.x);
        }
    }

    /**
     * Check if a tile has been explored.
     */
    public static boolean isExplored(MapLocation loc) {
        if (loc.y >= 60 || loc.x >= 60) return false;
        return (explored[loc.y] & (1L << loc.x)) != 0;
    }

    /**
     * Mark tile passability for symmetry detection.
     */
    public static void markPassability(MapLocation loc, boolean isPassable) {
        if (loc.y < 60 && loc.x < 60) {
            if (isPassable) {
                passable[loc.y] |= (1L << loc.x);
            } else {
                impassable[loc.y] |= (1L << loc.x);
            }
        }
    }

    /**
     * Get passability data for symmetry checks.
     */
    public static long getPassableRow(int y) {
        if (y >= 60) return 0;
        return passable[y];
    }

    public static long getImpassableRow(int y) {
        if (y >= 60) return 0;
        return impassable[y];
    }

    /**
     * Find nearest unexplored zone center.
     */
    public static MapLocation findUnexploredZone(MapLocation from) {
        int bestDist = Integer.MAX_VALUE;
        MapLocation best = null;

        // Check each chunk
        for (int cy = 0; cy < mapHeight / CHUNK_SIZE; cy++) {
            for (int cx = 0; cx < mapWidth / CHUNK_SIZE; cx++) {
                // Check if chunk is mostly unexplored
                int unexploredCount = 0;
                int centerX = cx * CHUNK_SIZE + CHUNK_SIZE / 2;
                int centerY = cy * CHUNK_SIZE + CHUNK_SIZE / 2;

                for (int dy = 0; dy < CHUNK_SIZE && (cy * CHUNK_SIZE + dy) < mapHeight; dy++) {
                    int y = cy * CHUNK_SIZE + dy;
                    long row = explored[y];
                    for (int dx = 0; dx < CHUNK_SIZE && (cx * CHUNK_SIZE + dx) < mapWidth; dx++) {
                        int x = cx * CHUNK_SIZE + dx;
                        if ((row & (1L << x)) == 0) {
                            unexploredCount++;
                        }
                    }
                }

                // If mostly unexplored (>50% of tiles), consider it
                if (unexploredCount > (CHUNK_SIZE * CHUNK_SIZE) / 2) {
                    MapLocation center = new MapLocation(
                        Math.min(centerX, mapWidth - 1),
                        Math.min(centerY, mapHeight - 1)
                    );
                    int dist = from.distanceSquaredTo(center);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = center;
                    }
                }
            }
        }
        return best;
    }

    // ========== Sensing Integration ==========

    /**
     * Lightweight POI update - only track towers, skip expensive operations.
     * Call this periodically (not every turn) to save bytecode.
     */
    public static void updateFromSensors(RobotController rc) throws GameActionException {
        Team myTeam = rc.getTeam();

        // ONLY track towers - ruins and exploration tracked elsewhere
        RobotInfo[] nearbyRobots = rc.senseNearbyRobots(-1);
        for (RobotInfo robot : nearbyRobots) {
            if (robot.getType().isTowerType()) {
                if (robot.getTeam() == myTeam) {
                    markAllyTower(robot.getLocation(), robot.getID());
                } else {
                    markEnemyTower(robot.getLocation(), robot.getID());
                }
            }
        }

        // Mark our current location as explored (cheap)
        markExplored(rc.getLocation());
    }

    /**
     * Quick ruin tracking when we happen to see ruins.
     * Call only when you already have ruin data.
     */
    public static void updateRuins(RobotController rc, MapLocation[] ruins) throws GameActionException {
        for (MapLocation ruin : ruins) {
            RobotInfo robotOnRuin = rc.senseRobotAtLocation(ruin);
            if (robotOnRuin != null && robotOnRuin.getType().isTowerType()) {
                markRuin(ruin, -1);  // Tower built
            } else {
                markRuin(ruin, 1);  // Unclaimed
            }
        }
    }

    // ========== Message Broadcasting ==========

    /**
     * Check if we should broadcast POI info (rate limiting).
     */
    public static boolean shouldBroadcast(int currentRound) {
        return currentRound - lastBroadcastRound >= BROADCAST_COOLDOWN;
    }

    /**
     * Record that we broadcasted.
     */
    public static void recordBroadcast(int currentRound) {
        lastBroadcastRound = currentRound;
    }

    /**
     * Encode tower info for message payload.
     * Format: [8 bits: chunkX][8 bits: chunkY] in the 16-bit payload
     */
    public static int encodeTowerPayload(MapLocation towerLoc, boolean isAlly) {
        int cx = towerLoc.x / CHUNK_SIZE;
        int cy = towerLoc.y / CHUNK_SIZE;
        int allyFlag = isAlly ? 0x8000 : 0;  // High bit = ally
        return allyFlag | (cx << 8) | cy;
    }

    /**
     * Decode tower info from message payload.
     */
    public static void decodeTowerPayload(int payload, MapLocation msgLoc) {
        boolean isAlly = (payload & 0x8000) != 0;
        // Use the message location directly since it contains the tower position
        if (isAlly) {
            markAllyTower(msgLoc, 1);  // We don't know the ID, use placeholder
        } else {
            markEnemyTower(msgLoc, 1);
        }
    }

    /**
     * Encode ruin info for message payload.
     * Format: [8 bits: state][8 bits: reserved]
     */
    public static int encodeRuinPayload(int state) {
        return state & 0xFF;
    }

    /**
     * Decode ruin info from message payload.
     */
    public static void decodeRuinPayload(int payload, MapLocation msgLoc) {
        int state = payload & 0xFF;
        markRuin(msgLoc, state);
    }
}
