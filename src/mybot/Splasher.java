package mybot;

import battlecode.common.*;

/**
 * Splasher behavior using Priority Chain Pattern.
 *
 * Priorities are processed in order - first match wins (early return).
 * Splashers excel at: area paint attacks, territory control.
 */
public class Splasher {

    // Thresholds (tune these during competition) - AGGRESSIVE
    private static final int HEALTH_CRITICAL = 20;  // Lower = fight longer
    private static final int PAINT_LOW = 60;        // Lower = fight longer
    // Splash thresholds now in Scoring.java

    // ==================== FSM STATE ====================
    enum SplasherState { IDLE, MOVING_TO_SPLASH, ADVANCING_TERRITORY }
    private static SplasherState state = SplasherState.IDLE;
    private static MapLocation stateTarget = null;
    private static int stateTurns = 0;

    // State timeout values (turns)
    private static final int SPLASH_TIMEOUT = 15;
    private static final int ADVANCING_TIMEOUT = 30;

    public static void run(RobotController rc) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        int round = rc.getRoundNum();

        // ===== METRICS: Periodic self-report =====
        if (Metrics.ENABLED && round % 500 == 0) {
            Metrics.reportSplasherStats(rc.getID(), round);
        }

        // ==================== FSM UPDATE ====================
        stateTurns++;

        // Check state exit conditions (cheap checks first)
        updateStateTransitions(rc);

        // If in active state, execute it and return
        if (state != SplasherState.IDLE) {
            executeCurrentState(rc);
            return;
        }

        // ==================== PRIORITY CHAIN (when IDLE) ====================

        // ===== PRIORITY 0: SURVIVAL =====
        if (rc.getHealth() < HEALTH_CRITICAL) {
            Metrics.trackSplasherPriority(0);
            Metrics.trackRetreat();
            retreat(rc);
            return;
        }

        // ===== PRIORITY 0.5: PAINT TOWER CRITICAL =====
        MapLocation criticalTower = Comms.getLocationFromMessage(rc, Comms.MessageType.PAINT_TOWER_CRITICAL);
        if (criticalTower != null) {
            Metrics.trackSplasherPriority(0);
            rc.setIndicatorString("P0.5: TOWER CRITICAL - DEFENDING!");
            rc.setIndicatorLine(myLoc, criticalTower, 255, 0, 0);
            Navigation.moveTo(rc, criticalTower);
            // Splash attack near the tower
            if (rc.canAttack(criticalTower)) {
                rc.attack(criticalTower);
                Metrics.trackSplash();
            }
            return;
        }

        // ===== PRIORITY 1: COORDINATED ATTACK (focus fire) =====
        MapLocation attackTarget = Comms.getLocationFromMessage(rc, Comms.MessageType.ATTACK_TARGET);
        if (attackTarget != null) {
            int dist = myLoc.distanceSquaredTo(attackTarget);
            if (dist <= 100) {  // Within 10 tiles
                Metrics.trackSplasherPriority(1);
                Metrics.trackMessageActedOn();
                rc.setIndicatorString("P1: FOCUS FIRE!");
                rc.setIndicatorLine(myLoc, attackTarget, 255, 0, 255);

                if (rc.canAttack(attackTarget)) {
                    rc.attack(attackTarget);
                    Metrics.trackSplash();
                }
                Navigation.moveTo(rc, attackTarget);
                return;
            }
        }

        // ===== PRIORITY 1.5: RESUPPLY =====
        if (rc.getPaint() < PAINT_LOW) {
            Metrics.trackSplasherPriority(1);
            Metrics.trackLowPaint();
            retreatForPaint(rc);
            return;
        }

        // ===== PRIORITY 1.5: SUPPORT TOWER BUILDING =====
        // Soldiers can't paint over enemy paint - splashers must help!
        MapLocation towerSite = Comms.getLocationFromMessage(rc, Comms.MessageType.TOWER_BUILDING);
        if (towerSite != null) {
            int dist = myLoc.distanceSquaredTo(towerSite);
            // Only respond if reasonably close (within 100 tiles squared)
            if (dist <= 100) {
                Metrics.trackSplasherPriority(2);  // Counts as high-value
                Metrics.trackMessageActedOn();
                rc.setIndicatorString("P1.5: Supporting tower build at " + towerSite);
                rc.setIndicatorLine(myLoc, towerSite, 0, 255, 0);
                if (dist <= rc.getType().actionRadiusSquared && rc.canAttack(towerSite)) {
                    rc.attack(towerSite);
                    Metrics.trackSplash();
                } else {
                    Navigation.moveTo(rc, towerSite);
                }
                return;
            }
        }

