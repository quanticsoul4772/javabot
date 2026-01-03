package mybot;

import battlecode.common.*;

/**
 * Soldier behavior using Priority Chain Pattern.
 *
 * Priorities are processed in order - first match wins (early return).
 * Easy to reorder by moving code blocks.
 */
public class Soldier {

    // ==================== DEBUG LOGGING ====================
    private static final boolean DEBUG = true;  // Set to false for competition

    private static void log(RobotController rc, String prefix, String msg) {
        if (!DEBUG) return;
        System.out.println("[S#" + rc.getID() + " r" + rc.getRoundNum() + "] " + prefix + ": " + msg);
    }

    private static void logState(RobotController rc, SoldierState from, SoldierState to, String reason) {
        if (!DEBUG) return;
        System.out.println("[S#" + rc.getID() + " r" + rc.getRoundNum() + "] STATE: " + from + " -> " + to + " (" + reason + ")");
    }

    private static void logPrio(RobotController rc, int prio, String action) {
        if (!DEBUG) return;
        System.out.println("[S#" + rc.getID() + " r" + rc.getRoundNum() + "] P" + prio + ": " + action);
    }

    private static MapLocation targetRuin = null;
    private static UnitType targetTowerType = null;  // Tower type being built

    // Thresholds (tune these during competition)
    // CRITICAL: In Battlecode 2025, paint=0 means CAN'T MOVE! Must retreat early.
    private static final int HEALTH_CRITICAL = 15;  // Lower = fight longer
    private static final int PAINT_LOW = 50;        // Raised! Need paint to reach tower
    private static final int WEAK_ENEMY_HEALTH = 60; // Higher = target more enemies
    private static final int EARLY_GAME_ROUNDS = 100;

    // ==================== SPAWN LOCATION ====================
    private static MapLocation spawnLocation = null;  // Remember where we spawned
    private static int lastPaintLevel = 100;          // Track paint changes

    // ==================== FSM STATE ====================
    enum SoldierState { IDLE, BUILDING_TOWER, BUILDING_SRP, DEFENDING_TOWER, RETREATING }
    private static SoldierState state = SoldierState.IDLE;
    private static MapLocation stateTarget = null;
    private static int stateStartRound = 0;
    private static int stateTurns = 0;

    // State timeout values (turns)
    private static final int BUILDING_TIMEOUT = 100;
    private static final int SRP_TIMEOUT = 60;  // Shorter timeout for SRP building
    private static final int DEFENDING_TIMEOUT = 30;
    private static final int RETREATING_TIMEOUT = 50;

    // SRP (Special Resource Pattern) state
    private static MapLocation targetSRP = null;

    public static void run(RobotController rc) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        int round = rc.getRoundNum();

        // ===== DEBUG: Status every 25 rounds =====
        if (DEBUG && round % 25 == 0) {
            log(rc, "STATUS", "state=" + state + " paint=" + rc.getPaint() + " hp=" + rc.getHealth() +
                " loc=" + myLoc + " target=" + targetRuin);
        }

        // ===== SPAWN LOCATION: Remember where we came from =====
        if (spawnLocation == null) {
            RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
            for (RobotInfo ally : allies) {
                if (ally.getType().isTowerType()) {
                    spawnLocation = ally.getLocation();
                    break;
                }
            }
            if (spawnLocation == null) {
                spawnLocation = myLoc;
            }
        }

        // ===== TRACK PAINT REFILL SUCCESS =====
        int currentPaint = rc.getPaint();
        if (currentPaint > lastPaintLevel + 20) {
            Metrics.trackRetreatOutcome("success");
        }
        lastPaintLevel = currentPaint;

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

        // ===== PRIORITY 1: COORDINATED ATTACK (focus fire) =====
        MapLocation attackTarget = Comms.getLocationFromMessage(rc, Comms.MessageType.ATTACK_TARGET);
        if (attackTarget != null) {
            int dist = myLoc.distanceSquaredTo(attackTarget);
            if (dist <= 100) {  // Within 10 tiles
                Metrics.trackSoldierPriority(1);
                Metrics.trackMessageActedOn();
                rc.setIndicatorString("P1: FOCUS FIRE!");
                rc.setIndicatorLine(myLoc, attackTarget, 255, 0, 255);

                if (rc.canAttack(attackTarget)) {
                    rc.attack(attackTarget);
                    Metrics.trackAttack();
                }
                Navigation.moveTo(rc, attackTarget);
                return;
            }
        }

