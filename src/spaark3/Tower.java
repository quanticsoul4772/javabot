package spaark3;

import battlecode.common.*;

/**
 * Tower logic - spawning and defense.
 * Uses phase-weighted debt-based spawning system.
 *
 * Tower Types:
 * - Paint Tower: Primary resource, refills unit paint
 * - Money Tower: Chip generation
 * - Defense Tower: Strong attack capability
 */
public class Tower {

    // Spawn debt tracking (accumulates when can't spawn)
    private static double soldierDebt = 0;
    private static double splasherDebt = 0;
    private static double mopperDebt = 0;

    // Total spawns for ratio tracking
    private static int totalSpawns = 0;
    private static int soldierSpawns = 0;
    private static int splasherSpawns = 0;
    private static int mopperSpawns = 0;
    private static int lastSpawnRound = -1;

    // Spawn locations sorted toward map center (SPAARK strategy)
    private static MapLocation[] spawnLocs;
    private static boolean spawnLocsInitialized = false;

    // Tower type detection
    private static boolean isPaintTower = false;
    private static boolean towerTypeChecked = false;

    /**
     * Main tower logic - called every turn.
     */
    public static void run() throws GameActionException {
        // Check tower type once
        if (!towerTypeChecked) {
            UnitType t = G.type;
            isPaintTower = (t == UnitType.LEVEL_ONE_PAINT_TOWER ||
                           t == UnitType.LEVEL_TWO_PAINT_TOWER ||
                           t == UnitType.LEVEL_THREE_PAINT_TOWER);
            towerTypeChecked = true;
        }

        // Initialize spawn locations sorted toward center (once)
        if (!spawnLocsInitialized) {
            initSpawnLocs();
            spawnLocsInitialized = true;
        }

        // Attack enemies in range
        tryAttack();

        // Try to spawn units
        trySpawn();
    }

    /**
     * Initialize spawn locations sorted by distance to map center.
     * This projects units toward the center like SPAARK does.
     */
    private static void initSpawnLocs() {
        // All 8 adjacent + 4 two-away cardinal directions
        MapLocation[] locs = new MapLocation[12];
        locs[0] = G.me.add(Direction.NORTH);
        locs[1] = G.me.add(Direction.NORTHEAST);
        locs[2] = G.me.add(Direction.EAST);
        locs[3] = G.me.add(Direction.SOUTHEAST);
        locs[4] = G.me.add(Direction.SOUTH);
        locs[5] = G.me.add(Direction.SOUTHWEST);
        locs[6] = G.me.add(Direction.WEST);
        locs[7] = G.me.add(Direction.NORTHWEST);
        locs[8] = G.me.add(Direction.NORTH).add(Direction.NORTH);
        locs[9] = G.me.add(Direction.EAST).add(Direction.EAST);
        locs[10] = G.me.add(Direction.SOUTH).add(Direction.SOUTH);
        locs[11] = G.me.add(Direction.WEST).add(Direction.WEST);

        // Sort by distance to map center (closest first)
        for (int i = 0; i < locs.length - 1; i++) {
            for (int j = i + 1; j < locs.length; j++) {
                int distI = locs[i].distanceSquaredTo(G.mapCenter);
                int distJ = locs[j].distanceSquaredTo(G.mapCenter);
                if (distJ < distI) {
                    MapLocation temp = locs[i];
                    locs[i] = locs[j];
                    locs[j] = temp;
                }
            }
        }
        spawnLocs = locs;
    }

    /**
     * Attack nearby enemies.
     * Prioritizes targets based on threat level.
     */
    private static void tryAttack() throws GameActionException {
        RobotInfo[] enemies = G.getEnemies();
        if (enemies.length == 0) return;

        RobotInfo best = null;
        int bestScore = Integer.MIN_VALUE;

        for (int i = enemies.length; --i >= 0;) {
            RobotInfo enemy = enemies[i];
            int dist = G.me.distanceSquaredTo(enemy.location);

            // Must be in attack range
            if (dist > G.type.actionRadiusSquared) continue;

            int score = 0;

            // Prioritize splashers (highest threat)
            if (enemy.type == UnitType.SPLASHER) {
                score += 500;
            }
            // Then soldiers
            else if (enemy.type == UnitType.SOLDIER) {
                score += 300;
            }
            // Then moppers
            else if (enemy.type == UnitType.MOPPER) {
                score += 200;
            }

            // Low HP bonus
            score += 200 - enemy.paintAmount;

            // Close range bonus
            score += 50 - dist;

            if (score > bestScore) {
                bestScore = score;
                best = enemy;
            }
        }

        // Execute attack
        if (best != null && G.rc.canAttack(best.location)) {
            G.rc.attack(best.location);
        }
    }

