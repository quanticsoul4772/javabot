package mybot;

import battlecode.common.*;

/**
 * Soldier behavior: primary combat and territory control unit.
 *
 * Priorities (UPDATED based on winning strategies):
 * 0. CRITICAL: Defend Paint Towers under attack
 * 1. Early game: Stay near base to counter rushes
 * 2. Build towers on ruins
 * 3. Attack enemies in range
 * 4. Paint territory (prefer ally-painted tiles)
 * 5. Explore unpainted areas
 */
public class Soldier {

    private static MapLocation targetRuin = null;
    private static int turnsWithoutProgress = 0;

    // Early game defense - stay near towers for first N rounds
    private static final int EARLY_GAME_THRESHOLD = 100;

    public static void run(RobotController rc) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        int round = rc.getRoundNum();

        // ========== PHASE 6: Check for urgent messages ==========
        MapLocation alertedTower = Comms.getLocationFromMessage(rc, Comms.MessageType.PAINT_TOWER_DANGER);
        if (alertedTower != null) {
            // Rush to defend the alerted paint tower
            rc.setIndicatorString("Responding to PAINT TOWER DANGER!");
            Navigation.moveTo(rc, alertedTower);
            Utils.tryPaintCurrent(rc);
            return;
        }

        // ========== PHASE 1: CRITICAL - Defend Paint Towers ==========
        RobotInfo paintTowerUnderAttack = Utils.findPaintTowerUnderAttack(rc);
        if (paintTowerUnderAttack != null) {
            defendPaintTower(rc, paintTowerUnderAttack);
            return;
        }

        // ========== Check paint level - retreat if low ==========
        if (rc.getPaint() < 50) {
            retreatForPaint(rc);
            return;
        }

        // ========== PHASE 6: Check for rush alert ==========
        boolean rushAlerted = Comms.hasMessageOfType(rc, Comms.MessageType.RUSH_ALERT);

        // ========== PHASE 2: Early game - stay near base ==========
        if (round < EARLY_GAME_THRESHOLD || rushAlerted) {
            // Check for nearby enemies (rush detection)
            RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
            if (enemies.length > 0) {
                // Fight nearby enemies
                RobotInfo target = Utils.closestRobot(myLoc, enemies);
                if (target != null) {
                    engageEnemy(rc, target);
                    return;
                }
            }

            // Stay somewhat close to ally towers in early game
            RobotInfo nearestTower = Utils.findNearestPaintTower(rc);
            if (nearestTower != null) {
                int distToTower = myLoc.distanceSquaredTo(nearestTower.getLocation());
                // If too far from tower, move back
                if (distToTower > 100) { // ~10 tiles
                    Navigation.moveTo(rc, nearestTower.getLocation());
                    Utils.tryPaintCurrent(rc);
                    rc.setIndicatorString("Early game: staying near base");
                    return;
                }
            }
        }

        // ========== Normal behavior: Build towers ==========
        MapLocation[] ruins = rc.senseNearbyRuins(-1);
        MapLocation bestRuin = findBuildableRuin(rc, ruins);

        if (bestRuin != null) {
            targetRuin = bestRuin;
            turnsWithoutProgress = 0;
            handleTowerBuilding(rc, bestRuin);
            return;
        }

        // ========== Attack nearby enemies ==========
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if (enemies.length > 0) {
            RobotInfo target = Utils.closestRobot(myLoc, enemies);
            if (target != null) {
                // PHASE 6: Report enemy to nearby tower
                Comms.reportEnemy(rc, target.getLocation(), enemies.length);
                engageEnemy(rc, target);
                return;
            }
        }

        // ========== Paint and explore ==========
        Utils.tryPaintCurrent(rc);

        MapLocation paintTarget = findUnpaintedTile(rc);
        if (paintTarget != null) {
            Navigation.moveTo(rc, paintTarget);
        } else {
            Utils.tryMoveRandom(rc);
        }

