package omnom;

import battlecode.common.*;

/**
 * Defense tower specific behavior.
 * Adds auto-destruct when idle (from spec Part 16.6).
 */
public class DefenseTower {
    private static int lastEnemySeen = -1;

    public static void run() throws GameActionException {
        // Check if we've seen enemies recently
        RobotInfo[] enemies = G.getEnemies();
        if (enemies.length > 0) {
            lastEnemySeen = G.round;
        }

        // Check for enemy paint nearby
        MapInfo[] tiles = G.rc.senseNearbyMapInfos();
        for (int i = tiles.length; --i >= 0;) {
            if (tiles[i].getPaint().isEnemy()) {
                lastEnemySeen = G.round;
                break;
            }
        }

        // SELF-DESTRUCT: If no enemy activity for 30 rounds
        if (G.round - lastEnemySeen > 30) {
            System.out.println("DEFENSE_DESTRUCT:" + G.round + ":TOWER:" + G.id +
                ":reason=idle_30_rounds:last_enemy=" + lastEnemySeen);
            G.rc.disintegrate();
            return;
        }

        // Use standard tower logic
        Tower.run();
    }
}
