package main_bot;

import battlecode.common.*;
import main_bot.*;

public abstract class Robot {

    public static final Direction[] DIRECTIONS = {
        Direction.NORTH, Direction.NORTHEAST, Direction.EAST,
        Direction.SOUTHEAST, Direction.SOUTH, Direction.SOUTHWEST,
        Direction.WEST, Direction.NORTHWEST,
    };

    public static final UnitType[] TOWER_TYPES = {
        UnitType.LEVEL_ONE_PAINT_TOWER,
        UnitType.LEVEL_ONE_MONEY_TOWER,
        UnitType.LEVEL_ONE_DEFENSE_TOWER,
    };

    public static final Direction[] CARDINAL_DIRS = {
        Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST,
    };

    public final RobotController rc;
    public final int width;
    public final int height;

    public final MapLocation spawnLoc;
    public final int spawnTurn;

    public final MapData mapData;
    public final Explore explorer;

    public final Comms comms;

    public MapLocation needMopperAt = null;
    public MapLocation frontlineLoc = null;

    public final boolean BUG_RIGHT;

    public Robot(RobotController rc) throws GameActionException {
        this.rc       = rc;
        this.width    = rc.getMapWidth();
        this.height   = rc.getMapHeight();
        this.spawnLoc = rc.getLocation();
        this.spawnTurn = rc.getRoundNum();
        this.BUG_RIGHT = (rc.getID() % 2 == 0);

        mapData = new MapData(rc, width, height);
        comms   = new Comms(rc, this);

        if (!rc.getType().isTowerType()) {
            explorer = new Explore(rc, mapData);
        } else {
            explorer = null;
        }
    }

    public abstract void turn() throws GameActionException;

    public void run() {
        while (true) {
            try {
                turn();
            } catch (GameActionException e) {
                System.out.println("[GameActionException] " + rc.getType() + " at " + rc.getLocation());
                e.printStackTrace();
            } catch (Exception e) {
                System.out.println("[Exception] " + rc.getType() + " at " + rc.getLocation());
                e.printStackTrace();
            } finally {
                Clock.yield();
            }
        }
    }

    public void onRuinMessage(MapLocation loc) throws GameActionException {
        mapData.addRuin(loc);
    }

    public void onEnemyTowerMessage(MapLocation loc, UnitType type) throws GameActionException {
    }

    public void onNeedPaintMessage(MapLocation loc) throws GameActionException {
        needMopperAt = loc;
    }

    public void onFriendlyTowerMessage(MapLocation loc, UnitType type) throws GameActionException {
        RobotInfo placeholder = new RobotInfo(0, rc.getTeam(), type, 2000, loc, 500);
        mapData.addFriendlyTower(placeholder);
    }

    public Direction dirTo(MapLocation target) {
        return rc.getLocation().directionTo(target);
    }

    public int distTo(MapLocation target) {
        return rc.getLocation().distanceSquaredTo(target);
    }

    public Direction[] nearbyDirs(Direction dir) {
        return new Direction[]{
            dir,
            dir.rotateLeft(),
            dir.rotateRight(),
            dir.rotateLeft().rotateLeft(),
            dir.rotateRight().rotateRight(),
            dir.opposite().rotateRight(),
            dir.opposite().rotateLeft(),
            dir.opposite(),
        };
    }

    public Direction randomDirection() throws GameActionException {
        Direction[] dirs = Direction.values();
        for (int i = dirs.length - 1; i > 0; i--) {
            int j = (int)(Math.random() * (i + 1));
            Direction tmp = dirs[i]; dirs[i] = dirs[j]; dirs[j] = tmp;
        }
        for (Direction d : dirs) {
            if (d != Direction.CENTER && rc.canMove(d)) return d;
        }
        return Direction.CENTER;
    }

    public boolean inEnemyTowerRange(MapLocation loc) {
        for (int i = mapData.enemyTowerList.size(); --i >= 0;) {
            RobotInfo t = mapData.enemyTowerList.get(i);
            if (loc.isWithinDistanceSquared(t.location, t.type.actionRadiusSquared)) return true;
        }
        return false;
    }

    public UnitType determineTowerPattern(MapLocation ruinLoc) {
        double ratio = mapData.moneyTowers < 6 ? 2.5 : 1.5;
        boolean needsMoney = mapData.moneyTowers
                < Math.max(1, mapData.paintTowers) * ratio
                && rc.getChips() < 4000;
        return needsMoney ? UnitType.LEVEL_ONE_MONEY_TOWER : UnitType.LEVEL_ONE_PAINT_TOWER;
    }

    public Direction towerTypeToMarkDirection(UnitType towerType) {
        if (towerType == UnitType.LEVEL_ONE_PAINT_TOWER)  return Direction.NORTH;
        if (towerType == UnitType.LEVEL_ONE_MONEY_TOWER)  return Direction.NORTHWEST;
        return Direction.NORTHEAST;
    }

    public UnitType markDirectionToTowerType(Direction markDirection) {
        if (markDirection == Direction.NORTH)     return UnitType.LEVEL_ONE_PAINT_TOWER;
        if (markDirection == Direction.NORTHWEST) return UnitType.LEVEL_ONE_MONEY_TOWER;
        return UnitType.LEVEL_ONE_DEFENSE_TOWER;
    }

}