package omnom;

import battlecode.common.*;

/**
 * Points of Interest - track towers.
 * Source: bot_spec.md Part 14, spaark3 POI.java
 */
public class POI {
    // Tower storage
    private static final int MAX_TOWERS = 144;
    private static int[] towerX = new int[MAX_TOWERS];
    private static int[] towerY = new int[MAX_TOWERS];
    private static int[] towerTeam = new int[MAX_TOWERS];  // 0=neutral, 1=ally, 2=enemy
    private static int[] towerType = new int[MAX_TOWERS];  // UnitType ordinal
    private static int towerCount = 0;

    // Explored bitfield (from spec Part 16.2)
    public static long[] explored = new long[60];

    // Symmetry tracking (from spec Part 16.1)
    public static boolean[] symmetry = new boolean[] { true, true, true };

    /**
     * Predict enemy tower location via symmetry.
     * Returns null if can't predict yet (multiple valid symmetries).
     */
    public static MapLocation predictEnemyTower() {
        // Count valid symmetries
        int validCount = 0;
        int validType = -1;
        for (int i = 0; i < 3; i++) {
            if (symmetry[i]) {
                validCount++;
                validType = i;
            }
        }

        // Need exactly 1 valid symmetry to predict
        if (validCount != 1) return null;

        // Find an ally tower and mirror it
        for (int i = towerCount; --i >= 0;) {
            if (towerTeam[i] == 1) {  // Ally tower
                MapLocation ally = new MapLocation(towerX[i], towerY[i]);
                return getOppositeMapLocation(ally, validType);
            }
        }

        return null;
    }

    /**
     * Get opposite location via symmetry type.
     */
    public static MapLocation getOppositeMapLocation(MapLocation loc, int symType) {
        switch (symType) {
            case 0: return new MapLocation(loc.x, G.mapHeight - 1 - loc.y);  // Horizontal
            case 1: return new MapLocation(G.mapWidth - 1 - loc.x, loc.y);   // Vertical
            case 2: return new MapLocation(G.mapWidth - 1 - loc.x, G.mapHeight - 1 - loc.y);  // Rotational
        }
        return loc;
    }

    /**
     * Update POI - called once per round in RobotPlayer.
     */
    public static void update() throws GameActionException {
        // Use cached allies/enemies from RobotPlayer
        RobotInfo[] allies = RobotPlayer.allAllies;
        RobotInfo[] enemies = RobotPlayer.allEnemies;

        // Scan allies for towers
        if (allies != null) {
            for (int i = allies.length; --i >= 0;) {
                if (allies[i].type.isTowerType()) {
                    updateTower(allies[i].location, allies[i].team, allies[i].type);
                }
            }
        }

        // Scan enemies for towers
        if (enemies != null) {
            for (int i = enemies.length; --i >= 0;) {
                if (enemies[i].type.isTowerType()) {
                    updateTower(enemies[i].location, enemies[i].team, enemies[i].type);
                }
            }
        }

        // Scan for neutral ruins (only once, robots sense them)
        MapLocation[] ruins = G.rc.senseNearbyRuins(-1);
        if (ruins != null) {
            for (int i = ruins.length; --i >= 0;) {
                // Check if ruin has tower already
                if (G.rc.canSenseRobotAtLocation(ruins[i])) {
                    RobotInfo r = G.rc.senseRobotAtLocation(ruins[i]);
                    if (r != null && r.type.isTowerType()) {
                        updateTower(ruins[i], r.team, r.type);
                    } else {
                        // Neutral ruin (no tower yet)
                        updateTower(ruins[i], Team.NEUTRAL, null);
                    }
                } else {
                    // Neutral ruin
                    updateTower(ruins[i], Team.NEUTRAL, null);
                }
            }
        }

        // Update tower counts
        countTowers();
    }

    /**
     * Add or update tower.
     */
    public static void updateTower(MapLocation loc, Team team, UnitType type) {
        int x = loc.x;
        int y = loc.y;
        int teamVal = team == Team.NEUTRAL ? 0 : (team == G.team ? 1 : 2);
        int typeVal = type != null ? type.ordinal() : -1;

        // Check if already known
        for (int i = towerCount; --i >= 0;) {
            if (towerX[i] == x && towerY[i] == y) {
                towerTeam[i] = teamVal;  // Update
                towerType[i] = typeVal;
                return;
            }
        }

        // Add new
        if (towerCount < MAX_TOWERS) {
            towerX[towerCount] = x;
            towerY[towerCount] = y;
            towerTeam[towerCount] = teamVal;
            towerType[towerCount] = typeVal;
            towerCount++;
        }
    }

    private static void countTowers() {
        G.numTowers = 0;
        G.allyPaintTowers = 0;
        G.allyMoneyTowers = 0;

        for (int i = towerCount; --i >= 0;) {
            if (towerTeam[i] == 1) {  // Ally
                G.numTowers++;
                int type = towerType[i];
                // Paint towers: ordinals 3-5
                if (type >= 3 && type <= 5) {
                    G.allyPaintTowers++;
                }
                // Money towers: ordinals 6-8
                else if (type >= 6 && type <= 8) {
                    G.allyMoneyTowers++;
                }
            }
        }
    }

    /**
     * Find nearest ally paint tower (type checking).
     */
    public static MapLocation findNearestAllyPaintTower() {
        MapLocation best = null;
        int bestDist = Integer.MAX_VALUE;

        for (int i = towerCount; --i >= 0;) {
            if (towerTeam[i] == 1) {  // Ally
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

    /**
     * Find nearest neutral ruin.
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
     * Mark tile as explored.
     */
    public static void markExplored(MapLocation loc) {
        if (loc.y >= 0 && loc.y < 60 && loc.x >= 0 && loc.x < 64) {
            explored[loc.y] |= (1L << loc.x);
        }
    }

    /**
     * Check if tile is explored.
     */
    public static boolean isExplored(MapLocation loc) {
        if (loc.y < 0 || loc.y >= 60 || loc.x < 0 || loc.x >= 64) return false;
        return ((explored[loc.y] >> loc.x) & 1) == 1;
    }

    /**
     * Find nearest enemy tower.
     */
    public static MapLocation findNearestEnemyTower() {
        MapLocation best = null;
        int bestDist = Integer.MAX_VALUE;

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

        return best;
    }

    /**
     * Find nearest enemy paint tower (highest priority target).
     */
    public static MapLocation findNearestEnemyPaintTower() {
        MapLocation best = null;
        int bestDist = Integer.MAX_VALUE;

        for (int i = towerCount; --i >= 0;) {
            if (towerTeam[i] == 2) {  // Enemy
                int type = towerType[i];
                // Paint towers: ordinals 3-5
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
}
