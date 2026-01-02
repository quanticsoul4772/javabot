package mybot;

import battlecode.common.*;

/**
 * Mopper behavior: utility unit for paint removal and support.
 *
 * Priorities:
 * 1. Mop enemy paint in our territory
 * 2. Support soldiers near enemies
 * 3. Clean up enemy paint generally
 */
public class Mopper {

    public static void run(RobotController rc) throws GameActionException {
        MapLocation myLoc = rc.getLocation();

        // Check paint level
        if (rc.getPaint() < 30) {
            retreatForPaint(rc);
            return;
        }

        // Find nearby enemies for mop swing
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());

        // Try mop swing if enemies nearby
        if (enemies.length > 0) {
            RobotInfo closest = Utils.closestRobot(myLoc, enemies);
            if (closest != null) {
                Direction toEnemy = myLoc.directionTo(closest.getLocation());
                if (rc.canMopSwing(toEnemy)) {
                    rc.mopSwing(toEnemy);
                    rc.setIndicatorString("Mop swing at enemies!");
                    return;
                }
                // Move closer to swing range
                Navigation.moveTo(rc, closest.getLocation());
                return;
            }
        }

        // Find enemy paint to clean
        MapLocation enemyPaint = findEnemyPaint(rc);
        if (enemyPaint != null) {
            rc.setIndicatorString("Cleaning enemy paint");
            rc.setIndicatorDot(enemyPaint, 255, 0, 0);

            int dist = myLoc.distanceSquaredTo(enemyPaint);
            if (dist <= 2 && rc.canAttack(enemyPaint)) {
                rc.attack(enemyPaint);
            } else {
                Navigation.moveTo(rc, enemyPaint);
            }
            return;
        }

        // Explore if nothing to do
        Utils.tryMoveRandom(rc);
        rc.setIndicatorString("Exploring");
    }

    /**
     * Find nearby enemy paint to clean up.
     */
    private static MapLocation findEnemyPaint(RobotController rc) throws GameActionException {
        MapInfo[] tiles = rc.senseNearbyMapInfos();
        MapLocation myLoc = rc.getLocation();
        MapLocation best = null;
        int bestDist = Integer.MAX_VALUE;

        for (MapInfo tile : tiles) {
            if (!tile.isPassable()) continue;

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
     * Retreat toward ally towers for paint.
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

        // Move toward ally paint
        MapInfo[] tiles = rc.senseNearbyMapInfos();
        for (MapInfo tile : tiles) {
            if (tile.getPaint().isAlly()) {
                Navigation.moveTo(rc, tile.getMapLocation());
                return;
            }
        }

        Utils.tryMoveRandom(rc);
    }
}
