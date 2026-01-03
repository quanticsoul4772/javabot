package mybot.core;

import battlecode.common.*;
import mybot.Utils;
import mybot.Scoring;

/**
 * Instance-based navigation using improved Bug2 algorithm.
 * Each robot has its own Navigator instance for independent pathfinding.
 *
 * Features:
 * - Paint-aware movement (prefer ally-painted tiles)
 * - C-shape handling (direction reversal after timeout)
 * - Left/right hand rule switching
 */
public class Navigator {

    private final RobotController rc;

    // Bug navigation state (instance-based, not static)
    private MapLocation currentTarget = null;
    private boolean isTracing = false;
    private Direction tracingDir = null;
    private MapLocation lineStart = null;
    private int stuckCounter = 0;

    // C-shape handling
    private int tracingTurns = 0;
    private boolean useRightHand = true;
    private static final int DIRECTION_SWITCH_THRESHOLD = 15;

    /**
     * Create a new Navigator for a robot.
     */
    public Navigator(RobotController rc) {
        this.rc = rc;
    }

    /**
     * Move toward a target location using improved Bug2 navigation.
     * Returns true if movement occurred.
     */
    public boolean moveTo(MapLocation target) throws GameActionException {
        if (target == null) return false;
        if (!rc.isMovementReady()) return false;

        MapLocation myLoc = rc.getLocation();

        // Already at target
        if (myLoc.equals(target)) return false;

        // Reset if target changed
        if (!target.equals(currentTarget)) {
            currentTarget = target;
            isTracing = false;
            tracingDir = null;
            lineStart = myLoc;
            stuckCounter = 0;
            tracingTurns = 0;
            useRightHand = true;
        }

        // Direct path toward target
        Direction targetDir = myLoc.directionTo(target);

        if (!isTracing) {
            // Try to move toward target, preferring painted tiles
            Direction bestDir = findBestDirectionToward(target);
            if (bestDir != null && rc.canMove(bestDir)) {
                rc.move(bestDir);
                return true;
            }

            // Fallback: try direct movement
            if (rc.canMove(targetDir)) {
                rc.move(targetDir);
                return true;
            }
            // Try adjacent directions
            Direction left = targetDir.rotateLeft();
            Direction right = targetDir.rotateRight();
            if (rc.canMove(left)) {
                rc.move(left);
                return true;
            }
            if (rc.canMove(right)) {
                rc.move(right);
                return true;
            }

            // Start obstacle tracing
            isTracing = true;
            tracingDir = targetDir;
            lineStart = myLoc;
            tracingTurns = 0;
        }

        if (isTracing) {
            tracingTurns++;

            // C-shape handling - switch direction after threshold
            if (tracingTurns > DIRECTION_SWITCH_THRESHOLD) {
                useRightHand = !useRightHand;
                tracingTurns = 0;
            }

            // Check if we've crossed the start-target line closer to target
            if (isOnLine(myLoc, lineStart, target) &&
                myLoc.distanceSquaredTo(target) < lineStart.distanceSquaredTo(target)) {
                isTracing = false;
                tracingTurns = 0;
                return moveTo(target);
            }

            // Follow obstacle using current hand rule
            Direction bestTraceDir = null;
            int bestScore = Integer.MIN_VALUE;

            for (int i = 0; i < 8; i++) {
                Direction tryDir;
                if (useRightHand) {
                    tryDir = tracingDir.rotateRight();
                } else {
                    tryDir = tracingDir.rotateLeft();
                }

                if (rc.canMove(tryDir)) {
                    MapLocation newLoc = myLoc.add(tryDir);
                    int score = Utils.scoreTile(rc, newLoc);
                    score -= newLoc.distanceSquaredTo(target) / 10;

                    if (score > bestScore) {
                        bestScore = score;
                        bestTraceDir = tryDir;
                    }
                }

                if (useRightHand) {
                    tracingDir = tracingDir.rotateLeft();
                } else {
                    tracingDir = tracingDir.rotateRight();
                }
            }

            if (bestTraceDir != null) {
                rc.move(bestTraceDir);
                if (useRightHand) {
                    tracingDir = bestTraceDir.rotateLeft().rotateLeft();
                } else {
                    tracingDir = bestTraceDir.rotateRight().rotateRight();
                }
                stuckCounter = 0;
                return true;
            }

            // Stuck - try resetting
            stuckCounter++;
            if (stuckCounter > 10) {
                isTracing = false;
                currentTarget = null;
                tracingTurns = 0;
                return Utils.tryMoveRandom(rc);
            }
        }

        return false;
    }

    /**
     * Find the best direction toward target considering paint.
     */
    private Direction findBestDirectionToward(MapLocation target) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        Direction targetDir = myLoc.directionTo(target);

        Direction bestDir = null;
        int bestScore = Integer.MIN_VALUE;

        Direction[] tryDirs = {
            targetDir,
            targetDir.rotateLeft(),
            targetDir.rotateRight(),
            targetDir.rotateLeft().rotateLeft(),
            targetDir.rotateRight().rotateRight()
        };

        for (Direction dir : tryDirs) {
            if (!rc.canMove(dir)) continue;

            MapLocation newLoc = myLoc.add(dir);
            int score = Utils.scoreTile(rc, newLoc);

            int distBefore = myLoc.distanceSquaredTo(target);
            int distAfter = newLoc.distanceSquaredTo(target);
            if (distAfter < distBefore) {
                score += Scoring.WEIGHT_GETTING_CLOSER_BONUS;
            }

            if (dir != targetDir) {
                score += Scoring.WEIGHT_NON_DIRECT_PENALTY;
            }

            if (score > bestScore) {
                bestScore = score;
                bestDir = dir;
            }
        }

        return (bestScore > -50) ? bestDir : null;
    }

    /**
     * Check if point is approximately on line between start and end.
     */
    private boolean isOnLine(MapLocation point, MapLocation start, MapLocation end) {
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
     * Move away from a location (flee behavior).
     */
    public boolean moveAway(MapLocation threat) throws GameActionException {
        if (threat == null) return false;
        if (!rc.isMovementReady()) return false;

        Direction awayDir = rc.getLocation().directionTo(threat).opposite();

        if (rc.canMove(awayDir)) {
            rc.move(awayDir);
            return true;
        }
        if (rc.canMove(awayDir.rotateLeft())) {
            rc.move(awayDir.rotateLeft());
            return true;
        }
        if (rc.canMove(awayDir.rotateRight())) {
            rc.move(awayDir.rotateRight());
            return true;
        }

        return Utils.tryMoveRandom(rc);
    }

    /**
     * Reset navigation state.
     */
    public void reset() {
        currentTarget = null;
        isTracing = false;
        tracingDir = null;
        lineStart = null;
        stuckCounter = 0;
        tracingTurns = 0;
        useRightHand = true;
    }

    /**
     * Get the current navigation target.
     */
    public MapLocation getTarget() {
        return currentTarget;
    }

    /**
     * Check if currently tracing an obstacle.
     */
    public boolean isTracing() {
        return isTracing;
    }
}
