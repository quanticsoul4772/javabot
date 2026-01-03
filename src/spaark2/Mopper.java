package spaark2;

import battlecode.common.*;

/**
 * Mopper: support unit.
 * Clears enemy paint, transfers paint to allies, swing attack.
 */
public class Mopper {

    public static void run(RobotController rc) throws GameActionException {
        Globals.init(rc);
        POI.init(rc);
        POI.scanNearby(rc);

        // Retreat check (same as soldier)
        if (shouldRetreat(rc)) {
            runRetreat(rc);
            return;
        }

        // Try actions in priority order
        if (rc.isActionReady()) {
            // Try swing attack if enemies nearby
            if (trySwingAttack(rc)) {
                // Swung successfully
            }
            // Try to mop enemy paint
            else if (tryMopPaint(rc)) {
                // Mopped successfully
            }
            // Transfer paint to low-paint ally
            else {
                tryTransferPaint(rc);
            }
        }

        // Move toward areas needing support
        moveTowardWork(rc);
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
     * Try swing attack if enemies are lined up.
     */
    private static boolean trySwingAttack(RobotController rc) throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(2, rc.getTeam().opponent());
        if (enemies.length < 2) return false;  // Only swing if hitting 2+

        // Check each direction for swing
        Direction[] dirs = Globals.DIRECTIONS;
        MapLocation myLoc = rc.getLocation();
        for (int d = dirs.length; --d >= 0;) {
            Direction dir = dirs[d];
            if (rc.canMopSwing(dir)) {
                // Count enemies in swing arc
                int count = 0;
                MapLocation[] swingTiles = getSwingTiles(myLoc, dir);
                for (int e = enemies.length; --e >= 0;) {
                    MapLocation enemyLoc = enemies[e].getLocation();
                    for (int t = swingTiles.length; --t >= 0;) {
                        if (enemyLoc.equals(swingTiles[t])) {
                            count++;
                            break;
                        }
                    }
                }
                if (count >= 2) {
                    rc.mopSwing(dir);
                    return true;
                }
            }
        }
        return false;
    }

    private static MapLocation[] getSwingTiles(MapLocation from, Direction d) {
        // Swing hits tiles in an arc in front
        return new MapLocation[] {
            from.add(d),
            from.add(d.rotateLeft()),
            from.add(d.rotateRight())
        };
    }

    /**
     * Mop the best enemy paint tile.
     */
    private static boolean tryMopPaint(RobotController rc) throws GameActionException {
        MapInfo[] nearby = rc.senseNearbyMapInfos(2);  // Mop range = 2

        for (int i = nearby.length; --i >= 0;) {
            MapInfo info = nearby[i];
            if (info.getPaint().isEnemy()) {
                MapLocation loc = info.getMapLocation();
                if (rc.canAttack(loc)) {
                    rc.attack(loc);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Transfer paint to a low-paint ally.
     */
    private static boolean tryTransferPaint(RobotController rc) throws GameActionException {
        int myPaint = rc.getPaint();
        if (myPaint < 100) return false;  // Keep some for ourselves

        RobotInfo[] allies = rc.senseNearbyRobots(2, rc.getTeam());
        RobotInfo neediest = null;
        int lowestPaint = Integer.MAX_VALUE;

        for (int i = allies.length; --i >= 0;) {
            RobotInfo ally = allies[i];
            // Only transfer to mobile units (not towers)
            if (ally.getType().isTowerType()) continue;

            int paintAmt = ally.getPaintAmount();
            if (paintAmt < lowestPaint && paintAmt < 50) {
                lowestPaint = paintAmt;
                neediest = ally;
            }
        }

        if (neediest != null && rc.canTransferPaint(neediest.getLocation(), 50)) {
            rc.transferPaint(neediest.getLocation(), 50);
            return true;
        }
        return false;
    }

    /**
     * Move toward enemy paint or allies to support.
     */
    private static void moveTowardWork(RobotController rc) throws GameActionException {
        if (!rc.isMovementReady()) return;

        MapLocation myLoc = rc.getLocation();
        Direction bestDir = null;
        int bestScore = Integer.MIN_VALUE;

        Direction[] dirs = Globals.DIRECTIONS;
        Team myTeam = rc.getTeam();
        for (int i = dirs.length; --i >= 0;) {
            Direction d = dirs[i];
            if (!rc.canMove(d)) continue;

            MapLocation newLoc = myLoc.add(d);
            int score = 0;

            // Score by work available
            for (int j = dirs.length; --j >= 0;) {
                MapLocation check = newLoc.add(dirs[j]);
                if (rc.canSenseLocation(check)) {
                    MapInfo info = rc.senseMapInfo(check);
                    if (info.getPaint().isEnemy()) score += 5;  // Enemy paint to mop
                }
            }

            // Prefer being near allies (support role)
            RobotInfo[] nearbyAllies = rc.senseNearbyRobots(newLoc, 4, myTeam);
            score += nearbyAllies.length;

            // Avoid dangerous tiles
            MapInfo tileInfo = rc.senseMapInfo(newLoc);
            if (tileInfo.getPaint().isEnemy()) score -= 3;

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
