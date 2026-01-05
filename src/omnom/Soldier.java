package omnom;

import battlecode.common.*;

/**
 * Soldier behavior with mode system.
 * Source: bot_spec.md Part 14.1
 */
public class Soldier {
    private enum Mode { EXPLORE, ATTACK, RETREAT, BUILD_TOWER, ATTACK_TOWER, BUILD_RESOURCE, EXPAND_RESOURCE }
    private static Mode mode = Mode.EXPLORE;
    private static MapLocation attackTarget = null;
    private static MapLocation retreatTarget = null;
    private static MapLocation buildTarget = null;
    private static MapLocation srpTarget = null;
    private static int buildTimeout = 0;
    private static int srpTimeout = 0;

    // SRP expansion queue (16 locations from spec Part 7.4)
    private static MapLocation[] srpExpansionLocs = new MapLocation[0];
    private static int srpExpansionIndex = 0;

    public static void run() throws GameActionException {
        // FORCE POSITIONING: Every 10 rounds, log spatial distribution
        if (G.round % 10 == 0) {
            RobotInfo[] allies = G.getAllies();
            RobotInfo[] enemies = G.getEnemies();

            // Calculate force concentration (avg distance to allies)
            int avgDistToAllies = 0;
            if (allies.length > 0) {
                int totalDist = 0;
                for (int i = allies.length; --i >= 0;) {
                    totalDist += G.me.distanceSquaredTo(allies[i].location);
                }
                avgDistToAllies = totalDist / allies.length;
            }

            // Find nearest enemy distance
            int nearestEnemyDist = 9999;
            if (enemies.length > 0) {
                for (int i = enemies.length; --i >= 0;) {
                    int dist = G.me.distanceSquaredTo(enemies[i].location);
                    if (dist < nearestEnemyDist) nearestEnemyDist = dist;
                }
            }

            System.out.println("TACTICAL:" + G.round + ":SOLDIER:" + G.id +
                ":pos=" + G.me +
                ":avgDistAllies=" + avgDistToAllies +
                ":nearestEnemy=" + nearestEnemyDist +
                ":allies=" + allies.length +
                ":enemies=" + enemies.length);

        // VISIBILITY: STATE snapshot every 10 rounds
            System.out.println("STATE:" + G.round + ":SOLDIER:" + G.id +
                ":pos=" + G.me +
                ":paint=" + G.paint +
                ":chips=" + G.chips +
                ":mode=" + mode +
                ":allies=" + allies.length +
                ":enemies=" + enemies.length +
                ":towers=" + G.numTowers);

            // ENEMY_COUNT: Track enemy composition
            if (enemies.length > 0) {
                int soldiers = 0, splashers = 0, moppers = 0, towers = 0;
                for (int i = enemies.length; --i >= 0;) {
                    if (enemies[i].type == UnitType.SOLDIER) soldiers++;
                    else if (enemies[i].type == UnitType.SPLASHER) splashers++;
                    else if (enemies[i].type == UnitType.MOPPER) moppers++;
                    else if (enemies[i].type.isTowerType()) towers++;
                }

                System.out.println("ENEMY_COUNT:" + G.round + ":" + G.id +
                    ":total=" + enemies.length +
                    ":soldiers=" + soldiers +
                    ":splashers=" + splashers +
                    ":moppers=" + moppers +
                    ":towers=" + towers);
            }
        }

        // Update mode
        G.profileStart();
        Mode oldMode = mode;
        updateMode();
        G.profileEnd("updateMode");

        // Log mode transitions
        if (oldMode != mode) {
            System.out.println("DECISION:" + G.round + ":SOLDIER:" + G.id +
                ":from=" + oldMode + ":to=" + mode +
                ":paint=" + G.paint);
        }

        // SELF-DESTRUCT: If paint=0 and high chips, die to avoid blocking
        if (G.paint == 0 && G.chips > 5000) {
            RobotInfo[] allies = G.getAllies();
            for (int i = allies.length; --i >= 0;) {
                if (G.me.distanceSquaredTo(allies[i].location) <= 8
                    && allies[i].type.isRobotType()) {
                    System.out.println("SELF_DESTRUCT:" + G.round + ":SOLDIER:" + G.id +
                        ":reason=paint_zero_high_chips:paint=" + G.paint + ":chips=" + G.chips);
                    G.rc.disintegrate();
                    return;
                }
            }
        }

        // Execute mode behavior
        G.profileStart();
        switch (mode) {
            case EXPLORE: explore(); break;
            case ATTACK: attack(); break;
            case RETREAT: retreat(); break;
            case ATTACK_TOWER: attackTower(); break;
            case BUILD_TOWER: buildTower(); break;
            case BUILD_RESOURCE: buildSRP(); break;
            case EXPAND_RESOURCE: expandSRP(); break;
        }
        G.profileEnd("execute_" + mode);
    }

