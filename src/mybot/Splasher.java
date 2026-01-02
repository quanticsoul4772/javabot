package mybot;

import battlecode.common.*;

/**
 * Splasher behavior using Priority Chain Pattern.
 *
 * Priorities are processed in order - first match wins (early return).
 * Splashers excel at: area paint attacks, territory control.
 */
public class Splasher {

    // Thresholds (tune these during competition)
    private static final int HEALTH_CRITICAL = 30;
    private static final int PAINT_LOW = 100;
    private static final int SPLASH_HIGH_VALUE = 5;   // Great target
    private static final int SPLASH_MEDIUM_VALUE = 3; // Worth attacking

    public static void run(RobotController rc) throws GameActionException {
        MapLocation myLoc = rc.getLocation();

        // ===== PRIORITY 0: SURVIVAL =====
        if (rc.getHealth() < HEALTH_CRITICAL) {
            retreat(rc);
            return;
        }

        // ===== PRIORITY 1: RESUPPLY =====
        if (rc.getPaint() < PAINT_LOW) {
            retreatForPaint(rc);
            return;
        }

        // ===== PRIORITY 2: HIGH-VALUE SPLASH (5+ tiles) =====
        MapLocation splashTarget = findBestSplashTarget(rc);
        if (splashTarget != null) {
            int score = scoreSplashLocation(rc, splashTarget);
            if (score >= SPLASH_HIGH_VALUE) {
                executeSplash(rc, splashTarget, score);
                return;
            }
        }

        // ===== PRIORITY 3: MEDIUM-VALUE SPLASH (3+ tiles) =====
        if (splashTarget != null) {
            int score = scoreSplashLocation(rc, splashTarget);
            if (score >= SPLASH_MEDIUM_VALUE) {
                executeSplash(rc, splashTarget, score);
                return;
            }
        }

        // ===== PRIORITY 4: ADVANCE TO ENEMY TERRITORY =====
        MapLocation enemyTerritory = findEnemyTerritory(rc);
        if (enemyTerritory != null) {
            rc.setIndicatorString("P4: Advancing to enemy territory");
            Navigation.moveTo(rc, enemyTerritory);
            return;
        }

        // ===== PRIORITY 5: DEFAULT - EXPLORE =====
        explore(rc);
    }

    // ==================== HELPER METHODS ====================

    /**
     * Execute a splash attack at target location.
     */
    private static void executeSplash(RobotController rc, MapLocation target, int score) throws GameActionException {
        MapLocation myLoc = rc.getLocation();

        if (rc.canAttack(target)) {
            rc.attack(target);
            rc.setIndicatorString("P2/3: SPLASH! Score=" + score);
            rc.setIndicatorDot(target, 0, 255, 255);
        } else {
            rc.setIndicatorString("P2/3: Moving to splash target");
            Navigation.moveTo(rc, target);
        }
    }

    /**
     * Find the best location to splash (maximize value).
     */
    private static MapLocation findBestSplashTarget(RobotController rc) throws GameActionException {
        MapInfo[] tiles = rc.senseNearbyMapInfos();
        MapLocation myLoc = rc.getLocation();

        MapLocation best = null;
        int bestScore = 0;

        for (MapInfo tile : tiles) {
            if (!tile.isPassable()) continue;

            MapLocation loc = tile.getMapLocation();
            int dist = myLoc.distanceSquaredTo(loc);

            // Must be in or near attack range
            if (dist > rc.getType().actionRadiusSquared + 4) continue;

            int score = scoreSplashLocation(rc, loc);
            if (score > bestScore) {
                bestScore = score;
                best = loc;
            }
        }

        return best;
    }

    /**
     * Score a location for splash attack.
     * Enemy paint = 2 points, neutral = 1 point.
     */
    private static int scoreSplashLocation(RobotController rc, MapLocation center) throws GameActionException {
        int score = 0;

        // Check 3x3 area around splash center
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                MapLocation loc = center.translate(dx, dy);
                if (!rc.canSenseLocation(loc)) continue;

                MapInfo info = rc.senseMapInfo(loc);
                if (!info.isPassable()) continue;

                PaintType paint = info.getPaint();
                if (paint.isEnemy()) {
                    score += 2;
                } else if (paint == PaintType.EMPTY) {
                    score += 1;
                }
            }
        }

        return score;
    }

    /**
     * Find enemy territory to contest.
     */
    private static MapLocation findEnemyTerritory(RobotController rc) throws GameActionException {
        MapInfo[] tiles = rc.senseNearbyMapInfos();
        MapLocation myLoc = rc.getLocation();
        MapLocation best = null;
        int bestDist = Integer.MAX_VALUE;

        for (MapInfo tile : tiles) {
            if (tile.getPaint().isEnemy()) {
                int dist = myLoc.distanceSquaredTo(tile.getMapLocation());
                if (dist < bestDist) {
                    bestDist = dist;
                    best = tile.getMapLocation();
                }
            }
        }

        return best;
    }

    /**
     * Emergency retreat when health is critical.
     */
    private static void retreat(RobotController rc) throws GameActionException {
        rc.setIndicatorString("P0: CRITICAL HEALTH - RETREATING!");

        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo ally : allies) {
            if (ally.getType().isTowerType()) {
                Navigation.moveTo(rc, ally.getLocation());
                return;
            }
        }

        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if (enemies.length > 0) {
            MapLocation myLoc = rc.getLocation();
            Direction awayFromEnemy = enemies[0].getLocation().directionTo(myLoc);
            if (rc.canMove(awayFromEnemy)) {
                rc.move(awayFromEnemy);
            }
        } else {
            Utils.tryMoveRandom(rc);
        }
    }

    /**
     * Retreat for paint resupply.
     */
    private static void retreatForPaint(RobotController rc) throws GameActionException {
        rc.setIndicatorString("P1: LOW PAINT - retreating");

        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo ally : allies) {
            if (ally.getType().isTowerType()) {
                Navigation.moveTo(rc, ally.getLocation());
                return;
            }
        }

        MapInfo[] tiles = rc.senseNearbyMapInfos();
        for (MapInfo tile : tiles) {
            if (tile.getPaint().isAlly()) {
                Navigation.moveTo(rc, tile.getMapLocation());
                return;
            }
        }

        Utils.tryMoveRandom(rc);
    }

    /**
     * Default exploration behavior.
     */
    private static void explore(RobotController rc) throws GameActionException {
        Utils.tryMoveRandom(rc);
        rc.setIndicatorString("P5: Exploring");
    }
}