        // ===== PRIORITY 2: HIGH-VALUE SPLASH (5+ tiles) =====
        MapLocation splashTarget = findBestSplashTarget(rc);
        if (splashTarget != null) {
            int score = Scoring.scoreSplashTarget(rc, splashTarget);
            if (score >= Scoring.THRESHOLD_SPLASH_HIGH) {
                Metrics.trackSplasherPriority(2);
                if (!rc.canAttack(splashTarget)) {
                    // Need to move to target - enter persistent state
                    enterState(SplasherState.MOVING_TO_SPLASH, splashTarget);
                }
                executeSplash(rc, splashTarget, score);
                return;
            }
        }

        // ===== PRIORITY 2.5: ATTACK ENEMIES (combat splash) =====
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if (enemies.length > 0) {
            RobotInfo target = Utils.closestRobot(myLoc, enemies);
            if (target != null && rc.canAttack(target.getLocation())) {
                rc.attack(target.getLocation());
                Metrics.trackSplash();
                rc.setIndicatorString("P2.5: Combat splash!");
                return;
            }
        }

        // ===== PRIORITY 3: ADVANCE TO ENEMY TERRITORY (moved up) =====
        // Splashers are the ONLY units that can contest enemy paint!
        MapLocation enemyTerritory = findEnemyTerritory(rc);
        if (enemyTerritory != null) {
            Metrics.trackSplasherPriority(3);
            enterState(SplasherState.ADVANCING_TERRITORY, enemyTerritory);
            rc.setIndicatorString("P3: Advancing to enemy territory");
            Navigation.moveTo(rc, enemyTerritory);
            return;
        }

        // ===== PRIORITY 4: MEDIUM-VALUE SPLASH (3+ tiles) =====
        if (splashTarget != null) {
            int score = Scoring.scoreSplashTarget(rc, splashTarget);
            if (score >= Scoring.THRESHOLD_SPLASH_WORTH) {
                Metrics.trackSplasherPriority(4);
                if (!rc.canAttack(splashTarget)) {
                    enterState(SplasherState.MOVING_TO_SPLASH, splashTarget);
                }
                executeSplash(rc, splashTarget, score);
                return;
            }
        }