    private static void updateMode() throws GameActionException {
        // TOWER LOSS DETECTION: If we lost a tower, all units defend/rebuild
        if (G.numTowers < G.lastTowerCount && G.numTowers > 0) {
            // Lost a tower - EMERGENCY
            MapLocation ourTower = POI.findNearestAllyTower();
            if (ourTower != null) {
                // Check if enemies near our remaining towers
                RobotInfo[] enemies = G.getEnemies();
                for (int i = enemies.length; --i >= 0;) {
                    if (ourTower.distanceSquaredTo(enemies[i].location) <= 36) {
                        // Enemy threatening tower - DEFEND
                        mode = Mode.ATTACK;
                        attackTarget = enemies[i].location;
                        return;
                    }
                }
            }
        }
        G.lastTowerCount = G.numTowers;

        // RETREAT: SPAARK 3-condition check
        boolean shouldRetreat = G.paint < G.RETREAT_PAINT
                                && G.chips < G.RETREAT_CHIPS
                                && G.getAllies().length < G.RETREAT_ALLY_THRESHOLD;

        if (shouldRetreat) {
            mode = Mode.RETREAT;
            return;
        }

        // Exit retreat if refueled AND at safe distance (don't block tower)
        if (mode == Mode.RETREAT && G.paint > 100) {
            if (retreatTarget != null) {
                int dist = G.me.distanceSquaredTo(retreatTarget);
                // Only leave if not adjacent (prevent blocking)
                if (dist <= 8) {
                    // Move away from tower before switching modes
                    Direction away = retreatTarget.directionTo(G.me);
                    if (away != Direction.CENTER) {
                        MapLocation awayLoc = G.me.add(away).add(away).add(away);
                        if (G.rc.canMove(G.me.directionTo(awayLoc))) {
                            G.rc.move(G.me.directionTo(awayLoc));
                        }
                    }
                }
            }
            mode = Mode.EXPLORE;
            retreatTarget = null;
        }

        // BUILD_RESOURCE: SRP building (after round 50)
        if (mode == Mode.EXPLORE && G.round >= 50 && G.rc.getNumberTowers() < 10) {
            // Check if can build SRP at current location or nearby
            for (int i = 0; i < 9; i++) {
                MapLocation loc = G.me.translate(G.range20X[i], G.range20Y[i]);
                if (G.rc.onTheMap(loc) && G.rc.canCompleteResourcePattern(loc)) {
                    mode = Mode.BUILD_RESOURCE;
                    srpTarget = loc;
                    srpTimeout = 0;
                    return;
                }
            }
        }

        // Exit BUILD_RESOURCE if timeout
        if (mode == Mode.BUILD_RESOURCE) {
            srpTimeout++;
            if (srpTimeout > 50 || srpTarget == null) {
                mode = Mode.EXPLORE;
                srpTarget = null;
                srpTimeout = 0;
            }
        }

        // BUILD_TOWER disabled - focus on rally concentration test

        // COUNTER-STRATEGY: Priority target enemy paint towers (from spec Part 17.3)
        RobotInfo[] enemies = G.getEnemies();

        // Look for enemy paint towers specifically (highest priority)
        for (int i = enemies.length; --i >= 0;) {
            if (enemies[i].type.isTowerType()) {
                // Check if it's a paint tower (highest value target)
                String typeStr = enemies[i].type.toString();
                if (typeStr.contains("PAINT")) {
                    mode = Mode.ATTACK_TOWER;
                    attackTarget = enemies[i].location;
                    return;
                }
            }
        }

        // Attack any enemy tower
        for (int i = enemies.length; --i >= 0;) {
            if (enemies[i].type.isTowerType()) {
                mode = Mode.ATTACK_TOWER;
                attackTarget = enemies[i].location;
                return;
            }
        }

        // Attack nearby enemies
        if (enemies.length > 0 && mode != Mode.RETREAT) {
            for (int i = enemies.length; --i >= 0;) {
                if (G.me.distanceSquaredTo(enemies[i].location) <= 20) {
                    mode = Mode.ATTACK;
                    attackTarget = enemies[i].location;
                    return;
                }
            }
        }

        // Default: EXPLORE
        if (mode != Mode.RETREAT) {
            mode = Mode.EXPLORE;
        }
    }

