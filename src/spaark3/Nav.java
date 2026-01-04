package spaark3;

import battlecode.common.*;

/**
 * Navigation system combining Bug2 pathfinding with Boids flocking.
 * Boids provides emergent coordination without messaging overhead.
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

    // Boids constants
    private static final int SEPARATION_RADIUS = 9;   // Avoid crowding
    private static final int COHESION_RADIUS = 25;    // Stay near group

    // Movement weights
    private static final int SEPARATION_WEIGHT = 3;
    private static final int COHESION_WEIGHT = 1;

    // Debug tracking
    public static String lastNavInfo = "";

    /**
     * Move toward target using Bug2 + controlled chaos.
     * 10% randomization to prevent deterministic behavior.
     */
    public static boolean moveTo(MapLocation target) throws GameActionException {
        if (target == null || G.me.equals(target)) {
            return false;
        }

        // 10% chance for random move (controlled chaos)
        if (Random.nextInt(10) == 0) {
            Direction randDir = randomValidDirection();
            if (randDir != null) {
                G.rc.move(randDir);
                return true;
            }
        }

        // Bug2 movement
        Direction dir = bug2(target);

        // Try direct, then adjacent (with randomized order)
        if (G.rc.canMove(dir)) {
            G.rc.move(dir);
            return true;
        }

        // Randomly choose left or right first
        boolean tryLeftFirst = Random.nextBoolean();
        Direction first = tryLeftFirst ? dir.rotateLeft() : dir.rotateRight();
        Direction second = tryLeftFirst ? dir.rotateRight() : dir.rotateLeft();

        if (G.rc.canMove(first)) {
            G.rc.move(first);
            return true;
        }

        if (G.rc.canMove(second)) {
            G.rc.move(second);
            return true;
        }

        return false;
    }

    /**
     * Move with micro combat awareness.
     * Uses Micro scoring system for threat avoidance.
     */
    public static boolean moveToWithMicro(MapLocation target) throws GameActionException {
        int[] scores = Micro.scoreAllDirections(target);
        int bestDir = G.maxIndex(scores);

        if (scores[bestDir] > Integer.MIN_VALUE) {
            Direction dir = G.ALL_DIRECTIONS[bestDir];
            if (dir != Direction.CENTER && G.rc.canMove(dir)) {
                G.rc.move(dir);
                return true;
            }
        }
        return false;
    }

    /**
     * Retreat from threats toward safety.
     */
    public static boolean retreatFrom(MapLocation threat) throws GameActionException {
        if (threat == null) return false;

        // Find direction away from threat
        Direction away = threat.directionTo(G.me);

        // Try away and adjacent directions
        Direction[] tryDirs = {away, away.rotateLeft(), away.rotateRight(),
                              away.rotateLeft().rotateLeft(), away.rotateRight().rotateRight()};

        for (int i = 0; i < tryDirs.length; i++) {
            Direction dir = tryDirs[i];
            if (G.rc.canMove(dir)) {
                MapLocation newLoc = G.me.add(dir);
                // Prefer ally paint
                MapInfo info = G.rc.senseMapInfo(newLoc);
                if (info.getPaint().isAlly()) {
                    G.rc.move(dir);
                    return true;
                }
            }
        }

        // If no ally paint, just move away
        for (int i = 0; i < tryDirs.length; i++) {
            if (G.rc.canMove(tryDirs[i])) {
                G.rc.move(tryDirs[i]);
                return true;
            }
        }

        return false;
    }

    /**
     * Move randomly for exploration.
     */
    public static boolean moveRandom() throws GameActionException {
        Direction dir = randomValidDirection();
        if (dir != null && G.rc.canMove(dir)) {
            G.rc.move(dir);
            return true;
        }
        return false;
    }

    /**
     * Bug2 pathfinding algorithm.
     * Returns best direction toward target, handling obstacles.
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

            // Start tracing
            bugTracing = true;
            bugStartLoc = G.me;
            bugStartDist = G.me.distanceSquaredTo(target);
            bugTracingDir = targetDir;
            bugRotateRight = Random.nextBoolean();
            bugTurns = 0;
        }

        // Tracing - follow obstacle
        if (bugTracing) {
            // Check if we can leave trace (closer than start)
            int curDist = G.me.distanceSquaredTo(target);
            if (curDist < bugStartDist && G.rc.canMove(targetDir)) {
                bugTracing = false;
                return targetDir;
            }

            // Timeout tracing after 20 turns
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
     * Compute Boids flocking vector from nearby allies.
     * Three rules: Separation, Alignment (implicit), Cohesion.
     */
    private static Direction computeBoidsVector(RobotInfo[] allies) {
        if (allies.length == 0) return Direction.CENTER;

        int sepX = 0, sepY = 0;  // Separation vector
        int cohX = 0, cohY = 0;  // Cohesion center
        int cohCount = 0;

        MapLocation myLoc = G.me;
        int myX = myLoc.x;
        int myY = myLoc.y;

        // Reversed loop for bytecode efficiency
        for (int i = allies.length; --i >= 0;) {
            RobotInfo ally = allies[i];
            if (ally.type.isTowerType()) continue;  // Skip towers

            MapLocation allyLoc = ally.location;
            int dx = allyLoc.x - myX;
            int dy = allyLoc.y - myY;
            int distSq = dx * dx + dy * dy;

            // Separation: push away from close allies
            if (distSq < SEPARATION_RADIUS && distSq > 0) {
                sepX -= dx;
                sepY -= dy;
            }

            // Cohesion: attract to group center
            if (distSq < COHESION_RADIUS) {
                cohX += allyLoc.x;
                cohY += allyLoc.y;
                cohCount++;
            }
        }

        // Combine vectors
        int finalX = sepX * SEPARATION_WEIGHT;
        int finalY = sepY * SEPARATION_WEIGHT;

        if (cohCount > 0) {
            int centerX = cohX / cohCount;
            int centerY = cohY / cohCount;
            finalX += (centerX - myX) * COHESION_WEIGHT;
            finalY += (centerY - myY) * COHESION_WEIGHT;
        }

        return G.directionFromVector(finalX, finalY);
    }

    /**
     * Blend two directions with given weights.
     */
    private static Direction blendDirections(Direction d1, Direction d2, double w1, double w2) {
        if (d1 == null || d1 == Direction.CENTER) return d2;
        if (d2 == null || d2 == Direction.CENTER) return d1;

        int dx = (int)(d1.dx * w1 + d2.dx * w2);
        int dy = (int)(d1.dy * w1 + d2.dy * w2);

        return G.directionFromVector(dx, dy);
    }

    /**
     * Get a random valid movement direction.
     */
    private static Direction randomValidDirection() throws GameActionException {
        int start = Random.nextInt(8);
        for (int i = 8; --i >= 0;) {
            Direction dir = G.DIRECTIONS[(start + i) & 7];
            if (G.rc.canMove(dir)) {
                return dir;
            }
        }
        return null;
    }

    /**
     * Find nearest unpainted tile for exploration.
     */
    public static MapLocation findUnpaintedTile() throws GameActionException {
        MapLocation best = null;
        int bestDist = Integer.MAX_VALUE;

        MapInfo[] tiles = G.getNearbyTiles();
        for (int i = tiles.length; --i >= 0;) {
            MapInfo info = tiles[i];
            if (!info.getPaint().isAlly() && info.isPassable()) {
                int dist = G.me.distanceSquaredTo(info.getMapLocation());
                if (dist < bestDist) {
                    bestDist = dist;
                    best = info.getMapLocation();
                }
            }
        }

        return best;
    }
}
