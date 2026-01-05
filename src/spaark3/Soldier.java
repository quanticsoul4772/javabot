package spaark3;

import battlecode.common.*;

public class Soldier {

    private enum Mode { EXPLORE, BUILD_TOWER, RETREAT, ATTACK, ATTACK_TOWER }
    private static Mode mode = Mode.EXPLORE;
    private static MapLocation buildTarget = null;
    private static int buildTimeout = 0;
    private static MapLocation retreatTarget = null;
    private static MapLocation towerTarget = null;
    private static int towerAttackTime = 0;
    private static int attacksOnTower = 0;  // Track attacks for double-hit micro

    // Cache our paint tower location (doesn't change)
    private static MapLocation cachedOurTower = null;
    private static boolean ourTowerCached = false;

    // Paint drain tracking
    private static int lastPaint = -1;

    public static void run() throws GameActionException {
        // Initialize paint tracking
        if (lastPaint == -1) {
            lastPaint = G.paint;
        }
        // STATE SNAPSHOT: Every 10 rounds
        if (G.round % 10 == 0) {
            System.out.println("STATE:" + G.round + ":SOLDIER:" + G.id +
                ":pos=" + G.me +
                ":paint=" + G.paint +
                ":chips=" + G.chips +
                ":mode=" + mode +
                ":target=" + (mode == Mode.EXPLORE ? "center" :
                             mode == Mode.BUILD_TOWER ? buildTarget :
                             mode == Mode.RETREAT ? retreatTarget :
                             mode == Mode.ATTACK_TOWER ? towerTarget : "none") +
                ":allies=" + G.getAllies().length +
                ":enemies=" + G.getEnemies().length +
                ":towers=" + G.numTowers);

            // ENEMY TRACKING: Log enemy positions and count by type
            RobotInfo[] enemies = G.getEnemies();
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

                // Log first 3 enemies with details
                StringBuilder enemyLog = new StringBuilder();
                enemyLog.append("ENEMIES:" + G.round + ":" + G.id);
                for (int i = Math.min(3, enemies.length); --i >= 0;) {
                    enemyLog.append(":e").append(i).append("=")
                            .append(enemies[i].type).append("@").append(enemies[i].location)
                            .append("(p").append(enemies[i].paintAmount).append(")");
                }
                System.out.println(enemyLog.toString());
            }
        }

        if (G.round < 50) { earlyGameRush(); return; }

        Mode oldMode = mode;
        updateMode();

        // DECISION TRACE: Log mode transitions
        if (oldMode != mode) {
            System.out.println("DECISION:" + G.round + ":SOLDIER:" + G.id +
                ":from=" + oldMode +
                ":to=" + mode +
                ":paint=" + G.paint +
                ":location=" + G.me);
        }

        switch (mode) {
            case EXPLORE: explore(); break;
            case BUILD_TOWER: buildTower(); break;
            case RETREAT: retreat(); break;
            case ATTACK: attack(); break;
            case ATTACK_TOWER: attackTower(); break;
        }
        paintCurrentTile();

        // PAINT DRAIN TRACKING: Sample every 5 rounds
        if (G.round % 5 == 0 && lastPaint > G.paint) {
            int paintLost = lastPaint - G.paint;
            PaintType terrain = G.rc.senseMapInfo(G.me).getPaint();

            System.out.println("DRAIN:" + G.round + ":" + G.id +
                ":terrain=" + (terrain.isAlly() ? "ALLY" : terrain.isEnemy() ? "ENEMY" : "NEUTRAL") +
                ":lost=" + paintLost +
                ":rate=" + (paintLost / 5) +
                ":location=" + G.me +
                ":mode=" + mode);
        }

        // CRITICAL: Low paint warning
        if (G.paint < 50) {
            System.out.println("CRITICAL:" + G.round + ":LOW_PAINT:" + G.id +
                ":paint=" + G.paint +
                ":pos=" + G.me +
                ":mode=" + mode);
        }

