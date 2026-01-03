package mybot;

import battlecode.common.*;
import mybot.core.POI;
import mybot.core.Symmetry;

/**
 * Soldier behavior using Priority Chain Pattern.
 *
 * Priorities are processed in order - first match wins (early return).
 * Easy to reorder by moving code blocks.
 */
public class Soldier {

    // ==================== DEBUG LOGGING ====================
    private static final boolean DEBUG = false;  // Set to false for competition

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

    // Thresholds - SPAARK philosophy: "NEVER RETREAT!!!!!!!!"
    // SPAARK only retreats when: paint<150 AND chips<6000 AND allies<9
    // Key insight: Keep fighting, only retreat when truly desperate
    private static final int HEALTH_CRITICAL = 15;  // Very low - keep fighting
    private static final int PAINT_LOW = 50;        // Much lower - SPAARK fights at 50 paint
    private static final int PAINT_MINIMUM = 5;     // ULTRA LOW - fight until empty!
    private static final int WEAK_ENEMY_HEALTH = 60; // Higher = target more enemies
    private static final int EARLY_GAME_ROUNDS = 100;

    // SRP timing - SPAARK: SOL_MIN_SRP_ROUND = 50 (not 200!)
    private static final int SRP_START_ROUND = 50;

    // Builder limit - SPAARK: SOL_MAX_TOWER_BUILDING_SOLDIERS = 2
    private static final int MAX_BUILDERS_PER_TOWER = 2;

    // SPAARK retreat conditions - exit retreat when ANY is true:
    // ULTRA AGGRESSIVE: Get back to fighting ASAP
    // Soldier max paint = 200, so 30 = 15% remaining (enough for 3 attacks)
    private static final int RETREAT_PAINT_THRESHOLD = 30;   // Exit retreat with 30 paint
    private static final int RETREAT_CHIPS_THRESHOLD = 1500; // Even lower - fight more
    private static final int RETREAT_ALLIES_THRESHOLD = 3;   // Fewer allies needed to fight

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

        // ===== SPAWN LOCATION: Remember where we came from (BEFORE any movement!) =====
        if (spawnLocation == null) {
            // Find nearest ally tower as spawn location
            RobotInfo[] allies = rc.senseNearbyRobots(8, rc.getTeam());
            for (RobotInfo ally : allies) {
                if (ally.getType().isTowerType()) {
                    spawnLocation = ally.getLocation();
                    break;
                }
            }
            if (spawnLocation == null) {
                spawnLocation = myLoc;  // Fallback to current position
            }
            POI.init(rc);       // Initialize POI system
            Symmetry.init(rc);  // Initialize symmetry detection
        }

        // ===== ULTRA-FAST PATH: Early game (rounds 1-10) - minimal bytecode =====
        // MUST be before complex logic to avoid bytecode overflow!
        // BUT: Stay near spawn tower, don't wander randomly!
        if (round <= 10) {
            // Attack current tile to paint
            if (rc.canAttack(myLoc)) {
                rc.attack(myLoc);
            }
            // Move toward enemies if nearby, else stay near tower
            RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
            if (enemies.length > 0) {
                Direction toEnemy = myLoc.directionTo(enemies[0].getLocation());
                if (rc.canMove(toEnemy)) {
                    rc.move(toEnemy);
                }
            } else if (myLoc.distanceSquaredTo(spawnLocation) > 16) {
                // Too far from tower, move back
                Direction toSpawn = myLoc.directionTo(spawnLocation);
                if (rc.canMove(toSpawn)) {
                    rc.move(toSpawn);
                }
            } else {
                // Stay near tower, just paint
                Direction dir = Utils.DIRECTIONS[Utils.rng.nextInt(8)];
                if (rc.canMove(dir) && myLoc.add(dir).distanceSquaredTo(spawnLocation) <= 16) {
                    rc.move(dir);
                }
            }
            return;
        }

        // ===== POI & SYMMETRY: Lightweight updates (skip early game for bytecode) =====
        // Only run expensive sensing after round 15 to avoid bytecode issues
        if (round > 15 && round % 3 == 0) {  // Every 3rd round after r15
            POI.updateFromSensors(rc);
        }
        if (round > 10) {
            Comms.processPOIMessages(rc);
        }

