package mybot;

import battlecode.common.*;

/**
 * Main entry point for mybot.
 * Dispatches control to unit-specific handlers.
 */
public class RobotPlayer {

    public static void run(RobotController rc) throws GameActionException {
        while (true) {
            try {
                switch (rc.getType()) {
                    case SOLDIER:
                        Soldier.run(rc);
                        break;
                    case MOPPER:
                        Mopper.run(rc);
                        break;
                    case SPLASHER:
                        Splasher.run(rc);
                        break;
                    default:
                        // All tower types
                        Tower.run(rc);
                        break;
                }
            } catch (GameActionException e) {
                System.out.println(rc.getType() + " GameActionException: " + e.getMessage());
                e.printStackTrace();
            } catch (Exception e) {
                System.out.println(rc.getType() + " Exception: " + e.getMessage());
                e.printStackTrace();
            } finally {
                Clock.yield();
            }
        }
    }
}
