package spaark3;

import battlecode.common.*;

/**
 * Micro combat scoring system.
 * Evaluates all directions for optimal movement.
 */
public class Micro {

    // Scoring constants
    private static final int ENEMY_PAINT_PENALTY = -25;
    private static final int ALLY_PAINT_BONUS = 10;
    private static final int THREAT_PENALTY = -50;
    private static final int TARGET_BONUS = 20;
    private static final int NEUTRAL_PAINT_BONUS = 5;
    private static final int ENEMY_MOPPER_PENALTY = -20;  // SPAARK: Avoid enemy moppers
    private static final int TOWER_THREAT_PENALTY = -100;  // SPAARK: Avoid tower range

    // Threat range (squared distance)
    private static final int THREAT_RANGE = 9;
    private static final int MOPPER_RANGE = 8;  // Mopper attack range

    /**
     * Score all 9 directions (including CENTER) for movement.
     * Higher score = better direction.
     */
    public static int[] scoreAllDirections(MapLocation target) throws GameActionException {
        int[] scores = new int[9];

        MapLocation myLoc = G.me;
        Direction targetDir = target != null ? myLoc.directionTo(target) : Direction.CENTER;

        // Score each direction
        for (int d = 9; --d >= 0;) {
            Direction dir = G.ALL_DIRECTIONS[d];
            MapLocation newLoc = myLoc.add(dir);

            // Check if we can move there (CENTER always valid)
            if (d != 8 && !G.rc.canMove(dir)) {
                scores[d] = Integer.MIN_VALUE;
                continue;
            }

            // For CENTER, use current location
            if (d == 8) {
                newLoc = myLoc;
            }

            int score = 0;

            // Paint scoring
            if (G.rc.canSenseLocation(newLoc)) {
                MapInfo info = G.rc.senseMapInfo(newLoc);
                PaintType paint = info.getPaint();

                if (paint.isEnemy()) {
                    score += ENEMY_PAINT_PENALTY;
                } else if (paint.isAlly()) {
                    score += ALLY_PAINT_BONUS;
                } else {
                    score += NEUTRAL_PAINT_BONUS;
                }
            }

            // Threat scoring - penalize positions near enemies
            RobotInfo[] enemies = G.getEnemies();
            for (int e = enemies.length; --e >= 0;) {
                RobotInfo enemy = enemies[e];
                int distSq = newLoc.distanceSquaredTo(enemy.location);
                if (distSq <= THREAT_RANGE) {
                    score += THREAT_PENALTY;
                }
                // SPAARK: Extra penalty for being near enemy moppers
                if (enemy.type == UnitType.MOPPER && distSq <= MOPPER_RANGE) {
                    score += ENEMY_MOPPER_PENALTY;
                }
                // SPAARK: Huge penalty for being in enemy tower range
                if (enemy.type.isTowerType()) {
                    int towerRange = enemy.type.actionRadiusSquared;
                    if (distSq <= towerRange) {
                        score += TOWER_THREAT_PENALTY;
                    }
                }
            }

            // Target direction bonus
            if (dir == targetDir) {
                score += TARGET_BONUS;
            } else if (dir == targetDir.rotateLeft() || dir == targetDir.rotateRight()) {
                score += TARGET_BONUS >> 1;  // Half bonus for adjacent
            }

            // Boids influence (phase-dependent)
            if (Phase.boidsInfluence() > 0.1) {
                score += boidsScore(dir) * 5;
            }

            scores[d] = score;
        }

        return scores;
    }

    /**
     * Simple Boids score for a direction.
     * Positive if direction aligns with nearby allies movement.
     */
    private static int boidsScore(Direction dir) throws GameActionException {
        RobotInfo[] allies = G.getAllies();
        if (allies.length == 0 || dir == Direction.CENTER) return 0;

        int score = 0;
        MapLocation myLoc = G.me;

        for (int i = allies.length; --i >= 0;) {
            RobotInfo ally = allies[i];
            if (ally.type.isTowerType()) continue;

            MapLocation allyLoc = ally.location;
            int dist = myLoc.distanceSquaredTo(allyLoc);

            // Cohesion: bonus for moving toward group
            if (dist > 16) {
                Direction toAlly = myLoc.directionTo(allyLoc);
                if (dir == toAlly) score += 2;
            }
            // Separation: bonus for moving away from close allies
            else if (dist < 4) {
                Direction awayFromAlly = allyLoc.directionTo(myLoc);
                if (dir == awayFromAlly) score += 1;
            }
        }

        return score;
    }

    /**
     * Check if robot should engage in combat.
     * Returns true if healthy enough to fight.
     */
    public static boolean shouldEngage() {
        // Need at least 1/3 health
        int maxPaint = G.type.paintCapacity;
        if (G.paint < maxPaint / 3) return false;

        // Need minimum paint to attack
        if (G.paint < 50) return false;

        return true;
    }

    /**
     * Find best enemy target to attack.
     * Prioritizes towers, then low HP units.
     */
    public static RobotInfo findBestTarget() throws GameActionException {
        RobotInfo[] enemies = G.getEnemies();
        if (enemies.length == 0) return null;

        RobotInfo best = null;
        int bestScore = Integer.MIN_VALUE;

        for (int i = enemies.length; --i >= 0;) {
            RobotInfo enemy = enemies[i];
            int score = 0;

            // Must be in attack range
            int dist = G.me.distanceSquaredTo(enemy.location);
            if (dist > G.type.actionRadiusSquared) continue;

            // Tower priority
            if (enemy.type.isTowerType()) {
                score += 1000;
            }

            // Low HP bonus (inverse of paint remaining)
            score += 500 - enemy.paintAmount;

            // Close range bonus
            score += 100 - dist;

            if (score > bestScore) {
                bestScore = score;
                best = enemy;
            }
        }

        return best;
    }

    /**
     * Find nearest enemy for targeting.
     */
    public static RobotInfo findNearestEnemy() throws GameActionException {
        RobotInfo[] enemies = G.getEnemies();
        if (enemies.length == 0) return null;

        RobotInfo nearest = null;
        int nearestDist = Integer.MAX_VALUE;

        for (int i = enemies.length; --i >= 0;) {
            int dist = G.me.distanceSquaredTo(enemies[i].location);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = enemies[i];
            }
        }

        return nearest;
    }

    /**
     * Check if there are threats nearby.
     */
    public static boolean hasNearbyThreats() throws GameActionException {
        RobotInfo[] enemies = G.getEnemies();
        for (int i = enemies.length; --i >= 0;) {
            if (G.me.distanceSquaredTo(enemies[i].location) <= THREAT_RANGE) {
                return true;
            }
        }
        return false;
    }
}
