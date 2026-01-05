package omnom;

import battlecode.common.*;

/**
 * Micro combat scoring system.
 * Source: bot_spec.md Part 8.1, SPAARK Motion.java defaultMicro
 */
public class Micro {
    // Constants from spec Part 8.2
    public static final int DEF_MICRO_E_PAINT_PENALTY = 5;
    public static final int DEF_MICRO_N_PAINT_PENALTY = 5;

    /**
     * Score all 9 directions for movement.
     * Returns array of scores (higher = better).
     */
    public static int[] scoreAllDirections(Direction targetDir) throws GameActionException {
        int[] scores = new int[9];

        // Direction bonus
        for (int i = 0; i < 9; i++) {
            if (G.ALL_DIRECTIONS[i] == targetDir) {
                scores[i] += 20;
            } else if (G.ALL_DIRECTIONS[i] == targetDir.rotateLeft() ||
                       G.ALL_DIRECTIONS[i] == targetDir.rotateRight()) {
                scores[i] += 15;
            }
        }

        // Calculate turnsToNext for paint penalties
        int turnsToNext = (G.cooldown(G.paint, GameConstants.MOVEMENT_COOLDOWN, G.type.paintCapacity) + 0) / 10;

        // Paint penalties (scaled by turnsToNext)
        int enemyPaintPenalty = DEF_MICRO_E_PAINT_PENALTY * GameConstants.PENALTY_ENEMY_TERRITORY * turnsToNext;
        int neutralPaintPenalty = DEF_MICRO_N_PAINT_PENALTY * GameConstants.PENALTY_NEUTRAL_TERRITORY * turnsToNext;

        // Apply paint penalties to each direction
        for (int i = 0; i < 9; i++) {
            Direction dir = G.ALL_DIRECTIONS[i];

            // Can't move there
            if (i != 8 && !G.rc.canMove(dir)) {
                scores[i] = Integer.MIN_VALUE;
                continue;
            }

            MapLocation nxt = G.me.add(dir);

            if (!G.rc.onTheMap(nxt)) {
                scores[i] = Integer.MIN_VALUE;
                continue;
            }

            // Paint penalties
            PaintType paint = G.rc.senseMapInfo(nxt).getPaint();
            if (paint.isEnemy()) {
                scores[i] -= enemyPaintPenalty;
            } else if (paint == PaintType.EMPTY) {
                scores[i] -= neutralPaintPenalty;
            }
        }

        return scores;
    }

    /**
     * Find nearest enemy for targeting.
     */
    public static RobotInfo findNearestEnemy() throws GameActionException {
        RobotInfo[] enemies = G.getEnemies();
        if (enemies.length == 0) return null;

        RobotInfo nearest = enemies[0];
        int nearestDist = G.me.distanceSquaredTo(enemies[0].location);

        for (int i = enemies.length; --i > 0;) {
            int dist = G.me.distanceSquaredTo(enemies[i].location);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = enemies[i];
            }
        }

        return nearest;
    }
}
