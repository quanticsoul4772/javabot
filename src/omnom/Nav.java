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

    // Exploration state (from SPAARK)
    private static MapLocation exploreLoc = null;
    private static int exploreTime = 0;

    /**
     * Smart exploration target selection (SPAARK Motion.java).
     */
    public static MapLocation exploreRandomlyLoc() throws GameActionException {
        if (G.rc.isMovementReady()) {
            --exploreTime;

            if (exploreLoc != null) {
                // Reset if reached
                if (G.rc.canSenseLocation(exploreLoc)) {
                    exploreLoc = null;
                }
                // Timeout
                else if (exploreTime == 0) {
                    exploreLoc = null;
                }
                // 3% random reset
                else if (Random.rand() % 35 == 0) {
                    exploreLoc = null;
                }
            }

            if (exploreLoc == null) {
                // Try symmetry prediction
                MapLocation predicted = POI.predictEnemyTower();
                if (predicted != null) {
                    exploreLoc = predicted;
                } else {
                    // Random map location
                    int x = Random.nextInt(G.mapWidth);
                    int y = Random.nextInt(G.mapHeight);
                    exploreLoc = new MapLocation(x, y);
                }
                exploreTime = G.me.distanceSquaredTo(exploreLoc) + 20;
            }
        }
        return exploreLoc;
    }

    /**
     * Explore with smart targeting.
     */
    public static void exploreWithTarget() throws GameActionException {
        MapLocation target = exploreRandomlyLoc();
        if (target != null) {
            moveTo(target);
        } else {
            moveTo(G.mapCenter);
        }
    }

    /**
     * Sophisticated retreat direction (SPAARK Motion.java line 685+).
     */
    public static Direction retreatDir(MapLocation retreatLoc) throws GameActionException {
        if (!G.rc.isMovementReady()) {
            return Direction.CENTER;
        }

        int dist = G.me.distanceSquaredTo(retreatLoc);

        // Within range of tower (dist <= 8)
        if (dist <= 8) {
            // Check if we're lowest paint in queue
            RobotInfo[] allies = G.getAllies();
            boolean lowest = true;

            for (int i = allies.length; --i >= 0;) {
                int allyDist = allies[i].location.distanceSquaredTo(retreatLoc);
                if (allyDist <= 8 && allies[i].paintAmount < G.paint) {
                    lowest = false;
                    break;
                }
            }

            // If lowest paint, approach tower
            if (lowest) {
                return bug2(retreatLoc);
            }
        }

        // Not at tower or not lowest - move toward but stop at distance
        if (dist > 8) {
            return bug2(retreatLoc);
        }

        return Direction.CENTER;
    }

    /**
     * Navigate toward target using bug2 + micro (SPAARK Motion.java).
     */
    public static void bugnavTowards(MapLocation dest) throws GameActionException {
        Direction dir = bug2(dest);
        int[] scores = Micro.scoreAllDirections(dir);
        int bestIdx = G.maxIndex(scores);
        Direction best = G.ALL_DIRECTIONS[bestIdx];

        if (best != Direction.CENTER && G.rc.canMove(best)) {
            G.rc.move(best);
        }
    }

    /**
     * Navigate away from target.
     */
    public static void bugnavAway(MapLocation dest) throws GameActionException {
        Direction dir = bug2(dest).opposite();
        if (G.rc.canMove(dir)) {
            G.rc.move(dir);
        }
    }

    /**
     * Move using micro scores (SPAARK Motion.java).
     */
    public static void microMove(int[] scores) throws GameActionException {
        if (!G.rc.isMovementReady()) return;

        int bestIdx = G.maxIndex(scores);
        Direction best = G.ALL_DIRECTIONS[bestIdx];

        if (scores[bestIdx] > Integer.MIN_VALUE && best != Direction.CENTER) {
            if (G.rc.canMove(best)) {
                G.rc.move(best);
            }
        }
    }

    /**
     * Paint transfer (SPAARK Motion.java line 747+).
     */
    public static void tryTransferPaint() throws GameActionException {
        MapLocation[] ruins = G.rc.senseNearbyRuins(-1);
        if (ruins == null) return;

        for (int i = ruins.length; --i >= 0;) {
            if (G.rc.canSenseRobotAtLocation(ruins[i])) {
                RobotInfo tower = G.rc.senseRobotAtLocation(ruins[i]);
                if (tower.type.isTowerType()) {
                    // Take paint from tower (negative = take)
                    int amt = -Math.min(G.rc.getType().paintCapacity - G.rc.getPaint(), tower.paintAmount);

                    if (amt != 0 && G.rc.canTransferPaint(ruins[i], amt)) {
                        G.rc.transferPaint(ruins[i], amt);
                    }
                }
            }
        }
    }

    // Retreat state
    private static int retreatTower = -1;
    private static MapLocation retreatLoc = null;
    private static MapLocation retreatWaitingLoc = null;
    private static final int MAX_RETREAT_ROBOTS = 4;
    private static MapLocation[] retreatWaitingLocs = new MapLocation[] {
        new MapLocation(2, 2), new MapLocation(2, -2),
        new MapLocation(-2, 2), new MapLocation(-2, -2),
        new MapLocation(2, 0), new MapLocation(0, 2),
        new MapLocation(-2, 0), new MapLocation(0, -2)
    };

    /**
     * Update retreat waiting position (SPAARK Motion.java).
     */
    public static void updateRetreatWaitingLoc() throws GameActionException {
        int ourWeight = -G.paint;
        int robotsWithHigherWeight = 0;

        int dist = G.me.distanceSquaredTo(retreatLoc);
        if (dist == 4 || dist == 8) {
            for (int i = 8; --i >= 0;) {
                MapLocation waitingLoc = retreatWaitingLocs[i].translate(retreatLoc.x, retreatLoc.y);
                if (waitingLoc.equals(G.me)) continue;

                if (G.rc.canSenseLocation(waitingLoc) && G.rc.canSenseRobotAtLocation(waitingLoc)) {
                    RobotInfo r = G.rc.senseRobotAtLocation(waitingLoc);
                    int weight = -r.paintAmount;
                    if (weight > ourWeight) {
                        robotsWithHigherWeight++;
                        if (robotsWithHigherWeight >= MAX_RETREAT_ROBOTS) {
                            retreatTower = -1;  // Queue full
                            return;
                        }
                    }
                }
            }
        }

        // Find best waiting position
        int bestIdx = 0;
        int bestDist = Integer.MAX_VALUE;

        for (int i = 8; --i >= 0;) {
            MapLocation waitingLoc = retreatWaitingLocs[i].translate(retreatLoc.x, retreatLoc.y);
            int d = G.me.distanceSquaredTo(waitingLoc);
            if (d < bestDist) {
                bestDist = d;
                bestIdx = i;
            }
        }

        retreatWaitingLoc = retreatWaitingLocs[bestIdx].translate(retreatLoc.x, retreatLoc.y);
    }

    /**
     * Set retreat tower (SPAARK Motion.java).
     */
    public static void setRetreatLoc() throws GameActionException {
        // Find nearest ally paint tower
        retreatLoc = POI.findNearestAllyPaintTower();
        if (retreatLoc == null) {
            retreatLoc = POI.findNearestAllyTower();
        }
        retreatTower = retreatLoc != null ? 0 : -1;
        retreatWaitingLoc = null;
    }
}
