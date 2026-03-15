package alternative_bots_2;

import battlecode.common.*;

public class Utils {

    static int turnCount = 0;
    static int rngState  = 6147;

    static int rand() {
        rngState ^= rngState << 13;
        rngState ^= rngState >> 17;
        rngState ^= rngState << 15;
        return rngState & 2147483647;
    }

    static final Direction[] directions = {
        Direction.NORTH, Direction.NORTHEAST, Direction.EAST, Direction.SOUTHEAST,
        Direction.SOUTH, Direction.SOUTHWEST, Direction.WEST, Direction.NORTHWEST,
    };

    static final double RETREAT_THRESHOLD        = 0.30; 
    static final double REFILL_FULL_RATIO        = 0.75;
    static final int    UPGRADE_CHIPS_THRESHOLD  = 6000;
    static final int    TOWER_BUILD_COST         = 1000;
    static final int    MIN_CHIPS_TO_SPAWN       = 600;
    static final int    SELF_DESTRUCT_BUFFER     = 15;

    static MapLocation cachedEnemyTowerLoc  = null;
    static UnitType    cachedEnemyTowerType = null;

    static MapLocation needMopperAt = null;

    static MapLocation frontlineLoc = null;
    static MapLocation cachedPaintTowerLoc = null;

    static final int MAX_KNOWN_TOWERS = 30;
    static MapLocation[] knownTowerLocs  = new MapLocation[MAX_KNOWN_TOWERS];
    static UnitType[]    knownTowerTypes = new UnitType[MAX_KNOWN_TOWERS];
    static int           knownTowerCount = 0;

    static void addKnownFriendlyTower(MapLocation loc, UnitType type) {
        for (int i = 0; i < knownTowerCount; i++) {
            if (knownTowerLocs[i].equals(loc)) {
                knownTowerTypes[i] = type;
                return;
            }
        }
        if (knownTowerCount < MAX_KNOWN_TOWERS) {
            knownTowerLocs[knownTowerCount]  = loc;
            knownTowerTypes[knownTowerCount] = type;
            knownTowerCount++;
        }
    }

    static int countKnownMoneyTowers() {
        int c = 0;
        for (int i = 0; i < knownTowerCount; i++) {
            UnitType base = knownTowerTypes[i].getBaseType();
            if (base == UnitType.LEVEL_ONE_MONEY_TOWER) c++;
        }
        return c;
    }

    static int countKnownPaintTowers() {
        int c = 0;
        for (int i = 0; i < knownTowerCount; i++) {
            UnitType base = knownTowerTypes[i].getBaseType();
            if (base == UnitType.LEVEL_ONE_PAINT_TOWER) c++;
        }
        return c;
    }
    static MapLocation[] exploreTargets = null;
    static int           exploreIdx     = 0;

    static MapLocation currentExploreTarget(RobotController rc) {
        if (exploreTargets == null) {
            int W = rc.getMapWidth(), H = rc.getMapHeight();
            int mX = W / 2, mY = H / 2;
            exploreTargets = new MapLocation[]{
                new MapLocation(2,     2),
                new MapLocation(W - 3, H - 3),
                new MapLocation(2,     H - 3),
                new MapLocation(W - 3, 2),
                new MapLocation(mX,    mY),
                new MapLocation(mX,    2),
                new MapLocation(2,     mY),
                new MapLocation(W - 3, mY),
                new MapLocation(mX,    H - 3),
            };
            exploreIdx = rand() % exploreTargets.length;
        }

        MapLocation t = exploreTargets[exploreIdx % exploreTargets.length];
        if (rc.getLocation().distanceSquaredTo(t) <= 8) {
            exploreIdx = (exploreIdx + 1) % exploreTargets.length;
            t = exploreTargets[exploreIdx];
        }
        return t;
    }

    static MapLocation predictEnemyLoc(RobotController rc, MapLocation ourLoc) {
        int W = rc.getMapWidth(), H = rc.getMapHeight();
        return new MapLocation(W - 1 - ourLoc.x, H - 1 - ourLoc.y);
    }

    static MapLocation findNearestPaintTower(RobotController rc) throws GameActionException {
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        MapLocation best = null;
        int bestDist = Integer.MAX_VALUE;
        for (RobotInfo r : allies) {
            UnitType t = r.getType();
            if (t == UnitType.LEVEL_ONE_PAINT_TOWER ||
                t == UnitType.LEVEL_TWO_PAINT_TOWER ||
                t == UnitType.LEVEL_THREE_PAINT_TOWER) {
                int d = rc.getLocation().distanceSquaredTo(r.getLocation());
                if (d < bestDist) { bestDist = d; best = r.getLocation(); }
            }
        }
        return best;
    }

    static MapLocation findRefillTower(RobotController rc) throws GameActionException {
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        MapLocation best = null;
        int bestDist = Integer.MAX_VALUE;
        for (RobotInfo r : allies) {
            if (!r.getType().isTowerType()) continue;
            boolean isPaint = (r.getType().getBaseType() == UnitType.LEVEL_ONE_PAINT_TOWER);
            if (isPaint && r.getPaintAmount() > 10) {
                int d = rc.getLocation().distanceSquaredTo(r.getLocation());
                if (d < bestDist) { bestDist = d; best = r.getLocation(); }
            }
        }
        if (best != null) return best;
        for (RobotInfo r : allies) {
            if (!r.getType().isTowerType()) continue;
            if (r.getPaintAmount() > 50) {
                int d = rc.getLocation().distanceSquaredTo(r.getLocation());
                if (d < bestDist) { bestDist = d; best = r.getLocation(); }
            }
        }
        if (best != null) return best;
        return cachedPaintTowerLoc;
    }

    static RobotInfo findNearestEnemyTower(RobotController rc) throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        RobotInfo best = null;
        int bestDist = Integer.MAX_VALUE;
        for (RobotInfo e : enemies) {
            if (!e.getType().isTowerType()) continue;
            int d = rc.getLocation().distanceSquaredTo(e.getLocation());
            if (d < bestDist) { bestDist = d; best = e; }
        }
        return best;
    }

    static MapInfo findBestRuin(RobotController rc) throws GameActionException {
        MapInfo[] tiles = rc.senseNearbyMapInfos(-1);
        MapInfo best = null;
        int bestDist = Integer.MAX_VALUE;
        for (MapInfo tile : tiles) {
            if (!tile.hasRuin()) continue;
            RobotInfo ex = rc.senseRobotAtLocation(tile.getMapLocation());
            if (ex != null) continue;
            int d = rc.getLocation().distanceSquaredTo(tile.getMapLocation());
            if (d < bestDist) { bestDist = d; best = tile; }
        }
        return best;
    }
    static UnitType getNewTowerType(RobotController rc) throws GameActionException {
        int n = rc.getNumberTowers();
        if (n < 4) return UnitType.LEVEL_ONE_MONEY_TOWER;

        int money = countKnownMoneyTowers();
        int paint = countKnownPaintTowers();
        if (paint == 0) return UnitType.LEVEL_ONE_PAINT_TOWER;
        double ratio = (double) money / paint;
        return ratio < 1.5 ? UnitType.LEVEL_ONE_MONEY_TOWER : UnitType.LEVEL_ONE_PAINT_TOWER;
    }

}
