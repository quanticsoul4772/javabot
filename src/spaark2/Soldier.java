package spaark2;

import battlecode.common.*;

/**
 * Soldier with mode-based state machine.
 * Modes: EXPLORE, BUILD_TOWER, RETREAT
 */
public class Soldier {

    private enum Mode { EXPLORE, BUILD_TOWER, RETREAT }
    private static Mode mode = Mode.EXPLORE;

    // Building state
    private static MapLocation buildTarget = null;
    private static int buildStartRound = 0;

    public static void run(RobotController rc) throws GameActionException {
        Globals.init(rc);

        // Check mode transitions
        checkModeTransitions(rc);

        // Execute current mode (handles attacking)
        switch (mode) {
            case RETREAT:
                doRetreat(rc);
                break;
            case BUILD_TOWER:
                doBuildTower(rc);
                break;
            default:
                doExplore(rc);
                break;
        }

        // Paint current tile AFTER other actions (enables splasher spawning)
        paintHere(rc);
    }

    private static void checkModeTransitions(RobotController rc) throws GameActionException {
        int paint = rc.getPaint();
        int chips = rc.getMoney();
        int allies = rc.senseNearbyRobots(-1, rc.getTeam()).length;

        // Enter retreat only if very low paint AND low chips AND few allies (SPAARK logic)
        // This prevents premature retreating during fights
        if (mode != Mode.RETREAT && paint < 50 && chips < 6000 && allies < 5) {
            mode = Mode.RETREAT;
            buildTarget = null;
        }

        // Exit retreat when resources recovered
        if (mode == Mode.RETREAT && (paint >= 100 || chips >= 6000 || allies >= 5)) {
            mode = Mode.EXPLORE;
        }
    }

    private static void doExplore(RobotController rc) throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        int round = rc.getRoundNum();

        // Priority 0: Rush enemy territory
        // Attack any enemy in range
        if (rc.isActionReady()) {
            RobotInfo target = null;
            for (RobotInfo enemy : enemies) {
                if (rc.canAttack(enemy.getLocation())) {
                    // Prefer towers, then lowest HP
                    if (target == null || enemy.getType().isTowerType() ||
                        (!target.getType().isTowerType() && enemy.getHealth() < target.getHealth())) {
                        target = enemy;
                    }
                }
            }
            if (target != null) {
                rc.attack(target.getLocation());
            }
        }

        // Priority 1: Attack enemy towers
        for (RobotInfo enemy : enemies) {
            if (enemy.getType().isTowerType()) {
                if (rc.canAttack(enemy.getLocation())) {
                    rc.attack(enemy.getLocation());
                }
                Nav.moveTo(rc, enemy.getLocation());
                return;
            }
        }

        // Priority 2: Move toward nearest enemy unit (if visible)
        if (enemies.length > 0) {
            RobotInfo closest = null;
            int closestDist = Integer.MAX_VALUE;
            for (RobotInfo enemy : enemies) {
                int dist = rc.getLocation().distanceSquaredTo(enemy.getLocation());
                if (dist < closestDist) {
                    closestDist = dist;
                    closest = enemy;
                }
            }
            if (closest != null && rc.isMovementReady()) {
                Nav.moveTo(rc, closest.getLocation());
            }
            return;  // Keep engaging enemies
        }

        // Priority 3: Rush toward center in early game
        if (round < 100) {
            MapLocation center = new MapLocation(rc.getMapWidth() / 2, rc.getMapHeight() / 2);
            if (rc.isMovementReady()) {
                Nav.moveTo(rc, center);
            }
            return;
        }

        // Priority 4: Build tower at ruin (skip if tower already there or enemies nearby)
        // Don't build in early game (round < 50) - focus on fighting first!
        if (round < 50) {
            // Skip tower building - early game is about territory control
        } else if (enemies.length > 0 && round < 150) {
            // Skip tower building if enemies nearby
        } else {
            MapLocation[] ruins = rc.senseNearbyRuins(-1);
            for (MapLocation ruin : ruins) {
                // Skip if there's already a tower at the ruin
                RobotInfo robotAtRuin = rc.senseRobotAtLocation(ruin);
                if (robotAtRuin != null && robotAtRuin.getType().isTowerType()) {
                    continue;  // Tower already built
                }

                // Skip if too many soldiers already building here (max 2)
                RobotInfo[] nearbyAllies = rc.senseNearbyRobots(ruin, 8, rc.getTeam());
                int buildingCount = 0;
                for (RobotInfo ally : nearbyAllies) {
                    if (ally.getType() == UnitType.SOLDIER && ally.getID() < rc.getID()) {
                        buildingCount++;
                    }
                }
                if (buildingCount >= 2) {
                    continue;  // Too many soldiers already here
                }

                // Start building
                mode = Mode.BUILD_TOWER;
                buildTarget = ruin;
                buildStartRound = round;
                return;
            }
        }

