package mybot;

import battlecode.common.*;

/**
 * Centralized scoring system with pre-computed weights.
 *
 * All decision-making weights in one place for easy tuning.
 * The cleverness is in the weights, not the architecture.
 */
public class Scoring {

    // ==================== TILE SCORING ====================
    public static final int WEIGHT_ALLY_PAINT = 10;
    public static final int WEIGHT_ENEMY_PAINT = -5;
    public static final int WEIGHT_NEUTRAL = 0;

    // ==================== MOVEMENT SCORING ====================
    public static final int WEIGHT_CLOSER_TO_TARGET = 2;
    public static final int WEIGHT_FARTHER_FROM_TARGET = -1;
    public static final int WEIGHT_DIRECT_DIRECTION = 3;
    public static final int WEIGHT_GETTING_CLOSER_BONUS = 20;
    public static final int WEIGHT_NON_DIRECT_PENALTY = -2;

    // ==================== THREAT SCORING ====================
    public static final int WEIGHT_ENEMY_SOLDIER = -15;
    public static final int WEIGHT_ENEMY_SPLASHER = -12;
    public static final int WEIGHT_ENEMY_MOPPER = -8;
    public static final int WEIGHT_ENEMY_TOWER = -20;

    // ==================== OPPORTUNITY SCORING ====================
    public static final int WEIGHT_WEAK_ENEMY = 25;
    public static final int WEIGHT_RUIN_NEARBY = 20;
    public static final int WEIGHT_ALLY_TOWER = 15;

    // ==================== SPLASH SCORING ====================
    public static final int WEIGHT_SPLASH_ENEMY = 2;
    public static final int WEIGHT_SPLASH_NEUTRAL = 1;
    public static final int WEIGHT_SPLASH_ALLY = -3;

    // ==================== HEALTH/RESOURCE FACTORS ====================
    public static final int WEIGHT_LOW_HEALTH = -30;
    public static final int WEIGHT_LOW_PAINT = -20;
    public static final int WEIGHT_FULL_RESOURCES = 5;

    // ==================== THRESHOLDS ====================
    public static final int THRESHOLD_GOOD_TILE = 5;
    public static final int THRESHOLD_BAD_TILE = -10;
    public static final int THRESHOLD_SPLASH_WORTH = 3;
    public static final int THRESHOLD_SPLASH_HIGH = 5;
    public static final int THRESHOLD_HIGH_THREAT = -25;

    // ==================== SCORING METHODS ====================

    /**
     * Score a tile based on paint type.
     */
    public static int scoreTile(RobotController rc, MapLocation loc) throws GameActionException {
        if (!rc.canSenseLocation(loc)) return 0;

        MapInfo info = rc.senseMapInfo(loc);
        if (!info.isPassable()) return THRESHOLD_BAD_TILE;

        int score = 0;
        PaintType paint = info.getPaint();

        if (paint.isAlly()) {
            score += WEIGHT_ALLY_PAINT;
        } else if (paint.isEnemy()) {
            score += WEIGHT_ENEMY_PAINT;
        } else {
            score += WEIGHT_NEUTRAL;
        }

        return score;
    }

    /**
     * Score a splash target location (3x3 area).
     */
    public static int scoreSplashTarget(RobotController rc, MapLocation center) throws GameActionException {
        int score = 0;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                MapLocation loc = center.translate(dx, dy);
                if (!rc.canSenseLocation(loc)) continue;

                MapInfo info = rc.senseMapInfo(loc);
                if (!info.isPassable()) continue;

                PaintType paint = info.getPaint();
                if (paint.isEnemy()) {
                    score += WEIGHT_SPLASH_ENEMY;
                } else if (paint == PaintType.EMPTY) {
                    score += WEIGHT_SPLASH_NEUTRAL;
                } else if (paint.isAlly()) {
                    score += WEIGHT_SPLASH_ALLY;
                }
            }
        }

        return score;
    }

    /**
     * Score an enemy as a target (higher = more priority to attack).
     */
    public static int scoreEnemyTarget(RobotInfo enemy, MapLocation myLoc) {
        int score = 0;

        // Prioritize by threat level
        switch (enemy.getType()) {
            case SOLDIER:
                score += 30;  // High threat
                break;
            case SPLASHER:
                score += 25;  // Area threat
                break;
            case MOPPER:
                score += 15;  // Lower threat
                break;
            default:
                if (enemy.getType().isTowerType()) {
                    score += 10;  // Static target
                }
                break;
        }

        // Bonus for low health targets (easier to kill)
        if (enemy.getHealth() < 40) {
            score += WEIGHT_WEAK_ENEMY;
        }

        // Prefer closer targets
        int dist = myLoc.distanceSquaredTo(enemy.getLocation());
        score -= dist / 5;

        return score;
    }

    /**
     * Assess overall situation (negative = danger, positive = opportunity).
     */
    public static int scoreSituation(RobotController rc) throws GameActionException {
        int score = 0;

        // Health factor
        int health = rc.getHealth();
        int maxHealth = rc.getType().health;
        if (health < maxHealth / 5) {
            score += WEIGHT_LOW_HEALTH;
        } else if (health > maxHealth * 4 / 5) {
            score += WEIGHT_FULL_RESOURCES;
        }

        // Paint factor
        int paint = rc.getPaint();
        int maxPaint = rc.getType().paintCapacity;
        if (paint < maxPaint / 5) {
            score += WEIGHT_LOW_PAINT;
        }

        // Nearby threats
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        for (RobotInfo enemy : enemies) {
            score += getEnemyWeight(enemy.getType());
        }

        // Nearby safety
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo ally : allies) {
            if (ally.getType().isTowerType()) {
                score += WEIGHT_ALLY_TOWER;
            }
        }

        return score;
    }

    /**
     * Get threat weight for an enemy type.
     */
    public static int getEnemyWeight(UnitType type) {
        switch (type) {
            case SOLDIER:
                return WEIGHT_ENEMY_SOLDIER;
            case SPLASHER:
                return WEIGHT_ENEMY_SPLASHER;
            case MOPPER:
                return WEIGHT_ENEMY_MOPPER;
            default:
                return type.isTowerType() ? WEIGHT_ENEMY_TOWER : 0;
        }
    }
}
