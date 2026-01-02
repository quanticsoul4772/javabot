package mybot;

import battlecode.common.*;

/**
 * Splasher behavior: area-of-effect paint unit.
 *
 * Priorities:
 * 1. Splash attack groups of enemies
 * 2. Paint large unpainted areas
 * 3. Contest enemy territory
 */
public class Splasher {

    public static void run(RobotController rc) throws GameActionException {
        MapLocation myLoc = rc.getLocation();

        // Check paint level - splashers use more paint
        if (rc.getPaint() < 100) {
            retreatForPaint(rc);
            return;
        }

        // Find best splash target (maximize tiles painted)
        MapLocation splashTarget = findBestSplashTarget(rc);

        if (splashTarget != null) {
            int dist = myLoc.distanceSquaredTo(splashTarget);

            // In attack range?
            if (rc.canAttack(splashTarget)) {
                rc.attack(splashTarget);
                rc.setIndicatorString("SPLASH! at " + splashTarget);
                return;
            }

            // Move toward target
            Navigation.moveTo(rc, splashTarget);
            rc.setIndicatorString("Moving to splash target");
            return;
        }

        // Explore toward enemy territory
        MapLocation enemyArea = findEnemyTerritory(rc);
        if (enemyArea != null) {
            Navigation.moveTo(rc, enemyArea);
            rc.setIndicatorString("Advancing to enemy territory");
            return;
        }

        // Default: explore
        Utils.tryMoveRandom(rc);
        rc.setIndicatorString("Exploring");
    }

    /**
     * Find the best location to splash (maximize unpainted/enemy tiles hit).
     */
    private static MapLocation findBestSplashTarget(RobotController rc) throws GameActionException {
        MapInfo[] tiles = rc.senseNearbyMapInfos();
        MapLocation myLoc = rc.getLocation();

        MapLocation best = null;
        int bestScore = 0;

        // Check each tile as potential splash center
        for (MapInfo tile : tiles) {
            if (!tile.isPassable()) continue;

            MapLocation loc = tile.getMapLocation();
            int dist = myLoc.distanceSquaredTo(loc);

            // Must be in attack range
            if (dist > rc.getType().actionRadiusSquared) continue;

            // Score based on nearby unpainted/enemy tiles
            int score = scoreSplashLocation(rc, loc);
            if (score > bestScore) {
                bestScore = score;
                best = loc;
            }
        }

        // Only return if worth splashing (at least 3 tiles benefit)
        return bestScore >= 3 ? best : null;
    }

    /**
     * Score a location for splash attack (how many tiles would benefit).
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
                    score += 2; // Enemy paint worth more
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
     * Retreat for paint resupply.
     */
    private static void retreatForPaint(RobotController rc) throws GameActionException {
        rc.setIndicatorString("LOW PAINT - retreating");

        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo ally : allies) {
            if (ally.getType().isTowerType()) {
                Navigation.moveTo(rc, ally.getLocation());
                return;
            }
        }

        Utils.tryMoveRandom(rc);
    }
}
