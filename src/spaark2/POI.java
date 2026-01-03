package spaark2;

import battlecode.common.*;

/**
 * Points of Interest - Global tower tracking system.
 * Enables units to find towers beyond sensor range.
 */
public class POI {

    // Tower tracking (max 144 ruins on 60x60 map)
    public static int numberOfTowers = 0;
    public static MapLocation[] towerLocs = new MapLocation[144];
    public static Team[] towerTeams = new Team[144];
    public static UnitType[] towerTypes = new UnitType[144];

    // Tower type counters for spawn decisions
    public static int allyPaintTowers = 0;
    public static int allyMoneyTowers = 0;
    public static int allyDefenseTowers = 0;

    // Initialization flag
    private static boolean initialized = false;

    /**
     * Initialize POI system for a robot.
     */
    public static void init(RobotController rc) {
        initialized = true;
    }

    /**
     * Update tower at location. Handles add/update/remove.
     */
    public static void updateTower(MapLocation loc, Team team, UnitType type) {
        // Check if we already know about this location
        for (int i = numberOfTowers; --i >= 0;) {
            if (towerLocs[i].equals(loc)) {
                // Update existing entry
                if (team == Team.NEUTRAL) {
                    // Tower destroyed - remove by swapping with last
                    if (towerTeams[i] != Team.NEUTRAL) {
                        decrementCounter(towerTypes[i], towerTeams[i]);
                    }
                    numberOfTowers--;
                    if (i < numberOfTowers) {
                        towerLocs[i] = towerLocs[numberOfTowers];
                        towerTeams[i] = towerTeams[numberOfTowers];
                        towerTypes[i] = towerTypes[numberOfTowers];
                    }
                } else if (towerTeams[i] != team) {
                    // Tower changed teams
                    if (towerTeams[i] != Team.NEUTRAL) {
                        decrementCounter(towerTypes[i], towerTeams[i]);
                    }
                    towerTeams[i] = team;
                    towerTypes[i] = type;
                    incrementCounter(type, team);
                }
                return;
            }
        }

        // New tower - add to list
        if (numberOfTowers < 144 && team != Team.NEUTRAL) {
            towerLocs[numberOfTowers] = loc;
            towerTeams[numberOfTowers] = team;
            towerTypes[numberOfTowers] = type;
            numberOfTowers++;
            incrementCounter(type, team);
        }
    }

    // Scan throttling to reduce bytecode
    private static int lastScanRound = -5;

    /**
     * Scan visible area and update tower info.
     * Throttled to every 5 rounds to save bytecode.
     */
    public static void scanNearby(RobotController rc) throws GameActionException {
        int round = rc.getRoundNum();
        if (round - lastScanRound < 5) return;  // Throttle scanning
        lastScanRound = round;

        // Check nearby ruins (limit to 3 to save bytecode)
        MapLocation[] ruins = rc.senseNearbyRuins(-1);
        int limit = Math.min(ruins.length, 3);
        for (int i = limit; --i >= 0;) {
            MapLocation ruin = ruins[i];
            if (rc.canSenseRobotAtLocation(ruin)) {
                RobotInfo robot = rc.senseRobotAtLocation(ruin);
                if (robot != null && robot.getType().isTowerType()) {
                    updateTower(ruin, robot.getTeam(), getBaseType(robot.getType()));
                }
            }
        }
    }

    /**
     * Find nearest ally paint tower for retreating.
     */
    public static MapLocation findNearestAllyPaintTower(MapLocation from, Team myTeam) {
        MapLocation best = null;
        int bestDist = Integer.MAX_VALUE;

        for (int i = numberOfTowers; --i >= 0;) {
            if (towerTeams[i] == myTeam && isPaintTower(towerTypes[i])) {
                int dist = from.distanceSquaredTo(towerLocs[i]);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = towerLocs[i];
                }
            }
        }
        return best;
    }

    /**
     * Get base tower type (level 1 version).
     */
    private static UnitType getBaseType(UnitType type) {
        switch (type) {
            case LEVEL_ONE_PAINT_TOWER:
            case LEVEL_TWO_PAINT_TOWER:
            case LEVEL_THREE_PAINT_TOWER:
                return UnitType.LEVEL_ONE_PAINT_TOWER;
            case LEVEL_ONE_MONEY_TOWER:
            case LEVEL_TWO_MONEY_TOWER:
            case LEVEL_THREE_MONEY_TOWER:
                return UnitType.LEVEL_ONE_MONEY_TOWER;
            case LEVEL_ONE_DEFENSE_TOWER:
            case LEVEL_TWO_DEFENSE_TOWER:
            case LEVEL_THREE_DEFENSE_TOWER:
                return UnitType.LEVEL_ONE_DEFENSE_TOWER;
            default:
                return UnitType.LEVEL_ONE_DEFENSE_TOWER;
        }
    }

    private static boolean isPaintTower(UnitType type) {
        return type == UnitType.LEVEL_ONE_PAINT_TOWER ||
               type == UnitType.LEVEL_TWO_PAINT_TOWER ||
               type == UnitType.LEVEL_THREE_PAINT_TOWER;
    }

    private static void incrementCounter(UnitType type, Team team) {
        // Only track ally towers for spawn decisions
        // (static vars shared across all units)
        switch (type) {
            case LEVEL_ONE_PAINT_TOWER:
            case LEVEL_TWO_PAINT_TOWER:
            case LEVEL_THREE_PAINT_TOWER:
                allyPaintTowers++;
                break;
            case LEVEL_ONE_MONEY_TOWER:
            case LEVEL_TWO_MONEY_TOWER:
            case LEVEL_THREE_MONEY_TOWER:
                allyMoneyTowers++;
                break;
            case LEVEL_ONE_DEFENSE_TOWER:
            case LEVEL_TWO_DEFENSE_TOWER:
            case LEVEL_THREE_DEFENSE_TOWER:
                allyDefenseTowers++;
                break;
        }
    }

    private static void decrementCounter(UnitType type, Team team) {
        switch (type) {
            case LEVEL_ONE_PAINT_TOWER:
            case LEVEL_TWO_PAINT_TOWER:
            case LEVEL_THREE_PAINT_TOWER:
                if (allyPaintTowers > 0) allyPaintTowers--;
                break;
            case LEVEL_ONE_MONEY_TOWER:
            case LEVEL_TWO_MONEY_TOWER:
            case LEVEL_THREE_MONEY_TOWER:
                if (allyMoneyTowers > 0) allyMoneyTowers--;
                break;
            case LEVEL_ONE_DEFENSE_TOWER:
            case LEVEL_TWO_DEFENSE_TOWER:
            case LEVEL_THREE_DEFENSE_TOWER:
                if (allyDefenseTowers > 0) allyDefenseTowers--;
                break;
        }
    }
}
