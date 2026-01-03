package spaark2;

import battlecode.common.*;

/**
 * Simple Bug2 navigation.
 * No paint-aware scoring - keep it simple.
 */
public class Nav {

    // Bug navigation state
    private static MapLocation currentTarget = null;
    private static boolean isTracing = false;
    private static Direction tracingDir = null;
    private static MapLocation lineStart = null;
    private static int tracingTurns = 0;
    private static boolean useRightHand = true;

    private static final int DIRECTION_SWITCH_THRESHOLD = 15;

    /**
     * Move toward a target using Bug2 navigation.
     * Returns true if movement occurred.
     */
    public static boolean moveTo(RobotController rc, MapLocation target) throws GameActionException {
        if (target == null || !rc.isMovementReady()) return false;

        MapLocation myLoc = rc.getLocation();
        if (myLoc.equals(target)) return false;

        Direction targetDir = myLoc.directionTo(target);

        // Simple direct movement for early rounds to save bytecode
        int round = rc.getRoundNum();
        if (round < 15) {
            if (rc.canMove(targetDir)) {
                rc.move(targetDir);
                return true;
            }
            if (rc.canMove(targetDir.rotateLeft())) {
                rc.move(targetDir.rotateLeft());
                return true;
            }
            if (rc.canMove(targetDir.rotateRight())) {
                rc.move(targetDir.rotateRight());
                return true;
            }
            return false;
        }

        // Reset if target changed
        if (!target.equals(currentTarget)) {
            currentTarget = target;
            isTracing = false;
            tracingDir = null;
            lineStart = myLoc;
            tracingTurns = 0;
            useRightHand = true;
        }

        if (!isTracing) {
            // Try direct movement
            if (rc.canMove(targetDir)) {
                rc.move(targetDir);
                return true;
            }
            // Try adjacent
            if (rc.canMove(targetDir.rotateLeft())) {
                rc.move(targetDir.rotateLeft());
                return true;
            }
            if (rc.canMove(targetDir.rotateRight())) {
                rc.move(targetDir.rotateRight());
                return true;
            }

            // Start tracing
            isTracing = true;
            tracingDir = targetDir;
            lineStart = myLoc;
            tracingTurns = 0;
        }

        if (isTracing) {
            tracingTurns++;

            // Switch direction after threshold
            if (tracingTurns > DIRECTION_SWITCH_THRESHOLD) {
                useRightHand = !useRightHand;
                tracingTurns = 0;
            }

            // Check if we've crossed the line closer to target
            if (isOnLine(myLoc, lineStart, target) &&
                myLoc.distanceSquaredTo(target) < lineStart.distanceSquaredTo(target)) {
                isTracing = false;
                tracingTurns = 0;
                return moveTo(rc, target);
            }

            // Wall follow
            for (int i = 0; i < 8; i++) {
                Direction tryDir = useRightHand ? tracingDir.rotateRight() : tracingDir.rotateLeft();
                if (rc.canMove(tryDir)) {
                    rc.move(tryDir);
                    tracingDir = useRightHand ? tryDir.rotateLeft().rotateLeft() : tryDir.rotateRight().rotateRight();
                    return true;
                }
                tracingDir = useRightHand ? tracingDir.rotateLeft() : tracingDir.rotateRight();
            }
        }

        return false;
    }

    /**
     * Move away from a threat.
     */
    public static boolean moveAway(RobotController rc, MapLocation threat) throws GameActionException {
        if (threat == null || !rc.isMovementReady()) return false;

        Direction away = rc.getLocation().directionTo(threat).opposite();
        if (rc.canMove(away)) {
            rc.move(away);
            return true;
        }
        if (rc.canMove(away.rotateLeft())) {
            rc.move(away.rotateLeft());
            return true;
        }
        if (rc.canMove(away.rotateRight())) {
            rc.move(away.rotateRight());
            return true;
        }
        return false;
    }

    /**
     * Try to move in a random direction.
     */
    public static boolean moveRandom(RobotController rc) throws GameActionException {
        if (!rc.isMovementReady()) return false;

        Direction[] dirs = Globals.DIRECTIONS;
        int start = Globals.getRng(rc).nextInt(8);
        for (int i = 0; i < 8; i++) {
            Direction d = dirs[(start + i) % 8];
            if (rc.canMove(d)) {
                rc.move(d);
                return true;
            }
        }
        return false;
    }

    /**
     * Check if point is on line between start and end.
     */
    private static boolean isOnLine(MapLocation point, MapLocation start, MapLocation end) {
        int dx = end.x - start.x;
        int dy = end.y - start.y;
        int px = point.x - start.x;
        int py = point.y - start.y;

        int cross = Math.abs(dx * py - dy * px);
        int lineLength = (int) Math.sqrt(dx * dx + dy * dy);

        if (lineLength == 0) return point.equals(start);
        return cross / lineLength <= 2;
    }

    /**
     * Reset navigation state.
     */
    public static void reset() {
        currentTarget = null;
        isTracing = false;
        tracingDir = null;
        lineStart = null;
        tracingTurns = 0;
        useRightHand = true;
    }

    /**
     * Move toward target with micro scoring for combat situations.
     * Uses paint-aware and threat-aware movement.
     */
    public static boolean moveToWithMicro(RobotController rc, MapLocation target) throws GameActionException {
        if (!rc.isMovementReady()) return false;

        Direction targetDir = rc.getLocation().directionTo(target);
        Micro.score(rc, targetDir);
        Direction best = Micro.getBestDirection();

        if (best != Direction.CENTER && rc.canMove(best)) {
            rc.move(best);
            return true;
        }

        // Fall back to regular movement if micro fails
        return moveTo(rc, target);
    }

    /**
     * Retreat movement - prioritizes safety over reaching target.
     */
    public static boolean retreatFrom(RobotController rc, MapLocation threat) throws GameActionException {
        if (!rc.isMovementReady()) return false;

        Direction away = rc.getLocation().directionTo(threat).opposite();
        Micro.score(rc, away);
        Direction best = Micro.getBestDirection();

        if (best != Direction.CENTER && rc.canMove(best)) {
            rc.move(best);
            return true;
        }

        return moveAway(rc, threat);
    }
}
