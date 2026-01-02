package mybot;

import battlecode.common.*;

/**
 * Communication system for coordinating robots.
 *
 * Message format (32 bits / 4 bytes):
 * [4 bits: type][6 bits: x][6 bits: y][16 bits: payload]
 *
 * Towers can send 20 messages/turn, robots can send 1 message/turn.
 */
public class Comms {

    /**
     * Message types for inter-robot communication.
     */
    public enum MessageType {
        NONE,               // 0: No message / invalid
        ENEMY_SPOTTED,      // 1: Enemy seen at location (payload = enemy count)
        RUSH_ALERT,         // 2: Rush detected! All units defend
        PAINT_TOWER_DANGER, // 3: Paint tower under attack at location
        RUIN_FOUND,         // 4: Unclaimed ruin found at location
        TOWER_BUILT,        // 5: Tower completed at location (payload = type)
        TOWER_BUILDING,     // 6: Soldier building tower, needs splasher support
        PAINT_TOWER_CRITICAL, // 7: Paint tower health critical, ALL units defend
        SPLASHER_THREATENED,  // 8: Splasher under attack, moppers intercept
        PHASE_DEFEND,         // 9: All units shift to defensive behavior
        PHASE_ALL_OUT_ATTACK, // 10: Late game push, all units aggressive
        HELP_NEEDED,          // 11: Unit needs assistance at location
        ATTACK_TARGET,        // 12: Suggested attack target location
        RETREAT,              // 13: All units retreat to location
        ALL_CLEAR,            // 14: Threat cleared, resume normal ops
        // Add more as needed (up to 15 types with 4 bits)
    }

    /**
     * Encode a message into a 32-bit integer.
     *
     * @param type    The message type
     * @param loc     The location (x and y, 0-63 each)
     * @param payload Additional data (0-65535)
     * @return Encoded 32-bit message
     */
    public static int encode(MessageType type, MapLocation loc, int payload) {
        int x = loc != null ? (loc.x & 0x3F) : 0;  // 6 bits for x
        int y = loc != null ? (loc.y & 0x3F) : 0;  // 6 bits for y
        int p = payload & 0xFFFF;                   // 16 bits for payload

        return (type.ordinal() << 28) | (x << 22) | (y << 16) | p;
    }

    /**
     * Encode a message without payload.
     */
    public static int encode(MessageType type, MapLocation loc) {
        return encode(type, loc, 0);
    }

    /**
     * Decode the message type from a message.
     */
    public static MessageType decodeType(int msg) {
        int typeOrd = (msg >>> 28) & 0xF;
        MessageType[] types = MessageType.values();
        if (typeOrd < types.length) {
            return types[typeOrd];
        }
        return MessageType.NONE;
    }

    /**
     * Decode the X coordinate from a message.
     */
    public static int decodeX(int msg) {
        return (msg >>> 22) & 0x3F;
    }

    /**
     * Decode the Y coordinate from a message.
     */
    public static int decodeY(int msg) {
        return (msg >>> 16) & 0x3F;
    }

    /**
     * Decode the location from a message.
     */
    public static MapLocation decodeLocation(int msg) {
        return new MapLocation(decodeX(msg), decodeY(msg));
    }

    /**
     * Decode the payload from a message.
     */
    public static int decodePayload(int msg) {
        return msg & 0xFFFF;
    }

    // ========== Sending Helpers ==========

    /**
     * Send a message to a specific location (robot at that location receives it).
     * Returns true if sent successfully.
     */
    public static boolean sendTo(RobotController rc, MapLocation target, MessageType type,
                                  MapLocation aboutLoc, int payload) throws GameActionException {
        if (!rc.canSendMessage(target)) return false;

        int msg = encode(type, aboutLoc, payload);
        rc.sendMessage(target, msg);
        return true;
    }

    /**
     * Send a simple message to a location.
     */
    public static boolean sendTo(RobotController rc, MapLocation target, MessageType type,
                                  MapLocation aboutLoc) throws GameActionException {
        return sendTo(rc, target, type, aboutLoc, 0);
    }

    /**
     * Broadcast to all visible allies (for towers with 20 msg/turn budget).
     * Returns number of messages sent.
     */
    public static int broadcastToAllies(RobotController rc, MessageType type,
                                         MapLocation aboutLoc, int payload) throws GameActionException {
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        int sent = 0;

        for (RobotInfo ally : allies) {
            if (sendTo(rc, ally.getLocation(), type, aboutLoc, payload)) {
                sent++;
            }
        }

        return sent;
    }

    // ========== Receiving Helpers ==========

    /**
     * Check if we received a specific message type recently.
     */
    public static boolean hasMessageOfType(RobotController rc, MessageType type) throws GameActionException {
        Message[] messages = rc.readMessages(-1);
        for (Message m : messages) {
            if (decodeType(m.getBytes()) == type) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the location from the first message of a specific type.
     * Returns null if no such message.
     */
    public static MapLocation getLocationFromMessage(RobotController rc, MessageType type) throws GameActionException {
        Message[] messages = rc.readMessages(-1);
        for (Message m : messages) {
            if (decodeType(m.getBytes()) == type) {
                return decodeLocation(m.getBytes());
            }
        }
        return null;
    }

    // ========== Specific Message Senders ==========

    /**
     * Report enemy sighting to nearby tower.
     */
    public static boolean reportEnemy(RobotController rc, MapLocation enemyLoc, int enemyCount) throws GameActionException {
        // Find nearby tower to report to
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo ally : allies) {
            if (ally.getType().isTowerType()) {
                return sendTo(rc, ally.getLocation(), MessageType.ENEMY_SPOTTED, enemyLoc, enemyCount);
            }
        }
        return false;
    }

    /**
     * Alert about rush attack.
     */
    public static int alertRush(RobotController rc) throws GameActionException {
        return broadcastToAllies(rc, MessageType.RUSH_ALERT, rc.getLocation(), 0);
    }

    /**
     * Alert about paint tower under attack.
     */
    public static int alertPaintTowerDanger(RobotController rc, MapLocation towerLoc) throws GameActionException {
        return broadcastToAllies(rc, MessageType.PAINT_TOWER_DANGER, towerLoc, 0);
    }

    /**
     * Report a found ruin.
     */
    public static boolean reportRuin(RobotController rc, MapLocation ruinLoc) throws GameActionException {
        // Find nearby tower to report to
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo ally : allies) {
            if (ally.getType().isTowerType()) {
                return sendTo(rc, ally.getLocation(), MessageType.RUIN_FOUND, ruinLoc, 0);
            }
        }
        return false;
    }

    /**
     * Alert when paint tower health is critical.
     */
    public static int alertPaintTowerCritical(RobotController rc, MapLocation towerLoc) throws GameActionException {
        return broadcastToAllies(rc, MessageType.PAINT_TOWER_CRITICAL, towerLoc, rc.getHealth());
    }

    /**
     * Broadcast attack target for coordinated focus fire.
     * Units only send 1 msg/turn, so send to first available ally.
     */
    public static void broadcastAttackTarget(RobotController rc, MapLocation target) throws GameActionException {
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo ally : allies) {
            if (!ally.getType().isTowerType() && rc.canSendMessage(ally.getLocation())) {
                int msg = encode(MessageType.ATTACK_TARGET, target, 0);
                rc.sendMessage(ally.getLocation(), msg);
                break;  // Units can only send 1 msg/turn
            }
        }
    }
}