        // ===== PRIORITY 1.5: CRITICAL ALERTS (from communication) =====
        MapLocation alertedTower = Comms.getLocationFromMessage(rc, Comms.MessageType.PAINT_TOWER_DANGER);
        if (alertedTower != null) {
            Metrics.trackSoldierPriority(1);
            Metrics.trackMessageActedOn();
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
            logPrio(rc, 3, "RETREAT paint=" + rc.getPaint() + " < threshold=" + paintThreshold);
            Metrics.trackSoldierPriority(3);
            Metrics.trackLowPaint();
            enterState(SoldierState.RETREATING, null, round);
            retreatForPaint(rc);
            return;
        }

        // ===== PRIORITY 3.5: BUILD SRP (economy boost) =====
        // SRPs boost income: income = (20 + 3*#SRPs) * #MoneyTowers
        if (round > 200 && rc.getPaint() > 100) {  // Mid-game with good paint
            // Continue existing SRP build
            if (state == SoldierState.BUILDING_SRP && targetSRP != null) {
                handleSRPBuilding(rc, targetSRP);
                return;
            }

            // Find new SRP location
            MapLocation srpLoc = findSRPLocation(rc);
            if (srpLoc != null) {
                System.out.println("[SRP] Soldier #" + rc.getID() + " starting SRP at " + srpLoc);
                Metrics.trackSRPAttempt();
                targetSRP = srpLoc;
                enterState(SoldierState.BUILDING_SRP, srpLoc, round);
                handleSRPBuilding(rc, srpLoc);
                return;
            }
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

        // ===== PRIORITY 4.5: HUNT ENEMY TOWERS =====
        RobotInfo[] allEnemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        for (RobotInfo enemy : allEnemies) {
            if (Utils.isPaintTower(enemy.getType())) {
                Metrics.trackSoldierPriority(4);
                rc.setIndicatorString("P4.5: HUNTING ENEMY TOWER!");
                rc.setIndicatorLine(myLoc, enemy.getLocation(), 255, 0, 0);

                // Broadcast to coordinate attack
                Comms.broadcastAttackTarget(rc, enemy.getLocation());

                if (rc.canAttack(enemy.getLocation())) {
                    rc.attack(enemy.getLocation());
                    Metrics.trackAttack();
                }
                Navigation.moveTo(rc, enemy.getLocation());
                return;
            }
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
        if (DEBUG && ruins.length > 0) {
            log(rc, "TOWER", "found " + ruins.length + " ruins, paint=" + rc.getPaint());
        }
        MapLocation bestRuin = findBuildableRuin(rc, ruins);
        if (bestRuin != null) {
            logPrio(rc, 6, "starting tower at " + bestRuin + " paint=" + rc.getPaint());
            Metrics.trackSoldierPriority(6);
            Metrics.trackTowerAttempt();  // Track attempt for TowerSuccess metric
            targetRuin = bestRuin;
            enterState(SoldierState.BUILDING_TOWER, bestRuin, round);
            handleTowerBuilding(rc, bestRuin);
            return;
        }

        // ===== PRIORITY 7: COMBAT (threat-based targeting) =====
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if (enemies.length > 0) {
            // Use threat-based targeting for intelligent combat
            RobotInfo target = Utils.findHighestThreat(rc, enemies);
            if (target != null) {
                Metrics.trackSoldierPriority(7);
                Comms.reportEnemy(rc, target.getLocation(), enemies.length);
                // Broadcast for focus fire coordination
                Comms.broadcastAttackTarget(rc, target.getLocation());
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

        // Track combat paint metrics
        Metrics.trackCombatTurn(currentTile.getPaint().isAlly());
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
     * Choose which tower type to build based on game state.
     * Strategy:
     *   - First tower: Paint Tower (need paint for units)
     *   - Second tower: Money Tower (economy is critical)
     *   - After that: Alternate Money/Paint, with Defense if under attack
     */
    private static UnitType chooseTowerType(RobotController rc) throws GameActionException {
        int round = rc.getRoundNum();

        // Count our tower types in visible range
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        int paintTowers = 0;
        int moneyTowers = 0;
        int defenseTowers = 0;

        for (RobotInfo ally : allies) {
            UnitType type = ally.getType();
            if (type == UnitType.LEVEL_ONE_PAINT_TOWER ||
                type == UnitType.LEVEL_TWO_PAINT_TOWER ||
                type == UnitType.LEVEL_THREE_PAINT_TOWER) {
                paintTowers++;
            } else if (type == UnitType.LEVEL_ONE_MONEY_TOWER ||
                       type == UnitType.LEVEL_TWO_MONEY_TOWER ||
                       type == UnitType.LEVEL_THREE_MONEY_TOWER) {
                moneyTowers++;
            } else if (type == UnitType.LEVEL_ONE_DEFENSE_TOWER ||
                       type == UnitType.LEVEL_TWO_DEFENSE_TOWER ||
                       type == UnitType.LEVEL_THREE_DEFENSE_TOWER) {
                defenseTowers++;
            }
        }

        // Check if under attack (enemies nearby)
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        boolean underAttack = enemies.length >= 2;

        // Decision logic - prioritize early defense:
        // - Defense towers protect paint towers (critical infrastructure)
        // - Money towers produce 4x resources vs paint towers
        // - Income = (20 + 3*SRPs) * #MoneyTowers

        // If we can see towers, use visible counts
        int totalVisible = paintTowers + moneyTowers + defenseTowers;

        if (totalVisible > 0) {
            // We can see towers - make informed decision
            // PRIORITY 1: Defense when under attack
            if (underAttack && defenseTowers == 0) {
                return UnitType.LEVEL_ONE_DEFENSE_TOWER;
            }

            // PRIORITY 2: First defense tower ASAP after first paint tower (before round 200)
            // Defense protects our paint towers from being destroyed
            if (defenseTowers == 0 && paintTowers >= 1 && round < 200) {
                return UnitType.LEVEL_ONE_DEFENSE_TOWER;
            }

            // After round 200, assume we probably have defense somewhere - focus economy
            // Money towers are crucial for income!
            if (moneyTowers < paintTowers * 2) {
                return UnitType.LEVEL_ONE_MONEY_TOWER;
            }

            // Keep defense towers proportional (1 per 2 money towers)
            if (defenseTowers < moneyTowers / 2) {
                return UnitType.LEVEL_ONE_DEFENSE_TOWER;
            }

            if (moneyTowers <= paintTowers) {
                return UnitType.LEVEL_ONE_MONEY_TOWER;
            }

            return UnitType.LEVEL_ONE_PAINT_TOWER;
        }

        // No towers visible - use ROUND-BASED cycling for determinism
        // Very early game (first tower): Paint tower for survival
        if (round < 75) {
            return UnitType.LEVEL_ONE_PAINT_TOWER;
        }

        // Early game: Get defense up quickly (round 75-150)
        if (round < 150) {
            // Alternate: Paint, Defense, Paint, Defense...
            int selector = (rc.getID() + round / 25) % 2;
            if (selector == 0) {
                return UnitType.LEVEL_ONE_DEFENSE_TOWER;
            }
            return UnitType.LEVEL_ONE_PAINT_TOWER;
        }

        // Mid/Late game: Focus on economy with defense support
        // Ratio: 2 Money : 1 Defense : 1 Paint (50% / 25% / 25%)
        int robotId = rc.getID();
        int selector = (robotId + round / 50) % 4;

        if (selector == 0 || selector == 1) {
            return UnitType.LEVEL_ONE_MONEY_TOWER;  // 50% money towers
        } else if (selector == 2) {
            return UnitType.LEVEL_ONE_DEFENSE_TOWER;  // 25% defense towers
        }

        return UnitType.LEVEL_ONE_PAINT_TOWER;  // 25% paint towers
    }

    /**
     * Find a ruin without a tower.
     */
    private static MapLocation findBuildableRuin(RobotController rc, MapLocation[] ruins) throws GameActionException {
        if (ruins == null || ruins.length == 0) return null;

        // Only start tower building if we have reasonable paint
        // Building pattern costs ~50 paint, need buffer for movement
        int currentPaint = rc.getPaint();
        if (currentPaint < 80) {
            return null;  // Not enough paint to build
        }

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

        // Choose tower type if not already set
        if (targetTowerType == null) {
            targetTowerType = chooseTowerType(rc);
            log(rc, "TOWER", "chose type=" + targetTowerType);
        }

        String towerName = targetTowerType.toString().replace("LEVEL_ONE_", "");
        rc.setIndicatorString("P6: Building " + towerName + " at " + ruin);
        rc.setIndicatorLine(myLoc, ruin, 0, 255, 0);

        // CRITICAL: In Battlecode 2025, units with paint=0 CANNOT MOVE!
        // Must retreat with enough paint to actually reach a tower.
        // Need ~50 paint: movement costs + paint drain + buffer
        if (rc.getPaint() < 50) {
            log(rc, "TOWER", "LOW PAINT=" + rc.getPaint() + " -> RETREATING state (need 50+)");
            rc.setIndicatorString("P6: Low paint, switching to RETREAT");
            // CRITICAL: Change state so we don't loop back here!
            state = SoldierState.RETREATING;
            stateTarget = null;
            stateTurns = 0;
            // Keep targetRuin/targetTowerType so we can resume after refill
            retreatForPaint(rc);
            return;
        }

        // Alert splashers to help clear enemy paint on pattern tiles
        Comms.broadcastToAllies(rc, Comms.MessageType.TOWER_BUILDING, ruin, 0);

        if (myLoc.distanceSquaredTo(ruin) > 2) {
            Navigation.moveTo(rc, ruin);
            Utils.tryPaintCurrent(rc);
            return;
        }

        if (rc.canMarkTowerPattern(targetTowerType, ruin)) {
            rc.markTowerPattern(targetTowerType, ruin);
        }

        MapInfo[] patternTiles = rc.senseNearbyMapInfos(ruin, 8);
        int tilesNeedingPaint = 0;
        int tilesEnemyPaint = 0;
        MapLocation tileToMoveTo = null;  // Track a tile we need to get closer to

        for (MapInfo tile : patternTiles) {
            PaintType mark = tile.getMark();
            PaintType paint = tile.getPaint();

            if (mark != PaintType.EMPTY && mark != paint) {
                tilesNeedingPaint++;
                MapLocation tileLoc = tile.getMapLocation();

                // Soldiers CAN'T paint over enemy paint - need splasher help
                if (paint.isEnemy()) {
                    tilesEnemyPaint++;
                    continue;  // Skip enemy tiles
                }

                boolean secondary = (mark == PaintType.ALLY_SECONDARY);
                if (rc.canAttack(tileLoc)) {
                    rc.attack(tileLoc, secondary);
                    return;
                } else {
                    // Can't attack - remember this tile to move towards it
                    if (tileToMoveTo == null) {
                        tileToMoveTo = tileLoc;
                    }
                }
            }
        }

        // If we couldn't paint anything but there are tiles needing paint, move toward them
        if (tileToMoveTo != null && rc.isMovementReady()) {
            Navigation.moveTo(rc, tileToMoveTo);
            return;
        }

        // If all remaining tiles have enemy paint, we need splasher help
        if (tilesEnemyPaint > 0 && tilesNeedingPaint == tilesEnemyPaint) {
            rc.setIndicatorString("P6: Need splasher help - enemy paint!");
            // Already broadcasted TOWER_BUILDING, just wait or move away
        }

        boolean canComplete = rc.canCompleteTowerPattern(targetTowerType, ruin);

        if (canComplete) {
            rc.completeTowerPattern(targetTowerType, ruin);
            Metrics.trackTowerBuilt();
            Metrics.trackTowerBuiltByType(targetTowerType.toString());
            Metrics.trackMilestone("tower", rc.getRoundNum());
            if (targetTowerType.toString().contains("DEFENSE")) {
                Metrics.trackMilestone("defense", rc.getRoundNum());
            }
            rc.setTimelineMarker(towerName + " built!", 0, 255, 0);
            System.out.println("[TOWER BUILT] " + targetTowerType + " at " + ruin);
            targetRuin = null;
            targetTowerType = null;  // Reset for next tower
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
        MapLocation myLoc = rc.getLocation();

        // Log current tile paint type and health
        MapInfo currentTile = rc.senseMapInfo(myLoc);
        PaintType standingOn = currentTile.getPaint();
        if (DEBUG) {
            log(rc, "RETREAT", "hp=" + rc.getHealth() + " paint=" + rc.getPaint() +
                " standing on " + standingOn + " at " + myLoc);
        }

        // Priority 1: Find visible PAINT or DEFENSE tower (they have paint to give)
        // NEVER go to MONEY towers - they have 0 paint!
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        MapLocation towerLoc = null;
        for (RobotInfo ally : allies) {
            UnitType type = ally.getType();
            if (type.isTowerType()) {
                // Only Paint and Defense towers have paint
                // Money towers have 0 paint - skip them!
                if (Utils.isPaintTower(type) || Utils.isDefenseTower(type)) {
                    towerLoc = ally.getLocation();
                    break;  // Found usable tower
                }
            }
        }

        if (towerLoc != null) {
            int dist = myLoc.distanceSquaredTo(towerLoc);
            log(rc, "RETREAT", "found tower at " + towerLoc + " dist=" + dist);

            // If adjacent to tower (dist <= 2), TAKE paint from tower!
            if (dist <= 2) {
                // KEY INSIGHT: In Battlecode 2025, units must TAKE paint from towers!
                // Use transferPaint with NEGATIVE amount to take paint.
                int currentPaint = rc.getPaint();
                int maxPaint = rc.getType().paintCapacity;  // 200 for soldiers
                int needed = maxPaint - currentPaint;

                if (needed > 10) {  // Only bother if we need paint
                    RobotInfo towerInfo = rc.senseRobotAtLocation(towerLoc);
                    int towerPaint = (towerInfo != null) ? towerInfo.getPaintAmount() : 0;

                    // Can only take what the tower has (and leave some for tower)
                    int canTake = Math.min(needed, towerPaint - 10);  // Leave 10 for tower
                    if (canTake > 0 && rc.isActionReady()) {
                        int takeAmount = -canTake;  // Negative = take from tower
                        if (rc.canTransferPaint(towerLoc, takeAmount)) {
                            rc.transferPaint(towerLoc, takeAmount);
                            int afterPaint = rc.getPaint();
                            log(rc, "RETREAT", "TOOK " + canTake + " paint! Now have " + afterPaint);
                            rc.setIndicatorString("Refilled: " + afterPaint + " paint");
                            // Exit retreat when we have enough paint to build (80+)
                            if (afterPaint >= 80) {
                                log(rc, "RETREAT", "Paint good (" + afterPaint + ") - resuming normal ops");
                                state = SoldierState.IDLE;
                            }
                            return;
                        }
                    }

                    // If tower has no paint, maybe find another tower nearby
                    if (towerPaint < 15) {
                        log(rc, "RETREAT", "Tower at " + towerLoc + " has low paint=" + towerPaint + ", waiting...");
                    }
                }

                // If paint is enough to build, we can leave
                if (currentPaint >= 80) {
                    log(rc, "RETREAT", "Paint refilled to " + currentPaint + ", exiting retreat");
                    state = SoldierState.IDLE;
                    return;
                }

                // If on ally paint, stay put and try again next turn
                if (standingOn.isAlly()) {
                    log(rc, "RETREAT", "Waiting on ally paint near tower - paint=" + rc.getPaint());
                    rc.setIndicatorString("Waiting for paint transfer...");
                    return;
                }

                // On EMPTY/enemy paint - need to get to ally paint
                // First: If we have paint, paint our current tile to stop drain!
                if (rc.getPaint() >= 5 && rc.canAttack(myLoc)) {
                    log(rc, "RETREAT", "painting current tile to stop drain");
                    rc.attack(myLoc);
                    return;
                }

                // Second: Try to move to ally paint tile near tower
                MapLocation bestAllyTile = null;
                int bestDist = Integer.MAX_VALUE;
                for (Direction dir : Direction.allDirections()) {
                    if (dir == Direction.CENTER) continue;
                    MapLocation adjLoc = towerLoc.add(dir);
                    if (!rc.canSenseLocation(adjLoc)) continue;
                    MapInfo adjInfo = rc.senseMapInfo(adjLoc);
                    if (adjInfo.getPaint().isAlly()) {
                        int d = myLoc.distanceSquaredTo(adjLoc);
                        if (d < bestDist) {
                            bestDist = d;
                            bestAllyTile = adjLoc;
                        }
                    }
                }
                if (bestAllyTile != null) {
                    log(rc, "RETREAT", "moving to ally paint at " + bestAllyTile);
                    Navigation.moveTo(rc, bestAllyTile);
                    return;
                }

                // No ally paint near tower - just wait here and hope tower paints
                log(rc, "RETREAT", "no ally paint near tower, waiting");
                return;
            }

            rc.setIndicatorString("P3: Retreating to tower at " + towerLoc);
            rc.setIndicatorLine(myLoc, towerLoc, 0, 255, 0);
            Metrics.trackRetreatOutcome("tower");
            Navigation.moveTo(rc, towerLoc);
            return;
        }

        // Priority 2: Follow ally paint trail toward spawn
        // Skip to spawn if we're not making progress toward a tower
        if (spawnLocation != null) {
            int distToSpawn = myLoc.distanceSquaredTo(spawnLocation);
            if (distToSpawn > 4) {  // If not near spawn, go directly there
                log(rc, "RETREAT", "heading to spawn at " + spawnLocation + " dist=" + distToSpawn);
                rc.setIndicatorString("P3: Returning to spawn (" + distToSpawn + " away)");
                Metrics.trackRetreatOutcome("spawn");
                Navigation.moveTo(rc, spawnLocation);
                return;
            }
        }

        // Priority 3: Navigate to spawn location
        if (spawnLocation != null) {
            int distToSpawn = myLoc.distanceSquaredTo(spawnLocation);
            log(rc, "RETREAT", "heading to spawn at " + spawnLocation + " dist=" + distToSpawn);
            rc.setIndicatorString("P3: Returning to spawn (" + distToSpawn + " away)");
            rc.setIndicatorLine(myLoc, spawnLocation, 255, 255, 0);
            Metrics.trackRetreatOutcome("wandering");
            Navigation.moveTo(rc, spawnLocation);
            return;
        }

        // Fallback: Random movement
        log(rc, "RETREAT", "LOST - no spawn, no paint, no tower!");
        rc.setIndicatorString("P3: LOST - no spawn location!");
        Metrics.trackRetreatOutcome("wandering");
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
                    targetTowerType = null;  // Reset tower type
                    return;
                }
                // Exit if target has a robot (tower built or enemy there)
                if (stateTarget != null && rc.canSenseLocation(stateTarget)) {
                    RobotInfo robot = rc.senseRobotAtLocation(stateTarget);
                    if (robot != null) {
                        state = SoldierState.IDLE;
                        targetRuin = null;
                        targetTowerType = null;  // Reset tower type
                    }
                }
                break;

            case BUILDING_SRP:
                if (stateTurns > SRP_TIMEOUT) {
                    state = SoldierState.IDLE;
                    targetSRP = null;
                    return;
                }
                // Note: After marking, canMarkResourcePattern returns false,
                // so we only exit on timeout or if we've completed successfully
                // (completion is handled in handleSRPBuilding)
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
                // Need 120+ paint to be productive (tower building needs ~70)
                if (rc.getHealth() > 50 && rc.getPaint() >= 120) {
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
            case BUILDING_SRP:
                continueBuildingSRP(rc);
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

    // ==================== SRP (Special Resource Pattern) METHODS ====================

    /**
     * Continue building an SRP at targetSRP.
     */
    private static void continueBuildingSRP(RobotController rc) throws GameActionException {
        if (targetSRP == null) {
            state = SoldierState.IDLE;
            return;
        }

        rc.setIndicatorString("FSM: BUILDING_SRP t=" + stateTurns);
        rc.setIndicatorLine(rc.getLocation(), targetSRP, 255, 255, 0);  // Yellow

        handleSRPBuilding(rc, targetSRP);
    }

    /**
     * Find a suitable location to build an SRP.
     * Strategy: Check all visible passable tiles - canMarkResourcePattern is the key filter.
     */
    private static MapLocation findSRPLocation(RobotController rc) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        MapLocation best = null;
        int bestScore = Integer.MIN_VALUE;

        // Get all visible tiles and find valid SRP centers
        MapInfo[] tiles = rc.senseNearbyMapInfos();
        for (MapInfo tile : tiles) {
            if (!tile.isPassable()) continue;  // Must be passable

            MapLocation candidate = tile.getMapLocation();

            // Check if we can mark SRP here (API handles all requirements)
            if (!rc.canMarkResourcePattern(candidate)) continue;

            // Score the location (prefer ally paint, penalize enemy)
            int score = scoreSRPLocation(rc, candidate);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        return best;
    }

    /**
     * Score a potential SRP location based on:
     * - Ally paint coverage (higher = better)
     * - Enemy paint (penalty)
     * - Distance from us (closer = better)
     */
    private static int scoreSRPLocation(RobotController rc, MapLocation center) throws GameActionException {
        int score = 0;
        int allyPaint = 0;

        // Check 5x5 area
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                MapLocation tile = center.translate(dx, dy);
                if (!rc.canSenseLocation(tile)) return -1000;
                MapInfo info = rc.senseMapInfo(tile);
                if (!info.isPassable()) return -1000;  // Wall/ruin blocks SRP
                if (info.getPaint().isAlly()) allyPaint++;
                if (info.getPaint().isEnemy()) score -= 5;  // Penalty for enemy paint
            }
        }

        score += allyPaint * 2;  // Reward ally paint coverage
        score -= rc.getLocation().distanceSquaredTo(center) / 4;  // Closer is better
        return score;
    }

    /**
     * Handle SRP pattern building (similar to tower building).
     */
    private static void handleSRPBuilding(RobotController rc, MapLocation center) throws GameActionException {
        MapLocation myLoc = rc.getLocation();

        rc.setIndicatorString("Building SRP at " + center);
        rc.setIndicatorLine(myLoc, center, 255, 255, 0);  // Yellow line

        // Step 1: Mark the pattern if not marked
        if (rc.canMarkResourcePattern(center)) {
            rc.markResourcePattern(center);
        }

        // Step 2: Check if we can complete
        if (rc.canCompleteResourcePattern(center)) {
            rc.completeResourcePattern(center);
            Metrics.trackSRPBuilt();
            Metrics.trackMilestone("srp", rc.getRoundNum());
            System.out.println("[SRP BUILT] #" + rc.getID() + " completed at " + center);
            rc.setTimelineMarker("SRP built!", 255, 255, 0);
            targetSRP = null;
            state = SoldierState.IDLE;
            return;
        }

        // Step 3: Paint pattern tiles
        boolean[][] pattern = rc.getResourcePattern();
        int tilesNeedingPaint = 0;
        int tilesWithMarks = 0;
        MapLocation tileToMoveTo = null;

        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                MapLocation tile = center.translate(dx, dy);
                if (!rc.canSenseLocation(tile)) continue;

                MapInfo info = rc.senseMapInfo(tile);
                PaintType mark = info.getMark();
                PaintType paint = info.getPaint();

                if (mark != PaintType.EMPTY) tilesWithMarks++;

                // Need to paint if mark doesn't match current paint
                if (mark != PaintType.EMPTY && mark != paint) {
                    tilesNeedingPaint++;

                    // Skip enemy paint - need splasher help
                    if (paint.isEnemy()) continue;

                    boolean secondary = (mark == PaintType.ALLY_SECONDARY);
                    if (rc.canAttack(tile)) {
                        rc.attack(tile, secondary);
                        return;  // One tile per turn
                    } else {
                        tileToMoveTo = tile;
                    }
                }
            }
        }

        // Move closer to tiles that need painting
        if (tileToMoveTo != null) {
            Navigation.moveTo(rc, tileToMoveTo);
        } else if (tilesNeedingPaint == 0 && tilesWithMarks > 0) {
            // All tiles painted but can't complete - move to center
            Navigation.moveTo(rc, center);
        }
    }
}
