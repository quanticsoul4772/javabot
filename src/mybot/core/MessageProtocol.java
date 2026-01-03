package mybot.core;

import battlecode.common.*;
import mybot.Metrics;

/**
 * Enhanced communication protocol for coordinating robots.
 *
 * Message format (32 bits / 4 bytes):
 * [4 bits: type][6 bits: x][6 bits: y][16 bits: payload]
 *
 * Towers can send 20 messages/turn, robots can send 1 message/turn.
 */
public class MessageProtocol {

    /**
     * Message types for inter-robot communication.
     * Organized by priority/frequency of use.
     */
    public enum MessageType {
        NONE,                 // 0: No message / invalid
        ENEMY_SPOTTED,        // 1: Enemy seen at location (payload = enemy count)
        RUSH_ALERT,           // 2: Rush detected! All units defend
        PAINT_TOWER_DANGER,   // 3: Paint tower under attack at location
        PAINT_TOWER_CRITICAL, // 4: Paint tower health critical, ALL units defend
        TOWER_TARGET,         // 5: Coordinated attack target (high priority)
        ATTACK_TARGET,        // 6: Suggested attack target location
        TOWER_BUILDING,       // 7: Soldier building tower, needs splasher support
        ZONE_CLAIM,           // 8: Unit claiming a zone (payload = zoneId)
        ZONE_NEED_HELP,       // 9: Zone needs reinforcement (payload = zoneId)
        SRP_CLUSTER_SITE,     // 10: Recommended SRP location (payload = score)
        ECONOMY_REPORT,       // 11: Tower paint level broadcast (payload = paint level)
        SPLASHER_THREATENED,  // 12: Splasher under attack, moppers intercept
        PHASE_DEFEND,         // 13: All units shift to defensive behavior
        PHASE_ALL_OUT_ATTACK, // 14: Late game push, all units aggressive
        RUIN_FOUND;           // 15: Unclaimed ruin found (max 16 types with 4 bits)
    }

    // ==================== ENCODING ====================

    /**
     * Encode a message into a 32-bit integer.
     */
    public static int encode(MessageType type, MapLocation loc, int payload) {
        int x = loc != null ? (loc.x & 0x3F) : 0;  // 6 bits for x (0-63)
        int y = loc != null ? (loc.y & 0x3F) : 0;  // 6 bits for y (0-63)
        int p = payload & 0xFFFF;                   // 16 bits for payload

        return (type.ordinal() << 28) | (x << 22) | (y << 16) | p;
    }

    /**
     * Encode a message without payload.
     */
    public static int encode(MessageType type, MapLocation loc) {
        return encode(type, loc, 0);
    }

    // ==================== DECODING ====================

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

    // ==================== SENDING ====================

    /**
     * Send a message to a specific location.
     */
    public static boolean sendTo(RobotController rc, MapLocation target, MessageType type,
                                  MapLocation aboutLoc, int payload) throws GameActionException {
        if (!rc.canSendMessage(target)) return false;

        int msg = encode(type, aboutLoc, payload);
        rc.sendMessage(target, msg);
        if (Metrics.ENABLED) Metrics.trackMessageSent();
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

    /**
     * Broadcast to nearby units only (within range squared).
     */
    public static int broadcastToNearby(RobotController rc, MessageType type,
                                         MapLocation aboutLoc, int payload,
                                         int maxDistSq) throws GameActionException {
        RobotInfo[] allies = rc.senseNearbyRobots(maxDistSq, rc.getTeam());
        int sent = 0;

        for (RobotInfo ally : allies) {
            if (sendTo(rc, ally.getLocation(), type, aboutLoc, payload)) {
                sent++;
            }
        }

        return sent;
    }

    // ==================== RECEIVING ====================

    /**
     * Check if we received a specific message type.
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

    /**
     * Get the payload from the first message of a specific type.
     */
    public static int getPayloadFromMessage(RobotController rc, MessageType type) throws GameActionException {
        Message[] messages = rc.readMessages(-1);
        for (Message m : messages) {
            if (decodeType(m.getBytes()) == type) {
                return decodePayload(m.getBytes());
            }
        }
        return -1;
    }

    /**
     * Get all messages of a specific type.
     */
    public static java.util.ArrayList<Message> getMessagesOfType(RobotController rc, MessageType type) throws GameActionException {
        java.util.ArrayList<Message> result = new java.util.ArrayList<>();
        Message[] messages = rc.readMessages(-1);
        for (Message m : messages) {
            if (decodeType(m.getBytes()) == type) {
                result.add(m);
            }
        }
        return result;
    }

    // ==================== SPECIFIC MESSAGE HELPERS ====================

    /**
     * Report enemy sighting to nearby tower.
     */
    public static boolean reportEnemy(RobotController rc, MapLocation enemyLoc, int enemyCount) throws GameActionException {
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
     * Alert when paint tower health is critical.
     */
    public static int alertPaintTowerCritical(RobotController rc, MapLocation towerLoc) throws GameActionException {
        return broadcastToAllies(rc, MessageType.PAINT_TOWER_CRITICAL, towerLoc, rc.getHealth());
    }

    /**
     * Broadcast coordinated tower attack target.
     */
    public static int broadcastTowerTarget(RobotController rc, MapLocation target, int priority) throws GameActionException {
        return broadcastToAllies(rc, MessageType.TOWER_TARGET, target, priority);
    }

    /**
     * Broadcast zone claim.
     */
    public static int broadcastZoneClaim(RobotController rc, int zoneId) throws GameActionException {
        return broadcastToAllies(rc, MessageType.ZONE_CLAIM, rc.getLocation(), zoneId);
    }

    /**
     * Request help for a zone.
     */
    public static int requestZoneHelp(RobotController rc, int zoneId, MapLocation zoneLoc) throws GameActionException {
        return broadcastToAllies(rc, MessageType.ZONE_NEED_HELP, zoneLoc, zoneId);
    }

    /**
     * Broadcast tower paint level (for refill coordination).
     */
    public static int broadcastPaintLevel(RobotController rc) throws GameActionException {
        return broadcastToAllies(rc, MessageType.ECONOMY_REPORT, rc.getLocation(), rc.getPaint());
    }

    /**
     * Broadcast SRP cluster site recommendation.
     */
    public static int broadcastSRPSite(RobotController rc, MapLocation site, int score) throws GameActionException {
        return broadcastToAllies(rc, MessageType.SRP_CLUSTER_SITE, site, score);
    }

    /**
     * Report a found ruin.
     */
    public static boolean reportRuin(RobotController rc, MapLocation ruinLoc) throws GameActionException {
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo ally : allies) {
            if (ally.getType().isTowerType()) {
                return sendTo(rc, ally.getLocation(), MessageType.RUIN_FOUND, ruinLoc, 0);
            }
        }
        return false;
    }
}