        Utils.tryPaintCurrent(rc);
        rc.setIndicatorString("Exploring");
    }

    /**
     * PHASE 1: Defend a Paint Tower under attack.
     */
    private static void defendPaintTower(RobotController rc, RobotInfo paintTower) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        MapLocation towerLoc = paintTower.getLocation();

        rc.setIndicatorString("DEFENDING PAINT TOWER!");
        rc.setIndicatorLine(myLoc, towerLoc, 255, 0, 0);

        // Find enemies near the paint tower
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        RobotInfo threat = null;
        int closestToTower = Integer.MAX_VALUE;

        for (RobotInfo enemy : enemies) {
            int dist = towerLoc.distanceSquaredTo(enemy.getLocation());
            if (dist < closestToTower) {
                closestToTower = dist;
                threat = enemy;
            }
        }

        if (threat != null) {
            // Attack the threat if in range
            if (rc.canAttack(threat.getLocation())) {
                rc.attack(threat.getLocation());
            }
            // Move toward the threat
            Navigation.moveTo(rc, threat.getLocation());
        } else {
            // Move toward the paint tower
            Navigation.moveTo(rc, towerLoc);
        }

        Utils.tryPaintCurrent(rc);
    }

    /**
     * Engage an enemy: attack if possible, close distance if needed.
     */
    private static void engageEnemy(RobotController rc, RobotInfo enemy) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        MapLocation enemyLoc = enemy.getLocation();

        rc.setIndicatorString("Engaging enemy at " + enemyLoc);
        rc.setIndicatorLine(myLoc, enemyLoc, 255, 128, 0);

        // Attack if in range
        if (rc.canAttack(enemyLoc)) {
            rc.attack(enemyLoc);
        }

        // Move toward enemy (but stay on paint if possible)
        // PHASE 3: Try to stay on painted tiles while fighting
        Direction toEnemy = myLoc.directionTo(enemyLoc);
        MapLocation bestMove = null;
        int bestScore = Integer.MIN_VALUE;

        // Check direct and adjacent directions
        Direction[] tryDirs = {toEnemy, toEnemy.rotateLeft(), toEnemy.rotateRight()};
        for (Direction dir : tryDirs) {
            if (rc.canMove(dir)) {
                MapLocation newLoc = myLoc.add(dir);
                int score = Utils.scoreTile(rc, newLoc);
                // Bonus for getting closer to enemy
                score += 20 - myLoc.add(dir).distanceSquaredTo(enemyLoc);
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
     * Find a ruin we can build a tower on (not already occupied).
     */
    private static MapLocation findBuildableRuin(RobotController rc, MapLocation[] ruins) throws GameActionException {
        if (ruins == null || ruins.length == 0) return null;

        MapLocation myLoc = rc.getLocation();
        MapLocation best = null;
        int bestDist = Integer.MAX_VALUE;

        for (MapLocation ruin : ruins) {
            // Check if ruin already has a tower
            RobotInfo robot = rc.senseRobotAtLocation(ruin);
            if (robot != null) continue; // Already has tower

            int dist = myLoc.distanceSquaredTo(ruin);
            if (dist < bestDist) {
                bestDist = dist;
                best = ruin;
            }
        }

        return best;
    }

    /**
     * Handle the tower building process at a ruin.
     */
    private static void handleTowerBuilding(RobotController rc, MapLocation ruin) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        int distToRuin = myLoc.distanceSquaredTo(ruin);

        rc.setIndicatorString("Building tower at " + ruin);
        rc.setIndicatorLine(myLoc, ruin, 0, 255, 0);

        // Move closer if needed
        if (distToRuin > 2) {
            Navigation.moveTo(rc, ruin);
            Utils.tryPaintCurrent(rc);
            return;
        }

        // Try to mark the tower pattern
        if (rc.canMarkTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, ruin)) {
            rc.markTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, ruin);
        }

        // Fill in the pattern
        MapInfo[] patternTiles = rc.senseNearbyMapInfos(ruin, 8);
        for (MapInfo tile : patternTiles) {
            PaintType mark = tile.getMark();
            PaintType paint = tile.getPaint();

            if (mark != PaintType.EMPTY && mark != paint) {
                boolean secondary = (mark == PaintType.ALLY_SECONDARY);
                if (rc.canAttack(tile.getMapLocation())) {
                    rc.attack(tile.getMapLocation(), secondary);
                    return; // One attack per turn
                }
            }
        }

        // Try to complete the tower
        if (rc.canCompleteTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, ruin)) {
            rc.completeTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, ruin);
            rc.setTimelineMarker("Tower built!", 0, 255, 0);
            targetRuin = null;
        }
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
            // Prioritize enemy paint, then neutral
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
        rc.setIndicatorString("LOW PAINT - retreating");

        // Find ally towers
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        MapLocation towerLoc = null;

        for (RobotInfo ally : allies) {
            if (ally.getType().isTowerType()) {
                towerLoc = ally.getLocation();
                break;
            }
        }

        if (towerLoc != null) {
            Navigation.moveTo(rc, towerLoc);
        } else {
            // Move toward painted ally territory
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
}
