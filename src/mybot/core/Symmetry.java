package mybot.core;

import battlecode.common.*;

/**
 * Symmetry detection system for predicting enemy positions.
 *
 * Battlecode maps are always symmetric in one of three ways:
 * - Horizontal (reflection across X-axis)
 * - Vertical (reflection across Y-axis)
 * - Rotational (180-degree rotation around center)
 *
 * By detecting which symmetry the map uses, we can:
 * - Predict enemy spawn location from our spawn
 * - Find enemy towers before seeing them
 * - Navigate efficiently toward enemy territory
 *
 * Detection method: XOR comparison of explored tiles.
 * If tile at (x,y) differs from its symmetric counterpart, eliminate that symmetry type.
 */
public class Symmetry {

    // Symmetry type flags (can be OR'd together)
    public static final int HORIZONTAL = 1;   // Reflect across horizontal center
    public static final int VERTICAL = 2;     // Reflect across vertical center
    public static final int ROTATIONAL = 4;   // 180-degree rotation

    // All symmetries possible until proven otherwise
    private static int possibleSymmetry = HORIZONTAL | VERTICAL | ROTATIONAL;

    // Map dimensions
    private static int mapWidth = 60;
    private static int mapHeight = 60;

    // Track which symmetries we've confirmed are impossible
    private static boolean initialized = false;

    /**
     * Initialize symmetry system with map dimensions.
     */
    public static void init(RobotController rc) {
        if (initialized) return;
        mapWidth = rc.getMapWidth();
        mapHeight = rc.getMapHeight();
        possibleSymmetry = HORIZONTAL | VERTICAL | ROTATIONAL;
        initialized = true;
    }

    /**
     * Update symmetry detection when we learn about a tile.
     * Call this when you sense a tile's passability.
     *
     * @param loc The location we sensed
     * @param passable Whether the tile is passable
     */
    public static void updateFromTile(RobotController rc, MapLocation loc, boolean passable) {
        if (!initialized) init(rc);

        int x = loc.x;
        int y = loc.y;

        // Check horizontal symmetry (reflect across Y)
        if ((possibleSymmetry & HORIZONTAL) != 0) {
            int symY = mapHeight - 1 - y;
            MapLocation symLoc = new MapLocation(x, symY);
            if (canCheckSymmetricLocation(rc, symLoc)) {
                try {
                    MapInfo symInfo = rc.senseMapInfo(symLoc);
                    if (symInfo.isPassable() != passable) {
                        // Passability differs - eliminate horizontal symmetry
                        possibleSymmetry &= ~HORIZONTAL;
                    }
                } catch (GameActionException e) {
                    // Can't sense - don't update
                }
            }
        }

        // Check vertical symmetry (reflect across X)
        if ((possibleSymmetry & VERTICAL) != 0) {
            int symX = mapWidth - 1 - x;
            MapLocation symLoc = new MapLocation(symX, y);
            if (canCheckSymmetricLocation(rc, symLoc)) {
                try {
                    MapInfo symInfo = rc.senseMapInfo(symLoc);
                    if (symInfo.isPassable() != passable) {
                        // Passability differs - eliminate vertical symmetry
                        possibleSymmetry &= ~VERTICAL;
                    }
                } catch (GameActionException e) {
                    // Can't sense - don't update
                }
            }
        }

        // Check rotational symmetry (180-degree rotation)
        if ((possibleSymmetry & ROTATIONAL) != 0) {
            int symX = mapWidth - 1 - x;
            int symY = mapHeight - 1 - y;
            MapLocation symLoc = new MapLocation(symX, symY);
            if (canCheckSymmetricLocation(rc, symLoc)) {
                try {
                    MapInfo symInfo = rc.senseMapInfo(symLoc);
                    if (symInfo.isPassable() != passable) {
                        // Passability differs - eliminate rotational symmetry
                        possibleSymmetry &= ~ROTATIONAL;
                    }
                } catch (GameActionException e) {
                    // Can't sense - don't update
                }
            }
        }
    }

    /**
     * Check if we can sense the symmetric location.
     */
    private static boolean canCheckSymmetricLocation(RobotController rc, MapLocation loc) {
        return rc.canSenseLocation(loc);
    }

    /**
     * Get the best predicted enemy spawn location.
     * Returns null if we can't determine symmetry yet.
     */
    public static MapLocation predictEnemySpawn(MapLocation allySpawn) {
        if (!initialized) return null;

        // Prefer rotational (most common in Battlecode)
        if ((possibleSymmetry & ROTATIONAL) != 0) {
            return new MapLocation(
                mapWidth - 1 - allySpawn.x,
                mapHeight - 1 - allySpawn.y
            );
        }

        // Then horizontal
        if ((possibleSymmetry & HORIZONTAL) != 0) {
            return new MapLocation(
                allySpawn.x,
                mapHeight - 1 - allySpawn.y
            );
        }

        // Then vertical
        if ((possibleSymmetry & VERTICAL) != 0) {
            return new MapLocation(
                mapWidth - 1 - allySpawn.x,
                allySpawn.y
            );
        }

        // No symmetry determined - return center as fallback
        return new MapLocation(mapWidth / 2, mapHeight / 2);
    }

    /**
     * Get symmetric location of a known ally tower to find enemy tower.
     */
    public static MapLocation predictEnemyTower(MapLocation allyTower) {
        return predictEnemySpawn(allyTower);  // Same logic
    }

    /**
     * Get the direction toward predicted enemy territory.
     * Useful for early game exploration.
     */
    public static Direction getEnemyDirection(MapLocation from) {
        MapLocation predicted = predictEnemySpawn(from);
        if (predicted == null) {
            // Default to map center if unknown
            predicted = new MapLocation(mapWidth / 2, mapHeight / 2);
        }
        return from.directionTo(predicted);
    }

    /**
     * Get distance to predicted enemy spawn.
     */
    public static int distanceToEnemy(MapLocation from, MapLocation allySpawn) {
        MapLocation enemySpawn = predictEnemySpawn(allySpawn);
        if (enemySpawn == null) return Integer.MAX_VALUE;
        return from.distanceSquaredTo(enemySpawn);
    }

    /**
     * Check if a location is likely in enemy territory.
     * Uses symmetry to estimate territory division.
     */
    public static boolean isLikelyEnemyTerritory(MapLocation loc, MapLocation allySpawn) {
        MapLocation enemySpawn = predictEnemySpawn(allySpawn);
        if (enemySpawn == null) return false;

        // If closer to enemy spawn than ally spawn, probably enemy territory
        return loc.distanceSquaredTo(enemySpawn) < loc.distanceSquaredTo(allySpawn);
    }

    /**
     * Get current possible symmetry flags for debugging.
     */
    public static int getPossibleSymmetry() {
        return possibleSymmetry;
    }

    /**
     * Check if we've narrowed down to a single symmetry type.
     */
    public static boolean isSymmetryDetermined() {
        // Only one bit set means we know the symmetry
        return possibleSymmetry == HORIZONTAL ||
               possibleSymmetry == VERTICAL ||
               possibleSymmetry == ROTATIONAL;
    }

    /**
     * Get symmetry name for debugging.
     */
    public static String getSymmetryName() {
        if (possibleSymmetry == HORIZONTAL) return "HORIZONTAL";
        if (possibleSymmetry == VERTICAL) return "VERTICAL";
        if (possibleSymmetry == ROTATIONAL) return "ROTATIONAL";
        return "UNKNOWN(" + possibleSymmetry + ")";
    }
}
