package mybot;

import battlecode.common.*;

/**
 * Tower behavior using Priority Chain Pattern.
 *
 * Towers don't move but have complex spawn decisions.
 * Each priority either acts or falls through to next.
 */
public class Tower {

    // Track spawned units for balance
    private static int soldiersSpawned = 0;
    private static int moppersSpawned = 0;
    private static int splashersSpawned = 0;

    // Rush detection state
    private static boolean rushDetected = false;
    private static int rushDetectedRound = 0;

    public static void run(RobotController rc) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        int round = rc.getRoundNum();
        UnitType myType = rc.getType();
        boolean isPaintTower = Utils.isPaintTower(myType);

        // ===== PRIORITY 0: ECONOMY UPDATE =====
        Utils.updateIncomeEstimate(rc);

        // ===== PRIORITY 1: COMMUNICATION (read messages) =====
        processMessages(rc);

        // Sense enemies once, reuse throughout
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        boolean underThreat = enemies.length > 0;
        int enemySoldierCount = Utils.countEnemySoldiers(rc);

        // ===== PRIORITY 2: RUSH DETECTION =====
        detectRush(rc, round, enemySoldierCount);
        boolean inRushMode = rushDetected && (round - rushDetectedRound < 100);

        // ===== PRIORITY 3: PANIC MODE (Paint Tower under attack) =====
        boolean panicMode = isPaintTower && underThreat;
        if (panicMode) {
            rc.setIndicatorString("P3: PAINT TOWER UNDER ATTACK!");
            Comms.alertPaintTowerDanger(rc, myLoc);
        }

        // ===== PRIORITY 4: BROADCAST THREATS =====
        if (enemies.length > 0) {
            RobotInfo closest = Utils.closestRobot(myLoc, enemies);
            if (closest != null) {
                Comms.broadcastToAllies(rc, Comms.MessageType.ENEMY_SPOTTED,
                    closest.getLocation(), enemies.length);
            }
        }

        // ===== PRIORITY 5: ATTACK ENEMIES =====
        attackEnemies(rc, enemies);

        // ===== PRIORITY 6: SPAWN UNITS =====
        spawnUnit(rc, round, underThreat, panicMode, inRushMode, enemies);

        // ===== PRIORITY 7: UPGRADE (late game only) =====
        if (round > 500 && !panicMode && !inRushMode) {
            tryUpgrade(rc);
        }