        // ===== PRIORITY 5: DEFAULT - EXPLORE =====
        Metrics.trackSplasherPriority(5);
        explore(rc);
    }

    // ==================== HELPER METHODS ====================

    /**
     * Execute a splash attack at target location with paint conservation.
     */
    private static void executeSplash(RobotController rc, MapLocation target, int score) throws GameActionException {
        MapLocation myLoc = rc.getLocation();

        // PAINT CONSERVATION: Move to ally paint before splashing if possible
        MapInfo currentTile = rc.senseMapInfo(myLoc);

        // Track combat paint metrics
        Metrics.trackCombatTurn(currentTile.getPaint().isAlly());
        if (!currentTile.getPaint().isAlly() && rc.isMovementReady()) {
            for (Direction dir : Utils.DIRECTIONS) {
                if (!rc.canMove(dir)) continue;
                MapLocation newLoc = myLoc.add(dir);
                if (!rc.canSenseLocation(newLoc)) continue;

                MapInfo newTile = rc.senseMapInfo(newLoc);
                int distToTarget = newLoc.distanceSquaredTo(target);

                // Must be ally paint AND in attack range
                if (newTile.getPaint().isAlly() &&
                    distToTarget <= rc.getType().actionRadiusSquared) {
                    rc.move(dir);
                    myLoc = newLoc;
                    rc.setIndicatorString("P2/3: Repositioned to ally paint");
                    break;
                }
            }
        }

        if (rc.canAttack(target)) {
            // Track if we're contesting enemy territory
            if (rc.canSenseLocation(target)) {
                MapInfo targetTile = rc.senseMapInfo(target);
                if (targetTile.getPaint().isEnemy()) {
                    Metrics.trackTileContested();
                }
            }
            rc.attack(target);
            Metrics.trackSplash();
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

            int score = Scoring.scoreSplashTarget(rc, loc);
            if (score > bestScore) {
                bestScore = score;
                best = loc;
            }
        }

        return best;
    }

    // Splash scoring now in Scoring.java

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

        // Alert moppers to help protect us
        MapLocation myLoc = rc.getLocation();
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());

        // Try to send help signal to nearby mopper
        for (RobotInfo ally : allies) {
            if (ally.getType() == UnitType.MOPPER) {
                if (rc.canSendMessage(ally.getLocation())) {
                    int msg = Comms.encode(Comms.MessageType.SPLASHER_THREATENED, myLoc, 0);
                    rc.sendMessage(ally.getLocation(), msg);
                    break;  // Only 1 message/turn for units
                }
            }
        }
        for (RobotInfo ally : allies) {
            if (ally.getType().isTowerType()) {
                Navigation.moveTo(rc, ally.getLocation());
                return;
            }
        }

        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if (enemies.length > 0) {
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

    // ==================== FSM METHODS ====================

    /**
     * Enter a new FSM state.
     */
    private static void enterState(SplasherState newState, MapLocation target) {
        state = newState;
        stateTarget = target;
        stateTurns = 0;
    }

    /**
     * Check state exit conditions and reset to IDLE if needed.
     */
    private static void updateStateTransitions(RobotController rc) throws GameActionException {
        switch (state) {
            case MOVING_TO_SPLASH:
                if (stateTurns > SPLASH_TIMEOUT) {
                    state = SplasherState.IDLE;
                    return;
                }
                // Exit if we reached target or target no longer valid
                if (stateTarget != null && rc.canAttack(stateTarget)) {
                    state = SplasherState.IDLE;  // Will immediately attack via priority chain
                }
                break;

            case ADVANCING_TERRITORY:
                if (stateTurns > ADVANCING_TIMEOUT) {
                    state = SplasherState.IDLE;
                    return;
                }
                // Exit if we reached the target area
                if (stateTarget != null) {
                    MapLocation myLoc = rc.getLocation();
                    if (myLoc.distanceSquaredTo(stateTarget) <= 4) {
                        state = SplasherState.IDLE;
                    }
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
        Metrics.trackSplasherState(state.ordinal());
        switch (state) {
            case MOVING_TO_SPLASH:
                continueMovingToSplash(rc);
                break;
            case ADVANCING_TERRITORY:
                continueAdvancing(rc);
                break;
            default:
                break;
        }
    }

    /**
     * Continue moving toward a splash target.
     */
    private static void continueMovingToSplash(RobotController rc) throws GameActionException {
        rc.setIndicatorString("FSM: MOVING_TO_SPLASH t=" + stateTurns);

        if (stateTarget == null) {
            state = SplasherState.IDLE;
            return;
        }

        // Check if we can now attack
        if (rc.canAttack(stateTarget)) {
            int score = Scoring.scoreSplashTarget(rc, stateTarget);
            rc.attack(stateTarget);
            Metrics.trackSplash();
            rc.setIndicatorString("FSM: SPLASH! Score=" + score);
            rc.setIndicatorDot(stateTarget, 0, 255, 255);
            state = SplasherState.IDLE;
            return;
        }

        // Keep moving toward target
        rc.setIndicatorLine(rc.getLocation(), stateTarget, 0, 255, 255);
        Navigation.moveTo(rc, stateTarget);
    }

    /**
     * Continue advancing into enemy territory.
     */
    private static void continueAdvancing(RobotController rc) throws GameActionException {
        rc.setIndicatorString("FSM: ADVANCING_TERRITORY t=" + stateTurns);

        // Check for splash opportunities along the way
        MapLocation splashTarget = findBestSplashTarget(rc);
        if (splashTarget != null) {
            int score = Scoring.scoreSplashTarget(rc, splashTarget);
            if (score >= Scoring.THRESHOLD_SPLASH_WORTH && rc.canAttack(splashTarget)) {
                rc.attack(splashTarget);
                Metrics.trackSplash();
                rc.setIndicatorString("FSM: OPPORTUNISTIC SPLASH!");
                return;
            }
        }

        // Continue toward territory target
        if (stateTarget != null) {
            rc.setIndicatorLine(rc.getLocation(), stateTarget, 128, 0, 255);
            Navigation.moveTo(rc, stateTarget);
        } else {
            // Lost target, find new enemy territory
            MapLocation enemyTerritory = findEnemyTerritory(rc);
            if (enemyTerritory != null) {
                stateTarget = enemyTerritory;
                Navigation.moveTo(rc, enemyTerritory);
            } else {
                state = SplasherState.IDLE;
            }
        }
    }
}
