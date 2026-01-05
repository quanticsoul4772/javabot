package omnom;

import battlecode.common.*;

/**
 * Tower behavior - TDD Week 1 skeleton with visibility.
 */
public class Tower {
    private static int totalSpawns = 0;
    private static int soldierSpawns = 0;
    private static int splasherSpawns = 0;
    private static int mopperSpawns = 0;
    private static int lastChips = 0;
    private static int lastSpawnRound = -1;

    // Debt accumulators
    private static double soldierDebt = 0;
    private static double splasherDebt = 0;
    private static double mopperDebt = 0;

    // Spawn locations (12 total: 8 adjacent + 4 two-away)
    private static MapLocation[] spawnLocs;
    private static boolean spawnLocsInitialized = false;

    // Track for money tower self-destruct
    private static int prevChips = 0;
    private static int prevTowers = 0;

    public static void run() throws GameActionException {
        // Initialize spawn locations once
        if (!spawnLocsInitialized) {
            initSpawnLocs();
            spawnLocsInitialized = true;
        }

        // MONEY TOWER NUKING: Trade money for paint (from spec Part 16.6)
        if (G.type == UnitType.LEVEL_ONE_MONEY_TOWER) {
            int threshold = G.id < 10000 ? 20000 : G.id * 3 - 10000;
            if (G.chips > threshold
                && prevChips < G.chips
                && G.rc.getNumberTowers() >= prevTowers
                && G.round % 5 == 0) {

                System.out.println("TOWER_NUKE:" + G.round + ":TOWER:" + G.id +
                    ":chips=" + G.chips + ":threshold=" + threshold);

                // Attack to spend paint, then self-destruct for 500 paint on next spawn
                tryAttack();
                G.rc.disintegrate();
                return;
            }
        }

        prevChips = G.chips;
        prevTowers = G.rc.getNumberTowers();

        // TERRITORY SAMPLING: Every 10 rounds, sample nearby paint
        if (G.round % 10 == 0) {
            MapInfo[] tiles = G.rc.senseNearbyMapInfos();
            int allyPaint = 0, enemyPaint = 0, neutralPaint = 0;

            for (int i = tiles.length; --i >= 0;) {
                PaintType paint = tiles[i].getPaint();
                if (paint.isAlly()) allyPaint++;
                else if (paint.isEnemy()) enemyPaint++;
                else neutralPaint++;
            }

            int totalSensed = tiles.length;
            int allyPercent = totalSensed > 0 ? (allyPaint * 100 / totalSensed) : 0;
            int enemyPercent = totalSensed > 0 ? (enemyPaint * 100 / totalSensed) : 0;

            System.out.println("TERRITORY:" + G.round + ":TOWER:" + G.id +
                ":ally=" + allyPercent + "%" +
                ":enemy=" + enemyPercent + "%" +
                ":neutral=" + (100 - allyPercent - enemyPercent) + "%" +
                ":sensed=" + totalSensed);

            int income = (G.round == 10) ? G.chips : (G.chips - lastChips);

            System.out.println("ECONOMY:" + G.round + ":TOWER:" + G.id +
                ":chips=" + G.chips +
                ":income=" + income +
                ":towers=" + G.rc.getNumberTowers() +
                ":units=" + G.getAllies().length +
                ":paint=" + G.paint);

            lastChips = G.chips;
        }

        // UPGRADE: As soon as we can afford it (priority over spawning)
        while (G.rc.canUpgradeTower(G.me)) {
            System.out.println("UPGRADE:" + G.round + ":TOWER:" + G.id +
                ":chips=" + G.rc.getMoney());
            G.rc.upgradeTower(G.me);
        }

        // ATTACK: Attack enemies in range
        tryAttack();

        // Try to spawn units
        trySpawn();
    }

    /**
     * Initialize 12 spawn locations sorted toward center.
     */
    private static void initSpawnLocs() {
        spawnLocs = new MapLocation[12];
        spawnLocs[0] = G.me.add(Direction.NORTH);
        spawnLocs[1] = G.me.add(Direction.NORTHEAST);
        spawnLocs[2] = G.me.add(Direction.EAST);
        spawnLocs[3] = G.me.add(Direction.SOUTHEAST);
        spawnLocs[4] = G.me.add(Direction.SOUTH);
        spawnLocs[5] = G.me.add(Direction.SOUTHWEST);
        spawnLocs[6] = G.me.add(Direction.WEST);
        spawnLocs[7] = G.me.add(Direction.NORTHWEST);
        spawnLocs[8] = G.me.add(Direction.NORTH).add(Direction.NORTH);
        spawnLocs[9] = G.me.add(Direction.EAST).add(Direction.EAST);
        spawnLocs[10] = G.me.add(Direction.SOUTH).add(Direction.SOUTH);
        spawnLocs[11] = G.me.add(Direction.WEST).add(Direction.WEST);

        // Sort by distance to center (closest first)
        for (int i = 0; i < spawnLocs.length - 1; i++) {
            for (int j = i + 1; j < spawnLocs.length; j++) {
                if (spawnLocs[j].distanceSquaredTo(G.mapCenter) <
                    spawnLocs[i].distanceSquaredTo(G.mapCenter)) {
                    MapLocation temp = spawnLocs[i];
                    spawnLocs[i] = spawnLocs[j];
                    spawnLocs[j] = temp;
                }
            }
        }
    }