        // ===== PRIORITY 8: METRICS REPORTING =====
        if (Metrics.ENABLED) {
            reportMetrics(rc, round);
        }
    }

    // ==================== PRIORITY HELPERS ====================

    /**
     * Priority 2: Detect enemy rush (early game + multiple soldiers).
     */
    private static void detectRush(RobotController rc, int round, int enemySoldierCount) throws GameActionException {
        if (round < 150 && enemySoldierCount >= 2 && !rushDetected) {
            rushDetected = true;
            rushDetectedRound = round;
            rc.setIndicatorString("P2: RUSH DETECTED!");
            rc.setTimelineMarker("Rush detected!", 255, 0, 0);
            Comms.alertRush(rc);
        }
    }

    /**
     * Priority 5: Attack enemies with priority targeting.
     */
    private static void attackEnemies(RobotController rc, RobotInfo[] enemies) throws GameActionException {
        if (enemies.length == 0) return;

        // Priority: soldiers > splashers > moppers
        RobotInfo target = findPriorityTarget(enemies);
        if (target != null && rc.canAttack(target.getLocation())) {
            rc.attack(target.getLocation());
            return;
        }

        // Fallback: attack any enemy in range
        for (RobotInfo enemy : enemies) {
            if (rc.canAttack(enemy.getLocation())) {
                rc.attack(enemy.getLocation());
                return;
            }
        }
    }

    /**
     * Priority 6: Spawn units based on game state.
     */
    private static void spawnUnit(RobotController rc, int round, boolean underThreat,
                                   boolean panicMode, boolean inRushMode,
                                   RobotInfo[] enemies) throws GameActionException {
        UnitType toSpawn = chooseUnitToSpawn(round, underThreat, panicMode, inRushMode);
        if (toSpawn == null) return;

        MapLocation spawnLoc = findSpawnLocation(rc, toSpawn, enemies);
        if (spawnLoc != null && rc.canBuildRobot(toSpawn, spawnLoc)) {
            rc.buildRobot(toSpawn, spawnLoc);
            trackSpawn(toSpawn);

            if (panicMode) {
                rc.setIndicatorString("P6: PANIC SPAWN " + toSpawn);
            } else if (inRushMode) {
                rc.setIndicatorString("P6: RUSH DEFENSE " + toSpawn);
            } else {
                rc.setIndicatorString("P6: Spawned " + toSpawn);
            }
        }
    }

    // ==================== SPAWN LOGIC ====================

    /**
     * Choose unit type to spawn.
     * Sub-priorities:
     *   A. Panic mode → soldiers only
     *   B. Rush mode → 90% soldiers
     *   C. Under threat → soldiers
     *   D. Early game → mostly soldiers
     *   E. Mid game → balanced composition
     *   F. Late game → more splashers
     */
    private static UnitType chooseUnitToSpawn(int round, boolean underThreat,
                                               boolean panicMode, boolean inRushMode) {
        // Sub-A: Panic mode
        if (panicMode) {
            return UnitType.SOLDIER;
        }

        // Sub-B: Rush mode
        if (inRushMode) {
            return Utils.rng.nextInt(10) < 9 ? UnitType.SOLDIER : UnitType.MOPPER;
        }

        // Sub-C: Under threat
        if (underThreat) {
            return UnitType.SOLDIER;
        }

        // Sub-D: Early game (rounds 1-300) - AGGRESSIVE SOLDIER SPAWNING
        if (round < 300) {
            // Almost all soldiers early for maximum paint coverage
            if (soldiersSpawned < 5 || Utils.rng.nextInt(10) < 9) {
                return UnitType.SOLDIER;
            }
            return UnitType.MOPPER;
        }

        // Sub-E: Mid game (200-600)
        if (round < 600) {
            int total = soldiersSpawned + moppersSpawned + splashersSpawned;
            if (total == 0) return UnitType.SOLDIER;

            float soldierRatio = (float) soldiersSpawned / total;
            float mopperRatio = (float) moppersSpawned / total;

            // Target: 50% soldiers, 30% moppers, 20% splashers
            if (soldierRatio < 0.5) return UnitType.SOLDIER;
            if (mopperRatio < 0.3) return UnitType.MOPPER;
            return UnitType.SPLASHER;
        }

        // Sub-F: Late game (600+)
        int choice = Utils.rng.nextInt(10);
        if (choice < 4) return UnitType.SOLDIER;
        if (choice < 6) return UnitType.MOPPER;
        return UnitType.SPLASHER;
    }

    // ==================== HELPER METHODS ====================

    /**
     * Find highest priority target (soldiers > splashers > moppers).
     */
    private static RobotInfo findPriorityTarget(RobotInfo[] enemies) {
        RobotInfo soldier = null;
        RobotInfo splasher = null;
        RobotInfo other = null;

        for (RobotInfo enemy : enemies) {
            switch (enemy.getType()) {
                case SOLDIER:
                    if (soldier == null) soldier = enemy;
                    break;
                case SPLASHER:
                    if (splasher == null) splasher = enemy;
                    break;
                default:
                    if (other == null) other = enemy;
                    break;
            }
        }

        if (soldier != null) return soldier;
        if (splasher != null) return splasher;
        return other;
    }

    /**
     * Find spawn location, preferring away from enemies.
     */
    private static MapLocation findSpawnLocation(RobotController rc, UnitType type,
                                                  RobotInfo[] enemies) throws GameActionException {
        MapLocation myLoc = rc.getLocation();

        // If enemies present, spawn on opposite side
        if (enemies.length > 0) {
            RobotInfo closest = Utils.closestRobot(myLoc, enemies);
            if (closest != null) {
                Direction awayFromEnemy = myLoc.directionTo(closest.getLocation()).opposite();
                MapLocation awayLoc = myLoc.add(awayFromEnemy);
                if (rc.canBuildRobot(type, awayLoc)) {
                    return awayLoc;
                }
                if (rc.canBuildRobot(type, myLoc.add(awayFromEnemy.rotateLeft()))) {
                    return myLoc.add(awayFromEnemy.rotateLeft());
                }
                if (rc.canBuildRobot(type, myLoc.add(awayFromEnemy.rotateRight()))) {
                    return myLoc.add(awayFromEnemy.rotateRight());
                }
            }
        }

        // Default: first available adjacent tile
        for (Direction dir : Utils.DIRECTIONS) {
            MapLocation loc = myLoc.add(dir);
            if (rc.canBuildRobot(type, loc)) {
                return loc;
            }
        }

        return null;
    }

    /**
     * Track spawned units for composition balance.
     */
    private static void trackSpawn(UnitType type) {
        switch (type) {
            case SOLDIER: soldiersSpawned++; break;
            case MOPPER: moppersSpawned++; break;
            case SPLASHER: splashersSpawned++; break;
            default: break;
        }
    }

    /**
     * Process incoming messages.
     */
    private static void processMessages(RobotController rc) throws GameActionException {
        Message[] messages = rc.readMessages(-1);
        // Future: use messages for coordination
    }

    /**
     * Try to upgrade this tower.
     */
    private static void tryUpgrade(RobotController rc) throws GameActionException {
        rc.setIndicatorString("P7: Would upgrade if possible");
    }

    /**
     * Report tower-local metrics periodically.
     * Note: In Battlecode, static state is NOT shared between robots.
     * Each robot reports its own stats via unit-specific methods.
     * Tower can only report tower-specific metrics here.
     */
    private static void reportMetrics(RobotController rc, int round) {
        // Tower-specific metrics only (spawns are tracked in Tower)
        if (round % 500 == 0) {
            System.out.println("[TOWER #" + rc.getID() + " r" + round + "] " +
                "spawned: soldiers=" + soldiersSpawned +
                " moppers=" + moppersSpawned +
                " splashers=" + splashersSpawned);
        }
    }
}
