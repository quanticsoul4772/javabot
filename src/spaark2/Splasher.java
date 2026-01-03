package spaark2;

import battlecode.common.*;

/**
 * Splasher: territory control unit.
 * Only unit that can paint over enemy paint.
 */
public class Splasher {

    public static void run(RobotController rc) throws GameActionException {
        Globals.init(rc);
        POI.init(rc);
        POI.scanNearby(rc);

        // Retreat check (same as soldier)
        if (shouldRetreat(rc)) {
            runRetreat(rc);
            return;
        }

        // Try to attack
        if (rc.isActionReady()) {
            attackBestTarget(rc);
        }

        // Move toward enemy territory
        moveTowardEnemy(rc);
    }

    private static boolean shouldRetreat(RobotController rc) throws GameActionException {
        int paint = rc.getPaint();
        int chips = rc.getMoney();
        int allies = rc.senseNearbyRobots(-1, rc.getTeam()).length;

        return paint < Globals.RETREAT_PAINT &&
               chips < Globals.RETREAT_CHIPS &&
               allies < Globals.RETREAT_ALLIES;
    }

    private static void runRetreat(RobotController rc) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        Team myTeam = rc.getTeam();

        // First try POI system for global tower knowledge
        MapLocation tower = POI.findNearestAllyPaintTower(myLoc, myTeam);

        // Fall back to visible towers
        if (tower == null) {
            tower = findNearestPaintTower(rc);
        }

        if (tower != null) {
            Nav.moveTo(rc, tower);
        } else {
            Nav.moveTo(rc, new MapLocation(rc.getMapWidth() / 2, rc.getMapHeight() / 2));
        }
    }

    /**
     * Score all attackable locations and attack the best one.
     * Prioritize enemy paint and neutral tiles.
     */
    private static void attackBestTarget(RobotController rc) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        MapLocation best = null;
        int bestScore = 0;

        // Splasher attack range is 2 (splash affects area)
        MapInfo[] nearby = rc.senseNearbyMapInfos(9);  // Range squared = 9

        for (int i = nearby.length; --i >= 0;) {
            MapLocation loc = nearby[i].getMapLocation();
            if (!rc.canAttack(loc)) continue;

            int score = scoreSplashTarget(rc, loc);
            if (score > bestScore) {
                bestScore = score;
                best = loc;
            }
        }

        if (best != null && bestScore > 0) {
            rc.attack(best);
        }
    }

    /**
     * Score a potential splash target.
     * Higher score = better target.
     */
    private static int scoreSplashTarget(RobotController rc, MapLocation center) throws GameActionException {
        int score = 0;

        // Check 5x5 area (splash radius)
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                MapLocation loc = new MapLocation(center.x + dx, center.y + dy);
                if (!rc.canSenseLocation(loc)) continue;

                MapInfo info = rc.senseMapInfo(loc);

                // Score by paint state
                if (info.getPaint().isEnemy()) {
                    score += 3;  // Enemy paint is highest priority
                } else if (!info.getPaint().isAlly() && info.isPassable()) {
                    score += 1;  // Neutral paintable tile
                }
                // Ally paint = 0 (don't waste paint)
            }
        }

        // Check for enemy units in splash
        RobotInfo[] enemies = rc.senseNearbyRobots(center, 2, rc.getTeam().opponent());
        score += enemies.length * 2;

        return score;
    }

    /**
     * Move toward areas with enemy paint.
     */
    private static void moveTowardEnemy(RobotController rc) throws GameActionException {
        if (!rc.isMovementReady()) return;

        MapLocation myLoc = rc.getLocation();
        Direction bestDir = null;
        int bestScore = Integer.MIN_VALUE;

        // Check for enemies - use micro if present
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if (enemies.length > 0 && Micro.shouldEngage(rc)) {
            // Find nearest enemy for direction bias
            RobotInfo closest = null;
            int closestDist = Integer.MAX_VALUE;
            for (int i = enemies.length; --i >= 0;) {
                int dist = myLoc.distanceSquaredTo(enemies[i].getLocation());
                if (dist < closestDist) {
                    closestDist = dist;
                    closest = enemies[i];
                }
            }
            if (closest != null) {
                Nav.moveToWithMicro(rc, closest.getLocation());
                return;
            }
        }

        Direction[] dirs = Globals.DIRECTIONS;
        for (int i = dirs.length; --i >= 0;) {
            Direction d = dirs[i];
            if (!rc.canMove(d)) continue;

            MapLocation newLoc = myLoc.add(d);
            int score = 0;

            // Check surrounding tiles for enemy paint
            for (int j = dirs.length; --j >= 0;) {
                MapLocation check = newLoc.add(dirs[j]);
                if (rc.canSenseLocation(check)) {
                    MapInfo info = rc.senseMapInfo(check);
                    if (info.getPaint().isEnemy()) score += 3;
                    else if (!info.getPaint().isAlly()) score += 1;
                }
            }

            // Avoid ally paint (already covered)
            MapInfo tileInfo = rc.senseMapInfo(newLoc);
            if (tileInfo.getPaint().isAlly()) score -= 5;

            if (score > bestScore) {
                bestScore = score;
                bestDir = d;
            }
        }

        if (bestDir != null) {
            rc.move(bestDir);
        } else {
            Nav.moveRandom(rc);
        }
    }

    private static MapLocation findNearestPaintTower(RobotController rc) throws GameActionException {
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        MapLocation myLoc = rc.getLocation();
        MapLocation best = null;
        int bestDist = Integer.MAX_VALUE;

        for (int i = allies.length; --i >= 0;) {
            RobotInfo ally = allies[i];
            UnitType type = ally.getType();
            if (type == UnitType.LEVEL_ONE_PAINT_TOWER ||
                type == UnitType.LEVEL_TWO_PAINT_TOWER ||
                type == UnitType.LEVEL_THREE_PAINT_TOWER) {
                int dist = myLoc.distanceSquaredTo(ally.getLocation());
                if (dist < bestDist) {
                    bestDist = dist;
                    best = ally.getLocation();
                }
            }
        }
        return best;
    }
}
