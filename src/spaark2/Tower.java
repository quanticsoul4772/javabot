package spaark2;

import battlecode.common.*;

/**
 * Tower logic with weighted spawn system.
 * Based on SPAARK's debt-based spawning approach.
 */
public class Tower {

    public static void run(RobotController rc) throws GameActionException {
        Globals.init(rc);
        POI.init(rc);

        // Register self and scan nearby
        POI.updateTower(rc.getLocation(), rc.getTeam(), rc.getType());
        POI.scanNearby(rc);

        // Attack enemies first
        attack(rc);

        // Try to spawn
        trySpawn(rc);
    }

    /**
     * Attack enemies - prioritize AOE, then lowest HP.
     */
    private static void attack(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return;

        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if (enemies.length == 0) return;

        // Try AOE attack first
        MapLocation aoeTarget = findAOETarget(rc, enemies);
        if (aoeTarget != null && rc.canAttack(aoeTarget)) {
            rc.attack(aoeTarget);
            return;
        }

        // Fall back to single target - lowest HP
        RobotInfo weakest = null;
        for (int i = enemies.length; --i >= 0;) {
            RobotInfo enemy = enemies[i];
            if (rc.canAttack(enemy.getLocation())) {
                if (weakest == null || enemy.getHealth() < weakest.getHealth()) {
                    weakest = enemy;
                }
            }
        }
        if (weakest != null) {
            rc.attack(weakest.getLocation());
        }
    }

    /**
     * Find best AOE attack target (hits multiple enemies).
     */
    private static MapLocation findAOETarget(RobotController rc, RobotInfo[] enemies) throws GameActionException {
        MapLocation best = null;
        int bestCount = 1;  // Only AOE if we hit 2+

        for (int i = enemies.length; --i >= 0;) {
            RobotInfo enemy = enemies[i];
            MapLocation loc = enemy.getLocation();
            if (!rc.canAttack(loc)) continue;

            // Count enemies in splash radius
            int count = 0;
            for (int j = enemies.length; --j >= 0;) {
                if (enemies[j].getLocation().isWithinDistanceSquared(loc, 2)) {
                    count++;
                }
            }
            if (count > bestCount) {
                bestCount = count;
                best = loc;
            }
        }
        return best;
    }

    /**
     * Try to spawn a unit - balanced army composition with dynamic weights.
     */
    private static void trySpawn(RobotController rc) throws GameActionException {
        int round = rc.getRoundNum();
        int money = rc.getMoney();
        int paint = rc.getPaint();

        // Early game: spam soldiers (rounds 1-50)
        if (round < 50 || Globals.spawnedTotal < Globals.EARLY_SOLDIERS_COUNT) {
            if (paint >= 200) {
                spawnUnit(rc, UnitType.SOLDIER, money, round);
            }
            return;
        }

        // Calculate dynamic weights based on game state
        int numTowers = POI.allyPaintTowers + POI.allyMoneyTowers + POI.allyDefenseTowers;
        if (numTowers == 0) numTowers = 1;  // Avoid edge cases

        // Base weights adjusted by tower count
        double soldierWeight = Math.max(0.3, Globals.SOLDIER_WEIGHT - (numTowers * 0.05));
        double splasherWeight = Globals.SPLASHER_WEIGHT + (POI.allyPaintTowers * 0.15);
        double mopperWeight = Globals.MOPPER_WEIGHT;

        // Normalize weights
        double sum = soldierWeight + splasherWeight + mopperWeight;
        soldierWeight /= sum;
        splasherWeight /= sum;
        mopperWeight /= sum;

        // Calculate debt using global spawn tracking
        double soldierDebt = Globals.fracSoldiers + soldierWeight - Globals.spawnedSoldiers;
        double splasherDebt = Globals.fracSplashers + splasherWeight - Globals.spawnedSplashers;
        double mopperDebt = Globals.fracMoppers + mopperWeight - Globals.spawnedMoppers;

        // Select unit type based on highest debt
        UnitType selected = UnitType.SOLDIER;
        if (splasherDebt >= soldierDebt && splasherDebt >= mopperDebt) {
            selected = UnitType.SPLASHER;
        } else if (mopperDebt >= soldierDebt) {
            selected = UnitType.MOPPER;
        }

        // Try to spawn selected type
        int paintCost = getPaintCost(selected);
        if (paint >= paintCost && spawnUnit(rc, selected, money, round)) {
            updateSpawnTracking(selected, soldierWeight, splasherWeight, mopperWeight);
            return;
        }

        // Fallback: try any unit we can afford
        if (paint >= 200) {
            if (spawnUnit(rc, UnitType.SOLDIER, money, round)) {
                updateSpawnTracking(UnitType.SOLDIER, soldierWeight, splasherWeight, mopperWeight);
            }
        } else if (paint >= 100) {
            if (spawnUnit(rc, UnitType.MOPPER, money, round)) {
                updateSpawnTracking(UnitType.MOPPER, soldierWeight, splasherWeight, mopperWeight);
            }
        }
    }

