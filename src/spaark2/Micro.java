package spaark2;

import battlecode.common.*;

/**
 * Micro/Kiting system for combat movement.
 * Scores all 9 movement directions based on paint, threats, and target direction.
 */
public class Micro {

    // Direction scores (indices 0-7 = directions, 8 = CENTER)
    public static int[] scores = new int[9];

    // Scoring constants
    public static final int ENEMY_PAINT_PENALTY = 25;
    public static final int NEUTRAL_PAINT_PENALTY = 5;
    public static final int ALLY_PAINT_BONUS = 10;
    public static final int ENEMY_THREAT_PENALTY = 50;
    public static final int TARGET_DIRECTION_BONUS = 20;
    public static final int ADJACENT_BONUS = 15;
    public static final int DEATH_PENALTY = 1000;

    /**
     * Calculate movement scores for all 9 directions.
     * Higher score = better direction to move.
     * Returns the scores array for inspection.
     * Optimized for low bytecode usage.
     */
    public static int[] score(RobotController rc, Direction targetDir) throws GameActionException {
        // Reset scores
        for (int i = 9; --i >= 0;) scores[i] = 0;

        MapLocation myLoc = rc.getLocation();

        // 1. Target direction bonus (simplified)
        if (targetDir != null && targetDir != Direction.CENTER) {
            int targetIdx = targetDir.ordinal();
            if (targetIdx < 8) {
                scores[targetIdx] += TARGET_DIRECTION_BONUS;
            }
        }

        // 2. Evaluate each direction (simplified - only check passability and paint)
        Direction[] allDirs = Globals.ALL_DIRECTIONS;
        for (int i = 8; --i >= 0;) {  // Skip CENTER (index 8)
            Direction d = allDirs[i];

            if (!rc.canMove(d)) {
                scores[i] = Integer.MIN_VALUE;
                continue;
            }

            MapLocation dest = myLoc.add(d);
            MapInfo info = rc.senseMapInfo(dest);
            PaintType paint = info.getPaint();
            if (paint.isEnemy()) {
                scores[i] -= ENEMY_PAINT_PENALTY;
            } else if (paint.isAlly()) {
                scores[i] += ALLY_PAINT_BONUS;
            }
        }

        // 3. Simple enemy avoidance (only check nearest 2 enemies)
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        int checkLimit = Math.min(enemies.length, 2);
        for (int e = checkLimit; --e >= 0;) {
            MapLocation enemyLoc = enemies[e].getLocation();
            int attackRadius = 9;  // Use fixed radius to save bytecode

            for (int i = 8; --i >= 0;) {
                if (scores[i] == Integer.MIN_VALUE) continue;
                if (myLoc.add(allDirs[i]).isWithinDistanceSquared(enemyLoc, attackRadius)) {
                    scores[i] -= ENEMY_THREAT_PENALTY;
                }
            }
        }

        return scores;
    }

    /**
     * Get best direction based on current scores.
     */
    public static Direction getBestDirection() {
        int best = 8;  // Default to CENTER
        int bestScore = scores[8];

        for (int i = 8; --i >= 0;) {
            if (scores[i] > bestScore) {
                bestScore = scores[i];
                best = i;
            }
        }

        return Globals.ALL_DIRECTIONS[best];
    }

    /**
     * Get estimated attack damage for a unit type.
     */
    private static int getAttackDamage(UnitType type) {
        switch (type) {
            case SOLDIER:
                return 10;
            case SPLASHER:
                return 5;
            case MOPPER:
                return 5;
            case LEVEL_ONE_PAINT_TOWER:
            case LEVEL_ONE_MONEY_TOWER:
            case LEVEL_ONE_DEFENSE_TOWER:
                return 10;
            case LEVEL_TWO_PAINT_TOWER:
            case LEVEL_TWO_MONEY_TOWER:
            case LEVEL_TWO_DEFENSE_TOWER:
                return 15;
            case LEVEL_THREE_PAINT_TOWER:
            case LEVEL_THREE_MONEY_TOWER:
            case LEVEL_THREE_DEFENSE_TOWER:
                return 25;
            default:
                return 10;
        }
    }

    /**
     * Check if there are any enemy threats nearby.
     */
    public static boolean hasThreats(RobotController rc) throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        return enemies.length > 0;
    }

    /**
     * Check if we should kite (stay at range and attack).
     * Returns true if we have high enough health to engage.
     */
    public static boolean shouldEngage(RobotController rc) throws GameActionException {
        int health = rc.getHealth();
        int maxHealth = rc.getType().health;
        int paint = rc.getPaint();

        // Engage if we have enough health and paint
        return health > maxHealth / 3 && paint > 50;
    }
}
