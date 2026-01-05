package omnom;

import battlecode.common.*;

/**
 * Splasher behavior - area paint attacks.
 * Source: bot_spec.md Part 14.9
 */
public class Splasher {
    private enum Mode { EXPLORE, RETREAT }
    private static Mode mode = Mode.EXPLORE;
    private static MapLocation retreatTarget = null;

    public static void run() throws GameActionException {
        // VISIBILITY: STATE snapshot every 10 rounds
        if (G.round % 10 == 0) {
            RobotInfo[] allies = G.getAllies();
            RobotInfo[] enemies = G.getEnemies();

            System.out.println("STATE:" + G.round + ":SPLASHER:" + G.id +
                ":pos=" + G.me +
                ":paint=" + G.paint +
                ":mode=" + mode +
                ":allies=" + allies.length +
                ":enemies=" + enemies.length);
        }

        // Update mode
        updateMode();

        // Execute mode
        switch (mode) {
            case EXPLORE: explore(); break;
            case RETREAT: retreat(); break;
        }
    }

    private static void updateMode() throws GameActionException {
        // RETREAT check
        if (G.paint < G.RETREAT_PAINT) {
            mode = Mode.RETREAT;
            return;
        }

        // Exit retreat
        if (mode == Mode.RETREAT && G.paint > 100) {
            mode = Mode.EXPLORE;
            retreatTarget = null;
        }

        // Default: EXPLORE
        if (mode != Mode.RETREAT) {
            mode = Mode.EXPLORE;
        }
    }

    private static void explore() throws GameActionException {
        // Attack best location (dynamic threshold from spec Part 10.1)
        int threshold = G.mapArea * 3 / G.round + 300;

        int bestScore = 0;
        MapLocation bestLoc = null;

        // Check tiles in range (simple 3x3 area)
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                MapLocation loc = G.me.translate(dx, dy);

                if (!G.rc.onTheMap(loc) || !G.rc.canAttack(loc)) continue;

                int score = 0;
                MapInfo info = G.rc.senseMapInfo(loc);

                // Score from spec Part 14.9.1
                if (info.getPaint() == PaintType.EMPTY) {
                    score += 25;  // Empty tile
                } else if (info.getPaint().isEnemy()) {
                    score += 50;  // Enemy paint
                }

                if (score > bestScore) {
                    bestScore = score;
                    bestLoc = loc;
                }
            }
        }

        // Attack if score above threshold
        if (bestLoc != null && bestScore >= threshold && G.rc.canAttack(bestLoc)) {
            G.rc.attack(bestLoc);
        }

        // Move toward enemy territory
        MapLocation target = POI.findNearestEnemyTower();
        if (target == null) {
            target = G.mapCenter;
        }

        Nav.moveTo(target);
    }

    private static void retreat() throws GameActionException {
        if (retreatTarget == null) {
            retreatTarget = POI.findNearestAllyPaintTower();
            if (retreatTarget == null) retreatTarget = POI.findNearestAllyTower();
        }

        if (retreatTarget != null) {
            Nav.moveTo(retreatTarget);
        }
    }
}