        // ===== TRACK PAINT REFILL SUCCESS (skip first 5 rounds) =====
        int currentPaint = rc.getPaint();
        if (round > 5 && currentPaint > lastPaintLevel + 20) {
            Metrics.trackRetreatOutcome("success");
        }
        lastPaintLevel = currentPaint;

        // ===== METRICS: Frequent early game reports (every 25 rounds until r200) =====
        if (Metrics.ENABLED) {
            if (round <= 200 && round % 25 == 0) {
                Metrics.reportEarlyGame(round, rc.getID(), "SOLDIER");
            } else if (round % 100 == 0) {
                Metrics.reportSoldierStats(rc.getID(), round);
            }
        }

        // ==================== FSM UPDATE ====================
        stateTurns++;

        // Check state exit conditions (cheap checks first)
        updateStateTransitions(rc);

        // BUILDING_TOWER: Exit state if under heavy attack (2+ enemies nearby)
        // Let the priority chain handle combat instead of being stuck building
        if (state == SoldierState.BUILDING_TOWER) {
            RobotInfo[] nearbyEnemies = rc.senseNearbyRobots(16, rc.getTeam().opponent());
            if (nearbyEnemies.length >= 2) {
                state = SoldierState.IDLE;
                targetRuin = null;
                targetTowerType = null;
                rc.setIndicatorString("EXIT BUILDING - COMBAT!");
                // Fall through to priority chain for combat
            }
        }

        // If in active state, execute it and return
        if (state != SoldierState.IDLE) {
            executeCurrentState(rc);
            return;
        }

        // ==================== PRIORITY CHAIN (when IDLE) ====================

        // ===== PHASE CHECK (skip early rounds to save bytecode) =====
        int healthThreshold = HEALTH_CRITICAL;
        int paintThreshold = PAINT_LOW;

        // Only read messages after round 10 (saves bytecode early game)
        if (round > 10) {
            boolean defendMode = Comms.hasMessageOfType(rc, Comms.MessageType.PHASE_DEFEND);
            if (defendMode) {
                healthThreshold = HEALTH_CRITICAL + 10;
                paintThreshold = PAINT_LOW + 20;
            } else {
                boolean attackMode = Comms.hasMessageOfType(rc, Comms.MessageType.PHASE_ALL_OUT_ATTACK);
                if (attackMode) {
                    healthThreshold = HEALTH_CRITICAL - 5;
                    paintThreshold = PAINT_LOW - 20;
                }
            }
        }

        // ===== PRIORITY 0: SURVIVAL =====
        if (rc.getHealth() < healthThreshold) {
            Metrics.trackSoldierPriority(0);
            Metrics.trackRetreat();
            enterState(SoldierState.RETREATING, null, round);
            retreat(rc);
            return;
        }

        // ===== PRIORITY 0.5-1.5: MESSAGE-BASED ACTIONS (skip early rounds) =====
        // Skip message processing in early game to save bytecode
        if (round > 10) {
            // PRIORITY 0.5: PAINT TOWER CRITICAL
            MapLocation criticalTower = Comms.getLocationFromMessage(rc, Comms.MessageType.PAINT_TOWER_CRITICAL);
            if (criticalTower != null) {
                Metrics.trackSoldierPriority(0);
                rc.setIndicatorString("P0.5: TOWER CRITICAL!");
                Navigation.moveTo(rc, criticalTower);
                RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
                for (RobotInfo enemy : enemies) {
                    if (rc.canAttack(enemy.getLocation())) {
                        rc.attack(enemy.getLocation());
                        break;
                    }
                }
                return;
            }

            // PRIORITY 1: COORDINATED ATTACK (focus fire)
            MapLocation attackTarget = Comms.getLocationFromMessage(rc, Comms.MessageType.ATTACK_TARGET);
            if (attackTarget != null && myLoc.distanceSquaredTo(attackTarget) <= 100) {
                Metrics.trackSoldierPriority(1);
                rc.setIndicatorString("P1: FOCUS FIRE!");
                if (rc.canAttack(attackTarget)) {
                    rc.attack(attackTarget);
                    Metrics.trackAttack();
                }
                Navigation.moveTo(rc, attackTarget);
                return;
            }

            // PRIORITY 1.5: TOWER DANGER ALERTS
            MapLocation alertedTower = Comms.getLocationFromMessage(rc, Comms.MessageType.PAINT_TOWER_DANGER);
            if (alertedTower != null) {
                Metrics.trackSoldierPriority(1);
                rc.setIndicatorString("P1.5: Tower alert!");
                Navigation.moveTo(rc, alertedTower);
                Utils.tryPaintCurrent(rc);
                return;
            }
        } // End of round > 10 message processing block

