package spaark3;

import battlecode.common.*;

/**
 * Mopper unit - support and utility.
 * Removes enemy paint, transfers paint to allies.
 *
 * Priority Chain:
 * 1. Swing attack (2+ enemies)
 * 2. Mop enemy paint
 * 3. Transfer paint to low allies
 * 4. Follow allies (Boids cohesion)
 */
public class Mopper {

    // Mopper modes
    private enum Mode {
        COMBAT,
        MOP,
        TRANSFER,
        FOLLOW,
        RETREAT
    }

    private static Mode mode = Mode.MOP;
    private static MapLocation retreatTarget = null;

    // Transfer threshold - ally needs paint below this
    private static final int TRANSFER_THRESHOLD = 100;
    // Paint to keep for ourselves
    private static final int SELF_RESERVE = 50;

    /**
     * Main mopper logic - called every turn.
     */
    public static void run() throws GameActionException {
        // Check for mode transitions
        updateMode();

        // Execute mode-specific behavior
        switch (mode) {
            case COMBAT:
                combat();
                break;
            case MOP:
                mop();
                break;
            case TRANSFER:
                transfer();
                break;
            case FOLLOW:
                follow();
                break;
            case RETREAT:
                retreat();
                break;
        }
    }

    /**
     * Update mode based on game state - priority chain.
     */
    private static void updateMode() throws GameActionException {
        // Check retreat condition
        if (G.shouldRetreat()) {
            if (mode != Mode.RETREAT) {
                mode = Mode.RETREAT;
                retreatTarget = POI.findNearestAllyPaintTower();
                if (retreatTarget == null) {
                    retreatTarget = POI.findNearestAllyTower();
                }
            }
            return;
        }

        // Exit retreat if healthy
        if (mode == Mode.RETREAT && G.paint > G.RETREAT_PAINT * 2) {
            mode = Mode.MOP;
            retreatTarget = null;
        }

        // Priority 1: Combat if 2+ enemies nearby (swing attack opportunity)
        RobotInfo[] enemies = G.getEnemies();
        if (enemies.length >= 2) {
            int nearbyCount = countNearbyEnemies(enemies, 9);  // Within swing range
            if (nearbyCount >= 2) {
                mode = Mode.COMBAT;
                return;
            }
        }

        // Priority 2: Mop if enemy paint nearby
        if (hasEnemyPaintNearby()) {
            mode = Mode.MOP;
            return;
        }

        // Priority 3: Transfer if ally needs paint
        if (G.paint > SELF_RESERVE && hasLowPaintAlly()) {
            mode = Mode.TRANSFER;
            return;
        }

        // Priority 4: Follow allies
        mode = Mode.FOLLOW;
    }

    /**
     * Combat mode - use swing attack on multiple enemies.
     */
    private static void combat() throws GameActionException {
        // Try swing attack (mop in direction with most enemies)
        RobotInfo[] enemies = G.getEnemies();
        Direction swingDir = findBestSwingDirection(enemies);
        if (swingDir != null && G.rc.canMopSwing(swingDir)) {
            G.rc.mopSwing(swingDir);
        }

        // Move with micro
        if (enemies.length > 0) {
            RobotInfo nearest = Micro.findNearestEnemy();
            if (nearest != null) {
                // Stay at swing range, not too close
                int dist = G.me.distanceSquaredTo(nearest.location);
                if (dist < 4) {
                    Nav.retreatFrom(nearest.location);
                } else if (dist > 9) {
                    Nav.moveToWithMicro(nearest.location);
                }
            }
        }
    }

    /**
     * Mop mode - remove enemy paint.
     */
    private static void mop() throws GameActionException {
        // Try to mop enemy paint
        if (tryMop()) {
            // Move after mopping
            MapLocation target = findNearestEnemyPaint();
            if (target != null) {
                Nav.moveTo(target);
            }
            return;
        }

        // Move toward enemy paint
        MapLocation target = findNearestEnemyPaint();
        if (target != null) {
            Nav.moveTo(target);
        } else {
            // No enemy paint, follow allies
            mode = Mode.FOLLOW;
        }

        // Try mop again after moving
        tryMop();
    }

    /**
     * Transfer mode - give paint to low allies.
     */
    private static void transfer() throws GameActionException {
        RobotInfo lowAlly = findLowPaintAlly();
        if (lowAlly == null) {
            mode = Mode.FOLLOW;
            return;
        }

        int dist = G.me.distanceSquaredTo(lowAlly.location);

        // Transfer if in range
        if (dist <= 2 && G.rc.canTransferPaint(lowAlly.location, 50)) {
            G.rc.transferPaint(lowAlly.location, 50);
        }

        // Move toward ally
        if (dist > 2) {
            Nav.moveTo(lowAlly.location);
        }
    }

    /**
     * Follow mode - stay with the group.
     */
    private static void follow() throws GameActionException {
        // Try opportunistic mopping
        tryMop();

        // Follow Boids vector toward allies
        Direction boidDir = Comm.followPaintGradient();
        if (boidDir != Direction.CENTER) {
            Nav.moveTo(G.me.add(boidDir).add(boidDir));
        } else {
            // No allies nearby, move toward map center
            Nav.moveTo(G.mapCenter);
        }
    }