        // DEATH LOG: Detect when paint hits 0 or very low
        if (G.paint == 0) {
            System.out.println("DEATH:" + G.round + ":SOLDIER:" + G.id +
                ":paint=" + G.paint +
                ":pos=" + G.me +
                ":mode=" + mode +
                ":last_target=" + (mode == Mode.BUILD_TOWER ? buildTarget :
                                   mode == Mode.RETREAT ? retreatTarget :
                                   mode == Mode.ATTACK_TOWER ? towerTarget : "none"));
        }

        // Update last paint for next turn
        lastPaint = G.paint;
    }

    private static void earlyGameRush() throws GameActionException {
        // RETREAT CHECK: SPAARK's 3 conditions
        boolean shouldRetreat = G.paint < G.RETREAT_PAINT
                                && G.chips < G.RETREAT_CHIPS
                                && G.getAllies().length < G.RETREAT_ALLY_THRESHOLD;

        if (shouldRetreat) {
            if (mode != Mode.RETREAT) {
                System.out.println("DECISION:" + G.round + ":SOLDIER:" + G.id +
                    ":from=" + mode + ":to=RETREAT:reason=spaark_early:paint=" + G.paint);
            }
            mode = Mode.RETREAT;
            retreat();
            return;
        }

        RobotInfo[] enemies = G.getEnemies();

        // Cache our tower once (save bytecode on repeated lookups)
        if (!ourTowerCached) {
            cachedOurTower = POI.findNearestAllyPaintTower();
            ourTowerCached = true;
        }

        // DEFENSIVE CHECK: If enemies near our paint tower, defend it!
        // Skip rounds 1-5 (enemies haven't reached us yet)
        if (G.round > 5 && cachedOurTower != null && enemies.length > 0) {
            for (int i = enemies.length; --i >= 0;) {
                int distToOurTower = enemies[i].location.distanceSquaredTo(cachedOurTower);
                if (distToOurTower <= 36) {  // Enemy within 6 tiles of our tower (optimal)
                    if (G.rc.canAttack(enemies[i].location)) {
                        G.rc.attack(enemies[i].location);
                    }
                    Nav.moveTo(enemies[i].location);
                    return;
                }
            }
        }

        // Attack enemy towers if visible (PRIORITY on small maps)
        for (int i = enemies.length; --i >= 0;) {
            if (enemies[i].type.isTowerType()) {
                towerTarget = enemies[i].location;
                mode = Mode.ATTACK_TOWER;
                attackTower();
                return;
            }
        }

        // Attack any enemies in range while moving
        for (int i = enemies.length; --i >= 0;) {
            if (G.rc.canAttack(enemies[i].location)) {
                G.rc.attack(enemies[i].location);
                break;
            }
        }

        // Navigate toward enemy towers
        MapLocation target = POI.findNearestEnemyPaintTower();
        if (target == null) {
            target = POI.findNearestEnemyTower();
        }
        if (target == null) {
            target = G.mapCenter;
        }
        Nav.moveTo(target);

        // Paint current tile
        paintCurrentTile();
    }

    private static void updateMode() throws GameActionException {
        // RETREAT CHECK: Use SPAARK's 3 conditions (not just paint)
        boolean shouldRetreat = G.paint < G.RETREAT_PAINT
                                && G.chips < G.RETREAT_CHIPS
                                && G.getAllies().length < G.RETREAT_ALLY_THRESHOLD;

        if (shouldRetreat) {
            if (mode != Mode.RETREAT) {
                System.out.println("DECISION:" + G.round + ":SOLDIER:" + G.id +
                    ":from=" + mode + ":to=RETREAT:reason=spaark_conditions" +
                    ":paint=" + G.paint + ":chips=" + G.chips + ":allies=" + G.getAllies().length);
            }
            mode = Mode.RETREAT;
            return;  // Don't check other modes
        }

        // Exit retreat if paint recovered
        if (mode == Mode.RETREAT && G.paint > G.RETREAT_PAINT * 2) {
            mode = Mode.EXPLORE;
            retreatTarget = null;
        }

        // TOWER DEFENSE: Defend our towers if enemies nearby
        MapLocation ourTower = POI.findNearestAllyTower();
        if (ourTower != null) {
            RobotInfo[] enemies = G.getEnemies();
            for (int i = enemies.length; --i >= 0;) {
                int distToOurTower = enemies[i].location.distanceSquaredTo(ourTower);
                if (distToOurTower <= 25) {  // Enemy within 5 tiles of our tower
                    // DEFEND - attack this enemy
                    if (mode != Mode.ATTACK) {
                        System.out.println("DECISION:" + G.round + ":SOLDIER:" + G.id +
                            ":from=" + mode + ":to=ATTACK:reason=defend_tower:enemy=" + enemies[i].type);
                    }
                    mode = Mode.ATTACK;
                    return;
                }
            }
        }

        // Attack enemy towers (but only if paint > threshold)
        RobotInfo[] enemies = G.getEnemies();
        for (int i = enemies.length; --i >= 0;) {
            if (enemies[i].type.isTowerType() && !wouldDieAttackingTower(enemies[i])) {
                towerTarget = enemies[i].location; towerAttackTime = 0; mode = Mode.ATTACK_TOWER; return;
            }
        }
        if (mode == Mode.ATTACK_TOWER) {
            towerAttackTime++;
            if (towerTarget != null && G.rc.canSenseLocation(towerTarget)) {
                RobotInfo r = G.rc.senseRobotAtLocation(towerTarget);
                if (r != null && r.team == G.opponent && r.type.isTowerType()) return;
            }
            mode = Mode.EXPLORE; towerTarget = null;
        }
        if (enemies.length > 0) {
            RobotInfo n = Micro.findNearestEnemy();
            if (n != null && G.me.distanceSquaredTo(n.location) <= 20) { mode = Mode.ATTACK; return; }
        }
        // Build towers - start immediately like SPAARK (no round > 50 restriction)
        if (mode == Mode.EXPLORE && Phase.shouldBuildTowers()) {
            MapLocation ruin = findBuildableRuin();
            if (ruin != null) { mode = Mode.BUILD_TOWER; buildTarget = ruin; buildTimeout = 80; return; }
        }
        if (mode == Mode.BUILD_TOWER) { buildTimeout--; if (buildTimeout <= 0 || buildTarget == null) { mode = Mode.EXPLORE; buildTarget = null; } }
        if (mode == Mode.ATTACK && enemies.length == 0) mode = Mode.EXPLORE;
    }

    private static void explore() throws GameActionException {
        // Attack enemies in range first
        if (tryAttack()) return;

        // AGGRESSIVE: Hunt enemy splashers (they paint our territory!)
        RobotInfo[] enemies = G.getEnemies();
        for (int i = enemies.length; --i >= 0;) {
            if (enemies[i].type == UnitType.SPLASHER) {
                Nav.moveToWithMicro(enemies[i].location);
                return;
            }
        }

        // Handle other nearby enemies with micro
        if (enemies.length > 0) {
            RobotInfo n = Micro.findNearestEnemy();
            if (n != null) { Nav.moveToWithMicro(n.location); return; }
        }

        // PRIORITY: Always target enemy PAINT towers (hurt their economy!)
        MapLocation paintTower = POI.findNearestEnemyPaintTower();
        if (paintTower != null) {
            Nav.moveTo(paintTower);
            return;
        }

        // Then any enemy tower
        MapLocation enemyTower = POI.findNearestEnemyTower();
        if (enemyTower != null) {
            Nav.moveTo(enemyTower);
            return;
        }

        // Only build if no enemy targets and we have few towers
        if (G.rc.getNumberTowers() < 10) {
            MapLocation ruin = POI.findNearestNeutralRuin();
            if (ruin != null) {
                Nav.moveTo(ruin);
                return;
            }
        }

        // Explore unexplored areas
        MapLocation unexplored = Comm.findUnexploredArea();
        if (unexplored != null) {
            Nav.moveTo(unexplored);
        } else {
            Nav.moveRandom();
        }
    }

    private static void buildTower() throws GameActionException {
        if (buildTarget == null) { mode = Mode.EXPLORE; return; }

        // BUILD PROGRESS: Log every 5 rounds
        if (buildTimeout % 5 == 0) {
            System.out.println("BUILD_PROGRESS:" + G.round + ":SOLDIER:" + G.id +
                ":location=" + buildTarget +
                ":buildTime=" + buildTimeout +
                ":dist=" + G.me.distanceSquaredTo(buildTarget));
        }

        if (G.rc.canSenseLocation(buildTarget)) {
            RobotInfo r = G.rc.senseRobotAtLocation(buildTarget);
            if (r != null && r.type.isTowerType()) {
                System.out.println("TOWER_COMPLETE:" + G.round + ":SOLDIER:" + G.id +
                    ":location=" + buildTarget +
                    ":type=" + r.type +
                    ":buildTime=" + buildTimeout);
                POI.updateTower(buildTarget, r.team, r.type);
                mode = Mode.EXPLORE;
                buildTarget = null;
                buildTimeout = 0;
                return;
            }
        }

        if (G.getEnemies().length > 0 && Micro.hasNearbyThreats()) {
            System.out.println("BUILD_ABORT:" + G.round + ":SOLDIER:" + G.id +
                ":reason=enemy_threat:buildTime=" + buildTimeout);
            mode = Mode.EXPLORE;
            buildTarget = null;
            buildTimeout = 0;
            return;
        }

        if (G.me.distanceSquaredTo(buildTarget) > 2) { Nav.moveTo(buildTarget); return; }

        if (tryCompleteTowerPattern()) {
            mode = Mode.EXPLORE;
            buildTarget = null;
            buildTimeout = 0;
            return;
        }

        tryPaintForTower();
        buildTimeout++;
    }

    private static void retreat() throws GameActionException {
        if (retreatTarget == null) {
            retreatTarget = POI.findNearestAllyPaintTower();
            if (retreatTarget == null) retreatTarget = POI.findNearestAllyTower();
        }

        if (retreatTarget == null) {
            // No tower found, just run away from enemies
            RobotInfo[] e = G.getEnemies();
            if (e.length > 0) Nav.retreatFrom(e[0].location);
            else Nav.moveRandom();
            return;
        }

        int dist = G.me.distanceSquaredTo(retreatTarget);

        // Leave tower after refueling (don't block spawns)
        if (dist <= 8 && G.paint > 100) {
            mode = Mode.EXPLORE;
            retreatTarget = null;
            // Move away from tower
            Direction away = retreatTarget.directionTo(G.me);
            if (away != Direction.CENTER) {
                Nav.moveTo(G.me.add(away).add(away).add(away));  // Move 3 tiles away
            }
            return;
        }

        // Move toward tower if far
        if (dist > 8) {
            Nav.moveTo(retreatTarget);
        }
        // Else stay at distance <= 8 and wait for refuel
    }

    private static void attack() throws GameActionException {
        if (tryAttack()) { RobotInfo t = Micro.findNearestEnemy(); if (t != null) Nav.moveToWithMicro(t.location); return; }
        RobotInfo n = Micro.findNearestEnemy(); if (n != null) Nav.moveToWithMicro(n.location); else mode = Mode.EXPLORE;
    }

    private static void attackTower() throws GameActionException {
        if (towerTarget == null) { mode = Mode.EXPLORE; attacksOnTower = 0; return; }
        RobotInfo ti = G.rc.canSenseLocation(towerTarget) ? G.rc.senseRobotAtLocation(towerTarget) : null;
        int towerRange = ti != null ? ti.type.actionRadiusSquared : 25;
        int dist = G.me.distanceSquaredTo(towerTarget);

        // DOUBLE-HIT MICRO: Stay in range for 2 attacks before retreating
        if (dist <= towerRange) {
            if (G.rc.canAttack(towerTarget)) {
                G.rc.attack(towerTarget);
                attacksOnTower++;
            }
            if (attacksOnTower >= 2) {
                Nav.retreatFrom(towerTarget);
                attacksOnTower = 0;
            }
        } else {
            attacksOnTower = 0;
            if (G.rc.isActionReady()) {
                Nav.moveTo(towerTarget);
                if (G.rc.canAttack(towerTarget)) {
                    G.rc.attack(towerTarget);
                    attacksOnTower++;
                }
            }
        }
    }

    private static boolean tryAttack() throws GameActionException {
        RobotInfo t = Micro.findBestTarget(); if (t != null && G.rc.canAttack(t.location)) { G.rc.attack(t.location); return true; } return false;
    }

    private static void paintCurrentTile() throws GameActionException {
        // Paint under self first
        MapInfo i = G.rc.senseMapInfo(G.me);
        if (!i.getPaint().isAlly() && G.rc.canAttack(G.me)) {
            G.rc.attack(G.me);
            return;
        }
        // Paint one adjacent empty tile (simplified for bytecode)
        if (G.rc.isActionReady() && G.paint > 30) {
            // Only check cardinal directions (4 vs 8) for efficiency
            MapLocation n = G.me.add(Direction.NORTH);
            if (G.rc.canAttack(n) && G.rc.senseMapInfo(n).getPaint() == PaintType.EMPTY) { G.rc.attack(n); return; }
            MapLocation e = G.me.add(Direction.EAST);
            if (G.rc.canAttack(e) && G.rc.senseMapInfo(e).getPaint() == PaintType.EMPTY) { G.rc.attack(e); return; }
            MapLocation s = G.me.add(Direction.SOUTH);
            if (G.rc.canAttack(s) && G.rc.senseMapInfo(s).getPaint() == PaintType.EMPTY) { G.rc.attack(s); return; }
            MapLocation w = G.me.add(Direction.WEST);
            if (G.rc.canAttack(w) && G.rc.senseMapInfo(w).getPaint() == PaintType.EMPTY) { G.rc.attack(w); }
        }
    }

    private static MapLocation findBuildableRuin() throws GameActionException {
        MapLocation[] r = G.getNearbyRuins(); if (r == null || r.length == 0) return null;
        for (int i = r.length; --i >= 0;) { if (G.rc.senseRobotAtLocation(r[i]) == null && G.me.distanceSquaredTo(r[i]) <= 36) return r[i]; }
        return null;
    }

    private static boolean tryCompleteTowerPattern() throws GameActionException {
        if (buildTarget == null) return false;
        if (G.rc.canCompleteTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, buildTarget)) { G.rc.completeTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, buildTarget); return true; }
        return false;
    }

    private static void tryPaintForTower() throws GameActionException {
        if (buildTarget == null) return;
        if (G.rc.canMarkTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, buildTarget)) G.rc.markTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, buildTarget);
        MapInfo[] pt = G.rc.senseNearbyMapInfos(buildTarget, 8);
        for (int i = pt.length; --i >= 0;) {
            PaintType m = pt[i].getMark(), p = pt[i].getPaint();
            if (m == PaintType.EMPTY || m == p || p.isEnemy()) continue;
            MapLocation tl = pt[i].getMapLocation();
            if (G.rc.canAttack(tl)) { G.rc.attack(tl, m == PaintType.ALLY_SECONDARY); return; }
        }
    }

    private static boolean wouldDieAttackingTower(RobotInfo t) {
        if (t.health <= 50) return false;
        int ta = Math.max(1, (G.rc.getMovementCooldownTurns() + 15) / 10);
        return G.rc.getHealth() <= (t.type.attackStrength + t.type.aoeAttackStrength) * ta;
    }
}
