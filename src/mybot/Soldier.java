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

    // ==================== FSM STATE ====================
    enum SoldierState { IDLE, BUILDING_TOWER, DEFENDING_TOWER, RETREATING }
    private static SoldierState state = SoldierState.IDLE;
    private static MapLocation stateTarget = null;
    private static int stateStartRound = 0;
    private static int stateTurns = 0;

    // State timeout values (turns)
    private static final int BUILDING_TIMEOUT = 100;
    private static final int DEFENDING_TIMEOUT = 30;
    private static final int RETREATING_TIMEOUT = 50;

    public static void run(RobotController rc) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        int round = rc.getRoundNum();

        // ===== METRICS: Periodic self-report =====
        if (Metrics.ENABLED && round % 500 == 0) {
            Metrics.reportSoldierStats(rc.getID(), round);
        }

        // ==================== FSM UPDATE ====================
        stateTurns++;

        // Check state exit conditions (cheap checks first)
        updateStateTransitions(rc);

        // If in active state, execute it and return
        if (state != SoldierState.IDLE) {
            executeCurrentState(rc);
            return;
        }

        // ==================== PRIORITY CHAIN (when IDLE) ====================

        // ===== PHASE CHECK =====
        boolean defendMode = Comms.hasMessageOfType(rc, Comms.MessageType.PHASE_DEFEND);
        boolean attackMode = Comms.hasMessageOfType(rc, Comms.MessageType.PHASE_ALL_OUT_ATTACK);

        // Adjust thresholds based on phase
        int healthThreshold = HEALTH_CRITICAL;
        int paintThreshold = PAINT_LOW;
        if (defendMode) {
            // In defend mode, retreat earlier to preserve units
            healthThreshold = HEALTH_CRITICAL + 10;  // 30 instead of 20
            paintThreshold = PAINT_LOW + 20;         // 70 instead of 50
        } else if (attackMode) {
            // In attack mode, be more aggressive
            healthThreshold = HEALTH_CRITICAL - 5;   // 15 instead of 20
            paintThreshold = PAINT_LOW - 20;         // 30 instead of 50
        }

        // ===== PRIORITY 0: SURVIVAL =====
        if (rc.getHealth() < healthThreshold) {
            Metrics.trackSoldierPriority(0);
            Metrics.trackRetreat();
            enterState(SoldierState.RETREATING, null, round);
            retreat(rc);
            return;
        }

        // ===== PRIORITY 0.5: PAINT TOWER CRITICAL =====
        MapLocation criticalTower = Comms.getLocationFromMessage(rc, Comms.MessageType.PAINT_TOWER_CRITICAL);
        if (criticalTower != null) {
            Metrics.trackSoldierPriority(0);
            rc.setIndicatorString("P0.5: TOWER CRITICAL - DEFENDING!");
            rc.setIndicatorLine(myLoc, criticalTower, 255, 0, 0);
            Navigation.moveTo(rc, criticalTower);
            // Attack enemies near the tower
            RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
            for (RobotInfo enemy : enemies) {
                if (rc.canAttack(enemy.getLocation())) {
                    rc.attack(enemy.getLocation());
                    break;
                }
            }
            return;
        }

        // ===== PRIORITY 1: CRITICAL ALERTS (from communication) =====
        MapLocation alertedTower = Comms.getLocationFromMessage(rc, Comms.MessageType.PAINT_TOWER_DANGER);
        if (alertedTower != null) {
            Metrics.trackSoldierPriority(1);
            rc.setIndicatorString("P1: Responding to tower alert!");
            Navigation.moveTo(rc, alertedTower);
            Utils.tryPaintCurrent(rc);
            return;
        }

        // ===== PRIORITY 2: DEFEND PAINT TOWERS =====
        RobotInfo towerUnderAttack = Utils.findPaintTowerUnderAttack(rc);
        if (towerUnderAttack != null) {
            Metrics.trackSoldierPriority(2);
            enterState(SoldierState.DEFENDING_TOWER, towerUnderAttack.getLocation(), round);
            defendPaintTower(rc, towerUnderAttack);
            return;
        }

        // ===== PRIORITY 3: RESUPPLY =====
        if (rc.getPaint() < paintThreshold) {
            Metrics.trackSoldierPriority(3);
            enterState(SoldierState.RETREATING, null, round);
            retreatForPaint(rc);
            return;
        }

        // ===== PRIORITY 4: OPPORTUNISTIC KILLS =====
        RobotInfo weakEnemy = findWeakEnemy(rc);
        if (weakEnemy != null && canKill(rc, weakEnemy)) {
            Metrics.trackSoldierPriority(4);
            rc.setIndicatorString("P4: Finishing weak enemy!");
            if (rc.canAttack(weakEnemy.getLocation())) {
                rc.attack(weakEnemy.getLocation());
                Metrics.trackAttack();
            }
            Navigation.moveTo(rc, weakEnemy.getLocation());
            return;
        }

        // ===== PRIORITY 5: EARLY GAME / RUSH DEFENSE =====
        boolean rushAlert = Comms.hasMessageOfType(rc, Comms.MessageType.RUSH_ALERT);
        if (round < EARLY_GAME_ROUNDS || rushAlert) {
            RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
            if (enemies.length > 0) {
                Metrics.trackSoldierPriority(5);
                rc.setIndicatorString("P5: Early game defense!");
                engageEnemy(rc, Utils.closestRobot(myLoc, enemies));
                return;
            }
            // Stay near base
            RobotInfo nearestTower = Utils.findNearestPaintTower(rc);
            if (nearestTower != null && myLoc.distanceSquaredTo(nearestTower.getLocation()) > 100) {
                Metrics.trackSoldierPriority(5);
                Navigation.moveTo(rc, nearestTower.getLocation());
                Utils.tryPaintCurrent(rc);
                return;
            }
        }

        // ===== PRIORITY 5.5: RUIN DENIAL =====
        // Paint empty ruins to deny enemy tower construction
        MapLocation[] nearbyRuins = rc.senseNearbyRuins(-1);
        for (MapLocation ruin : nearbyRuins) {
            // Skip if tower already exists
            RobotInfo robot = rc.senseRobotAtLocation(ruin);
            if (robot != null) continue;

            // Check if we can paint it (not enemy paint)
            if (rc.canSenseLocation(ruin)) {
                MapInfo ruinInfo = rc.senseMapInfo(ruin);
                if (ruinInfo.getPaint().isEnemy()) {
                    // Report for splashers to handle
                    Comms.reportRuin(rc, ruin);
                    continue;
                }

                // Paint to deny enemy
                if (!ruinInfo.getPaint().isAlly() && rc.canAttack(ruin)) {
                    Metrics.trackSoldierPriority(5);
                    Metrics.trackRuinDenied();
                    rc.attack(ruin);
                    rc.setIndicatorString("P5.5: Denying ruin!");
                    rc.setIndicatorDot(ruin, 255, 165, 0);
                    return;
                }
            }
        }

        // ===== PRIORITY 6: TOWER BUILDING =====
        MapLocation[] ruins = rc.senseNearbyRuins(-1);
        MapLocation bestRuin = findBuildableRuin(rc, ruins);
        if (bestRuin != null) {
            Metrics.trackSoldierPriority(6);
            targetRuin = bestRuin;
            enterState(SoldierState.BUILDING_TOWER, bestRuin, round);
            handleTowerBuilding(rc, bestRuin);
            return;
        }

        // ===== PRIORITY 7: COMBAT =====
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if (enemies.length > 0) {
            RobotInfo target = Utils.closestRobot(myLoc, enemies);
            if (target != null) {
                Metrics.trackSoldierPriority(7);
                Comms.reportEnemy(rc, target.getLocation(), enemies.length);
                engageEnemy(rc, target);
                return;
            }
        }

        // ===== PRIORITY 8: DEFAULT - EXPLORE & PAINT =====
        Metrics.trackSoldierPriority(8);
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
     * Engage an enemy with paint conservation.
     * Prioritizes attacking from ally-painted tiles to reduce paint damage.
     */
    private static void engageEnemy(RobotController rc, RobotInfo enemy) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        MapLocation enemyLoc = enemy.getLocation();

        rc.setIndicatorString("P7: Engaging " + enemy.getType());
        rc.setIndicatorLine(myLoc, enemyLoc, 255, 128, 0);

        // PAINT CONSERVATION: Move to ally paint before attacking if possible
        MapInfo currentTile = rc.senseMapInfo(myLoc);
        if (!currentTile.getPaint().isAlly() && rc.isMovementReady()) {
            // Find adjacent ally-painted tile still in attack range
            for (Direction dir : Utils.DIRECTIONS) {
                if (!rc.canMove(dir)) continue;
                MapLocation newLoc = myLoc.add(dir);
                if (!rc.canSenseLocation(newLoc)) continue;

                MapInfo newTile = rc.senseMapInfo(newLoc);
                int distToEnemy = newLoc.distanceSquaredTo(enemyLoc);

                // Must be ally paint AND in attack range
                if (newTile.getPaint().isAlly() &&
                    distToEnemy <= rc.getType().actionRadiusSquared) {
                    rc.move(dir);
                    myLoc = newLoc;
                    rc.setIndicatorString("P7: Repositioned to ally paint");
                    break;
                }
            }
        }

        // Now attack from current position
        if (rc.canAttack(enemyLoc)) {
            rc.attack(enemyLoc);
            Metrics.trackAttack();
        }

        // If still need to move closer, use paint-aware scoring
        if (!rc.isMovementReady()) return;

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

        // Alert splashers to help clear enemy paint on pattern tiles
        Comms.broadcastToAllies(rc, Comms.MessageType.TOWER_BUILDING, ruin, 0);

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
            Metrics.trackTowerBuilt();
            rc.setTimelineMarker("Tower built!", 0, 255, 0);
            targetRuin = null;
        }
    }

    /**
     * Default behavior: paint and explore.
     * NOTE: Soldiers CANNOT paint over enemy paint - only splashers can!
     * Focus on expanding into unpainted territory.
     */
    private static void exploreAndPaint(RobotController rc) throws GameActionException {
        Utils.tryPaintCurrent(rc);

        // Priority 1: Find unpainted tiles (real expansion)
        MapLocation paintTarget = findUnpaintedTile(rc);
        if (paintTarget != null) {
            Navigation.moveTo(rc, paintTarget);
            Utils.tryPaintCurrent(rc);
            Metrics.trackTileExpanded();
            rc.setIndicatorString("P8: Expanding territory");
            return;
        }

        // Priority 2: Random exploration when no unpainted nearby
        Utils.tryMoveRandom(rc);
        Utils.tryPaintCurrent(rc);
        Metrics.trackTileExpanded();
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

    // ==================== FSM METHODS ====================

    /**
     * Enter a new FSM state.
     */
    private static void enterState(SoldierState newState, MapLocation target, int round) {
        state = newState;
        stateTarget = target;
        stateStartRound = round;
        stateTurns = 0;
    }

    /**
     * Check state exit conditions and reset to IDLE if needed.
     */
    private static void updateStateTransitions(RobotController rc) throws GameActionException {
        // Always reset on state-specific timeouts
        switch (state) {
            case BUILDING_TOWER:
                if (stateTurns > BUILDING_TIMEOUT) {
                    state = SoldierState.IDLE;
                    return;
                }
                // Exit if target has a robot (tower built or enemy there)
                if (stateTarget != null && rc.canSenseLocation(stateTarget)) {
                    RobotInfo robot = rc.senseRobotAtLocation(stateTarget);
                    if (robot != null) {
                        state = SoldierState.IDLE;
                        targetRuin = null;
                    }
                }
                break;

            case DEFENDING_TOWER:
                if (stateTurns > DEFENDING_TIMEOUT) {
                    state = SoldierState.IDLE;
                    return;
                }
                // Exit if no more threats near the tower
                if (Utils.findPaintTowerUnderAttack(rc) == null) {
                    state = SoldierState.IDLE;
                }
                break;

            case RETREATING:
                if (stateTurns > RETREATING_TIMEOUT) {
                    state = SoldierState.IDLE;
                    return;
                }
                // Exit if health and paint restored
                if (rc.getHealth() > 80 && rc.getPaint() > 100) {
                    state = SoldierState.IDLE;
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
        Metrics.trackSoldierState(state.ordinal());
        switch (state) {
            case BUILDING_TOWER:
                continueBuildingTower(rc);
                break;
            case DEFENDING_TOWER:
                continueDefending(rc);
                break;
            case RETREATING:
                continueRetreating(rc);
                break;
            default:
                break;
        }
    }

    /**
     * Continue building a tower at stateTarget.
     */
    private static void continueBuildingTower(RobotController rc) throws GameActionException {
        if (stateTarget == null) {
            state = SoldierState.IDLE;
            return;
        }

        rc.setIndicatorString("FSM: BUILDING_TOWER t=" + stateTurns);
        rc.setIndicatorLine(rc.getLocation(), stateTarget, 0, 255, 0);

        // Use existing tower building logic
        handleTowerBuilding(rc, stateTarget);
    }

    /**
     * Continue defending a paint tower.
     */
    private static void continueDefending(RobotController rc) throws GameActionException {
        rc.setIndicatorString("FSM: DEFENDING_TOWER t=" + stateTurns);

        // Find the tower we're defending
        RobotInfo towerUnderAttack = Utils.findPaintTowerUnderAttack(rc);
        if (towerUnderAttack != null) {
            defendPaintTower(rc, towerUnderAttack);
        } else {
            // No threat found, transition will handle exit
            Utils.tryPaintCurrent(rc);
        }
    }

    /**
     * Continue retreating until health/paint restored.
     */
    private static void continueRetreating(RobotController rc) throws GameActionException {
        rc.setIndicatorString("FSM: RETREATING t=" + stateTurns);

        // Decide based on what triggered the retreat
        if (rc.getHealth() < HEALTH_CRITICAL) {
            retreat(rc);
        } else if (rc.getPaint() < PAINT_LOW) {
            retreatForPaint(rc);
        } else {
            // Try to fully recover before re-engaging
            retreatForPaint(rc);
        }
    }
}
