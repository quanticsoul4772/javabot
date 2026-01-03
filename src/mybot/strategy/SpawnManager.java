package mybot.strategy;

import battlecode.common.*;
import mybot.core.GamePhase;
import mybot.core.GamePhase.Phase;
import mybot.Utils;

/**
 * Manages unit spawning with adaptive ratios based on:
 * - Game phase (early/mid/late)
 * - Enemy composition detection
 * - Economy strength (paint/chip levels)
 * - Win progress (territory control)
 *
 * Called by Tower.java to determine what unit to spawn.
 */
public class SpawnManager {

    // Detected enemy strategy
    public enum EnemyStrategy {
        UNKNOWN,        // Default
        RUSH,           // Heavy early aggression
        TURTLE,         // Defensive, tower-focused
        SPLASHER_HEAVY, // Lots of splashers
        BALANCED        // Standard play
    }

    // Track recent enemy sightings (rolling window via static counters)
    private static int recentEnemySoldiers = 0;
    private static int recentEnemySplashers = 0;
    private static int recentEnemyMoppers = 0;
    private static int recentEnemyTowers = 0;
    private static int lastUpdateRound = 0;

    /**
     * Determine what unit type to spawn based on game state.
     * Returns the UnitType to spawn, or null if shouldn't spawn.
     */
    public static UnitType getSpawnType(RobotController rc) throws GameActionException {
        int round = rc.getRoundNum();
        Phase phase = GamePhase.get(round);

        // Update enemy tracking
        updateEnemyTracking(rc, round);

        // Detect enemy strategy
        EnemyStrategy strategy = detectEnemyStrategy();

        // Get base ratios from phase
        float soldierRatio = GamePhase.getSoldierRatio(phase);
        float splasherRatio = GamePhase.getSplasherRatio(phase);
        float mopperRatio = GamePhase.getMopperRatio(phase);

        // Adjust ratios based on enemy strategy
        switch (strategy) {
            case RUSH:
                // Counter with more moppers and soldiers
                soldierRatio += 0.15f;
                mopperRatio += 0.10f;
                splasherRatio -= 0.25f;
                break;
            case SPLASHER_HEAVY:
                // Counter with moppers (can attack splashers)
                mopperRatio += 0.20f;
                splasherRatio -= 0.10f;
                soldierRatio -= 0.10f;
                break;
            case TURTLE:
                // Need splashers to break through painted defenses
                splasherRatio += 0.15f;
                soldierRatio -= 0.10f;
                mopperRatio -= 0.05f;
                break;
            case BALANCED:
            case UNKNOWN:
            default:
                // Keep phase-based ratios
                break;
        }

        // Normalize ratios
        float total = soldierRatio + splasherRatio + mopperRatio;
        if (total > 0) {
            soldierRatio /= total;
            splasherRatio /= total;
            mopperRatio /= total;
        }

        // Early game spawn ratios - need splashers early to contest territory!
        // CRITICAL: NO MOPPERS early game - save all paint for soldiers/splashers
        // Moppers cost 100 paint which prevents accumulating 200 for splashers
        if (round < 150) {
            float roll = Utils.rng.nextFloat();
            if (round < 15) {
                // Very early (r1-14): 100% soldiers (need combat units ASAP)
                return UnitType.SOLDIER;
            } else if (round < 40) {
                // Early (r15-39): 50% soldiers, 50% splashers
                // Splashers are CRITICAL to contest enemy territory
                if (roll < 0.50f) return UnitType.SOLDIER;
                return UnitType.SPLASHER;
            } else if (round < 80) {
                // Mid-early (r40-79): 40% soldiers, 60% splashers
                if (roll < 0.40f) return UnitType.SOLDIER;
                return UnitType.SPLASHER;
            } else {
                // Late-early (r80-149): 30% soldiers, 70% splashers
                if (roll < 0.30f) return UnitType.SOLDIER;
                return UnitType.SPLASHER;
            }
        }

        // Use weighted random selection based on ratios
        float roll = Utils.rng.nextFloat();
        if (roll < soldierRatio) {
            return UnitType.SOLDIER;
        } else if (roll < soldierRatio + splasherRatio) {
            return UnitType.SPLASHER;
        } else {
            return UnitType.MOPPER;
        }
    }

