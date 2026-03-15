package alternative-bots-2;

import battlecode.common.*;

public class Comms {

    public static final int MSG_RUIN       = 0;
    public static final int MSG_SRP        = 1;
    public static final int MSG_ENEMY      = 2;
    public static final int MSG_NEED_PAINT = 3;
    public static final int MSG_TOWER      = 4;

    public static int encodeLocation(int prefix, MapLocation loc) {
        int msg = 0;
        msg |= (prefix & 0x7)  << 28;
        msg |= (loc.x  & 0x3F) << 22;
        msg |= (loc.y  & 0x3F) << 16;
        return msg;
    }

    public static int encodeLocExtra(int prefix, MapLocation loc, int extra) {
        int msg = encodeLocation(prefix, loc);
        msg |= (extra & 0x3) << 14;
        return msg;
    }

    public static int     msgPrefix(int raw)  { return (raw >>> 28) & 0x7; }
    public static int     msgX(int raw)       { return (raw >>> 22) & 0x3F; }
    public static int     msgY(int raw)       { return (raw >>> 16) & 0x3F; }
    public static int     msgExtra(int raw)   { return (raw >>> 14) & 0x3; }
    public static MapLocation msgLoc(int raw) { return new MapLocation(msgX(raw), msgY(raw)); }

    public static int encodeTowerType(UnitType type) {
        UnitType base = type.getBaseType();
        if (base == UnitType.LEVEL_ONE_PAINT_TOWER)  return 0;
        if (base == UnitType.LEVEL_ONE_MONEY_TOWER)  return 1;
        return 2;
    }

    public static UnitType decodeTowerType(int bits) {
        switch (bits & 0x3) {
            case 0:  return UnitType.LEVEL_ONE_PAINT_TOWER;
            case 1:  return UnitType.LEVEL_ONE_MONEY_TOWER;
            default: return UnitType.LEVEL_ONE_DEFENSE_TOWER;
        }
    }

    static int spawnRound = -1;

    public static void parseMessages(RobotController rc) {
        try {
            if (spawnRound < 0) spawnRound = rc.getRoundNum();

            int round = rc.getRoundNum();
            int turnsBack = (round - spawnRound > 2) ? 1 : 2;

            for (Message msg : rc.readMessages(round - turnsBack)) {
                int raw    = msg.getBytes();
                int prefix = msgPrefix(raw);

                switch (prefix) {
                    case MSG_RUIN:
                        break;

                    case MSG_SRP:
                        break;

                    case MSG_ENEMY:
                        Utils.cachedEnemyTowerLoc  = msgLoc(raw);
                        Utils.cachedEnemyTowerType = decodeTowerType(msgExtra(raw));
                        break;

                    case MSG_NEED_PAINT:
                        Utils.needMopperAt = msgLoc(raw);
                        break;

                    case MSG_TOWER:
                        MapLocation tLoc = msgLoc(raw);
                        UnitType tType = decodeTowerType(msgExtra(raw));
                        Utils.addKnownFriendlyTower(tLoc, tType);
                        break;
                }
            }
        } catch (Exception ignored) {}
    }

    public static boolean sendToNearestTower(RobotController rc, int message)
            throws GameActionException {
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo r : allies) {
            if (r.getType().isTowerType() && rc.canSendMessage(r.getLocation(), message)) {
                rc.sendMessage(r.getLocation(), message);
                return true;
            }
        }
        return false;
    }

    public static boolean broadcastToTowers(RobotController rc, int message)
            throws GameActionException {
        if (rc.canBroadcastMessage()) {
            rc.broadcastMessage(message);
            return true;
        }
        return false;
    }

    public static boolean sendTo(RobotController rc, MapLocation target, int message)
            throws GameActionException {
        if (rc.canSendMessage(target, message)) {
            rc.sendMessage(target, message);
            return true;
        }
        return false;
    }
}