    /**
     * Attack nearby enemies (from spec Part 8, Tower.tryAttack).
     */
    private static void tryAttack() throws GameActionException {
        RobotInfo[] enemies = G.getEnemies();
        if (enemies.length == 0) return;

        RobotInfo best = null;
        int bestScore = Integer.MIN_VALUE;

        for (int i = enemies.length; --i >= 0;) {
            if (G.me.distanceSquaredTo(enemies[i].location) > G.type.actionRadiusSquared) {
                continue;
            }

            int score = 0;

            // Prioritize splashers
            if (enemies[i].type == UnitType.SPLASHER) score += 500;
            else if (enemies[i].type == UnitType.SOLDIER) score += 300;
            else if (enemies[i].type == UnitType.MOPPER) score += 200;

            // Low HP bonus
            score += 200 - enemies[i].paintAmount;

            // Close range bonus
            score += 50 - G.me.distanceSquaredTo(enemies[i].location);

            if (score > bestScore) {
                bestScore = score;
                best = enemies[i];
            }
        }

        if (best != null && G.rc.canAttack(best.location)) {
            G.rc.attack(best.location);
        }
    }

    private static void trySpawn() throws GameActionException {
        // Base SPAARK weights
        double soldierWeight = 1.5 - G.rc.getNumberTowers() * 0.05;
        double splasherWeight = 0.2 + G.allyPaintTowers * 0.3;
        double mopperWeight = 1.2;

        // Phase Shifting disabled - match ends before round 150 activation

        double sum = soldierWeight + splasherWeight + mopperWeight;
        soldierWeight /= sum;
        splasherWeight /= sum;
        mopperWeight /= sum;

        // Debt-based selection
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

        // First 3 spawns MUST be soldiers
        if (totalSpawns < 3) {
            toSpawn = UnitType.SOLDIER;
        }

        // SPAARK spawn conditions (original)
        boolean shouldSpawn = G.round < 10
            || G.rc.getNumberTowers() >= 25
            || (G.rc.getMoney() - toSpawn.moneyCost >= 900
                && (G.round < 100 || (lastSpawnRound + 1 < G.round)));

        if (!shouldSpawn) {
            return;  // Can't afford
        }

        // Can't afford
        if (G.rc.getMoney() < toSpawn.moneyCost) {
            return;
        }

        // Try all 12 spawn locations (sorted toward center)
        int blockedCount = 0;
        for (int i = 0; i < spawnLocs.length; i++) {
            MapLocation spawnLoc = spawnLocs[i];

            if (G.rc.canBuildRobot(toSpawn, spawnLoc)) {
                G.rc.buildRobot(toSpawn, spawnLoc);

                // VISIBILITY: Log spawn
                System.out.println("SPAWN:" + G.round + ":TOWER:" + G.id +
                    ":unit=" + toSpawn +
                    ":location=" + spawnLoc +
                    ":total=" + (totalSpawns + 1) +
                    ":soldiers=" + (toSpawn == UnitType.SOLDIER ? soldierSpawns + 1 : soldierSpawns) +
                    ":splashers=" + (toSpawn == UnitType.SPLASHER ? splasherSpawns + 1 : splasherSpawns) +
                    ":moppers=" + (toSpawn == UnitType.MOPPER ? mopperSpawns + 1 : mopperSpawns));

                // Update counters
                totalSpawns++;
                lastSpawnRound = G.round;
                switch (toSpawn) {
                    case SOLDIER: soldierSpawns++; break;
                    case SPLASHER: splasherSpawns++; break;
                    case MOPPER: mopperSpawns++; break;
                }

                // Update debt
                soldierDebt += soldierWeight;
                splasherDebt += splasherWeight;
                mopperDebt += mopperWeight;

                return;
            } else {
                blockedCount++;
            }
        }

        // Log spawn failure (debugging)
        if (G.round % 10 == 0) {
            System.out.println("SPAWN_FAILED:" + G.round + ":TOWER:" + G.id +
                ":blocked=" + blockedCount + "/12" +
                ":trying=" + toSpawn +
                ":chips=" + G.rc.getMoney());
        }
    }
}