    /**
     * Update enemy sighting counters from visible enemies.
     */
    private static void updateEnemyTracking(RobotController rc, int round) throws GameActionException {
        // Decay old sightings every 50 rounds
        if (round - lastUpdateRound > 50) {
            recentEnemySoldiers = recentEnemySoldiers * 2 / 3;
            recentEnemySplashers = recentEnemySplashers * 2 / 3;
            recentEnemyMoppers = recentEnemyMoppers * 2 / 3;
            recentEnemyTowers = recentEnemyTowers * 2 / 3;
            lastUpdateRound = round;
        }

        // Count current visible enemies
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        for (RobotInfo enemy : enemies) {
            switch (enemy.getType()) {
                case SOLDIER:
                    recentEnemySoldiers++;
                    break;
                case SPLASHER:
                    recentEnemySplashers++;
                    break;
                case MOPPER:
                    recentEnemyMoppers++;
                    break;
                default:
                    if (enemy.getType().isTowerType()) {
                        recentEnemyTowers++;
                    }
                    break;
            }
        }
    }

    /**
     * Detect enemy strategy based on unit composition.
     */
    private static EnemyStrategy detectEnemyStrategy() {
        int total = recentEnemySoldiers + recentEnemySplashers + recentEnemyMoppers;
        if (total < 5) return EnemyStrategy.UNKNOWN;

        float soldierPct = (float) recentEnemySoldiers / total;
        float splasherPct = (float) recentEnemySplashers / total;
        float mopperPct = (float) recentEnemyMoppers / total;

        // Detect strategies
        if (soldierPct > 0.6f && recentEnemyTowers < 3) {
            return EnemyStrategy.RUSH;
        }
        if (splasherPct > 0.5f) {
            return EnemyStrategy.SPLASHER_HEAVY;
        }
        if (recentEnemyTowers > 5 && soldierPct < 0.3f) {
            return EnemyStrategy.TURTLE;
        }

        return EnemyStrategy.BALANCED;
    }

    /**
     * Get the best spawn direction toward enemy territory.
     */
    public static Direction getBestSpawnDirection(RobotController rc) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        MapLocation mapCenter = new MapLocation(rc.getMapWidth() / 2, rc.getMapHeight() / 2);

        // Spawn toward center if we're near edge, otherwise toward enemy territory
        Direction toCenter = myLoc.directionTo(mapCenter);

        // Check for enemy territory (enemy paint) and spawn toward it
        Direction bestDir = null;
        int bestEnemyPaint = 0;

        for (Direction dir : Direction.allDirections()) {
            if (dir == Direction.CENTER) continue;
            MapLocation spawnLoc = myLoc.add(dir);

            if (!rc.canSenseLocation(spawnLoc)) continue;
            if (!rc.sensePassability(spawnLoc)) continue;

            // Count enemy paint in that direction
            int enemyPaint = 0;
            for (int i = 0; i < 3; i++) {
                MapLocation checkLoc = spawnLoc.add(dir);
                if (rc.canSenseLocation(checkLoc)) {
                    MapInfo info = rc.senseMapInfo(checkLoc);
                    if (info.getPaint().isEnemy()) {
                        enemyPaint++;
                    }
                }
                spawnLoc = checkLoc;
            }

            if (enemyPaint > bestEnemyPaint) {
                bestEnemyPaint = enemyPaint;
                bestDir = dir;
            }
        }

        // Default to toward center if no enemy paint found
        return bestDir != null ? bestDir : toCenter;
    }

    /**
     * Check if we should prioritize defense over spawning.
     * Returns true if tower is under attack and low health.
     */
    public static boolean shouldDefendInsteadOfSpawn(RobotController rc) throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(9, rc.getTeam().opponent()); // Range 3
        if (enemies.length == 0) return false;

        // If enemies are close and we're damaged, prioritize attacking them
        int healthPct = rc.getHealth() * 100 / rc.getType().health;
        return enemies.length >= 2 && healthPct < 70;
    }

    /**
     * Get current detected enemy strategy for debugging/metrics.
     */
    public static EnemyStrategy getCurrentEnemyStrategy() {
        return detectEnemyStrategy();
    }
}