        // ===== PRIORITY 2: DEFEND PAINT TOWERS (skip early rounds) =====
        // Skip expensive tower defense check in early game to save bytecode
        if (round > 15) {
            RobotInfo towerUnderAttack = Utils.findPaintTowerUnderAttack(rc);
            if (towerUnderAttack != null) {
                Metrics.trackSoldierPriority(2);
                enterState(SoldierState.DEFENDING_TOWER, towerUnderAttack.getLocation(), round);
                defendPaintTower(rc, towerUnderAttack);
                return;
            }
        }

        // ===== PRIORITY 3: HUNT ENEMY TOWERS (HIGHEST COMBAT PRIORITY!) =====
        // SPAARK wins by destroying our towers - we must destroy theirs FIRST!
        // Check for enemy towers BEFORE any other combat logic
        RobotInfo[] allEnemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());

        for (RobotInfo enemy : allEnemies) {
            if (enemy.getType().isTowerType()) {
                Metrics.trackSoldierPriority(3);
                rc.setIndicatorString("P3: ATTACKING ENEMY TOWER!");
                rc.setIndicatorLine(myLoc, enemy.getLocation(), 255, 0, 0);
                Comms.broadcastAttackTarget(rc, enemy.getLocation());
                if (rc.canAttack(enemy.getLocation())) {
                    rc.attack(enemy.getLocation());
                    Metrics.trackAttack();
                }
                Navigation.moveTo(rc, enemy.getLocation());
                return;
            }
        }

        // ===== PRIORITY 3.5: RESUPPLY =====
        // Retreat when paint is very low (can only do 1 more attack)
        // Attack costs ~10 paint, so retreat at < 10
        int myPaint = rc.getPaint();
        if (myPaint < 10) {
            Metrics.trackSoldierPriority(3);
            enterState(SoldierState.RETREATING, null, round);
            retreatForPaint(rc);
            return;
        }

        // ===== PRIORITY 4: FIGHT VISIBLE ENEMIES =====
        // Engage any visible enemies (after tower hunting)
        if (allEnemies.length > 0) {
            RobotInfo closestEnemy = Utils.closestRobot(myLoc, allEnemies);
            if (closestEnemy != null) {
                Metrics.trackSoldierPriority(4);
                rc.setIndicatorString("P4: COMBAT!");
                engageEnemy(rc, closestEnemy);
                return;
            }
        }

        // ===== PRIORITY 4.5: PUSH TOWARD ENEMY BASE =====
        // After round 30: Start pushing toward enemy to find and kill their units/towers!
        // AGGRESSIVE: The best defense is a good offense
        if (round >= 30) {
            MapLocation enemyBase = Symmetry.predictEnemySpawn(spawnLocation);
            if (enemyBase != null && myLoc.distanceSquaredTo(enemyBase) > 25) {
                Metrics.trackSoldierPriority(4);
                rc.setIndicatorString("P4.5: PUSHING TO ENEMY BASE!");
                Navigation.moveTo(rc, enemyBase);
                return;
            }
        }

        // ===== PRIORITY 5: TOWER BUILDING =====
        // STRATEGIC: Only build towers AFTER round 50 when we have splashers to help
        if (round >= 50) {
            MapLocation[] ruins = rc.senseNearbyRuins(-1);
            if (DEBUG && ruins.length > 0) {
                log(rc, "TOWER", "found " + ruins.length + " ruins, paint=" + rc.getPaint());
            }
            MapLocation bestRuin = findBuildableRuin(rc, ruins);
            if (bestRuin != null) {
                logPrio(rc, 5, "starting tower at " + bestRuin + " paint=" + rc.getPaint());
                Metrics.trackSoldierPriority(5);
                Metrics.trackTowerAttempt();
                targetRuin = bestRuin;
                enterState(SoldierState.BUILDING_TOWER, bestRuin, round);
                handleTowerBuilding(rc, bestRuin);
                return;
            }
        }

        // ===== PRIORITY 6: RUIN DENIAL =====
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
                    Metrics.trackSoldierPriority(6);
                    Metrics.trackRuinDenied();
                    rc.attack(ruin);
                    rc.setIndicatorString("P6: Denying ruin!");
                    rc.setIndicatorDot(ruin, 255, 165, 0);
                    return;
                }
            }
        }

        // ===== PRIORITY 7: BUILD SRP (economy boost) =====
        // SRPs boost income: income = (20 + 3*#SRPs) * #MoneyTowers
        // SPAARK: SOL_MIN_SRP_ROUND = 50 (economy boost early!)
        if (round > SRP_START_ROUND && rc.getPaint() > 100) {  // Earlier SRP building
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
     * Find a ruin without a tower AND without enemy paint on pattern tiles.
     * Soldiers can only paint EMPTY tiles, not enemy paint!
     * SPAARK limits to MAX_BUILDERS_PER_TOWER (2) soldiers per ruin.
     */
    private static MapLocation findBuildableRuin(RobotController rc, MapLocation[] ruins) throws GameActionException {
        if (ruins == null || ruins.length == 0) return null;

        // Only start tower building if we have reasonable paint
        // Lowered from 80 to 40: soldiers will make more trips but keep building
        int currentPaint = rc.getPaint();
        if (currentPaint < 40) {
            return null;  // Not enough paint to build
        }

        MapLocation myLoc = rc.getLocation();
        MapLocation best = null;
        int bestDist = Integer.MAX_VALUE;

        // Sense nearby ally soldiers to enforce builder limit
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());

        for (MapLocation ruin : ruins) {
            RobotInfo robot = rc.senseRobotAtLocation(ruin);
            if (robot != null) continue;

            // SPAARK-inspired: Limit builders per tower to MAX_BUILDERS_PER_TOWER
            // Count ally soldiers near this ruin (within build range ~8)
            int buildersAtRuin = 0;
            for (RobotInfo ally : allies) {
                if (ally.getType() == UnitType.SOLDIER) {
                    int allyDistToRuin = ally.getLocation().distanceSquaredTo(ruin);
                    if (allyDistToRuin <= 8) {  // Within build range
                        buildersAtRuin++;
                    }
                }
            }
            // Skip if already at builder limit (unless we're already building here)
            if (buildersAtRuin >= MAX_BUILDERS_PER_TOWER &&
                (targetRuin == null || !targetRuin.equals(ruin))) {
                if (DEBUG) log(rc, "TOWER", "skipping ruin at " + ruin + " - " + buildersAtRuin + " builders");
                continue;
            }

            // Check for enemy paint on pattern tiles (soldiers can't paint over it!)
            // Pattern is 5x5 around the ruin center
            int enemyPaintCount = 0;
            MapInfo[] patternTiles = rc.senseNearbyMapInfos(ruin, 8);  // 5x5 = radius 2.8 -> squared = 8
            for (MapInfo tile : patternTiles) {
                if (tile.getPaint().isEnemy()) {
                    enemyPaintCount++;
                }
            }

            // Skip ruins with ANY enemy paint - soldiers can't complete without splashers!
            // Even 1-2 enemy tiles can block tower completion indefinitely.
            // Better to find a clean ruin than waste time on a blocked one.
            if (enemyPaintCount > 0) {
                if (DEBUG) log(rc, "TOWER", "skipping ruin at " + ruin + " - " + enemyPaintCount + " enemy tiles");
                continue;
            }

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
        int dist = myLoc.distanceSquaredTo(ruin);
        int myPaint = rc.getPaint();
        int round = rc.getRoundNum();

        // PRIORITY: Complete tower building if we're close (dist < 4)
        // A new tower is worth more than fighting a few enemies
        // Only abandon building if we're far away or enemies are very numerous
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if (enemies.length >= 3 && dist > 4) {
            // Too many enemies and we're far from ruin - abandon and defend
            state = SoldierState.IDLE;
            targetRuin = null;
            targetTowerType = null;
            RobotInfo closestEnemy = Utils.closestRobot(myLoc, enemies);
            if (closestEnemy != null) {
                rc.setIndicatorString("DEFEND! Abandoning distant build");
                engageEnemy(rc, closestEnemy);
            }
            return;
        }
        // If close to ruin (dist < 4), continue building even with enemies

        // DEBUG: Track tower building attempts
        if (Metrics.ENABLED) {
            System.out.println("[BUILDING #" + rc.getID() + " r" + rc.getRoundNum() + "] " +
                "ruin=" + ruin + " dist=" + dist + " paint=" + myPaint);
        }

        // Choose tower type if not already set
        if (targetTowerType == null) {
            targetTowerType = chooseTowerType(rc);
            log(rc, "TOWER", "chose type=" + targetTowerType);
        }

        String towerName = targetTowerType.toString().replace("LEVEL_ONE_", "");
        rc.setIndicatorString("P6: Building " + towerName + " at " + ruin);
        rc.setIndicatorLine(myLoc, ruin, 0, 255, 0);

        // Alert splashers to help clear enemy paint on pattern tiles
        Comms.broadcastToAllies(rc, Comms.MessageType.TOWER_BUILDING, ruin, 0);

        // Move toward ruin if too far
        if (myLoc.distanceSquaredTo(ruin) > 2) {
            Navigation.moveTo(rc, ruin);
            Utils.tryPaintCurrent(rc);
            return;
        }

        // CRITICAL: Mark pattern FIRST before counting tiles!
        // Otherwise tilesRemaining=0 because no tiles are marked yet.
        if (rc.canMarkTowerPattern(targetTowerType, ruin)) {
            rc.markTowerPattern(targetTowerType, ruin);
            if (Metrics.ENABLED) {
                System.out.println("[MARKED #" + rc.getID() + " r" + rc.getRoundNum() + "] " +
                    "pattern marked for " + targetTowerType + " at " + ruin);
            }
        }

        // NOW count how many tiles still need painting (after marking!)
        MapInfo[] preCheckTiles = rc.senseNearbyMapInfos(ruin, 8);
        int tilesRemaining = 0;
        int tilesEnemyBlocked = 0;
        for (MapInfo tile : preCheckTiles) {
            PaintType mark = tile.getMark();
            PaintType paint = tile.getPaint();
            if (mark != PaintType.EMPTY && mark != paint) {
                if (paint.isEnemy()) {
                    tilesEnemyBlocked++;
                } else {
                    tilesRemaining++;
                }
            }
        }

        // Calculate paint threshold - ULTRA AGGRESSIVE to complete patterns!
        // Problem: Soldiers need ~22 tiles, can paint ~7/trip. With threshold=20, they retreat too early.
        // Solution: Paint until nearly empty, then retreat.
        int paintThreshold;
        if (tilesRemaining <= 5) {
            paintThreshold = 0;  // FINISH IT! Paint until completely empty
        } else if (tilesRemaining <= 10) {
            paintThreshold = 5;  // Very aggressive - keep painting
        } else {
            paintThreshold = 10; // Still aggressive - paint more per trip
        }
        // NO distance penalty - we want soldiers to commit to building

        // DEBUG: Show pre-check status with threshold
        if (Metrics.ENABLED && dist <= 2) {
            System.out.println("[PRECHECK #" + rc.getID() + " r" + rc.getRoundNum() + "] " +
                "dist=" + dist + " paint=" + myPaint + " tilesLeft=" + tilesRemaining +
                " enemyBlocked=" + tilesEnemyBlocked + " threshold=" + paintThreshold);
        }

        // CRITICAL FIX: Even with threshold=0, if we have 0 paint we MUST retreat!
        // Previous bug: soldier stuck at ruin with paint=0, tilesLeft=4, threshold=0 forever
        boolean mustRetreat = (rc.getPaint() == 0 && tilesRemaining > 0) ||
                              (rc.getPaint() < paintThreshold);

        if (mustRetreat) {
            log(rc, "TOWER", "LOW PAINT=" + rc.getPaint() + " threshold=" + paintThreshold +
                " tilesLeft=" + tilesRemaining + " -> RETREATING");
            rc.setIndicatorString("P6: Low paint, switching to RETREAT");
            // CRITICAL: Change state so we don't loop back here!
            state = SoldierState.RETREATING;
            stateTarget = null;
            stateTurns = 0;
            // Keep targetRuin/targetTowerType so we can resume after refill
            retreatForPaint(rc);
            return;
        }

        // IMPORTANT: Paint the tile under ourselves FIRST to reduce paint drain damage!
        // Standing on neutral/enemy paint costs health AND paint per turn
        // BUT: Don't self-paint if we're almost done (<=3 tiles left) - prioritize pattern!
        MapInfo currentTile = rc.senseMapInfo(myLoc);
        if (!currentTile.getPaint().isAlly() && rc.canAttack(myLoc) && tilesRemaining > 3) {
            if (Metrics.ENABLED) {
                System.out.println("[SELF-PAINT #" + rc.getID() + " r" + rc.getRoundNum() + "] " +
                    "painting self at " + myLoc + " tilesLeft=" + tilesRemaining);
            }
            rc.attack(myLoc);  // Paint ourselves to reduce damage
            return;  // Continue building next turn
        }

        MapInfo[] patternTiles = rc.senseNearbyMapInfos(ruin, 8);
        int tilesNeedingPaint = 0;
        int tilesEnemyPaint = 0;
        int tilesCorrect = 0;
        MapLocation tileToMoveTo = null;  // Track a tile we need to get closer to

        for (MapInfo tile : patternTiles) {
            PaintType mark = tile.getMark();
            PaintType paint = tile.getPaint();

            if (mark != PaintType.EMPTY) {
                if (mark == paint) {
                    tilesCorrect++;  // Already painted correctly
                } else {
                    tilesNeedingPaint++;
                    MapLocation tileLoc = tile.getMapLocation();

                    // Soldiers CAN'T paint over enemy paint - need splasher help
                    if (paint.isEnemy()) {
                        tilesEnemyPaint++;
                        continue;  // Skip enemy tiles
                    }

                    boolean secondary = (mark == PaintType.ALLY_SECONDARY);
                    if (rc.canAttack(tileLoc)) {
                        if (Metrics.ENABLED) {
                            System.out.println("[PAINT #" + rc.getID() + " r" + rc.getRoundNum() + "] " +
                                "painting " + tileLoc + " correct=" + tilesCorrect + " need=" + tilesNeedingPaint);
                        }
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

        // DEBUG: Show pattern status when we reach this point (all tiles either correct or unreachable)
        if (Metrics.ENABLED) {
            System.out.println("[PATTERN #" + rc.getID() + " r" + rc.getRoundNum() + "] " +
                "ruin=" + ruin + " correct=" + tilesCorrect + " need=" + tilesNeedingPaint +
                " enemy=" + tilesEnemyPaint + " canComplete=" + canComplete);
        }

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
     * AGGRESSIVE EXPANSION: Always push toward enemy territory!
     * Key insight: To reach 70%, we must actively expand, not wander randomly.
     */
    private static void exploreAndPaint(RobotController rc) throws GameActionException {
        Utils.tryPaintCurrent(rc);

        // ALWAYS push toward enemy territory - this is how we reach 70%!
        MapLocation pushTarget = getPushTarget(rc);

        // Priority 1: Find unpainted tiles IN THE PUSH DIRECTION
        MapLocation paintTarget = findUnpaintedTileInDirection(rc, pushTarget);
        if (paintTarget != null) {
            Navigation.moveTo(rc, paintTarget);
            Utils.tryPaintCurrent(rc);
            Metrics.trackTileExpanded();
            rc.setIndicatorString("P8: Pushing toward " + pushTarget);
            return;
        }

        // Priority 2: Just push forward even on ally paint
        Navigation.moveTo(rc, pushTarget);
        Utils.tryPaintCurrent(rc);
        Metrics.trackTileExpanded();
        rc.setIndicatorString("P8: Advancing to " + pushTarget);
    }

    /**
     * Calculate push direction (away from spawn toward enemy).
     * Original logic that worked before POI/Symmetry changes.
     */
    private static MapLocation getPushTarget(RobotController rc) {
        MapLocation myLoc = rc.getLocation();
        int mapWidth = rc.getMapWidth();
        int mapHeight = rc.getMapHeight();
        MapLocation mapCenter = new MapLocation(mapWidth / 2, mapHeight / 2);

        if (spawnLocation != null) {
            int dx = myLoc.x - spawnLocation.x;
            int dy = myLoc.y - spawnLocation.y;

            // If haven't moved far from spawn, push toward center first
            if (Math.abs(dx) < 3 && Math.abs(dy) < 3) {
                return mapCenter;
            }

            // Push in that direction (away from base toward enemy)
            int targetX = Math.min(mapWidth - 1, Math.max(0, myLoc.x + dx * 2));
            int targetY = Math.min(mapHeight - 1, Math.max(0, myLoc.y + dy * 2));
            return new MapLocation(targetX, targetY);
        }

        return mapCenter;
    }

    /**
     * Find unpainted tile that is in the direction of our push target.
     */
    private static MapLocation findUnpaintedTileInDirection(RobotController rc, MapLocation pushTarget) throws GameActionException {
        MapInfo[] tiles = rc.senseNearbyMapInfos();
        MapLocation myLoc = rc.getLocation();
        MapLocation best = null;
        int bestScore = Integer.MIN_VALUE;

        for (MapInfo tile : tiles) {
            if (!tile.isPassable()) continue;

            PaintType paint = tile.getPaint();
            // Only consider unpainted tiles (soldiers can't paint over enemy)
            if (paint == PaintType.EMPTY) {
                MapLocation loc = tile.getMapLocation();
                int distToMe = myLoc.distanceSquaredTo(loc);
                int distToPush = loc.distanceSquaredTo(pushTarget);

                // Score: prefer tiles that are close AND in push direction
                int score = 100 - distToMe - distToPush / 2;

                if (score > bestScore) {
                    bestScore = score;
                    best = loc;
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
                            // SPAARK exits retreat at 150+ paint - stay healthy!
                            if (afterPaint >= 150) {
                                log(rc, "RETREAT", "Paint good (" + afterPaint + ") - resuming normal ops");
                                state = SoldierState.IDLE;
                            }
                            return;
                        }
                    }

                    // If tower has no paint, don't wait forever!
                    // Exit retreat with whatever paint we have if tower is depleted
                    if (towerPaint < 15 && currentPaint >= PAINT_MINIMUM) {
                        log(rc, "RETREAT", "Tower low paint=" + towerPaint + ", exiting with " + currentPaint);
                        state = SoldierState.IDLE;
                        return;
                    }
                }

                // If paint is enough to fight (SPAARK uses 150), we can leave
                if (currentPaint >= 150) {
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
                // Exit if no more threats near the tower (only check every 5 turns to save bytecode)
                if (stateTurns % 5 == 0 && Utils.findPaintTowerUnderAttack(rc) == null) {
                    state = SoldierState.IDLE;
                }
                break;

            case RETREATING:
                if (stateTurns > RETREATING_TIMEOUT) {
                    state = SoldierState.IDLE;
                    return;
                }
                // SPAARK exits retreat when ANY condition breaks:
                // paint >= 150 OR chips >= 6000 OR allies >= 9
                int money = rc.getMoney();
                RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
                if (rc.getHealth() > 30 &&
                    (rc.getPaint() >= RETREAT_PAINT_THRESHOLD ||
                     money >= RETREAT_CHIPS_THRESHOLD ||
                     allies.length >= RETREAT_ALLIES_THRESHOLD)) {
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