    /**
     * Try to spawn units - SPAARK-style balanced spawning.
     */
    private static void trySpawn() throws GameActionException {
        // SPAARK weights - tuned for competitive play
        double soldierWeight = 1.5 - G.rc.getNumberTowers() * 0.05;
        double splasherWeight = 0.2 + G.allyPaintTowers * 0.3;
        double mopperWeight = 1.2;
        double sum = soldierWeight + splasherWeight + mopperWeight;
        soldierWeight /= sum;
        splasherWeight /= sum;
        mopperWeight /= sum;

        // Debt-based selection (SPAARK style)
        double soldier = soldierDebt + soldierWeight - soldierSpawns;
        double splasher = splasherDebt + splasherWeight - splasherSpawns;
        double mopper = mopperDebt + mopperWeight - mopperSpawns;

        UnitType toSpawn;
        if (soldier >= splasher && soldier >= mopper) {
            toSpawn = UnitType.SOLDIER;
        } else if (mopper >= splasher) {
            toSpawn = UnitType.MOPPER;
        } else {
            toSpawn = UnitType.SPLASHER;
        }

        // First 3 spawns MUST be soldiers (SPAARK line 147)
        if ((G.round < 50 || !isPaintTower) && totalSpawns < 3) {
            toSpawn = UnitType.SOLDIER;
        }

        // Check spawn conditions (SPAARK line 153-154)
        // CRITICAL: Always spawn when round < 10!
        boolean hasEnoughChips = G.rc.getMoney() - toSpawn.moneyCost >= 900;
        boolean shouldSpawn;
        if (G.round < 10) {
            // Always spawn in early game
            shouldSpawn = true;
        } else if (G.rc.getNumberTowers() >= 25) {
            // Always spawn when maxed towers
            shouldSpawn = true;
        } else if (G.round < 100) {
            // Before round 100: spawn if we have chips
            shouldSpawn = hasEnoughChips;
        } else {
            // After round 100: spawn if chips AND (didn't spawn recently AND few allies)
            int nearbyAllies = 0;
            try { nearbyAllies = G.getAllies().length; } catch (Exception e) {}
            shouldSpawn = hasEnoughChips && (lastSpawnRound + 1 < G.round && nearbyAllies < 4);
        }

        if (!shouldSpawn) {
            return;
        }

        // Try to spawn
        if (trySpawnUnit(toSpawn)) {
            // Update debt accumulators (SPAARK style)
            soldierDebt += soldierWeight;
            splasherDebt += splasherWeight;
            mopperDebt += mopperWeight;
        }
    }

    /**
     * Try to spawn a specific unit type.
     * Uses sorted spawn locations (toward center) like SPAARK.
     */
    private static boolean trySpawnUnit(UnitType type) throws GameActionException {
        // Try spawn locations in order (sorted toward center)
        for (int i = 0; i < spawnLocs.length; i++) {
            MapLocation spawnLoc = spawnLocs[i];

            if (G.rc.canBuildRobot(type, spawnLoc)) {
                G.rc.buildRobot(type, spawnLoc);

                // Update spawn counts (SPAARK style)
                totalSpawns++;
                lastSpawnRound = G.round;
                switch (type) {
                    case SOLDIER: soldierSpawns++; break;
                    case SPLASHER: splasherSpawns++; break;
                    case MOPPER: mopperSpawns++; break;
                    default: break;
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Clamp value to range.
     */
    private static double clamp(double val, double min, double max) {
        if (val < min) return min;
        if (val > max) return max;
        return val;
    }
}
