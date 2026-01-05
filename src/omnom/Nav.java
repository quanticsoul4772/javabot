package omnom;

import battlecode.common.*;

/**
 * Navigation - Bug2 pathfinding.
 * Source: bot_spec.md Part 14.2
 */
public class Nav {
    // Bug2 state
    private static MapLocation bugTarget;
    private static boolean bugTracing;
    private static Direction bugTracingDir;
    private static MapLocation bugStartLoc;
    private static int bugStartDist;
    private static boolean bugRotateRight;
    private static int bugTurns;

    /**
     * Move toward target using Bug2.
     */
    public static boolean moveTo(MapLocation target) throws GameActionException {
        if (target == null || G.me.equals(target)) {
            return false;
        }

        // 15% CONTROLLED CHAOS: Random move instead of optimal
        if (Random.nextDouble() < 0.15) {
            return moveRandom();
        }

        Direction dir = bug2(target);

        // Try to move
        if (G.rc.canMove(dir)) {
            G.rc.move(dir);
            return true;
        }

        // Try adjacent directions
        if (G.rc.canMove(dir.rotateLeft())) {
            G.rc.move(dir.rotateLeft());
            return true;
        }

        if (G.rc.canMove(dir.rotateRight())) {
            G.rc.move(dir.rotateRight());
            return true;
        }

        return false;
    }

    /**
     * Move with micro scoring.
     */
    public static boolean moveToWithMicro(MapLocation target) throws GameActionException {
        if (target == null || G.me.equals(target)) {
            return false;
        }

        Direction targetDir = G.me.directionTo(target);
        int[] scores = Micro.scoreAllDirections(targetDir);

        int bestIdx = G.maxIndex(scores);
        Direction best = G.ALL_DIRECTIONS[bestIdx];

        if (best != Direction.CENTER && G.rc.canMove(best)) {
            G.rc.move(best);
            return true;
        }

        return false;
    }

    /**
     * Compute Boids flocking vector.
     * Source: bot_spec.md Part 14.3
     */
    public static Direction computeBoidsVector(RobotInfo[] allies) {
        int sepX = 0, sepY = 0;  // Separation
        int cohX = 0, cohY = 0;  // Cohesion
        int count = 0;

        for (int i = allies.length; --i >= 0;) {
            MapLocation allyLoc = allies[i].location;
            int dist = G.me.distanceSquaredTo(allyLoc);

            // Separation: avoid nearby allies (< 9 tiles)
            if (dist < 9 && dist > 0) {
                sepX -= (allyLoc.x - G.me.x);
                sepY -= (allyLoc.y - G.me.y);
            }

            // Cohesion: move toward group center (< 25 tiles)
            if (dist < 25) {
                cohX += allyLoc.x;
                cohY += allyLoc.y;
                count++;
            }
        }

        // Combine vectors with weights
        int finalX = sepX * 3;  // Separation weight: 3
        int finalY = sepY * 3;

        if (count > 0) {
            finalX += (cohX / count - G.me.x);  // Cohesion weight: 1
            finalY += (cohY / count - G.me.y);
        }

        return G.directionFromVector(finalX, finalY);
    }

    /**
     * Move with Boids coordination (for mid/late game).
     */
    public static boolean moveToWithBoids(MapLocation target) throws GameActionException {
        if (target == null || G.me.equals(target)) {
            return false;
        }

        // Blend Bug2 with Boids (alternate based on round)
        Direction dir;
        if ((G.round & 1) == 0) {
            // Even rounds: strategic (Bug2)
            dir = bug2(target);
        } else {
            // Odd rounds: tactical (Boids)
            try {
                RobotInfo[] allies = G.getAllies();
                dir = computeBoidsVector(allies);
            } catch (GameActionException e) {
                dir = bug2(target);  // Fallback
            }
        }

        if (G.rc.canMove(dir)) {
            G.rc.move(dir);
            return true;
        }

        return false;
    }

    /**
     * Bug2 with mode support (TOWARDS/AWAY/AROUND).
     * Source: bot_spec.md Part 14.18
     */
    public static final int TOWARDS = 0;
    public static final int AWAY = 1;
    public static final int AROUND = 2;

    public static Direction bug2Directional(MapLocation target, int mode) throws GameActionException {
        Direction dir = bug2(target);

        switch (mode) {
            case AWAY:
                // Flee from target
                return dir.opposite();

            case AROUND:
                // Circle around target
                int dist = G.me.distanceSquaredTo(target);
                if (dist < 16) {
                    return dir.opposite();  // Too close, move away
                } else if (dist <= 64) {
                    return dir.rotateLeft().rotateLeft();  // Circle
                }
                // else fall through to TOWARDS

            case TOWARDS:
            default:
                return dir;
        }
    }

    /**
     * Bug2 pathfinding algorithm.
     */
    private static Direction bug2(MapLocation target) throws GameActionException {
        // Reset if target changed
        if (!target.equals(bugTarget)) {
            bugTarget = target;
            bugTracing = false;
        }

        Direction targetDir = G.me.directionTo(target);

        // If not tracing, try direct path
        if (!bugTracing) {
            if (G.rc.canMove(targetDir)) {
                return targetDir;
            }

            // Start tracing obstacle
            bugTracing = true;
            bugStartLoc = G.me;
            bugStartDist = G.me.distanceSquaredTo(target);
            bugTracingDir = targetDir;
            bugRotateRight = Random.nextBoolean();
            bugTurns = 0;
        }

        // Tracing - follow obstacle
        if (bugTracing) {
            // Check if can leave trace (closer than start)
            int curDist = G.me.distanceSquaredTo(target);
            if (curDist < bugStartDist && G.rc.canMove(targetDir)) {
                bugTracing = false;
                return targetDir;
            }

            // Timeout after 20 turns
            bugTurns++;
            if (bugTurns > 20) {
                bugTracing = false;
                return targetDir;
            }

            // Rotate around obstacle
            Direction dir = bugTracingDir;
            for (int i = 8; --i >= 0;) {
                if (G.rc.canMove(dir)) {
                    bugTracingDir = bugRotateRight ? dir.rotateLeft() : dir.rotateRight();
                    return dir;
                }
                dir = bugRotateRight ? dir.rotateRight() : dir.rotateLeft();
            }
        }

        return targetDir;
    }

    /**
     * Move randomly for exploration.
     */
    public static boolean moveRandom() throws GameActionException {
        Direction dir = G.DIRECTIONS[Random.nextInt(8)];

        for (int i = 8; --i >= 0;) {
            if (G.rc.canMove(dir)) {
                G.rc.move(dir);
                return true;
            }
            dir = dir.rotateRight();
        }

        return false;
    }
}
