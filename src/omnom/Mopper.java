package omnom;

import battlecode.common.*;

/**
 * Mopper behavior - remove enemy paint, support allies.
 * Source: bot_spec.md Part 14.10
 */
public class Mopper {
    private enum Mode { EXPLORE, RETREAT }
    private static Mode mode = Mode.EXPLORE;
    private static MapLocation retreatTarget = null;

    public static void run() throws GameActionException {
        // VISIBILITY: STATE snapshot every 10 rounds
        if (G.round % 10 == 0) {
            RobotInfo[] allies = G.getAllies();
            RobotInfo[] enemies = G.getEnemies();

            System.out.println("STATE:" + G.round + ":MOPPER:" + G.id +
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
        // Find best mop target (enemy paint)
        int bestScore = 0;
        MapLocation bestLoc = null;

        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                MapLocation loc = G.me.translate(dx, dy);

                if (!G.rc.onTheMap(loc) || !G.rc.canAttack(loc)) continue;

                MapInfo info = G.rc.senseMapInfo(loc);
                if (info.getPaint().isEnemy()) {
                    int score = 50;  // Base score for enemy paint

                    // Bonus near towers
                    MapLocation ourTower = POI.findNearestAllyTower();
                    if (ourTower != null && loc.distanceSquaredTo(ourTower) <= 25) {
                        score += 150;  // MOP_TOWER_WEIGHT
                    }

                    if (score > bestScore) {
                        bestScore = score;
                        bestLoc = loc;
                    }
                }
            }
        }

        // Mop enemy paint
        if (bestLoc != null && G.rc.canAttack(bestLoc)) {
            G.rc.attack(bestLoc);
        }

        // Follow allies (simple Boids cohesion)
        try {
            RobotInfo[] allies = G.getAllies();
            if (allies.length > 0) {
                Direction boidDir = Nav.computeBoidsVector(allies);
                if (G.rc.canMove(boidDir)) {
                    G.rc.move(boidDir);
                    return;
                }
            }
        } catch (Exception e) {
            // Fallback to random
        }

        // Move randomly
        Nav.moveRandom();
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
