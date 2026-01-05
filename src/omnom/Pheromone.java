package omnom;

import battlecode.common.*;

/**
 * Paint-as-Pheromone system (stigmergic communication).
 * Source: bot_spec.md Part 5
 */
public class Pheromone {
    /**
     * Follow paint gradient (move toward ally paint concentration).
     */
    public static Direction followPaintGradient() throws GameActionException {
        int[] scores = new int[8];

        for (int i = 0; i < 8; i++) {
            Direction dir = G.DIRECTIONS[i];
            scores[i] = countAllyPaintInDirection(dir, 10);
        }

        int bestIdx = 0;
        int bestScore = scores[0];
        for (int i = 8; --i > 0;) {
            if (scores[i] > bestScore) {
                bestScore = scores[i];
                bestIdx = i;
            }
        }

        return G.DIRECTIONS[bestIdx];
    }

    /**
     * Count ally paint tiles in direction (from spec Part 14.20).
     */
    private static int countAllyPaintInDirection(Direction dir, int range) throws GameActionException {
        int count = 0;
        MapLocation check = G.me;

        for (int i = 0; i < range; i++) {
            check = check.add(dir);
            if (!G.rc.onTheMap(check)) break;

            if (G.rc.canSenseLocation(check)) {
                if (G.rc.senseMapInfo(check).getPaint().isAlly()) {
                    count++;
                }
            }
        }

        return count;
    }

    /**
     * Read paint signal at location (from spec Part 5.1).
     */
    public static String readPaintSignal(MapLocation loc) throws GameActionException {
        if (!G.rc.canSenseLocation(loc)) return "UNKNOWN";

        MapInfo[] nearby = G.rc.senseNearbyMapInfos(loc, 8);
        int allyCount = 0, enemyCount = 0, neutralCount = 0;

        for (int i = nearby.length; --i >= 0;) {
            PaintType paint = nearby[i].getPaint();
            if (paint.isAlly()) allyCount++;
            else if (paint.isEnemy()) enemyCount++;
            else neutralCount++;
        }

        // Signal interpretation (from spec)
        if (allyCount > 6 && enemyCount == 0) return "SAFE_ZONE";
        if (enemyCount > 3) return "FRONTLINE";
        if (neutralCount > 5) return "UNEXPLORED";

        return "NORMAL";
    }
}
