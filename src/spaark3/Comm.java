package spaark3;

import battlecode.common.*;

/**
 * Paint-as-Pheromone communication system.
 * Uses paint patterns for stigmergic coordination (zero bytecode messaging).
 *
 * Signals:
 * - SAFE_ZONE: Dense ally paint (>6 tiles in 3x3)
 * - FRONTLINE: Mixed paint (enemy present)
 * - UNEXPLORED: Mostly neutral paint
 * - NORMAL: Default state
 */
public class Comm {

    // Signal types
    public static final int SIGNAL_NORMAL = 0;
    public static final int SIGNAL_SAFE_ZONE = 1;
    public static final int SIGNAL_FRONTLINE = 2;
    public static final int SIGNAL_UNEXPLORED = 3;

    // Thresholds
    private static final int SAFE_ZONE_THRESHOLD = 6;
    private static final int FRONTLINE_THRESHOLD = 3;
    private static final int UNEXPLORED_THRESHOLD = 5;

    /**
     * Read paint signal at current location.
     * Interprets paint patterns as coordination signals.
     */
    public static int readPaintSignal() throws GameActionException {
        return readPaintSignal(G.me);
    }

    /**
     * Read paint signal at specified location.
     */
    public static int readPaintSignal(MapLocation loc) throws GameActionException {
        MapInfo[] nearby = G.rc.senseNearbyMapInfos(loc, 8);

        int allyCount = 0;
        int enemyCount = 0;
        int neutralCount = 0;

        for (int i = nearby.length; --i >= 0;) {
            PaintType paint = nearby[i].getPaint();
            if (paint.isAlly()) allyCount++;
            else if (paint.isEnemy()) enemyCount++;
            else neutralCount++;
        }

        // Interpret pattern
        if (allyCount >= SAFE_ZONE_THRESHOLD && enemyCount == 0) {
            return SIGNAL_SAFE_ZONE;
        }
        if (enemyCount >= FRONTLINE_THRESHOLD) {
            return SIGNAL_FRONTLINE;
        }
        if (neutralCount >= UNEXPLORED_THRESHOLD) {
            return SIGNAL_UNEXPLORED;
        }

        return SIGNAL_NORMAL;
    }

    /**
     * Find direction following ally paint gradient.
     * Move toward areas with more ally paint.
     */
    public static Direction followPaintGradient() throws GameActionException {
        int[] scores = new int[8];

        for (int d = 8; --d >= 0;) {
            Direction dir = G.DIRECTIONS[d];
            MapLocation target = G.me.add(dir);

            if (!G.rc.canSenseLocation(target)) {
                scores[d] = -100;
                continue;
            }

            // Count ally paint in direction
            scores[d] = countAllyPaintInDirection(target);
        }

        int best = G.maxIndex(scores);
        return scores[best] > 0 ? G.DIRECTIONS[best] : Direction.CENTER;
    }

    /**
     * Find direction following enemy paint gradient.
     * Move toward areas with enemy paint (for splashers).
     */
    public static Direction followEnemyPaintGradient() throws GameActionException {
        int[] scores = new int[8];

        for (int d = 8; --d >= 0;) {
            Direction dir = G.DIRECTIONS[d];
            MapLocation target = G.me.add(dir);

            if (!G.rc.canSenseLocation(target)) {
                scores[d] = -100;
                continue;
            }

            // Count enemy paint in direction
            scores[d] = countEnemyPaintInDirection(target);
        }

        int best = G.maxIndex(scores);
        return scores[best] > 0 ? G.DIRECTIONS[best] : Direction.CENTER;
    }

    /**
     * Count ally paint tiles around a location.
     */
    private static int countAllyPaintInDirection(MapLocation loc) throws GameActionException {
        int count = 0;
        MapInfo[] tiles = G.rc.senseNearbyMapInfos(loc, 4);

        for (int i = tiles.length; --i >= 0;) {
            if (tiles[i].getPaint().isAlly()) count++;
        }

        return count;
    }

    /**
     * Count enemy paint tiles around a location.
     */
    private static int countEnemyPaintInDirection(MapLocation loc) throws GameActionException {
        int count = 0;
        MapInfo[] tiles = G.rc.senseNearbyMapInfos(loc, 4);

        for (int i = tiles.length; --i >= 0;) {
            if (tiles[i].getPaint().isEnemy()) count++;
        }

        return count;
    }

    /**
     * Find unpainted tiles for exploration.
     * Prioritizes neutral over enemy paint.
     */
    public static MapLocation findUnexploredArea() throws GameActionException {
        MapLocation best = null;
        int bestScore = Integer.MIN_VALUE;

        MapInfo[] tiles = G.getNearbyTiles();
        for (int i = tiles.length; --i >= 0;) {
            MapInfo info = tiles[i];
            if (!info.isPassable()) continue;

            PaintType paint = info.getPaint();
            int score = 0;

            if (!paint.isAlly()) {
                // Neutral is preferred
                score = paint == PaintType.EMPTY ? 10 : 5;

                // Distance penalty
                score -= G.me.distanceSquaredTo(info.getMapLocation()) >> 2;

                if (score > bestScore) {
                    bestScore = score;
                    best = info.getMapLocation();
                }
            }
        }

        return best;
    }

    /**
     * Check if we're at the frontline (near enemy territory).
     */
    public static boolean atFrontline() throws GameActionException {
        return readPaintSignal() == SIGNAL_FRONTLINE;
    }

    /**
     * Check if we're in safe territory.
     */
    public static boolean inSafeZone() throws GameActionException {
        return readPaintSignal() == SIGNAL_SAFE_ZONE;
    }
}
