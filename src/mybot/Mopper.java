package mybot;

import battlecode.common.*;

/**
 * Mopper behavior using Priority Chain Pattern.
 *
 * Priorities are processed in order - first match wins (early return).
 * Moppers excel at: AOE damage via mop swing, paint removal.
 */
public class Mopper {

    // Thresholds (tune these during competition) - AGGRESSIVE
    private static final int HEALTH_CRITICAL = 10;  // Lower = fight longer
    private static final int PAINT_LOW = 30;

    // ==================== SPAWN LOCATION ====================
    private static MapLocation spawnLocation = null;  // Remember where we spawned
    private static int lastPaintLevel = 100;          // Track paint changes

    // ==================== FSM STATE ====================
    enum MopperState { IDLE, CHASING_ENEMY, CLEANING_AREA }
    private static MopperState state = MopperState.IDLE;
    private static MapLocation stateTarget = null;
    private static int stateTurns = 0;

    // State timeout values (turns) - AGGRESSIVE
    private static final int CHASING_TIMEOUT = 25;  // Longer = chase harder
    private static final int CLEANING_TIMEOUT = 20;

    public static void run(RobotController rc) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        int round = rc.getRoundNum();

        // ===== SPAWN LOCATION: Remember where we came from =====
        if (spawnLocation == null) {
            // On first turn, find nearest tower (our spawn point)
            RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
            for (RobotInfo ally : allies) {
                if (ally.getType().isTowerType()) {
                    spawnLocation = ally.getLocation();
                    break;
                }
            }
            // Fallback: use current location if no tower visible
            if (spawnLocation == null) {
                spawnLocation = myLoc;
            }
        }

        // ===== TRACK PAINT REFILL SUCCESS =====
        int currentPaint = rc.getPaint();
        if (currentPaint > lastPaintLevel + 20) {
            // Paint increased significantly - we refilled!
            Metrics.trackRetreatOutcome("success");
        }
        lastPaintLevel = currentPaint;

        // ===== METRICS: Periodic self-report =====
        if (Metrics.ENABLED && round % 500 == 0) {
            Metrics.reportMopperStats(rc.getID(), round);
        }

        // ==================== FSM UPDATE ====================
        stateTurns++;

        // Check state exit conditions (cheap checks first)
        updateStateTransitions(rc);

        // If in active state, execute it and return
        if (state != MopperState.IDLE) {
            executeCurrentState(rc);
            return;
        }

        // ==================== PRIORITY CHAIN (when IDLE) ====================

        // ===== PRIORITY 0: SURVIVAL =====
        if (rc.getHealth() < HEALTH_CRITICAL) {
            Metrics.trackMopperPriority(0);
            Metrics.trackRetreat();
            retreat(rc);
            return;
        }

        // ===== PRIORITY 0.5: PAINT TOWER CRITICAL =====
        MapLocation criticalTower = Comms.getLocationFromMessage(rc, Comms.MessageType.PAINT_TOWER_CRITICAL);
        if (criticalTower != null) {
            Metrics.trackMopperPriority(0);
            rc.setIndicatorString("P0.5: TOWER CRITICAL - DEFENDING!");
            rc.setIndicatorLine(myLoc, criticalTower, 255, 0, 0);
            Navigation.moveTo(rc, criticalTower);
            // Mop swing enemies near the tower
            tryMopSwing(rc);
            return;
        }

        // ===== PRIORITY 1: COORDINATED ATTACK (focus fire) =====
        MapLocation attackTarget = Comms.getLocationFromMessage(rc, Comms.MessageType.ATTACK_TARGET);
        if (attackTarget != null) {
            int dist = myLoc.distanceSquaredTo(attackTarget);
            if (dist <= 100) {  // Within 10 tiles
                Metrics.trackMopperPriority(1);
                Metrics.trackMessageActedOn();
                rc.setIndicatorString("P1: FOCUS FIRE!");
                rc.setIndicatorLine(myLoc, attackTarget, 255, 0, 255);

                Navigation.moveTo(rc, attackTarget);
                // Try mop swing when we get close
                tryMopSwing(rc);
                return;
            }
        }

        // ===== PRIORITY 1.5: RESUPPLY =====
        if (rc.getPaint() < PAINT_LOW) {
            Metrics.trackMopperPriority(1);
            Metrics.trackLowPaint();
            retreatForPaint(rc);
            return;
        }

