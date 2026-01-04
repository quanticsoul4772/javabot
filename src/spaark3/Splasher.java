package spaark3;

import battlecode.common.*;

/**
 * Splasher unit - critical for territory contest.
 * Only unit that can paint over enemy paint.
 *
 * Phase-specific behavior:
 * - EARLY: Limited spawning, support soldiers
 * - MID: Begin contesting territory
 * - LATE: Primary offensive unit
 * - ENDGAME: Maximum paint coverage push
 */
public class Splasher {

    // Splasher modes
    private enum Mode {
        SEEK_ENEMY_PAINT,
        ATTACK,
        RETREAT,
        SUPPORT
    }

    private static Mode mode = Mode.SEEK_ENEMY_PAINT;
    private static MapLocation retreatTarget = null;

    // Splash scoring constants
    private static final int ENEMY_PAINT_SCORE = 3;
    private static final int NEUTRAL_PAINT_SCORE = 1;
    private static final int ALLY_PAINT_PENALTY = -2;

    /**
     * Main splasher logic - called every turn.
     */
    public static void run() throws GameActionException {
        // Check for mode transitions
        updateMode();

        // Execute mode-specific behavior
        switch (mode) {
            case SEEK_ENEMY_PAINT:
                seekEnemyPaint();
                break;
            case ATTACK:
                attack();
                break;
            case RETREAT:
                retreat();
                break;
            case SUPPORT:
                support();
                break;
        }
    }

    /**
     * Update mode based on game state.
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
            mode = Mode.SEEK_ENEMY_PAINT;
            retreatTarget = null;
        }

        // Check for enemies to attack
        RobotInfo[] enemies = G.getEnemies();
        if (enemies.length > 0 && Micro.shouldEngage()) {
            RobotInfo nearestEnemy = Micro.findNearestEnemy();
            if (nearestEnemy != null && G.me.distanceSquaredTo(nearestEnemy.location) <= 16) {
                mode = Mode.ATTACK;
                return;
            }
        }

        // Check for enemy paint nearby
        if (hasEnemyPaintNearby()) {
            mode = Mode.SEEK_ENEMY_PAINT;
            return;
        }

        // In early game, support soldiers instead
        if (Phase.isEarly()) {
            mode = Mode.SUPPORT;
            return;
        }

        // Default to seeking enemy paint
        if (mode == Mode.ATTACK && enemies.length == 0) {
            mode = Mode.SEEK_ENEMY_PAINT;
        }
    }

    /**
     * Seek and splash enemy paint.
     */
    private static void seekEnemyPaint() throws GameActionException {
        // Try to splash first
        if (trySplash()) {
            // Move after splashing if possible
            Direction dir = Comm.followEnemyPaintGradient();
            if (dir != Direction.CENTER && G.rc.canMove(dir)) {
                G.rc.move(dir);
            }
            return;
        }

        // Move toward enemy paint
        MapLocation target = findBestSplashTarget();
        if (target != null) {
            Nav.moveTo(target);
        } else {
            // Follow enemy paint gradient
            Direction dir = Comm.followEnemyPaintGradient();
            if (dir != Direction.CENTER) {
                Nav.moveTo(G.me.add(dir).add(dir).add(dir));
            } else {
                // No enemy paint found, explore
                MapLocation exploreTarget = Comm.findUnexploredArea();
                if (exploreTarget != null) {
                    Nav.moveTo(exploreTarget);
                } else {
                    Nav.moveRandom();
                }
            }
        }

        // Try splash again after moving
        trySplash();
    }

    /**
     * Attack mode - focus on enemies.
     */
    private static void attack() throws GameActionException {
        // Try to attack enemies
        if (tryAttack()) {
            // Move with micro after attacking
            RobotInfo target = Micro.findNearestEnemy();
            if (target != null) {
                Nav.moveToWithMicro(target.location);
            }
            return;
        }

        // Move toward enemies
        RobotInfo nearest = Micro.findNearestEnemy();
        if (nearest != null) {
            Nav.moveToWithMicro(nearest.location);
        } else {
            mode = Mode.SEEK_ENEMY_PAINT;
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
                mode = Mode.SEEK_ENEMY_PAINT;
                retreatTarget = null;
            }
            return;
        }

