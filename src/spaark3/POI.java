package spaark3;

import battlecode.common.*;

/**
 * Points of Interest system - tracks tower locations globally.
 * Enables coordination without explicit communication.
 */
public class POI {

    // Tower storage (max 144 towers on a 60x60 map)
    private static final int MAX_TOWERS = 144;
    private static int[] towerX = new int[MAX_TOWERS];
    private static int[] towerY = new int[MAX_TOWERS];
    private static int[] towerTeam = new int[MAX_TOWERS];  // 0=neutral, 1=ally, 2=enemy
    private static int[] towerType = new int[MAX_TOWERS];  // UnitType ordinal
    private static int towerCount = 0;

    // Throttling
    private static int lastScanRound = -20;
    private static final int SCAN_INTERVAL = 10;

    /**
     * Update POI system - call every turn.
     * Throttled to save bytecode.
     */
    public static void update() throws GameActionException {
        // Update our own tower if we are one
        if (G.type.isTowerType()) {
            updateTower(G.me, G.team, G.type);
        }

        // Scan nearby only every SCAN_INTERVAL rounds
        if (G.round - lastScanRound >= SCAN_INTERVAL) {
            scanNearby();
            lastScanRound = G.round;
        }

        // Update tower counts
        countTowers();
    }

    /**
     * Scan nearby ruins and towers to update POI.
     * Uses lazy-loaded allies/enemies to avoid duplicate sensing.
     */
    private static void scanNearby() throws GameActionException {
        // Scan ally towers
        RobotInfo[] allies = G.getAllies();
        for (int i = allies.length; --i >= 0;) {
            RobotInfo robot = allies[i];
            if (robot.type.isTowerType()) {
                updateTower(robot.location, robot.team, robot.type);
            }
        }

        // Scan enemy towers
        RobotInfo[] enemies = G.getEnemies();
        for (int i = enemies.length; --i >= 0;) {
            RobotInfo robot = enemies[i];
            if (robot.type.isTowerType()) {
                updateTower(robot.location, robot.team, robot.type);
            }
        }
    }

    /**
     * Add or update a tower in our knowledge base.
     */
    public static void updateTower(MapLocation loc, Team team, UnitType type) {
        int x = loc.x;
        int y = loc.y;
        int teamVal = team == Team.NEUTRAL ? 0 : (team == G.team ? 1 : 2);
        int typeVal = type != null ? type.ordinal() : -1;

        // Check if we already know this tower
        for (int i = towerCount; --i >= 0;) {
            if (towerX[i] == x && towerY[i] == y) {
                // Update existing entry
                towerTeam[i] = teamVal;
                towerType[i] = typeVal;
                return;
            }
        }

        // Add new tower
        if (towerCount < MAX_TOWERS) {
            towerX[towerCount] = x;
            towerY[towerCount] = y;
            towerTeam[towerCount] = teamVal;
            towerType[towerCount] = typeVal;
            towerCount++;
        }
    }

    /**
     * Count ally towers by type for spawn weight calculations.
     */
    private static void countTowers() {
        G.numTowers = 0;
        G.allyPaintTowers = 0;
        G.allyMoneyTowers = 0;
        G.allyDefenseTowers = 0;

        for (int i = towerCount; --i >= 0;) {
            if (towerTeam[i] == 1) {  // Ally tower
                G.numTowers++;
                int type = towerType[i];
                // Check tower type (paint towers are ordinals 3-5, money 6-8, defense 9-11)
                if (type >= 3 && type <= 5) {
                    G.allyPaintTowers++;
                } else if (type >= 6 && type <= 8) {
                    G.allyMoneyTowers++;
                } else if (type >= 9 && type <= 11) {
                    G.allyDefenseTowers++;
                }
            }
        }
    }

