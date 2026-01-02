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

    // Critical health threshold (~30% of tower health)
    private static final int TOWER_HEALTH_CRITICAL = 100;

    // Phase coordination
    private static int lastPhaseBroadcast = 0;
    private static final int PHASE_BROADCAST_INTERVAL = 50;
    private static final int LATE_GAME_ROUND = 800;  // Earlier aggression

    public static void run(RobotController rc) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        int round = rc.getRoundNum();
        UnitType myType = rc.getType();
        boolean isPaintTower = Utils.isPaintTower(myType);

        // ===== PRIORITY 0: ECONOMY UPDATE =====
        Utils.updateIncomeEstimate(rc);

        // ===== PRIORITY 0.5: PAINT SAMPLING (every 50 rounds) =====
        if (round % 50 == 0) {
            samplePaintCoverage(rc);
        }

        // ===== PRIORITY 1: COMMUNICATION (read messages) =====
        processMessages(rc);

        // Sense enemies once, reuse throughout
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        boolean underThreat = enemies.length > 0;
        int enemySoldierCount = Utils.countEnemySoldiers(rc);

        // ===== PRIORITY 2: RUSH DETECTION =====
        detectRush(rc, round, enemySoldierCount);
        boolean inRushMode = rushDetected && (round - rushDetectedRound < 100);

        // ===== PRIORITY 2.5: CRITICAL HEALTH ALERT =====
        if (isPaintTower && rc.getHealth() < TOWER_HEALTH_CRITICAL) {
            rc.setIndicatorString("P2.5: PAINT TOWER CRITICAL!");
            rc.setTimelineMarker("Tower critical!", 255, 0, 0);
            Comms.alertPaintTowerCritical(rc, myLoc);
        }

        // ===== PRIORITY 3: PANIC MODE (Paint Tower under attack) =====
        boolean panicMode = isPaintTower && underThreat;
        if (panicMode) {
            rc.setIndicatorString("P3: PAINT TOWER UNDER ATTACK!");
            Comms.alertPaintTowerDanger(rc, myLoc);
        }

        // ===== PRIORITY 3.5: COORDINATE ATTACK ON THREATS =====
        // Find allies' paint towers and coordinate attacks on enemies near them
        if (enemies.length > 0) {
            // Track enemy composition for strategy detection
            Utils.trackEnemyComposition(enemies);

            // Find enemy closest to our location (paint tower priority)
            RobotInfo biggestThreat = null;
            int closestDist = Integer.MAX_VALUE;
            for (RobotInfo enemy : enemies) {
                int dist = enemy.getLocation().distanceSquaredTo(myLoc);
                if (dist < closestDist && dist <= 64) {  // Within 8 tiles
                    closestDist = dist;
                    biggestThreat = enemy;
                }
            }

            if (biggestThreat != null) {
                Comms.broadcastToAllies(rc, Comms.MessageType.ATTACK_TARGET,
                    biggestThreat.getLocation(), closestDist);
            }
        }

        // ===== PRIORITY 4: BROADCAST THREATS =====
        if (enemies.length > 0) {
            RobotInfo closest = Utils.closestRobot(myLoc, enemies);
            if (closest != null) {
                Comms.broadcastToAllies(rc, Comms.MessageType.ENEMY_SPOTTED,
                    closest.getLocation(), enemies.length);
            }
        }

        // ===== PRIORITY 4.5: PHASE COORDINATION =====
        if (round - lastPhaseBroadcast >= PHASE_BROADCAST_INTERVAL) {
            if (panicMode || inRushMode) {
                Comms.broadcastToAllies(rc, Comms.MessageType.PHASE_DEFEND, myLoc, 0);
                lastPhaseBroadcast = round;
            } else if (round > LATE_GAME_ROUND || Utils.isEconomyStrong()) {
                // Late game OR strong economy: all-out attack
                Comms.broadcastToAllies(rc, Comms.MessageType.PHASE_ALL_OUT_ATTACK, myLoc, 0);
                lastPhaseBroadcast = round;
            } else if (Utils.isEconomyWeak() && round > 300) {
                // Weak economy after early game: defensive posture
                Comms.broadcastToAllies(rc, Comms.MessageType.PHASE_DEFEND, myLoc, 0);
                lastPhaseBroadcast = round;
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
        // Count enemy splashers for adaptive spawning
        int enemySplashers = 0;
        for (RobotInfo enemy : enemies) {
            if (enemy.getType() == UnitType.SPLASHER) {
                enemySplashers++;
            }
        }

        UnitType toSpawn = chooseUnitToSpawn(round, underThreat, panicMode, inRushMode, enemySplashers);
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
     *   C1. Strategy counter → adaptive spawning
     *   C2. Counter enemy splashers → more soldiers/moppers
     *   D. Early game → mostly soldiers
     *   E. Mid game → balanced composition
     *   F. Late game → more splashers
     */
    private static UnitType chooseUnitToSpawn(int round, boolean underThreat,
                                               boolean panicMode, boolean inRushMode,
                                               int enemySplashers) {
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

        // Sub-C1: Strategy-based counter spawning
        Utils.EnemyStrategy strategy = Utils.detectEnemyStrategy(round);
        switch (strategy) {
            case RUSHING:
                // Counter rush with soldiers
                return UnitType.SOLDIER;
            case SPLASHER_HEAVY:
                // Counter splashers with soldiers to hunt them
                if (Utils.rng.nextInt(10) < 8) return UnitType.SOLDIER;
                return UnitType.MOPPER;
            case TURTLING:
                // Break turtle with splashers
                if (Utils.rng.nextInt(10) < 7) return UnitType.SPLASHER;
                return UnitType.SOLDIER;
            default:
                break;  // Fall through to normal logic
        }

        // Sub-C2: Counter enemy splashers (adaptive spawning)
        if (enemySplashers >= 2) {
            // Splashers are slow and vulnerable - soldiers hunt them
            // Spawn extra soldiers/moppers to eliminate enemy splashers
            if (Utils.rng.nextInt(10) < 7) {  // 70% soldiers
                return UnitType.SOLDIER;
            }
            return UnitType.MOPPER;  // 30% moppers for mop swing
        }

        // Sub-C3: Economy-aware spawning
        if (Utils.isEconomyWeak() && round > 200) {
            // Weak economy: focus on soldiers for tower building
            if (Utils.rng.nextInt(10) < 8) return UnitType.SOLDIER;  // 80%
            return UnitType.MOPPER;  // 20%
        }

        if (Utils.isEconomyStrong() && round > 400) {
            // Strong economy: can afford more splashers for territory push
            int choice = Utils.rng.nextInt(10);
            if (choice < 6) return UnitType.SPLASHER;  // 60%
            if (choice < 9) return UnitType.SOLDIER;   // 30%
            return UnitType.MOPPER;  // 10%
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

            // Target: 40% soldiers, 20% moppers, 40% splashers (MORE SPLASHERS)
            if (soldierRatio < 0.4) return UnitType.SOLDIER;
            if (mopperRatio < 0.2) return UnitType.MOPPER;
            return UnitType.SPLASHER;
        }

        // Sub-F: Late game (600+) - SPLASHER HEAVY
        int choice = Utils.rng.nextInt(10);
        if (choice < 3) return UnitType.SOLDIER;  // 30%
        if (choice < 5) return UnitType.MOPPER;   // 20%
        return UnitType.SPLASHER;                  // 50%
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
            case SOLDIER:
                soldiersSpawned++;
                Metrics.trackSpawn(0);
                break;
            case MOPPER:
                moppersSpawned++;
                Metrics.trackSpawn(2);
                break;
            case SPLASHER:
                splashersSpawned++;
                Metrics.trackSpawn(1);
                break;
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
     * Sample visible paint tiles for coverage metrics.
     */
    private static void samplePaintCoverage(RobotController rc) throws GameActionException {
        MapInfo[] tiles = rc.senseNearbyMapInfos();
        int ally = 0, enemy = 0, neutral = 0;

        for (MapInfo tile : tiles) {
            if (!tile.isPassable()) continue;
            PaintType paint = tile.getPaint();
            if (paint.isAlly()) ally++;
            else if (paint.isEnemy()) enemy++;
            else neutral++;
        }

        Metrics.trackPaintSample(ally, enemy, neutral);
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

        // Game summary metrics every 100 rounds
        if (round % 100 == 0) {
            Metrics.reportGameSummary(round);
        }
    }
}
