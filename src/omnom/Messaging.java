package omnom;

import battlecode.common.*;

/**
 * 16-bit messaging system for tower coordination.
 * Source: bot_spec.md Part 16.7, SPAARK POI.java readMessages
 */
public class Messaging {
    /**
     * Encode location to 12 bits (6 bits x, 6 bits y).
     */
    public static int intifyLocation(MapLocation loc) {
        return ((loc.y << 6) | loc.x);
    }

    /**
     * Decode location from 12 bits.
     */
    public static MapLocation parseLocation(int encoded) {
        int x = encoded & 0b111111;
        int y = (encoded >> 6) & 0b111111;
        return new MapLocation(x, y);
    }

    /**
     * Encode tower info to 16 bits.
     */
    public static int intifyTower(MapLocation loc, Team team, UnitType type) {
        int locBits = intifyLocation(loc);

        int teamType = 0;
        if (team == Team.NEUTRAL) {
            teamType = 0;
        } else {
            int typeOffset = 1;  // Default paint
            if (type.toString().contains("MONEY")) typeOffset = 2;
            else if (type.toString().contains("DEFENSE")) typeOffset = 3;

            teamType = typeOffset + (team == G.team ? 0 : 3);
        }

        return locBits | (teamType << 12);
    }

    /**
     * Parse and add tower from message.
     */
    public static void read16BitMessage(int senderID, int data) {
        MapLocation loc = parseLocation(data & 0xFFF);
        int teamType = (data >> 12) & 0x7;

        if (teamType == 0) {
            POI.updateTower(loc, Team.NEUTRAL, null);
        } else if (teamType <= 3) {
            UnitType type = teamType == 1 ? UnitType.LEVEL_ONE_PAINT_TOWER :
                           teamType == 2 ? UnitType.LEVEL_ONE_MONEY_TOWER :
                           UnitType.LEVEL_ONE_DEFENSE_TOWER;
            POI.updateTower(loc, G.team, type);
        } else {
            UnitType type = teamType == 4 ? UnitType.LEVEL_ONE_PAINT_TOWER :
                           teamType == 5 ? UnitType.LEVEL_ONE_MONEY_TOWER :
                           UnitType.LEVEL_ONE_DEFENSE_TOWER;
            POI.updateTower(loc, G.opponent, type);
        }
    }

    /**
     * Towers broadcast their location every 100 rounds.
     */
    public static void sendMessages() throws GameActionException {
        if (!G.type.isTowerType()) return;

        // Broadcast own location every 100 rounds
        if (G.round % 100 == 0) {
            int message = intifyTower(G.me, G.team, G.type) | (1 << 15);  // Relay flag
            G.rc.broadcastMessage(message);
        }
    }

    /**
     * Robots receive and parse messages (CORRECT API from SPAARK).
     */
    public static void receiveMessages() throws GameActionException {
        Message[] messages = G.rc.readMessages(G.round - 1);
        if (messages == null) return;

        for (Message m : messages) {
            int data = m.getBytes();  // CORRECT: getBytes(), not getCurrentMessage()

            // Parse 32-bit message as two 16-bit messages
            int msg1 = data & 0xFFFF;
            int msg2 = (data >> 16) & 0xFFFF;

            if (msg1 != 0) {
                read16BitMessage(m.getSenderID(), msg1);
            }
            if (msg2 != 0) {
                read16BitMessage(m.getSenderID(), msg2);
            }
        }
    }
}
