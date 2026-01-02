package mybot;

import battlecode.common.*;

/**
 * Tower behavior: spawn units and manage resources.
 *
 * Strategy:
 * - CRITICAL: Paint Towers spawn defenders aggressively when threatened
 * - Early game: Detect and counter enemy rushes
 * - Mid game: balanced composition
 * - Respond to threats with defensive spawns
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

        // Update economy tracking
        Utils.updateIncomeEstimate(rc);

        // Read incoming messages
        processMessages(rc);

        // Check for nearby enemies
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        boolean underThreat = enemies.length > 0;
        int enemySoldierCount = Utils.countEnemySoldiers(rc);

        // ========== PHASE 2: Rush Detection ==========
        // Early game + multiple enemy soldiers = RUSH INCOMING
        if (round < 150 && enemySoldierCount >= 2 && !rushDetected) {
            rushDetected = true;
            rushDetectedRound = round;
            rc.setIndicatorString("RUSH DETECTED!");
            rc.setTimelineMarker("Rush detected!", 255, 0, 0);
        }

        // Rush mode lasts for 100 rounds after detection
        boolean inRushMode = rushDetected && (round - rushDetectedRound < 100);

        // PHASE 6: Broadcast rush alert when first detected
        if (rushDetected && round == rushDetectedRound) {
            Comms.alertRush(rc);
        }

        // ========== PHASE 1: Paint Tower Priority Defense ==========
        // If this is a Paint Tower under threat, go into panic mode
        boolean panicMode = isPaintTower && underThreat;

        if (panicMode) {
            rc.setIndicatorString("PAINT TOWER UNDER ATTACK!");
            // PHASE 6: Alert allies about paint tower danger
            Comms.alertPaintTowerDanger(rc, myLoc);
        }

        // PHASE 6: Broadcast enemy positions to allies
        if (enemies.length > 0) {
            RobotInfo closest = Utils.closestRobot(myLoc, enemies);
            if (closest != null) {
                Comms.broadcastToAllies(rc, Comms.MessageType.ENEMY_SPOTTED,
                    closest.getLocation(), enemies.length);
            }
        }

        // Attack enemies in range (prioritize soldiers attacking us)
        RobotInfo priorityTarget = findPriorityTarget(enemies);
        if (priorityTarget != null && rc.canAttack(priorityTarget.getLocation())) {
            rc.attack(priorityTarget.getLocation());
        } else {
            // Attack any enemy in range
            for (RobotInfo enemy : enemies) {
                if (rc.canAttack(enemy.getLocation())) {
                    rc.attack(enemy.getLocation());
                    break;
                }
            }
        }

        // Spawn units - modified by panic mode and rush mode
        UnitType toSpawn = chooseUnitToSpawn(rc, round, underThreat, panicMode, inRushMode);
        if (toSpawn != null) {
            MapLocation spawnLoc = findSpawnLocation(rc, toSpawn, enemies);
            if (spawnLoc != null && rc.canBuildRobot(toSpawn, spawnLoc)) {
                rc.buildRobot(toSpawn, spawnLoc);
                trackSpawn(toSpawn);
                if (panicMode) {
                    rc.setIndicatorString("PANIC SPAWN: " + toSpawn);
                } else if (inRushMode) {
                    rc.setIndicatorString("RUSH DEFENSE: " + toSpawn);
                } else {
                    rc.setIndicatorString("Spawned " + toSpawn);
                }
            }
        }

        // Upgrade self if possible (late game, not during crisis)
        if (round > 500 && !panicMode && !inRushMode) {
            tryUpgrade(rc);
        }
    }

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
     * Choose which unit type to spawn based on game state.
     */
    private static UnitType chooseUnitToSpawn(RobotController rc, int round, boolean underThreat,
                                               boolean panicMode, boolean inRushMode) {
        // PANIC MODE: Paint Tower under attack - soldiers only!
        if (panicMode) {
            return UnitType.SOLDIER;
        }

        // RUSH MODE: Counter enemy rush with soldiers
        if (inRushMode) {
            // 90% soldiers during rush
            if (Utils.rng.nextInt(10) < 9) {
                return UnitType.SOLDIER;
            }
            return UnitType.MOPPER; // Some moppers to clean up
        }

        // Under threat - spawn soldiers for defense
        if (underThreat) {
            return UnitType.SOLDIER;
        }

        // Early game (rounds 1-200): mostly soldiers for expansion
        if (round < 200) {
            if (soldiersSpawned < 3 || Utils.rng.nextInt(10) < 8) {
                return UnitType.SOLDIER;
            }
            return UnitType.MOPPER;
        }

        // Mid game (200-600): balanced composition
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

        // Late game (600+): more splashers for territory push
        int choice = Utils.rng.nextInt(10);
        if (choice < 4) return UnitType.SOLDIER;
        if (choice < 6) return UnitType.MOPPER;
        return UnitType.SPLASHER;
    }

    /**
     * Find a valid spawn location for a unit.
     * Prefer spawning away from enemies during combat.
     */
    private static MapLocation findSpawnLocation(RobotController rc, UnitType type,
                                                  RobotInfo[] enemies) throws GameActionException {
        MapLocation myLoc = rc.getLocation();

        // If enemies present, try to spawn on opposite side
        if (enemies.length > 0) {
            RobotInfo closest = Utils.closestRobot(myLoc, enemies);
            if (closest != null) {
                Direction awayFromEnemy = myLoc.directionTo(closest.getLocation()).opposite();
                // Try away direction first
                MapLocation awayLoc = myLoc.add(awayFromEnemy);
                if (rc.canBuildRobot(type, awayLoc)) {
                    return awayLoc;
                }
                // Try adjacent to away
                if (rc.canBuildRobot(type, myLoc.add(awayFromEnemy.rotateLeft()))) {
                    return myLoc.add(awayFromEnemy.rotateLeft());
                }
                if (rc.canBuildRobot(type, myLoc.add(awayFromEnemy.rotateRight()))) {
                    return myLoc.add(awayFromEnemy.rotateRight());
                }
            }
        }

        // Default: try all adjacent tiles
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
                break;
            case MOPPER:
                moppersSpawned++;
                break;
            case SPLASHER:
                splashersSpawned++;
                break;
            default:
                break;
        }
    }

    /**
     * Process incoming messages from units.
     */
    private static void processMessages(RobotController rc) throws GameActionException {
        Message[] messages = rc.readMessages(-1);
        for (Message msg : messages) {
            // Could use messages to track enemy positions, coordinate attacks, etc.
            // For now just acknowledge
            // System.out.println("Received: " + msg.getBytes() + " from " + msg.getSenderID());
        }
    }

    /**
     * Try to upgrade this tower.
     */
    private static void tryUpgrade(RobotController rc) throws GameActionException {
        // Towers can be upgraded - implementation depends on API
        // For now, just mark that we'd like to upgrade
        rc.setIndicatorString("Would upgrade if possible");
    }
}
