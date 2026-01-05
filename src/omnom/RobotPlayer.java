package omnom;

import battlecode.common.*;

/**
 * Main entry point for omnom bot.
 * TDD Week 1: Skeleton with visibility logging.
 */
public class RobotPlayer {
    // Shared sensing (cached once per round for all units)
    public static RobotInfo[] allAllies;
    public static RobotInfo[] allEnemies;
    public static int lastSenseRound = -1;

    public static void run(RobotController rc) throws GameActionException {
        // One-time initialization
        G.rc = rc;
        Random.state = rc.getID() * 0x2bda6bc + 0x9734e9;

        // Initialize G.java
        G.init(rc);

        System.out.println("INIT:" + G.round + ":" + G.type + ":" + G.id +
            ":pos=" + G.me);

        // Register own tower in POI
        if (rc.getType().isTowerType()) {
            POI.updateTower(rc.getLocation(), G.team, rc.getType());
        }

        // Main game loop
        while (true) {
            try {
                // Sense ONCE per round (shared across all units)
                if (lastSenseRound != rc.getRoundNum()) {
                    allAllies = rc.senseNearbyRobots(-1, G.team);
                    allEnemies = rc.senseNearbyRobots(-1, G.opponent);
                    lastSenseRound = rc.getRoundNum();

                    // Update POI once per round too (not per unit)
                    POI.update();
                }

                // Update global state each turn (but use cached sensing)
                G.init(rc);

                // MESSAGING: Receive messages once per round
                if (lastSenseRound == rc.getRoundNum()) {
                    Messaging.receiveMessages();
                }

                // Dispatch to unit type
                switch (rc.getType()) {
                    case SOLDIER:
                        Soldier.run();
                        break;
                    case SPLASHER:
                        Splasher.run();
                        break;
                    case MOPPER:
                        Mopper.run();
                        break;
                    case LEVEL_ONE_DEFENSE_TOWER:
                    case LEVEL_TWO_DEFENSE_TOWER:
                    case LEVEL_THREE_DEFENSE_TOWER:
                        // Defense towers have auto-destruct logic
                        DefenseTower.run();
                        break;
                    default:
                        // Other tower types
                        Tower.run();
                        break;
                }

                // MESSAGING: Towers send after unit logic
                if (rc.getType().isTowerType()) {
                    Messaging.sendMessages();
                }

            } catch (GameActionException e) {
                System.out.println("GameActionException: " + e.getMessage());
                e.printStackTrace();
            } catch (Exception e) {
                System.out.println("Exception: " + e.getMessage());
                e.printStackTrace();
            }

            Clock.yield();
        }
    }
}
