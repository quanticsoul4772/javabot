package mybot.strategy;

import battlecode.common.*;
import mybot.core.MessageProtocol;
import mybot.Utils;

/**
 * Coordinates focus fire attacks on priority targets.
 * Towers broadcast targets, units converge for coordinated attacks.
 *
 * Priority order:
 * 1. Paint Towers (killing these cripples enemy paint production)
 * 2. Defense Towers (high damage threats)
 * 3. Money Towers (economic damage)
 * 4. Enemy units (splashers > soldiers > moppers)
 */
public class FocusFireCoordinator {

    // Priority constants
    public static final int PRIORITY_PAINT_TOWER = 100;
    public static final int PRIORITY_DEFENSE_TOWER = 80;
    public static final int PRIORITY_MONEY_TOWER = 60;
    public static final int PRIORITY_ENEMY_SPLASHER = 50;
    public static final int PRIORITY_ENEMY_SOLDIER = 40;
    public static final int PRIORITY_ENEMY_MOPPER = 30;

    // Minimum allies needed before coordinated attack
    public static final int MIN_ALLIES_FOR_TOWER_ATTACK = 3;

    /**
     * For towers: Identify and broadcast the highest priority target.
     * Call this from Tower.java each turn.
     */
    public static void coordinateAttack(RobotController rc) throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if (enemies.length == 0) return;

        // Find highest priority target
        RobotInfo bestTarget = null;
        int bestPriority = 0;

        for (RobotInfo enemy : enemies) {
            int priority = getTargetPriority(enemy);
            if (priority > bestPriority) {
                bestPriority = priority;
                bestTarget = enemy;
            }
        }

        if (bestTarget != null && bestPriority >= PRIORITY_ENEMY_MOPPER) {
            // Broadcast target to all nearby allies
            MessageProtocol.broadcastToAllies(rc, MessageProtocol.MessageType.TOWER_TARGET,
                bestTarget.getLocation(), bestPriority);
        }
    }

    /**
     * For units: Get the coordinated attack target from messages.
     * Returns null if no coordinated target or not enough allies.
     */
    public static MapLocation getCoordinatedTarget(RobotController rc) throws GameActionException {
        MapLocation target = MessageProtocol.getLocationFromMessage(rc,
            MessageProtocol.MessageType.TOWER_TARGET);

        if (target == null) return null;

        // Check if target is still valid (in vision)
        if (!rc.canSenseLocation(target)) {
            return target; // Trust the message, move toward it
        }

        // Verify target still exists
        RobotInfo targetRobot = rc.senseRobotAtLocation(target);
        if (targetRobot == null || targetRobot.getTeam() == rc.getTeam()) {
            return null; // Target destroyed or invalid
        }

        return target;
    }

    /**
     * Check if we have enough allies nearby to attack a tower.
     */
    public static boolean hasEnoughAlliesForTowerAttack(RobotController rc, MapLocation towerLoc)
            throws GameActionException {
        RobotInfo[] allies = rc.senseNearbyRobots(towerLoc, 20, rc.getTeam());
        int combatAllies = 0;

        for (RobotInfo ally : allies) {
            if (!ally.getType().isTowerType()) {
                combatAllies++;
            }
        }

        return combatAllies >= MIN_ALLIES_FOR_TOWER_ATTACK;
    }

    /**
     * Get priority score for a target.
     */
    public static int getTargetPriority(RobotInfo enemy) {
        UnitType type = enemy.getType();

        // Tower priorities
        if (Utils.isPaintTower(type)) return PRIORITY_PAINT_TOWER;
        if (Utils.isDefenseTower(type)) return PRIORITY_DEFENSE_TOWER;
        if (Utils.isMoneyTower(type)) return PRIORITY_MONEY_TOWER;

        // Unit priorities
        switch (type) {
            case SPLASHER: return PRIORITY_ENEMY_SPLASHER;
            case SOLDIER: return PRIORITY_ENEMY_SOLDIER;
            case MOPPER: return PRIORITY_ENEMY_MOPPER;
            default: return 0;
        }
    }

    /**
     * Score a potential attack considering focus fire benefits.
     * Higher score = better target.
     */
    public static int scoreAttackTarget(RobotController rc, RobotInfo enemy) throws GameActionException {
        int score = getTargetPriority(enemy);

        // Bonus for low health targets (finish them off)
        int healthPct = enemy.getHealth() * 100 / enemy.getType().health;
        if (healthPct < 30) score += 30;
        else if (healthPct < 50) score += 15;

        // Bonus for targets other allies are attacking (focus fire)
        MapLocation coordTarget = getCoordinatedTarget(rc);
        if (coordTarget != null && coordTarget.equals(enemy.getLocation())) {
            score += 40;
        }

        // Penalty for distance
        int dist = rc.getLocation().distanceSquaredTo(enemy.getLocation());
        score -= dist / 5;

        return score;
    }

    /**
     * Find the best attack target considering focus fire coordination.
     */
    public static RobotInfo findBestTarget(RobotController rc) throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if (enemies.length == 0) return null;

        RobotInfo best = null;
        int bestScore = Integer.MIN_VALUE;

        for (RobotInfo enemy : enemies) {
            int score = scoreAttackTarget(rc, enemy);
            if (score > bestScore) {
                bestScore = score;
                best = enemy;
            }
        }

        return best;
    }

    /**
     * Check if we should retreat from this combat situation.
     * Returns true if outnumbered and no tower support.
     */
    public static boolean shouldRetreat(RobotController rc) throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());

        int enemyThreat = 0;
        int allyStrength = 0;

        for (RobotInfo enemy : enemies) {
            if (enemy.getType().isTowerType()) {
                enemyThreat += 3; // Towers are dangerous
            } else {
                enemyThreat += 1;
            }
        }

        for (RobotInfo ally : allies) {
            if (ally.getType().isTowerType()) {
                allyStrength += 3; // Our towers help
            } else {
                allyStrength += 1;
            }
        }

        // Retreat if significantly outnumbered
        return enemyThreat > allyStrength * 1.5 && rc.getHealth() < rc.getType().health * 0.5;
    }
}
