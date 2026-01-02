package mybot;

import battlecode.common.*;

/**
 * Navigation utilities using improved Bug2 algorithm.
 *
 * Improvements based on winning team strategies:
 * - PHASE 3: Paint-aware movement (prefer ally-painted tiles)
 * - PHASE 5: C-shape handling (direction reversal after timeout)
 * - PHASE 5: Left/right hand rule switching
 */
public class Navigation {

    // Bug navigation state
    private static MapLocation currentTarget = null;
    private static boolean isTracing = false;
    private static Direction tracingDir = null;
    private static MapLocation lineStart = null;
    private static int stuckCounter = 0;

    // PHASE 5: C-shape handling
    private static int tracingTurns = 0;
    private static boolean useRightHand = true;  // Start with right-hand rule
    private static final int DIRECTION_SWITCH_THRESHOLD = 15;  // Switch after N turns

    /**
     * Move toward a target location using improved Bug2 navigation.
     * Returns true if movement occurred.
     */
    public static boolean moveTo(RobotController rc, MapLocation target) throws GameActionException {
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
            useRightHand = true;  // Reset to right-hand rule
        }

        // Direct path toward target
        Direction targetDir = myLoc.directionTo(target);

        if (!isTracing) {
            // PHASE 3: Try to move toward target, preferring painted tiles
            Direction bestDir = findBestDirectionToward(rc, target);
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

            // PHASE 5: C-shape handling - switch direction after threshold
            if (tracingTurns > DIRECTION_SWITCH_THRESHOLD) {
                useRightHand = !useRightHand;  // Switch hand rule
                tracingTurns = 0;
                // Don't reset completely - just change direction
            }

            // Check if we've crossed the start-target line closer to target
            if (isOnLine(myLoc, lineStart, target) &&
                myLoc.distanceSquaredTo(target) < lineStart.distanceSquaredTo(target)) {
                isTracing = false;
                tracingTurns = 0;
                return moveTo(rc, target);
            }

            // Follow obstacle using current hand rule
            Direction bestTraceDir = null;
            int bestScore = Integer.MIN_VALUE;

            for (int i = 0; i < 8; i++) {
                Direction tryDir;
                if (useRightHand) {
                    tryDir = tracingDir.rotateRight();
                } else {
                    tryDir = tracingDir.rotateLeft();  // Left-hand rule
                }

                if (rc.canMove(tryDir)) {
                    // PHASE 3: Score by paint preference
                    MapLocation newLoc = myLoc.add(tryDir);
                    int score = Utils.scoreTile(rc, newLoc);
                    // Also consider distance to target
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
     * PHASE 3: Find the best direction toward target considering paint.
     * Returns null if no good direction found.
     */
    private static Direction findBestDirectionToward(RobotController rc, MapLocation target) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        Direction targetDir = myLoc.directionTo(target);

        Direction bestDir = null;
        int bestScore = Integer.MIN_VALUE;

        // Check target direction and adjacent directions
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

            // Strongly prefer directions that get us closer to target
            int distBefore = myLoc.distanceSquaredTo(target);
            int distAfter = newLoc.distanceSquaredTo(target);
            if (distAfter < distBefore) {
                score += 20;  // Big bonus for getting closer
            }

            // Penalty for the non-direct directions
            if (dir != targetDir) {
                score -= 2;
            }

            if (score > bestScore) {
                bestScore = score;
                bestDir = dir;
            }
        }

        // Only return if we found something reasonable
        return (bestScore > -50) ? bestDir : null;
    }

    /**
     * Check if point is approximately on line between start and end.
     */
    private static boolean isOnLine(MapLocation point, MapLocation start, MapLocation end) {
        // Simplified check: within 2 tiles of the direct path
        int dx = end.x - start.x;
        int dy = end.y - start.y;
        int px = point.x - start.x;
        int py = point.y - start.y;

        // Cross product magnitude (area of parallelogram)
        int cross = Math.abs(dx * py - dy * px);
        int lineLength = (int) Math.sqrt(dx * dx + dy * dy);

        if (lineLength == 0) return point.equals(start);

        // Distance from point to line
        return cross / lineLength <= 2;
    }

    /**
     * Move away from a location (flee behavior).
     */
    public static boolean moveAway(RobotController rc, MapLocation threat) throws GameActionException {
        if (threat == null) return false;
        if (!rc.isMovementReady()) return false;

        Direction awayDir = rc.getLocation().directionTo(threat).opposite();

        // Try away direction and adjacent
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
     * Reset navigation state (call when switching targets).
     */
    public static void reset() {
        currentTarget = null;
        isTracing = false;
        tracingDir = null;
        lineStart = null;
        stuckCounter = 0;
    }
}