        // Priority 4: Move toward unexplored
        moveTowardUnpainted(rc);
    }

    private static void doBuildTower(RobotController rc) throws GameActionException {
        if (buildTarget == null) {
            mode = Mode.EXPLORE;
            return;
        }

        int round = rc.getRoundNum();

        // Abort building in early game or if enemies nearby
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if (round < 50 || (enemies.length > 0 && round < 150)) {
            mode = Mode.EXPLORE;
            buildTarget = null;
            return;
        }

        // Timeout check
        if (round - buildStartRound > 80) {
            mode = Mode.EXPLORE;
            buildTarget = null;
            return;
        }

        // Tower already built check
        if (rc.canSenseLocation(buildTarget)) {
            RobotInfo robot = rc.senseRobotAtLocation(buildTarget);
            if (robot != null && robot.getType().isTowerType()) {
                mode = Mode.EXPLORE;
                buildTarget = null;
                return;
            }
        }

        MapLocation myLoc = rc.getLocation();

        // Move closer if far
        if (myLoc.distanceSquaredTo(buildTarget) > 2) {
            Nav.moveTo(rc, buildTarget);
            return;
        }

        // Try to complete tower (paint tower first, then defense)
        UnitType towerType = UnitType.LEVEL_ONE_PAINT_TOWER;
        boolean canComplete = rc.canCompleteTowerPattern(towerType, buildTarget);
        if (canComplete) {
            rc.completeTowerPattern(towerType, buildTarget);
            mode = Mode.EXPLORE;
            buildTarget = null;
            return;
        }

        // Mark pattern first (required before painting!)
        if (rc.canMarkTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, buildTarget)) {
            rc.markTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, buildTarget);
        }

        // Paint tiles that need it
        if (rc.isActionReady()) {
            MapInfo[] patternTiles = rc.senseNearbyMapInfos(buildTarget, 8);
            int markedTiles = 0;
            int correctTiles = 0;
            int needPaint = 0;
            for (MapInfo tile : patternTiles) {
                PaintType mark = tile.getMark();
                PaintType paint = tile.getPaint();

                // Skip tiles not in pattern
                if (mark == PaintType.EMPTY) continue;
                markedTiles++;

                // Skip correctly painted tiles
                if (mark == paint) {
                    correctTiles++;
                    continue;
                }

                // Skip enemy paint (soldiers can't paint over it)
                if (paint.isEnemy()) continue;

                needPaint++;
                // Paint this tile
                MapLocation tileLoc = tile.getMapLocation();
                if (rc.canAttack(tileLoc)) {
                    boolean secondary = (mark == PaintType.ALLY_SECONDARY);
                    rc.attack(tileLoc, secondary);
                    return;
                } else if (rc.isMovementReady()) {
                    // Move closer to paint this tile
                    Nav.moveTo(rc, tileLoc);
                    return;
                }
            }
        }
    }

    private static void doRetreat(RobotController rc) throws GameActionException {
        // Find nearest ally tower (any type)
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        MapLocation nearestTower = null;
        int bestDist = Integer.MAX_VALUE;

        for (RobotInfo ally : allies) {
            if (ally.getType().isTowerType()) {
                int dist = rc.getLocation().distanceSquaredTo(ally.getLocation());
                if (dist < bestDist) {
                    bestDist = dist;
                    nearestTower = ally.getLocation();
                }
            }
        }

        if (nearestTower != null) {
            Nav.moveTo(rc, nearestTower);
        } else {
            // Move toward center as fallback
            Nav.moveTo(rc, new MapLocation(rc.getMapWidth() / 2, rc.getMapHeight() / 2));
        }
        // Don't attack while retreating - save paint
    }

    private static void paintHere(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return;
        MapLocation myLoc = rc.getLocation();
        MapInfo tile = rc.senseMapInfo(myLoc);

        // Only paint neutral tiles (soldiers can't paint enemy)
        if (!tile.getPaint().isAlly() && !tile.getPaint().isEnemy()) {
            if (rc.canAttack(myLoc)) {
                rc.attack(myLoc);
            }
        }
    }

    private static void moveTowardUnpainted(RobotController rc) throws GameActionException {
        if (!rc.isMovementReady()) return;

        MapLocation myLoc = rc.getLocation();
        Direction bestDir = null;
        int bestScore = Integer.MIN_VALUE;

        for (Direction d : Globals.DIRECTIONS) {
            if (!rc.canMove(d)) continue;

            MapLocation newLoc = myLoc.add(d);
            int score = 0;

            // Prefer tiles with less ally paint nearby
            if (rc.canSenseLocation(newLoc)) {
                MapInfo info = rc.senseMapInfo(newLoc);
                if (!info.getPaint().isAlly()) score += 10;
                if (info.getPaint().isEnemy()) score -= 5;
            }

            // Add randomness to avoid clustering
            score += Globals.rng.nextInt(5);

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
}