    private static void explore() throws GameActionException {
        // EXTREME CONCENTRATION: All units converge on [15,15]
        MapLocation rally = new MapLocation(15, 15);

        // Attack enemies in range
        RobotInfo[] enemies = G.getEnemies();
        for (int i = enemies.length; --i >= 0;) {
            if (G.rc.canAttack(enemies[i].location)) {
                G.rc.attack(enemies[i].location);
                break;
            }
        }

        // All move to same rally point
        Nav.moveTo(rally);

        // Paint current tile
        if (G.rc.canAttack(G.me)) {
            G.rc.attack(G.me);
        }
    }

    private static void attack() throws GameActionException {
        // COMBAT LOG: Track where attacks happen
        if (attackTarget != null && G.rc.canAttack(attackTarget)) {
            System.out.println("COMBAT:" + G.round + ":SOLDIER:" + G.id +
                ":from=" + G.me +
                ":target=" + attackTarget +
                ":our_paint=" + G.paint +
                ":allies_nearby=" + G.getAllies().length +
                ":enemies_nearby=" + G.getEnemies().length);
            G.rc.attack(attackTarget);
        }

        // Move toward target with micro
        if (attackTarget != null) {
            Nav.moveToWithMicro(attackTarget);
        }
    }

    private static void retreat() throws GameActionException {
        // Find nearest tower
        if (retreatTarget == null) {
            retreatTarget = POI.findNearestAllyPaintTower();
            if (retreatTarget == null) {
                retreatTarget = POI.findNearestAllyTower();
            }
        }

        if (retreatTarget == null) {
            Nav.moveRandom();
            return;
        }

        int dist = G.me.distanceSquaredTo(retreatTarget);

        // DEBUG: Log retreat positioning
        if (G.round % 10 == 0) {
            System.out.println("RETREAT_POS:" + G.round + ":SOLDIER:" + G.id +
                ":myPos=" + G.me +
                ":tower=" + retreatTarget +
                ":dist=" + dist +
                ":paint=" + G.paint);
        }

        // RETREAT QUEUE: Check how many units at tower (from spec Part 14.14)
        if (dist <= 64) {  // Within retreat area
            int queueCount = 0;
            int[][] offsets = {{2,2}, {2,-2}, {-2,2}, {-2,-2}, {2,0}, {0,2}, {-2,0}, {0,-2}};

            for (int i = 0; i < offsets.length; i++) {
                MapLocation waitPos = new MapLocation(
                    retreatTarget.x + offsets[i][0],
                    retreatTarget.y + offsets[i][1]
                );

                if (G.rc.canSenseLocation(waitPos) && G.rc.canSenseRobotAtLocation(waitPos)) {
                    RobotInfo robot = G.rc.senseRobotAtLocation(waitPos);
                    if (robot.type.isRobotType() && robot.paintAmount < G.paint) {
                        // Someone with lower paint (higher priority) is waiting
                        queueCount++;
                    }
                }
            }

            // MAX_RETREAT_ROBOTS = 4 (from spec Part 7.2)
            if (queueCount >= 4) {
                // Queue full - find another tower
                System.out.println("RETREAT_OVERFLOW:" + G.round + ":" + G.id +
                    ":queue=" + queueCount + ":finding_other_tower");
                retreatTarget = null;  // Will find different tower next turn
                Nav.moveRandom();
                return;
            }
        }

        // CRITICAL: Maintain distance 16-64 (4-8 tiles) to prevent spawn blocking

        // Too close (dist < 16 = less than 4 tiles): Move AWAY
        if (dist < 16) {
            Direction away = retreatTarget.directionTo(G.me);
            if (away != Direction.CENTER) {
                MapLocation awayTarget = G.me.add(away).add(away).add(away);  // Move 3 tiles away
                Nav.moveTo(awayTarget);
            } else {
                Nav.moveRandom();
            }
            return;
        }

        // At waiting distance (16-64 = 4-8 tiles): STAY
        if (dist >= 16 && dist <= 64) {
            // Perfect distance - wait here for refuel, don't block tower
            return;
        }

        // Too far (dist > 64): Move TOWARD (will stop at 16)
        if (dist > 64) {
            Nav.moveTo(retreatTarget);
        }
    }

