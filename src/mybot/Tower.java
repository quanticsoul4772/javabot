package mybot;

import battlecode.common.*;
import mybot.strategy.FocusFireCoordinator;
import mybot.strategy.SpawnManager;

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
    private static int totalSpawned = 0;  // Total units spawned by this tower

    // Spawn pacing (like SPAARK) - track last spawn round
    private static int lastSpawnRound = 0;

    // AGGRESSIVE: Spawn as many units as possible!
    // More units = more combat power = better chance of winning
    private static final int EARLY_GAME_SPAWN_LIMIT = 20;  // Higher limit for more units
    private static final int EARLY_GAME_ROUND = 75;  // Longer aggressive spawning phase

    // Rush detection state
    private static boolean rushDetected = false;
    private static int rushDetectedRound = 0;

    // Critical health threshold (~30% of tower health)
    private static final int TOWER_HEALTH_CRITICAL = 100;

    // Phase coordination
    private static int lastPhaseBroadcast = 0;
    private static final int PHASE_BROADCAST_INTERVAL = 50;
    private static final int LATE_GAME_ROUND = 800;  // Earlier aggression

    // Extended spawn locations (8 adjacent + 4 distance 2, like SPAARK)
    private static MapLocation[] spawnLocs = null;

    public static void run(RobotController rc) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        int round = rc.getRoundNum();
        UnitType myType = rc.getType();
        boolean isPaintTower = Utils.isPaintTower(myType);

        // Initialize spawn locations once (like SPAARK: 8 adjacent + 4 distance 2)
        if (spawnLocs == null) {
            MapLocation center = new MapLocation(rc.getMapWidth() / 2, rc.getMapHeight() / 2);
            spawnLocs = new MapLocation[] {
                myLoc.add(Direction.NORTH),
                myLoc.add(Direction.NORTHEAST),
                myLoc.add(Direction.EAST),
                myLoc.add(Direction.SOUTHEAST),
                myLoc.add(Direction.SOUTH),
                myLoc.add(Direction.SOUTHWEST),
                myLoc.add(Direction.WEST),
                myLoc.add(Direction.NORTHWEST),
                // Distance 2 locations
                myLoc.add(Direction.NORTH).add(Direction.NORTH),
                myLoc.add(Direction.EAST).add(Direction.EAST),
                myLoc.add(Direction.SOUTH).add(Direction.SOUTH),
                myLoc.add(Direction.WEST).add(Direction.WEST),
            };
            // Sort by distance to center (spawn closer to center first)
            java.util.Arrays.sort(spawnLocs,
                (a, b) -> a.distanceSquaredTo(center) - b.distanceSquaredTo(center));
        }

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
        // Use FocusFireCoordinator for intelligent target prioritization
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

            // Use FocusFireCoordinator for prioritized target broadcast
            FocusFireCoordinator.coordinateAttack(rc);
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

        // ===== PRIORITY 5: SPAWN UNITS (BEFORE attacking!) =====
        // CRITICAL: Spawning and attacking both use the action cooldown.
        // We must spawn FIRST because creating new units > dealing damage.
        spawnUnit(rc, round, underThreat, panicMode, inRushMode, enemies);

        // ===== PRIORITY 6: ATTACK ENEMIES =====
        attackEnemies(rc, enemies);

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
     * Uses SpawnManager for adaptive, strategy-aware unit selection.
     * CRITICAL: When under attack, spawn MORE defenders, don't stop spawning!
     */
    private static void spawnUnit(RobotController rc, int round, boolean underThreat,
                                   boolean panicMode, boolean inRushMode,
                                   RobotInfo[] enemies) throws GameActionException {
        // REMOVED: shouldDefendInsteadOfSpawn check
        // When under attack, we NEED to spawn defenders, not stop spawning!

        // AGGRESSIVE SPAWNING: Always spawn what we can afford!
        // Paint costs: SOLDIER=200, MOPPER=100, SPLASHER=200
        UnitType toSpawn;
        int myPaint = rc.getPaint();
        int myMoney = rc.getMoney();

        if (round < EARLY_GAME_ROUND && totalSpawned < EARLY_GAME_SPAWN_LIMIT) {
            // Early game: spawn units ASAP for numbers advantage
            if (myPaint >= UnitType.SOLDIER.paintCost && myMoney >= UnitType.SOLDIER.moneyCost) {
                toSpawn = UnitType.SOLDIER;  // Can afford soldier
            } else if (round < 10 && myPaint >= UnitType.MOPPER.paintCost && myMoney >= UnitType.MOPPER.moneyCost) {
                // VERY EARLY GAME (round 1-9): Spawn moppers to get units on the map ASAP!
                // SPAARK has units at round 3 - we need to match that.
                toSpawn = UnitType.MOPPER;
                rc.setIndicatorString("P6: Early mopper (round=" + round + ")");
            } else if (enemies.length >= 2 && myPaint >= UnitType.MOPPER.paintCost && myMoney >= UnitType.MOPPER.moneyCost) {
                // Under attack and can't afford soldier - spawn mopper to help
                toSpawn = UnitType.MOPPER;
                rc.setIndicatorString("P6: Emergency mopper (enemies=" + enemies.length + ")");
            } else if (myPaint >= 100) {
                // Have some paint, wait for soldier
                rc.setIndicatorString("P6: Saving for soldier (" + myPaint + "/200)");
                return;
            } else {
                rc.setIndicatorString("P6: Waiting for resources");
                return;  // Can't afford anything
            }
        } else if (round < EARLY_GAME_ROUND) {
            // Already hit spawn limit - save paint for splashers
            if (myPaint >= UnitType.SPLASHER.paintCost && myMoney >= UnitType.SPLASHER.moneyCost) {
                toSpawn = UnitType.SPLASHER;
            } else if (enemies.length >= 1 && myPaint >= UnitType.MOPPER.paintCost) {
                // Emergency: under attack, spawn mopper to help
                toSpawn = UnitType.MOPPER;
                rc.setIndicatorString("P6: EMERGENCY mopper spawn!");
            } else {
                rc.setIndicatorString("P6: Waiting for splasher paint (" + myPaint + "/200)");
                return;
            }
        } else if (panicMode && rc.getHealth() < 200) {
            // True panic: tower about to die, spawn soldiers only
            toSpawn = UnitType.SOLDIER;
        } else if (splashersSpawned < 2) {
            // After round 50, prioritize getting 2 splashers out
            if (myPaint >= UnitType.SPLASHER.paintCost) {
                toSpawn = UnitType.SPLASHER;
            } else {
                rc.setIndicatorString("P6: Need splashers, waiting (" + myPaint + "/200)");
                return;  // Wait for splasher paint
            }
        } else {
            // Normal: Use SpawnManager for all spawning decisions
            toSpawn = SpawnManager.getSpawnType(rc);
        }

        if (toSpawn == null) return;

        // Try to spawn the chosen unit
        UnitType actualSpawn = trySpawnUnit(rc, toSpawn, enemies);

        if (actualSpawn != null) {
            if (enemies.length >= 2 || panicMode) {
                rc.setIndicatorString("P6: DEFENSE SPAWN " + actualSpawn);
            } else if (inRushMode) {
                rc.setIndicatorString("P6: RUSH DEFENSE " + actualSpawn);
            } else {
                rc.setIndicatorString("P6: Spawned " + actualSpawn);
            }
        } else if (Metrics.ENABLED && round <= 100 && round % 10 == 0) {
            // Debug: why couldn't we spawn?
            System.out.println("[TOWER #" + rc.getID() + " r" + round + "] SPAWN FAILED: " +
                "wanted=" + toSpawn + " money=" + rc.getMoney() + " paint=" + rc.getPaint() +
                " enemies=" + enemies.length);
        }
    }

    /**
     * Try to spawn the specified unit type at any available location.
     * PAINT-AWARE: Check if we have enough paint before trying to spawn.
     *
     * Paint costs: SOLDIER=200, MOPPER=100, SPLASHER=200
     * Money costs: SOLDIER=250, MOPPER=300, SPLASHER=250
     *
     * Returns the unit type spawned, or null if couldn't spawn anything.
     */
    private static UnitType trySpawnUnit(RobotController rc, UnitType unitType,
                                          RobotInfo[] enemies) throws GameActionException {
        int round = rc.getRoundNum();
        int myPaint = rc.getPaint();
        int myMoney = rc.getMoney();
        boolean isPaintTower = Utils.isPaintTower(rc.getType());

        // NON-PAINT TOWERS: Limited spawns after round 10
        // Money towers and Defense towers don't regenerate paint!
        // BUT: If under attack and have paint, spawn a defender!
        if (!isPaintTower && round >= 10) {
            // Allow emergency spawn if under attack and have paint for a mopper
            if (enemies.length >= 2 && myPaint >= UnitType.MOPPER.paintCost) {
                // Continue to try spawning a defender
            } else {
                // Not under attack or no paint - skip
                return null;
            }
        }

        // SPAWN PACING: Removed - the SPAARK-style limit in spawnUnit() handles this
        // by limiting early game spawns and requiring 200 paint for splashers

        // AGGRESSIVE: Don't block any spawn type - spawn whatever we can afford!
        // Early game units are valuable even if moppers

        // PAINT CHECK: Don't even try if we don't have enough paint
        if (myPaint < unitType.paintCost || myMoney < unitType.moneyCost) {
            // Can't afford requested unit
            // EMERGENCY: If under attack, spawn mopper as defender (costs 100 paint)
            if (enemies.length >= 1 && myPaint >= UnitType.MOPPER.paintCost &&
                myMoney >= UnitType.MOPPER.moneyCost) {
                MapLocation spawnLoc = findSpawnLocation(rc, UnitType.MOPPER, enemies);
                if (spawnLoc != null && rc.canBuildRobot(UnitType.MOPPER, spawnLoc)) {
                    rc.buildRobot(UnitType.MOPPER, spawnLoc);
                    trackSpawn(UnitType.MOPPER, round);
                    return UnitType.MOPPER;
                }
            }
            return null;
        }

        // EARLY GAME (round < 15): Spawn what SpawnManager says, no pacing limit
        // SpawnManager handles ratios: r1-14 = soldiers, r15+ = mix with splashers
        if (round < 15) {
            MapLocation spawnLoc = findSpawnLocation(rc, unitType, enemies);
            if (spawnLoc != null && rc.canBuildRobot(unitType, spawnLoc)) {
                rc.buildRobot(unitType, spawnLoc);
                trackSpawn(unitType, round);
                return unitType;
            }
            // If can't spawn requested type, try soldier as fallback
            if (unitType != UnitType.SOLDIER && myPaint >= UnitType.SOLDIER.paintCost) {
                spawnLoc = findSpawnLocation(rc, UnitType.SOLDIER, enemies);
                if (spawnLoc != null && rc.canBuildRobot(UnitType.SOLDIER, spawnLoc)) {
                    rc.buildRobot(UnitType.SOLDIER, spawnLoc);
                    trackSpawn(UnitType.SOLDIER, round);
                    return UnitType.SOLDIER;
                }
            }
            return null;
        }

        // MID/LATE GAME: Try requested type
        MapLocation spawnLoc = findSpawnLocation(rc, unitType, enemies);
        if (spawnLoc != null && rc.canBuildRobot(unitType, spawnLoc)) {
            rc.buildRobot(unitType, spawnLoc);
            trackSpawn(unitType, round);
            return unitType;
        }

        // If we wanted splasher but can't afford it, wait for paint
        if (unitType == UnitType.SPLASHER) {
            return null;
        }

        // For soldiers, if location blocked try splasher instead (same paint cost)
        // NO mopper fallback - save paint for high-value units
        if (unitType == UnitType.SOLDIER && myPaint >= UnitType.SPLASHER.paintCost &&
            myMoney >= UnitType.SPLASHER.moneyCost) {
            spawnLoc = findSpawnLocation(rc, UnitType.SPLASHER, enemies);
            if (spawnLoc != null && rc.canBuildRobot(UnitType.SPLASHER, spawnLoc)) {
                rc.buildRobot(UnitType.SPLASHER, spawnLoc);
                trackSpawn(UnitType.SPLASHER, round);
                return UnitType.SPLASHER;
            }
        }

        return null;  // Couldn't spawn - wait for paint
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
     * Find spawn location using extended spawn locations (12 tiles, sorted by center distance).
     * Prefers spawning away from enemies when under attack.
     */
    private static MapLocation findSpawnLocation(RobotController rc, UnitType type,
                                                  RobotInfo[] enemies) throws GameActionException {
        MapLocation myLoc = rc.getLocation();

        // If enemies present, try to spawn on opposite side first (adjacent only)
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

        // Use extended spawn locations (sorted by distance to center)
        // This includes 8 adjacent + 4 distance-2 tiles
        if (spawnLocs != null) {
            for (MapLocation loc : spawnLocs) {
                if (rc.canBuildRobot(type, loc)) {
                    return loc;
                }
            }
        }

        return null;
    }

    /**
     * Track spawned units for composition balance and pacing.
     */
    private static void trackSpawn(UnitType type, int round) {
        lastSpawnRound = round;  // Track for spawn pacing
        totalSpawned++;          // Track total for SPAARK-style limits
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
        // ===== METRICS: Frequent early game reports (every 25 rounds until r200) =====
        if (round <= 200 && round % 25 == 0) {
            System.out.println("[TOWER #" + rc.getID() + " r" + round + "] " +
                "spawns: " + soldiersSpawned + "S/" + moppersSpawned + "M/" + splashersSpawned + "X " +
                "hp=" + rc.getHealth() + "/" + rc.getType().health +
                " paint=" + rc.getPaint());
        } else if (round % 100 == 0) {
            System.out.println("[TOWER #" + rc.getID() + " r" + round + "] " +
                "spawned: soldiers=" + soldiersSpawned +
                " moppers=" + moppersSpawned +
                " splashers=" + splashersSpawned);
            Metrics.reportGameSummary(round);
        }
    }
}
