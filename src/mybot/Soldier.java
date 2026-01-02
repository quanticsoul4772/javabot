package mybot;

import battlecode.common.*;

/**
 * Soldier behavior using Priority Chain Pattern.
 *
 * Priorities are processed in order - first match wins (early return).
 * Easy to reorder by moving code blocks.
 */
public class Soldier {

    private static MapLocation targetRuin = null;

    // Thresholds (tune these during competition)
    private static final int HEALTH_CRITICAL = 20;
    private static final int PAINT_LOW = 50;
    private static final int WEAK_ENEMY_HEALTH = 40;
    private static final int EARLY_GAME_ROUNDS = 100;

    public static void run(RobotController rc) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        int round = rc.getRoundNum();

        // ===== PRIORITY 0: SURVIVAL =====
        if (rc.getHealth() < HEALTH_CRITICAL) {
            retreat(rc);
            return;
        }

        // ===== PRIORITY 1: CRITICAL ALERTS (from communication) =====
        MapLocation alertedTower = Comms.getLocationFromMessage(rc, Comms.MessageType.PAINT_TOWER_DANGER);
        if (alertedTower != null) {
            rc.setIndicatorString("P1: Responding to tower alert!");
            Navigation.moveTo(rc, alertedTower);
            Utils.tryPaintCurrent(rc);
            return;
        }

        // ===== PRIORITY 2: DEFEND PAINT TOWERS =====
        RobotInfo towerUnderAttack = Utils.findPaintTowerUnderAttack(rc);
        if (towerUnderAttack != null) {
            defendPaintTower(rc, towerUnderAttack);
            return;
        }

        // ===== PRIORITY 3: RESUPPLY =====
        if (rc.getPaint() < PAINT_LOW) {
            retreatForPaint(rc);
            return;
        }

        // ===== PRIORITY 4: OPPORTUNISTIC KILLS =====
        RobotInfo weakEnemy = findWeakEnemy(rc);
        if (weakEnemy != null && canKill(rc, weakEnemy)) {
            rc.setIndicatorString("P4: Finishing weak enemy!");
            if (rc.canAttack(weakEnemy.getLocation())) {
                rc.attack(weakEnemy.getLocation());
            }
            Navigation.moveTo(rc, weakEnemy.getLocation());
            return;
        }

        // ===== PRIORITY 5: EARLY GAME / RUSH DEFENSE =====
        boolean rushAlert = Comms.hasMessageOfType(rc, Comms.MessageType.RUSH_ALERT);
        if (round < EARLY_GAME_ROUNDS || rushAlert) {
            RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
            if (enemies.length > 0) {
                rc.setIndicatorString("P5: Early game defense!");
                engageEnemy(rc, Utils.closestRobot(myLoc, enemies));
                return;
            }
            // Stay near base
            RobotInfo nearestTower = Utils.findNearestPaintTower(rc);
            if (nearestTower != null && myLoc.distanceSquaredTo(nearestTower.getLocation()) > 100) {
                Navigation.moveTo(rc, nearestTower.getLocation());
                Utils.tryPaintCurrent(rc);
                return;
            }
        }

        // ===== PRIORITY 6: TOWER BUILDING =====
        MapLocation[] ruins = rc.senseNearbyRuins(-1);
        MapLocation bestRuin = findBuildableRuin(rc, ruins);
        if (bestRuin != null) {
            targetRuin = bestRuin;
            handleTowerBuilding(rc, bestRuin);
            return;
        }

        // ===== PRIORITY 7: COMBAT =====
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if (enemies.length > 0) {
            RobotInfo target = Utils.closestRobot(myLoc, enemies);
            if (target != null) {
                Comms.reportEnemy(rc, target.getLocation(), enemies.length);
                engageEnemy(rc, target);
                return;
            }
        }

