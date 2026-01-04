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

    // Cache our paint tower location (doesn't change)
    private static MapLocation cachedOurTower = null;
    private static boolean ourTowerCached = false;

    public static void run() throws GameActionException {
        if (G.round < 100) { earlyGameRush(); return; }
        updateMode();
        switch (mode) {
            case EXPLORE: explore(); break;
            case BUILD_TOWER: buildTower(); break;
            case RETREAT: retreat(); break;
            case ATTACK: attack(); break;
            case ATTACK_TOWER: attackTower(); break;
        }
        paintCurrentTile();
    }

    private static void earlyGameRush() throws GameActionException {
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
        if (mode == Mode.RETREAT && G.paint > 80) { mode = Mode.EXPLORE; retreatTarget = null; }
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
        if (G.rc.canSenseLocation(buildTarget)) {
            RobotInfo r = G.rc.senseRobotAtLocation(buildTarget);
            if (r != null && r.type.isTowerType()) { POI.updateTower(buildTarget, r.team, r.type); mode = Mode.EXPLORE; buildTarget = null; return; }
        }
        if (G.getEnemies().length > 0 && Micro.hasNearbyThreats()) { mode = Mode.EXPLORE; buildTarget = null; return; }
        if (G.me.distanceSquaredTo(buildTarget) > 2) { Nav.moveTo(buildTarget); return; }
        if (tryCompleteTowerPattern()) { mode = Mode.EXPLORE; buildTarget = null; return; }
        tryPaintForTower();
    }

    private static void retreat() throws GameActionException {
        if (retreatTarget == null) { retreatTarget = POI.findNearestAllyPaintTower(); if (retreatTarget == null) retreatTarget = POI.findNearestAllyTower(); }
        if (retreatTarget != null && G.me.distanceSquaredTo(retreatTarget) <= 2) { if (G.paint > G.RETREAT_PAINT * 2) { mode = Mode.EXPLORE; retreatTarget = null; } return; }
        if (retreatTarget != null) Nav.moveTo(retreatTarget);
        else { RobotInfo[] e = G.getEnemies(); if (e.length > 0) Nav.retreatFrom(e[0].location); else Nav.moveRandom(); }
    }

    private static void attack() throws GameActionException {
        if (tryAttack()) { RobotInfo t = Micro.findNearestEnemy(); if (t != null) Nav.moveToWithMicro(t.location); return; }
        RobotInfo n = Micro.findNearestEnemy(); if (n != null) Nav.moveToWithMicro(n.location); else mode = Mode.EXPLORE;
    }

    private static void attackTower() throws GameActionException {
        if (towerTarget == null) { mode = Mode.EXPLORE; return; }
        RobotInfo ti = G.rc.canSenseLocation(towerTarget) ? G.rc.senseRobotAtLocation(towerTarget) : null;
        int towerRange = ti != null ? ti.type.actionRadiusSquared : 25;
        int dist = G.me.distanceSquaredTo(towerTarget);

        // Simple fast tower attack: attack + kite
        if (dist <= towerRange) {
            if (G.rc.canAttack(towerTarget)) G.rc.attack(towerTarget);
            Nav.retreatFrom(towerTarget);
        } else if (G.rc.isActionReady()) {
            Nav.moveTo(towerTarget);
            if (G.rc.canAttack(towerTarget)) G.rc.attack(towerTarget);
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
