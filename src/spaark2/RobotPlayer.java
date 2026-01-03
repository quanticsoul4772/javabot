package spaark2;

import battlecode.common.*;

/**
 * Entry point for spaark2 bot.
 * Simple dispatch to unit handlers by type.
 */
public class RobotPlayer {

    public static void run(RobotController rc) throws GameActionException {
        while (true) {
            try {
                switch (rc.getType()) {
                    case SOLDIER:  Soldier.run(rc);  break;
                    case MOPPER:   Mopper.run(rc);   break;
                    case SPLASHER: Splasher.run(rc); break;
                    default:       Tower.run(rc);    break;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            Clock.yield();
        }
    }
}