        // ===== PRIORITY 1.5: PROTECT SPLASHER =====
        MapLocation threatenedSplasher = Comms.getLocationFromMessage(rc, Comms.MessageType.SPLASHER_THREATENED);
        if (threatenedSplasher != null) {
            int dist = myLoc.distanceSquaredTo(threatenedSplasher);
            if (dist <= 64) {  // Within 8 tiles
                Metrics.trackMopperPriority(1);
                Metrics.trackMessageActedOn();
                rc.setIndicatorString("P1.5: Protecting splasher!");
                rc.setIndicatorLine(myLoc, threatenedSplasher, 0, 255, 0);

                // Find enemies near the splasher
                RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
                RobotInfo closestToSplasher = null;
                int closestDist = Integer.MAX_VALUE;
                for (RobotInfo enemy : enemies) {
                    int d = enemy.getLocation().distanceSquaredTo(threatenedSplasher);
                    if (d < closestDist) {
                        closestDist = d;
                        closestToSplasher = enemy;
                    }
                }

                if (closestToSplasher != null) {
                    // Intercept the enemy
                    Navigation.moveTo(rc, closestToSplasher.getLocation());
                    tryMopSwing(rc);
                } else {
                    // Move to splasher location
                    Navigation.moveTo(rc, threatenedSplasher);
                }
                return;
            }
        }

        // ===== PRIORITY 2: MOP SWING (AOE ATTACK) =====
        if (tryMopSwing(rc)) {
            Metrics.trackMopperPriority(2);
            return;
        }

        // ===== PRIORITY 3: CHASE ENEMIES FOR MOP =====
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if (enemies.length > 0) {
            RobotInfo closest = Utils.closestRobot(myLoc, enemies);
            if (closest != null) {
                Metrics.trackMopperPriority(3);
                enterState(MopperState.CHASING_ENEMY, closest.getLocation());
                chaseForMop(rc, enemies);
                return;
            }
        }

        // ===== PRIORITY 4: CLEAN ENEMY PAINT =====
        MapLocation enemyPaint = findEnemyPaint(rc);
        if (enemyPaint != null) {
            Metrics.trackMopperPriority(4);
            enterState(MopperState.CLEANING_AREA, enemyPaint);
            cleanPaint(rc, enemyPaint);
            return;
        }