    /**
     * Update global spawn tracking after spawning.
     */
    private static void updateSpawnTracking(UnitType type, double sw, double spw, double mw) {
        Globals.spawnedTotal++;
        switch (type) {
            case SOLDIER:
                Globals.spawnedSoldiers++;
                break;
            case SPLASHER:
                Globals.spawnedSplashers++;
                break;
            case MOPPER:
                Globals.spawnedMoppers++;
                break;
        }
        // Update fractional accumulators
        Globals.fracSoldiers += sw;
        Globals.fracSplashers += spw;
        Globals.fracMoppers += mw;
    }

    /**
     * Attempt to spawn a specific unit type.
     * Returns true if spawned.
     */
    private static boolean spawnUnit(RobotController rc, UnitType type, int money, int round) throws GameActionException {
        int cost = getUnitCost(type);

        // Early game: spawn aggressively
        boolean earlyGame = round < 10;

        // Check money buffer (except early game)
        if (!earlyGame && money < cost + Globals.SPAWN_MONEY_BUFFER) {
            return false;
        }

        // Check paint
        int paint = rc.getPaint();
        int paintCost = getPaintCost(type);
        if (paint < paintCost) return false;

        // Find spawn direction
        Direction spawnDir = findSpawnDirection(rc, type);
        if (spawnDir == null) return false;

        MapLocation spawnLoc = rc.getLocation().add(spawnDir);
        if (rc.canBuildRobot(type, spawnLoc)) {
            rc.buildRobot(type, spawnLoc);
            return true;
        }
        return false;
    }

    /**
     * Find best direction to spawn.
     */
    private static Direction findSpawnDirection(RobotController rc, UnitType type) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        MapLocation mapCenter = new MapLocation(rc.getMapWidth() / 2, rc.getMapHeight() / 2);
        Direction toCenter = myLoc.directionTo(mapCenter);

        // Try center direction first, then rotate
        Direction[] tryOrder = {
            toCenter, toCenter.rotateLeft(), toCenter.rotateRight(),
            toCenter.rotateLeft().rotateLeft(), toCenter.rotateRight().rotateRight(),
            toCenter.opposite().rotateLeft(), toCenter.opposite().rotateRight(),
            toCenter.opposite()
        };

        for (int i = 0; i < tryOrder.length; i++) {
            Direction d = tryOrder[i];
            MapLocation loc = myLoc.add(d);
            if (rc.canBuildRobot(type, loc)) {
                return d;
            }
        }
        return null;
    }

    private static int getUnitCost(UnitType type) {
        switch (type) {
            case SOLDIER: return 250;
            case SPLASHER: return 250;
            case MOPPER: return 300;
            default: return 1000;
        }
    }

    private static int getPaintCost(UnitType type) {
        switch (type) {
            case SOLDIER: return Globals.SOLDIER_PAINT_COST;
            case SPLASHER: return Globals.SPLASHER_PAINT_COST;
            case MOPPER: return Globals.MOPPER_PAINT_COST;
            default: return 1000;
        }
    }
}