    /**
     * Find nearest ally paint tower for retreating.
     */
    public static MapLocation findNearestAllyPaintTower() {
        MapLocation best = null;
        int bestDist = Integer.MAX_VALUE;

        for (int i = towerCount; --i >= 0;) {
            if (towerTeam[i] == 1) {
                int type = towerType[i];
                // Paint towers are ordinals 3-5
                if (type >= 3 && type <= 5) {
                    MapLocation loc = new MapLocation(towerX[i], towerY[i]);
                    int dist = G.me.distanceSquaredTo(loc);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = loc;
                    }
                }
            }
        }

        return best;
    }

    /**
     * Find nearest ally tower of any type.
     */
    public static MapLocation findNearestAllyTower() {
        MapLocation best = null;
        int bestDist = Integer.MAX_VALUE;

        for (int i = towerCount; --i >= 0;) {
            if (towerTeam[i] == 1) {
                MapLocation loc = new MapLocation(towerX[i], towerY[i]);
                int dist = G.me.distanceSquaredTo(loc);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = loc;
                }
            }
        }

        return best;
    }

    // Cached symmetry prediction (computed once)
    private static MapLocation cachedEnemyPrediction = null;
    private static int lastPredictionRound = -100;

    /**
     * Find nearest enemy tower for attacking.
     * Uses cached symmetry prediction if no enemy towers are known.
     */
    public static MapLocation findNearestEnemyTower() {
        MapLocation best = null;
        int bestDist = Integer.MAX_VALUE;

        // First check known enemy towers
        for (int i = towerCount; --i >= 0;) {
            if (towerTeam[i] == 2) {  // Enemy
                MapLocation loc = new MapLocation(towerX[i], towerY[i]);
                int dist = G.me.distanceSquaredTo(loc);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = loc;
                }
            }
        }

        // If found known enemy, return it
        if (best != null) return best;

        // Use cached prediction if recent (within 20 rounds)
        if (cachedEnemyPrediction != null && G.round - lastPredictionRound < 20) {
            return cachedEnemyPrediction;
        }

        // Compute symmetry prediction once per 20 rounds
        for (int i = towerCount; --i >= 0;) {
            if (towerTeam[i] == 1) {  // Ally
                // Rotational symmetry (180 degrees)
                int predX = G.mapWidth - 1 - towerX[i];
                int predY = G.mapHeight - 1 - towerY[i];
                int dx = predX - G.me.x;
                int dy = predY - G.me.y;
                int dist = dx * dx + dy * dy;
                if (dist < bestDist) {
                    bestDist = dist;
                    best = new MapLocation(predX, predY);
                }
            }
        }

        // Cache the result
        cachedEnemyPrediction = best;
        lastPredictionRound = G.round;
        return best;
    }

    /**
     * Find nearest enemy PAINT tower - highest priority target.
     * Returns null if no enemy paint tower is known.
     */
    public static MapLocation findNearestEnemyPaintTower() {
        MapLocation best = null;
        int bestDist = Integer.MAX_VALUE;

        for (int i = towerCount; --i >= 0;) {
            if (towerTeam[i] == 2) {  // Enemy
                int type = towerType[i];
                // Paint towers are ordinals 3-5
                if (type >= 3 && type <= 5) {
                    MapLocation loc = new MapLocation(towerX[i], towerY[i]);
                    int dist = G.me.distanceSquaredTo(loc);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = loc;
                    }
                }
            }
        }

        return best;
    }

    /**
     * Find nearest neutral ruin for building.
     */
    public static MapLocation findNearestNeutralRuin() {
        MapLocation best = null;
        int bestDist = Integer.MAX_VALUE;

        for (int i = towerCount; --i >= 0;) {
            if (towerTeam[i] == 0) {  // Neutral
                MapLocation loc = new MapLocation(towerX[i], towerY[i]);
                int dist = G.me.distanceSquaredTo(loc);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = loc;
                }
            }
        }

        return best;
    }

    /**
     * Check if a location has a known tower.
     */
    public static boolean hasTower(MapLocation loc) {
        int x = loc.x;
        int y = loc.y;
        for (int i = towerCount; --i >= 0;) {
            if (towerX[i] == x && towerY[i] == y && towerTeam[i] != 0) {
                return true;
            }
        }
        return false;
    }
}
