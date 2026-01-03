package mybot.units;

import battlecode.common.*;
import mybot.core.Navigator;
import mybot.core.MessageProtocol;
import mybot.Utils;

/**
 * Base class for all mobile units (Soldier, Splasher, Mopper).
 * Provides common functionality and state management.
 */
public abstract class UnitBase {

    protected final RobotController rc;
    protected final Navigator nav;
    protected MapLocation spawnLocation;
    protected int lastPaintLevel;

    // Common thresholds (can be overridden by subclasses)
    protected int healthCritical = 15;
    protected int paintLow = 50;

    /**
     * Create a new unit.
     */
    public UnitBase(RobotController rc) {
        this.rc = rc;
        this.nav = new Navigator(rc);
        this.spawnLocation = rc.getLocation();
        this.lastPaintLevel = rc.getPaint();
    }

    /**
     * Main turn execution - implement in subclasses.
     */
    public abstract void runTurn() throws GameActionException;

    // ==================== COMMON SURVIVAL CHECKS ====================

    /**
     * Check if health is critically low.
     */
    protected boolean isHealthCritical() {
        return rc.getHealth() <= healthCritical;
    }

    /**
     * Check if paint is low.
     */
    protected boolean isPaintLow() {
        return rc.getPaint() < paintLow;
    }

    /**
     * Check if we're on dangerous tile (enemy or neutral paint).
     */
    protected boolean isOnDangerousTile() throws GameActionException {
        MapInfo info = rc.senseMapInfo(rc.getLocation());
        PaintType paint = info.getPaint();
        return paint != PaintType.ALLY_PRIMARY && paint != PaintType.ALLY_SECONDARY;
    }

    // ==================== COMMON TOWER FINDING ====================

    /**
     * Find the nearest paint tower for resupply.
     */
    protected RobotInfo findNearestPaintTower() throws GameActionException {
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        RobotInfo best = null;
        int bestDist = Integer.MAX_VALUE;

        for (RobotInfo ally : allies) {
            if (Utils.isPaintTower(ally.getType())) {
                int dist = rc.getLocation().distanceSquaredTo(ally.getLocation());
                if (dist < bestDist) {
                    bestDist = dist;
                    best = ally;
                }
            }
        }

        return best;
    }

    /**
     * Find any nearby tower (for emergency).
     */
    protected RobotInfo findNearestTower() throws GameActionException {
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        RobotInfo best = null;
        int bestDist = Integer.MAX_VALUE;

        for (RobotInfo ally : allies) {
            if (ally.getType().isTowerType()) {
                int dist = rc.getLocation().distanceSquaredTo(ally.getLocation());
                if (dist < bestDist) {
                    bestDist = dist;
                    best = ally;
                }
            }
        }

        return best;
    }

    // ==================== COMMON RETREAT BEHAVIOR ====================

    /**
     * Retreat to nearest paint tower or spawn location.
     * Returns true if we moved or are already at safety.
     */
    protected boolean retreatForPaint() throws GameActionException {
        // First, try to take paint from nearby tower
        RobotInfo paintTower = findNearestPaintTower();
        if (paintTower != null) {
            MapLocation towerLoc = paintTower.getLocation();
            int dist = rc.getLocation().distanceSquaredTo(towerLoc);

            // If adjacent to tower, try to take paint
            if (dist <= 2) {
                if (rc.canTransferPaint(towerLoc, -50)) {  // Take 50 paint
                    rc.transferPaint(towerLoc, -50);
                    return true;
                }
            }

            // Move toward tower
            return nav.moveTo(towerLoc);
        }

        // No paint tower visible - head to spawn location
        return nav.moveTo(spawnLocation);
    }

    /**
     * Retreat from combat (low health).
     * Tries to move away from enemies toward allies.
     */
    protected boolean retreatFromCombat() throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());

        if (enemies.length > 0) {
            // Find closest enemy
            RobotInfo closest = Utils.closestRobot(rc.getLocation(), enemies);
            if (closest != null) {
                // Move away from closest enemy
                return nav.moveAway(closest.getLocation());
            }
        }

        // No enemies - retreat to spawn
        return nav.moveTo(spawnLocation);
    }

    // ==================== COMMON PAINT BEHAVIOR ====================

    /**
     * Paint current tile if it's not ally paint.
     */
    protected boolean paintCurrentTile() throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        MapInfo info = rc.senseMapInfo(myLoc);
        PaintType current = info.getPaint();

        if (current != PaintType.ALLY_PRIMARY && current != PaintType.ALLY_SECONDARY) {
            if (rc.canAttack(myLoc)) {
                rc.attack(myLoc);
                return true;
            }
        }
        return false;
    }

    // ==================== COMMON ENEMY DETECTION ====================

    /**
     * Check for nearby enemies.
     */
    protected RobotInfo[] getNearbyEnemies() throws GameActionException {
        return rc.senseNearbyRobots(-1, rc.getTeam().opponent());
    }

    /**
     * Check for nearby enemy towers.
     */
    protected RobotInfo[] getNearbyEnemyTowers() throws GameActionException {
        RobotInfo[] enemies = getNearbyEnemies();
        java.util.ArrayList<RobotInfo> towers = new java.util.ArrayList<>();
        for (RobotInfo enemy : enemies) {
            if (enemy.getType().isTowerType()) {
                towers.add(enemy);
            }
        }
        return towers.toArray(new RobotInfo[0]);
    }

    /**
     * Find the highest threat enemy.
     */
    protected RobotInfo findHighestThreat() throws GameActionException {
        RobotInfo[] enemies = getNearbyEnemies();
        if (enemies.length == 0) return null;
        return Utils.findHighestThreat(rc, enemies);
    }

    // ==================== MESSAGE PROCESSING ====================

    /**
     * Process incoming messages. Override in subclasses for specific handling.
     */
    protected void processMessages() throws GameActionException {
        // Check for coordinated attack target
        MapLocation attackTarget = MessageProtocol.getLocationFromMessage(rc,
            MessageProtocol.MessageType.TOWER_TARGET);
        if (attackTarget != null) {
            // Subclasses can handle this
        }

        // Check for paint tower critical
        if (MessageProtocol.hasMessageOfType(rc, MessageProtocol.MessageType.PAINT_TOWER_CRITICAL)) {
            // Subclasses can handle this
        }
    }

    // ==================== UTILITY ====================

    /**
     * Get current round number.
     */
    protected int getRound() {
        return rc.getRoundNum();
    }

    /**
     * Get robot ID.
     */
    protected int getId() {
        return rc.getID();
    }

    /**
     * Get current location.
     */
    protected MapLocation getLocation() {
        return rc.getLocation();
    }

    /**
     * Get navigator for advanced pathfinding.
     */
    protected Navigator getNavigator() {
        return nav;
    }
}
