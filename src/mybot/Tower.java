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

        // ===== PRIORITY 0.25: REFILL ADJACENT LOW-PAINT UNITS =====
        // This is CRITICAL - units with low paint can't move or act!
        refillAdjacentUnits(rc);

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

            // Track enemy sightings for metrics
            int enemyTowerCount = 0;
            for (RobotInfo enemy : enemies) {
                if (enemy.getType().isTowerType()) {
                    enemyTowerCount++;
                }
            }
            Metrics.trackEnemySighting(enemies.length - enemyTowerCount, enemyTowerCount);

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

        // Try to spawn the chosen unit
        UnitType actualSpawn = trySpawnUnit(rc, toSpawn, enemies);

        if (actualSpawn != null) {
            if (panicMode) {
                rc.setIndicatorString("P6: PANIC SPAWN " + actualSpawn);
            } else if (inRushMode) {
                rc.setIndicatorString("P6: RUSH DEFENSE " + actualSpawn);
            } else {
                rc.setIndicatorString("P6: Spawned " + actualSpawn);
            }
        }
    }

    /**
     * Try to spawn the specified unit type at any available location.
     * Falls back to other unit types if the preferred one can't be built.
     * Returns the unit type spawned, or null if couldn't spawn anything.
     */
    private static UnitType trySpawnUnit(RobotController rc, UnitType unitType,
                                          RobotInfo[] enemies) throws GameActionException {
        // Define fallback order: preferred unit first, then alternatives
        UnitType[] fallbackOrder;
        switch (unitType) {
            case SPLASHER:
                // Splasher → Soldier → Mopper (keep combat capability)
                fallbackOrder = new UnitType[]{UnitType.SPLASHER, UnitType.SOLDIER, UnitType.MOPPER};
                break;
            case SOLDIER:
                // Soldier → Splasher → Mopper (keep combat capability)
                fallbackOrder = new UnitType[]{UnitType.SOLDIER, UnitType.SPLASHER, UnitType.MOPPER};
                break;
            case MOPPER:
            default:
                // Mopper → Soldier → Splasher
                fallbackOrder = new UnitType[]{UnitType.MOPPER, UnitType.SOLDIER, UnitType.SPLASHER};
                break;
        }

        // Try each unit type in fallback order
        for (UnitType tryType : fallbackOrder) {
            MapLocation spawnLoc = findSpawnLocation(rc, tryType, enemies);
            if (spawnLoc != null && rc.canBuildRobot(tryType, spawnLoc)) {
                rc.buildRobot(tryType, spawnLoc);
                trackSpawn(tryType);
                return tryType;
            }
        }

        return null;  // Couldn't spawn anything
    }

    // ==================== SPAWN LOGIC ====================

    /**
     * Choose unit type to spawn.
     * SIMPLIFIED: Round-based spawning with splashers from the start.
     * Only override for true emergencies (panic/rush).
     */
    private static UnitType chooseUnitToSpawn(int round, boolean underThreat,
                                               boolean panicMode, boolean inRushMode,
                                               int enemySplashers) {
        // EMERGENCY: Panic mode - need soldiers to defend
        if (panicMode) {
            return UnitType.SOLDIER;
        }

        // EMERGENCY: Rush detected - need soldiers fast
        if (inRushMode) {
            int choice = Utils.rng.nextInt(10);
            if (choice < 8) return UnitType.SOLDIER;  // 80%
            return UnitType.MOPPER;  // 20%
        }

        // ROUND-BASED SPAWNING (simplified, always includes splashers)

        // Rounds 1-75: All soldiers for initial expansion
        if (round < 75) {
            return UnitType.SOLDIER;
        }

        // Rounds 75-200: Start introducing splashers early!
        if (round < 200) {
            int choice = Utils.rng.nextInt(10);
            if (choice < 5) return UnitType.SOLDIER;   // 50%
            if (choice < 8) return UnitType.SPLASHER;  // 30% - start contesting early!
            return UnitType.MOPPER;                     // 20%
        }

        // Rounds 200-500: Balanced mid-game
        if (round < 500) {
            int choice = Utils.rng.nextInt(10);
            if (choice < 3) return UnitType.SOLDIER;   // 30%
            if (choice < 8) return UnitType.SPLASHER;  // 50% - heavy contesting!
            return UnitType.MOPPER;                     // 20%
        }

        // Rounds 500+: Late game push - maximum splashers
        int choice = Utils.rng.nextInt(10);
        if (choice < 2) return UnitType.SOLDIER;  // 20%
        if (choice < 3) return UnitType.MOPPER;   // 10%
        return UnitType.SPLASHER;                  // 70% - massive push!
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
     * NOTE: In Battlecode 2025, TOWERS CANNOT TRANSFER PAINT TO UNITS!
     * Only moppers can give paint to other robots.
     * Units must TAKE paint from towers using transferPaint with NEGATIVE amount.
     * This method is a no-op now - leaving for documentation.
     */
    private static void refillAdjacentUnits(RobotController rc) throws GameActionException {
        // TOWERS CANNOT TRANSFER PAINT - units must take it themselves!
        // See: "You can give paint to an allied robot if you are a mopper"
        // See: "You can give/take paint from allied towers regardless of type"
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

        // Track win progress (% of visible tiles that are ally)
        int total = ally + enemy + neutral;
        Metrics.trackWinProgress(ally, total);

        // Track economy
        Metrics.trackEconomy(rc.getMoney(), rc.getPaint());
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