    private static void attackTower() throws GameActionException {
        // Attack tower
        if (attackTarget != null && G.rc.canAttack(attackTarget)) {
            G.rc.attack(attackTarget);
        }

        // Move toward tower
        if (attackTarget != null) {
            Nav.moveTo(attackTarget);
        }
    }

    private static void buildTower() throws GameActionException {
        if (buildTarget == null) {
            mode = Mode.EXPLORE;
            return;
        }

        // Check if tower already built
        if (G.rc.canSenseLocation(buildTarget)) {
            RobotInfo r = G.rc.senseRobotAtLocation(buildTarget);
            if (r != null && r.type.isTowerType()) {
                // Tower complete
                System.out.println("TOWER_COMPLETE:" + G.round + ":SOLDIER:" + G.id +
                    ":location=" + buildTarget +
                    ":buildTime=" + buildTimeout);
                mode = Mode.EXPLORE;
                buildTarget = null;
                buildTimeout = 0;
                return;
            }
        }

        // Move to ruin if far
        if (G.me.distanceSquaredTo(buildTarget) > 2) {
            Nav.moveTo(buildTarget);
            return;
        }

        // Try to complete tower pattern
        if (G.rc.canCompleteTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, buildTarget)) {
            G.rc.completeTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, buildTarget);
            return;
        }

        // Paint tiles for tower pattern
        // Simple: paint around ruin
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                MapLocation loc = buildTarget.translate(dx, dy);
                if (G.rc.canAttack(loc)) {
                    G.rc.attack(loc);
                    return;
                }
            }
        }
    }

    private static void buildSRP() throws GameActionException {
        if (srpTarget == null) {
            mode = Mode.EXPLORE;
            return;
        }

        // Check if SRP complete
        if (!G.rc.canCompleteResourcePattern(srpTarget)) {
            // Already complete or blocked
            System.out.println("SRP_COMPLETE:" + G.round + ":SOLDIER:" + G.id +
                ":location=" + srpTarget +
                ":buildTime=" + srpTimeout);
            mode = Mode.EXPLORE;
            srpTarget = null;
            srpTimeout = 0;
            return;
        }

        // Complete SRP pattern
        G.rc.completeResourcePattern(srpTarget);

        // Mark center with ALLY_SECONDARY
        if (G.rc.canMark(srpTarget)) {
            G.rc.mark(srpTarget, true);  // true = ALLY_SECONDARY
        }

        System.out.println("SRP_COMPLETE:" + G.round + ":SOLDIER:" + G.id +
            ":location=" + srpTarget +
            ":buildTime=" + srpTimeout);

        // Queue 16 expansion locations (from spec Part 7.4)
        MapLocation center = srpTarget;
        srpExpansionLocs = new MapLocation[] {
            center.translate(4, 4), center.translate(4, 0), center.translate(4, -4), center.translate(0, -4),
            center.translate(-4, -4), center.translate(-4, 0), center.translate(-4, 4), center.translate(0, 4),
            center.translate(3, 4), center.translate(4, 3), center.translate(4, -3), center.translate(3, -4),
            center.translate(-3, -4), center.translate(-4, -3), center.translate(-4, 3), center.translate(-3, 4)
        };
        srpExpansionIndex = 0;

        mode = Mode.EXPAND_RESOURCE;
        srpTarget = null;
        srpTimeout = 0;
    }

    private static void expandSRP() throws GameActionException {
        // Check all 16 expansion locations
        if (srpExpansionIndex >= srpExpansionLocs.length) {
            // Done with all expansions
            mode = Mode.EXPLORE;
            srpExpansionLocs = new MapLocation[0];
            srpExpansionIndex = 0;
            return;
        }

        MapLocation target = srpExpansionLocs[srpExpansionIndex];

        // Move to target if far
        if (G.me.distanceSquaredTo(target) > 20) {
            Nav.moveTo(target);
            return;
        }

        // Check if can build SRP here
        if (G.rc.canCompleteResourcePattern(target)) {
            G.rc.completeResourcePattern(target);
            if (G.rc.canMark(target)) {
                G.rc.mark(target, true);  // ALLY_SECONDARY marker
            }
            System.out.println("SRP_EXPAND:" + G.round + ":SOLDIER:" + G.id +
                ":location=" + target + ":index=" + srpExpansionIndex);
        }

        // Move to next expansion location
        srpExpansionIndex++;
    }
}