        // Move toward retreat target
        if (retreatTarget != null) {
            Nav.moveTo(retreatTarget);
        } else {
            // No tower found, try to survive
            RobotInfo[] enemies = G.getEnemies();
            if (enemies.length > 0) {
                Nav.retreatFrom(enemies[0].location);
            } else {
                Nav.moveRandom();
            }
        }
    }

    /**
     * Support mode - follow soldiers in early game.
     */
    private static void support() throws GameActionException {
        // Try to splash any enemy paint encountered
        trySplash();

        // Follow nearest soldier ally
        MapLocation soldierLoc = findNearestSoldier();
        if (soldierLoc != null) {
            Nav.moveTo(soldierLoc);
        } else {
            // No soldiers found, move toward center
            Nav.moveTo(G.mapCenter);
        }
    }

    /**
     * Try to splash at best target location.
     */
    private static boolean trySplash() throws GameActionException {
        MapLocation best = null;
        int bestScore = 0;

        // Score all attackable locations
        MapInfo[] tiles = G.getNearbyTiles();
        for (int i = tiles.length; --i >= 0;) {
            MapInfo info = tiles[i];
            MapLocation loc = info.getMapLocation();

            // Check if we can attack here
            if (!G.rc.canAttack(loc)) continue;

            int score = scoreSplashTarget(loc);
            if (score > bestScore) {
                bestScore = score;
                best = loc;
            }
        }

        // Execute splash if worthwhile
        if (best != null && bestScore >= 3) {
            G.rc.attack(best);
            return true;
        }

        return false;
    }

    /**
     * Score a potential splash target location.
     * Higher score = better target.
     */
    private static int scoreSplashTarget(MapLocation center) throws GameActionException {
        int score = 0;

        // Score tiles in splash area (roughly 5x5)
        // Check the 9 tiles around center (3x3 approximation for bytecode)
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                MapLocation tile = center.translate(dx, dy);
                if (G.rc.canSenseLocation(tile)) {
                    MapInfo info = G.rc.senseMapInfo(tile);
                    if (!info.isPassable()) continue;

                    PaintType paint = info.getPaint();
                    if (paint.isEnemy()) {
                        score += ENEMY_PAINT_SCORE;
                    } else if (!paint.isAlly() && paint != PaintType.EMPTY) {
                        score += NEUTRAL_PAINT_SCORE;
                    } else if (paint == PaintType.EMPTY) {
                        score += NEUTRAL_PAINT_SCORE;
                    } else {
                        score += ALLY_PAINT_PENALTY;
                    }
                }
            }
        }

        return score;
    }

    /**
     * Find the best splash target location.
     */
    private static MapLocation findBestSplashTarget() throws GameActionException {
        MapLocation best = null;
        int bestScore = 0;

        MapInfo[] tiles = G.getNearbyTiles();
        for (int i = tiles.length; --i >= 0;) {
            MapInfo info = tiles[i];
            if (!info.isPassable()) continue;

            PaintType paint = info.getPaint();
            if (paint.isEnemy()) {
                int score = 10 - (G.me.distanceSquaredTo(info.getMapLocation()) >> 2);
                if (score > bestScore) {
                    bestScore = score;
                    best = info.getMapLocation();
                }
            }
        }

        return best;
    }

    /**
     * Try to attack an enemy.
     */
    private static boolean tryAttack() throws GameActionException {
        RobotInfo target = Micro.findBestTarget();
        if (target != null && G.rc.canAttack(target.location)) {
            G.rc.attack(target.location);
            return true;
        }
        return false;
    }

    /**
     * Check if there's enemy paint nearby.
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
     * Find nearest soldier ally to follow.
     */
    private static MapLocation findNearestSoldier() throws GameActionException {
        MapLocation best = null;
        int bestDist = Integer.MAX_VALUE;

        RobotInfo[] allies = G.getAllies();
        for (int i = allies.length; --i >= 0;) {
            RobotInfo ally = allies[i];
            if (ally.type == UnitType.SOLDIER) {
                int dist = G.me.distanceSquaredTo(ally.location);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = ally.location;
                }
            }
        }

        return best;
    }
}
