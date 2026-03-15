package alternative-bots-1;

import battlecode.common.*;
import alternative-bots-1.*;

public class Comms {

    private final RobotController rc;
    private final Robot robot;

    public Comms(RobotController rc, Robot robot) {
        this.rc  = rc;
        this.robot = robot;
    }

    public int encodeLocation(int prefix, MapLocation loc) {
        int msg = 0;
        msg |= (prefix & 0x7)  << 28;
        msg |= (loc.x  & 0x3F) << 22;
        msg |= (loc.y  & 0x3F) << 16;
        return msg;
    }

    public int encodeLocExtra(int prefix, MapLocation loc, int extra) {
        int msg = encodeLocation(prefix, loc);
        msg |= (extra & 0x3) << 14;
        return msg;
    }


    public static int encodeTowerType(UnitType type) {
        UnitType base = type.getBaseType();
        if (base == UnitType.LEVEL_ONE_PAINT_TOWER) return 0;
        if (base == UnitType.LEVEL_ONE_MONEY_TOWER) return 1;
        return 2;
    }

    public static UnitType decodeTowerType(int bits) {
        return switch (bits & 0x3) {
            case 0  -> UnitType.LEVEL_ONE_PAINT_TOWER;
            case 1  -> UnitType.LEVEL_ONE_MONEY_TOWER;
            default -> UnitType.LEVEL_ONE_DEFENSE_TOWER;
        };
    }

    public void parseMessages() throws GameActionException {
        int round = rc.getRoundNum();
        int turnsBack = (round - robot.spawnTurn > 2) ? 1 : 2;

        for (Message msg : rc.readMessages(round - turnsBack)) {
            int raw    = msg.getBytes();
            int prefix = (raw >>> 28) & 0x7;
            int x      = (raw >>> 22) & 0x3F;
            int y      = (raw >>> 16) & 0x3F;
            int extra  = (raw >>> 14) & 0x3;
            MapLocation loc = new MapLocation(x, y);

            switch (prefix) {
                case Constants.MSG_RUIN ->
                    robot.onRuinMessage(loc);

                case Constants.MSG_ENEMY ->
                    robot.onEnemyTowerMessage(loc, decodeTowerType(extra));

                case Constants.MSG_NEED_PAINT ->
                    robot.onNeedPaintMessage(loc);

                case Constants.MSG_TOWER -> robot.onFriendlyTowerMessage(loc, decodeTowerType(extra));

            }
        }
    }

    public boolean sendToNearestTower(int message) throws GameActionException {
        for (RobotInfo r : rc.senseNearbyRobots(-1, rc.getTeam())) {
            if (r.type.isTowerType() && rc.canSendMessage(r.location)) {
                rc.sendMessage(r.location, message);
                return true;
            }
        }
        return false;
    }

    public int broadcastToNearbyAllies(int message) throws GameActionException {
        int sent = 0;
        for (RobotInfo ally : rc.senseNearbyRobots(-1, rc.getTeam())) {
            if (!ally.type.isTowerType() && rc.canSendMessage(ally.location)) {
                rc.sendMessage(ally.location, message);
                sent++;
            }
        }
        return sent;
    }

    public boolean broadcastToTowers(int message) throws GameActionException {
        if (rc.canBroadcastMessage()) {
            rc.broadcastMessage(message);
            return true;
        }
        return false;
    }

    public boolean sendTo(MapLocation target, int message) throws GameActionException {
        if (rc.canSendMessage(target)) {
            rc.sendMessage(target, message);
            return true;
        }
        return false;
    }
}