    /**
     * Retreat mode - return to paint tower.
     */
    private static void retreat() throws GameActionException {
        if (retreatTarget == null) {
            retreatTarget = POI.findNearestAllyPaintTower();
            if (retreatTarget == null) {
                retreatTarget = POI.findNearestAllyTower();
            }
        }

        // Check if we're at tower
        if (retreatTarget != null && G.me.distanceSquaredTo(retreatTarget) <= 2) {
            // Wait to recharge
            if (G.paint > G.RETREAT_PAINT * 2) {
                mode = Mode.MOP;
                retreatTarget = null;
            }
            return;
        }

        // Move toward retreat target
        if (retreatTarget != null) {
            Nav.moveTo(retreatTarget);
        } else {
            // No tower found
            RobotInfo[] enemies = G.getEnemies();
            if (enemies.length > 0) {
                Nav.retreatFrom(enemies[0].location);
            } else {
                Nav.moveRandom();
            }
        }
    }

    /**
     * Find best direction for swing attack.
     * Returns direction with most enemies.
     */
    private static Direction findBestSwingDirection(RobotInfo[] enemies) {
        if (enemies.length == 0) return null;

        int[] counts = new int[8];

        for (int i = enemies.length; --i >= 0;) {
            RobotInfo enemy = enemies[i];
            Direction toEnemy = G.me.directionTo(enemy.location);
            int dist = G.me.distanceSquaredTo(enemy.location);

            // Swing hits in a cone - count enemies in each direction
            if (dist <= 9) {
                int idx = directionIndex(toEnemy);
                if (idx >= 0) {
                    counts[idx]++;
                    // Adjacent directions also count (cone effect)
                    counts[(idx + 1) & 7]++;
                    counts[(idx + 7) & 7]++;
                }
            }
        }

        int best = G.maxIndex(counts);
        return counts[best] >= 2 ? G.DIRECTIONS[best] : null;
    }

    /**
     * Get index for direction (0-7).
     */
    private static int directionIndex(Direction dir) {
        switch (dir) {
            case NORTH: return 0;
            case NORTHEAST: return 1;
            case EAST: return 2;
            case SOUTHEAST: return 3;
            case SOUTH: return 4;
            case SOUTHWEST: return 5;
            case WEST: return 6;
            case NORTHWEST: return 7;
            default: return -1;
        }
    }

    /**
     * Try to mop enemy paint at current or adjacent location.
     */
    private static boolean tryMop() throws GameActionException {
        // Try current location first
        MapInfo current = G.rc.senseMapInfo(G.me);
        if (current.getPaint().isEnemy() && G.rc.canAttack(G.me)) {
            G.rc.attack(G.me);
            return true;
        }

        // Try adjacent tiles
        for (int d = 8; --d >= 0;) {
            MapLocation adj = G.me.add(G.DIRECTIONS[d]);
            if (G.rc.canSenseLocation(adj)) {
                MapInfo info = G.rc.senseMapInfo(adj);
                if (info.getPaint().isEnemy() && G.rc.canAttack(adj)) {
                    G.rc.attack(adj);
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Check if enemy paint is nearby.
     */
    private static boolean hasEnemyPaintNearby() throws GameActionException {
        MapInfo[] tiles = G.getNearbyTiles();
        for (int i = tiles.length; --i >= 0;) {
            if (tiles[i].getPaint().isEnemy()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Find nearest enemy paint tile.
     */
    private static MapLocation findNearestEnemyPaint() throws GameActionException {
        MapLocation best = null;
        int bestDist = Integer.MAX_VALUE;

        MapInfo[] tiles = G.getNearbyTiles();
        for (int i = tiles.length; --i >= 0;) {
            MapInfo info = tiles[i];
            if (info.getPaint().isEnemy() && info.isPassable()) {
                int dist = G.me.distanceSquaredTo(info.getMapLocation());
                if (dist < bestDist) {
                    bestDist = dist;
                    best = info.getMapLocation();
                }
            }
        }

        return best;
    }

    /**
     * Check if any ally has low paint.
     */
    private static boolean hasLowPaintAlly() throws GameActionException {
        RobotInfo[] allies = G.getAllies();
        for (int i = allies.length; --i >= 0;) {
            RobotInfo ally = allies[i];
            if (!ally.type.isTowerType() && ally.paintAmount < TRANSFER_THRESHOLD) {
                return true;
            }
        }
        return false;
    }

    /**
     * Find ally with lowest paint for transfer.
     */
    private static RobotInfo findLowPaintAlly() throws GameActionException {
        RobotInfo best = null;
        int lowestPaint = TRANSFER_THRESHOLD;

        RobotInfo[] allies = G.getAllies();
        for (int i = allies.length; --i >= 0;) {
            RobotInfo ally = allies[i];
            if (!ally.type.isTowerType() && ally.paintAmount < lowestPaint) {
                lowestPaint = ally.paintAmount;
                best = ally;
            }
        }

        return best;
    }

    /**
     * Count enemies within given distance squared.
     */
    private static int countNearbyEnemies(RobotInfo[] enemies, int distSq) {
        int count = 0;
        for (int i = enemies.length; --i >= 0;) {
            if (G.me.distanceSquaredTo(enemies[i].location) <= distSq) {
                count++;
            }
        }
        return count;
    }
}