        // ===== PRIORITY 5: DEFAULT - EXPLORE =====
        Metrics.trackMopperPriority(5);
        explore(rc);
    }

    // ==================== HELPER METHODS ====================

    /**
     * Try to execute a mop swing if enemies are in range.
     * Returns true if swing was executed.
     */
    private static boolean tryMopSwing(RobotController rc) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());

        if (enemies.length == 0) return false;

        // Track combat paint metrics
        MapInfo currentTile = rc.senseMapInfo(myLoc);
        Metrics.trackCombatTurn(currentTile.getPaint().isAlly());

        // Find best direction to swing (hits most enemies)
        Direction bestDir = null;
        int bestHits = 0;

        for (Direction dir : Utils.DIRECTIONS) {
            if (!rc.canMopSwing(dir)) continue;

            // Count enemies that would be hit in this direction
            int hits = countEnemiesInSwingDirection(rc, myLoc, dir, enemies);
            if (hits > bestHits) {
                bestHits = hits;
                bestDir = dir;
            }
        }

        if (bestDir != null && bestHits > 0) {
            rc.mopSwing(bestDir);
            Metrics.trackMopSwing();
            rc.setIndicatorString("P2: Mop swing! Hit " + bestHits + " enemies");
            return true;
        }

        return false;
    }

    /**
     * Count enemies in the swing arc for a given direction.
     */
    private static int countEnemiesInSwingDirection(RobotController rc, MapLocation myLoc,
            Direction dir, RobotInfo[] enemies) {
        int count = 0;

        // Mop swing hits in a 3-tile arc in front
        MapLocation[] swingTiles = {
            myLoc.add(dir),
            myLoc.add(dir.rotateLeft()),
            myLoc.add(dir.rotateRight())
        };

        for (RobotInfo enemy : enemies) {
            for (MapLocation tile : swingTiles) {
                if (enemy.getLocation().equals(tile)) {
                    count++;
                    break;
                }
            }
        }

        return count;
    }

    /**
     * Chase enemies to get within mop swing range.
     */
    private static void chaseForMop(RobotController rc, RobotInfo[] enemies) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        RobotInfo closest = Utils.closestRobot(myLoc, enemies);

        if (closest != null) {
            rc.setIndicatorString("P3: Chasing for mop swing");
            rc.setIndicatorLine(myLoc, closest.getLocation(), 255, 165, 0);
            Navigation.moveTo(rc, closest.getLocation());
        }
    }

    /**
     * Clean enemy paint at a location.
     */
    private static void cleanPaint(RobotController rc, MapLocation target) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        int dist = myLoc.distanceSquaredTo(target);

        rc.setIndicatorString("P4: Cleaning enemy paint");
        rc.setIndicatorDot(target, 255, 0, 0);

        if (dist <= 2 && rc.canAttack(target)) {
            rc.attack(target);
        } else {
            Navigation.moveTo(rc, target);
        }
    }

    /**
     * Find nearest enemy paint to clean.
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
     * Retreat toward ally towers for paint refill.
     */
    private static void retreatForPaint(RobotController rc) throws GameActionException {
        MapLocation myLoc = rc.getLocation();

        // Priority 1: Find visible tower
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo ally : allies) {
            if (ally.getType().isTowerType()) {
                rc.setIndicatorString("P1: Retreating to tower at " + ally.getLocation());
                rc.setIndicatorLine(myLoc, ally.getLocation(), 0, 255, 0);
                Metrics.trackRetreatOutcome("tower");
                Navigation.moveTo(rc, ally.getLocation());
                return;
            }
        }

        // Priority 2: Navigate to spawn location (ALWAYS go home, don't follow random paint)
        if (spawnLocation != null) {
            int distToSpawn = myLoc.distanceSquaredTo(spawnLocation);
            rc.setIndicatorString("P1: Returning to spawn (" + distToSpawn + " away)");
            rc.setIndicatorLine(myLoc, spawnLocation, 255, 255, 0);
            Metrics.trackRetreatOutcome("wandering");
            Navigation.moveTo(rc, spawnLocation);
            return;
        }

        // Fallback: Random movement (should rarely happen)
        rc.setIndicatorString("P1: LOST - no spawn location!");
        Metrics.trackRetreatOutcome("wandering");
        Utils.tryMoveRandom(rc);
    }

    /**
     * Default exploration behavior.
     */
    private static void explore(RobotController rc) throws GameActionException {
        Utils.tryMoveRandom(rc);
        rc.setIndicatorString("P5: Exploring");
    }

    // ==================== FSM METHODS ====================

    /**
     * Enter a new FSM state.
     */
    private static void enterState(MopperState newState, MapLocation target) {
        state = newState;
        stateTarget = target;
        stateTurns = 0;
    }

    /**
     * Check state exit conditions and reset to IDLE if needed.
     */
    private static void updateStateTransitions(RobotController rc) throws GameActionException {
        switch (state) {
            case CHASING_ENEMY:
                if (stateTurns > CHASING_TIMEOUT) {
                    state = MopperState.IDLE;
                    return;
                }
                // Exit if target no longer visible or we lost the chase
                if (stateTarget != null) {
                    RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
                    if (enemies.length == 0) {
                        state = MopperState.IDLE;
                    }
                }
                break;

            case CLEANING_AREA:
                if (stateTurns > CLEANING_TIMEOUT) {
                    state = MopperState.IDLE;
                    return;
                }
                // Exit if no more enemy paint nearby
                MapLocation enemyPaint = findEnemyPaint(rc);
                if (enemyPaint == null) {
                    state = MopperState.IDLE;
                }
                break;

            default:
                break;
        }
    }

    /**
     * Execute the current FSM state.
     */
    private static void executeCurrentState(RobotController rc) throws GameActionException {
        Metrics.trackMopperState(state.ordinal());
        switch (state) {
            case CHASING_ENEMY:
                continueChasing(rc);
                break;
            case CLEANING_AREA:
                continueCleaning(rc);
                break;
            default:
                break;
        }
    }

    /**
     * Continue chasing an enemy.
     */
    private static void continueChasing(RobotController rc) throws GameActionException {
        rc.setIndicatorString("FSM: CHASING_ENEMY t=" + stateTurns);

        // First try mop swing if enemies in range
        if (tryMopSwing(rc)) {
            return;
        }

        // Chase the closest enemy
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if (enemies.length > 0) {
            RobotInfo closest = Utils.closestRobot(rc.getLocation(), enemies);
            if (closest != null) {
                stateTarget = closest.getLocation();
                rc.setIndicatorLine(rc.getLocation(), stateTarget, 255, 165, 0);
                Navigation.moveTo(rc, stateTarget);
            }
        }
    }

    /**
     * Continue cleaning enemy paint in an area.
     */
    private static void continueCleaning(RobotController rc) throws GameActionException {
        rc.setIndicatorString("FSM: CLEANING_AREA t=" + stateTurns);

        // Find nearest enemy paint to clean
        MapLocation enemyPaint = findEnemyPaint(rc);
        if (enemyPaint != null) {
            stateTarget = enemyPaint;
            rc.setIndicatorDot(stateTarget, 255, 0, 0);
            cleanPaint(rc, enemyPaint);
        }
    }
}
