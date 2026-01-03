package spaark2;

import battlecode.common.*;

/**
 * Tower logic with weighted spawn system.
 * Based on SPAARK's debt-based spawning approach.
 */
public class Tower {

    // Spawn tracking for debt-based system
    private static int soldierSpawns = 0;
    private static int splasherSpawns = 0;
    private static int mopperSpawns = 0;

    public static void run(RobotController rc) throws GameActionException {
        Globals.init(rc);

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
        for (RobotInfo enemy : enemies) {
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

        for (RobotInfo enemy : enemies) {
            MapLocation loc = enemy.getLocation();
            if (!rc.canAttack(loc)) continue;

            // Count enemies in splash radius
            int count = 0;
            for (RobotInfo other : enemies) {
                if (other.getLocation().isWithinDistanceSquared(loc, 2)) {
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
     * Try to spawn a unit - balanced army composition.
     */
    private static void trySpawn(RobotController rc) throws GameActionException {
        int round = rc.getRoundNum();
        int money = rc.getMoney();
        int paint = rc.getPaint();

        // Early game: spam soldiers (rounds 1-50)
        if (round < 50) {
            if (paint >= 200) {
                spawnUnit(rc, UnitType.SOLDIER, money, round);
            }
            return;
        }

        // Mid/late game: balanced composition
        // Calculate "debt" for each type (who we should spawn next)
        double soldierDebt = Globals.SOLDIER_WEIGHT * (soldierSpawns + splasherSpawns + mopperSpawns + 1) - soldierSpawns;
        double splasherDebt = Globals.SPLASHER_WEIGHT * (soldierSpawns + splasherSpawns + mopperSpawns + 1) - splasherSpawns;
        double mopperDebt = Globals.MOPPER_WEIGHT * (soldierSpawns + splasherSpawns + mopperSpawns + 1) - mopperSpawns;

        // Try splasher first if highest debt (needs ally paint)
        if (paint >= 200 && splasherDebt >= soldierDebt && splasherDebt >= mopperDebt) {
            if (spawnUnit(rc, UnitType.SPLASHER, money, round)) return;
        }

        // Try soldier if highest debt
        if (paint >= 200 && soldierDebt >= mopperDebt) {
            if (spawnUnit(rc, UnitType.SOLDIER, money, round)) return;
        }

        // Try mopper
        if (paint >= 100) {
            if (spawnUnit(rc, UnitType.MOPPER, money, round)) return;
        }

        // Fallback: spawn whatever we can
        if (paint >= 200) {
            spawnUnit(rc, UnitType.SOLDIER, money, round);
        } else if (paint >= 100) {
            spawnUnit(rc, UnitType.MOPPER, money, round);
        }
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
            trackSpawn(type);
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

        for (Direction d : tryOrder) {
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

    private static void trackSpawn(UnitType type) {
        switch (type) {
            case SOLDIER: soldierSpawns++; break;
            case SPLASHER: splasherSpawns++; break;
            case MOPPER: mopperSpawns++; break;
        }
    }

    private static int countAllyTowers(RobotController rc) throws GameActionException {
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        int count = 0;
        for (RobotInfo ally : allies) {
            if (ally.getType().isTowerType()) count++;
        }
        return count;
    }

    private static int countPaintTowers(RobotController rc) throws GameActionException {
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        int count = 0;
        for (RobotInfo ally : allies) {
            if (ally.getType() == UnitType.LEVEL_ONE_PAINT_TOWER ||
                ally.getType() == UnitType.LEVEL_TWO_PAINT_TOWER ||
                ally.getType() == UnitType.LEVEL_THREE_PAINT_TOWER) {
                count++;
            }
        }
        return count;
    }
}