        // ===== PRIORITY 8: DEFAULT - EXPLORE & PAINT =====
        exploreAndPaint(rc);
    }

    // ==================== HELPER METHODS ====================

    /**
     * Find an enemy with low health that we can finish off.
     */
    private static RobotInfo findWeakEnemy(RobotController rc) throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        RobotInfo weakest = null;
        int lowestHealth = WEAK_ENEMY_HEALTH + 1;

        for (RobotInfo enemy : enemies) {
            if (enemy.getHealth() < lowestHealth) {
                lowestHealth = enemy.getHealth();
                weakest = enemy;
            }
        }
        return weakest;
    }

    /**
     * Check if we can kill this enemy this turn (in attack range).
     */
    private static boolean canKill(RobotController rc, RobotInfo enemy) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        int dist = myLoc.distanceSquaredTo(enemy.getLocation());
        // Soldier attack range is 9 (action radius)
        return dist <= rc.getType().actionRadiusSquared && rc.canAttack(enemy.getLocation());
    }

    /**
     * Emergency retreat when health is critical.
     */
    private static void retreat(RobotController rc) throws GameActionException {
        rc.setIndicatorString("P0: CRITICAL HEALTH - RETREATING!");

        // Find nearest ally tower
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo ally : allies) {
            if (ally.getType().isTowerType()) {
                Navigation.moveTo(rc, ally.getLocation());
                return;
            }
        }

        // Move away from enemies
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
     * Defend a Paint Tower under attack.
     */
    private static void defendPaintTower(RobotController rc, RobotInfo paintTower) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        MapLocation towerLoc = paintTower.getLocation();

        rc.setIndicatorString("P2: DEFENDING PAINT TOWER!");
        rc.setIndicatorLine(myLoc, towerLoc, 255, 0, 0);

        // Find closest enemy to the tower
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        RobotInfo threat = null;
        int closestDist = Integer.MAX_VALUE;

        for (RobotInfo enemy : enemies) {
            int dist = towerLoc.distanceSquaredTo(enemy.getLocation());
            if (dist < closestDist) {
                closestDist = dist;
                threat = enemy;
            }
        }

        if (threat != null) {
            if (rc.canAttack(threat.getLocation())) {
                rc.attack(threat.getLocation());
            }
            Navigation.moveTo(rc, threat.getLocation());
        } else {
            Navigation.moveTo(rc, towerLoc);
        }

        Utils.tryPaintCurrent(rc);
    }

    /**
     * Engage an enemy with paint-aware movement.
     */
    private static void engageEnemy(RobotController rc, RobotInfo enemy) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        MapLocation enemyLoc = enemy.getLocation();

        rc.setIndicatorString("P7: Engaging " + enemy.getType());
        rc.setIndicatorLine(myLoc, enemyLoc, 255, 128, 0);

        if (rc.canAttack(enemyLoc)) {
            rc.attack(enemyLoc);
        }

        // Move toward enemy, prefer painted tiles
        Direction toEnemy = myLoc.directionTo(enemyLoc);
        Direction[] tryDirs = {toEnemy, toEnemy.rotateLeft(), toEnemy.rotateRight()};

        MapLocation bestMove = null;
        int bestScore = Integer.MIN_VALUE;

        for (Direction dir : tryDirs) {
            if (rc.canMove(dir)) {
                MapLocation newLoc = myLoc.add(dir);
                int score = Utils.scoreTile(rc, newLoc);
                score += 20 - newLoc.distanceSquaredTo(enemyLoc); // Closer is better
                if (score > bestScore) {
                    bestScore = score;
                    bestMove = newLoc;
                }
            }
        }

        if (bestMove != null) {
            rc.move(myLoc.directionTo(bestMove));
        }

        Utils.tryPaintCurrent(rc);
    }

    /**
     * Find a ruin without a tower.
     */
    private static MapLocation findBuildableRuin(RobotController rc, MapLocation[] ruins) throws GameActionException {
        if (ruins == null || ruins.length == 0) return null;

        MapLocation myLoc = rc.getLocation();
        MapLocation best = null;
        int bestDist = Integer.MAX_VALUE;

        for (MapLocation ruin : ruins) {
            RobotInfo robot = rc.senseRobotAtLocation(ruin);
            if (robot != null) continue;

            int dist = myLoc.distanceSquaredTo(ruin);
            if (dist < bestDist) {
                bestDist = dist;
                best = ruin;
            }
        }

        return best;
    }

    /**
     * Handle tower building at a ruin.
     */
    private static void handleTowerBuilding(RobotController rc, MapLocation ruin) throws GameActionException {
        MapLocation myLoc = rc.getLocation();

        rc.setIndicatorString("P6: Building tower at " + ruin);
        rc.setIndicatorLine(myLoc, ruin, 0, 255, 0);

        if (myLoc.distanceSquaredTo(ruin) > 2) {
            Navigation.moveTo(rc, ruin);
            Utils.tryPaintCurrent(rc);
            return;
        }

        if (rc.canMarkTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, ruin)) {
            rc.markTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, ruin);
        }

        MapInfo[] patternTiles = rc.senseNearbyMapInfos(ruin, 8);
        for (MapInfo tile : patternTiles) {
            PaintType mark = tile.getMark();
            PaintType paint = tile.getPaint();

            if (mark != PaintType.EMPTY && mark != paint) {
                boolean secondary = (mark == PaintType.ALLY_SECONDARY);
                if (rc.canAttack(tile.getMapLocation())) {
                    rc.attack(tile.getMapLocation(), secondary);
                    return;
                }
            }
        }

        if (rc.canCompleteTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, ruin)) {
            rc.completeTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, ruin);
            rc.setTimelineMarker("Tower built!", 0, 255, 0);
            targetRuin = null;
        }
    }

    /**
     * Default behavior: paint and explore.
     */
    private static void exploreAndPaint(RobotController rc) throws GameActionException {
        Utils.tryPaintCurrent(rc);

        MapLocation paintTarget = findUnpaintedTile(rc);
        if (paintTarget != null) {
            Navigation.moveTo(rc, paintTarget);
        } else {
            Utils.tryMoveRandom(rc);
        }

        Utils.tryPaintCurrent(rc);
        rc.setIndicatorString("P8: Exploring");
    }

    /**
     * Find nearest unpainted or enemy-painted tile.
     */
    private static MapLocation findUnpaintedTile(RobotController rc) throws GameActionException {
        MapInfo[] tiles = rc.senseNearbyMapInfos();
        MapLocation myLoc = rc.getLocation();
        MapLocation best = null;
        int bestDist = Integer.MAX_VALUE;

        for (MapInfo tile : tiles) {
            if (!tile.isPassable()) continue;

            PaintType paint = tile.getPaint();
            if (paint.isEnemy() || paint == PaintType.EMPTY) {
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
     * Retreat toward ally tower for paint refill.
     */
    private static void retreatForPaint(RobotController rc) throws GameActionException {
        rc.setIndicatorString("P3: LOW PAINT - retreating");

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
}
