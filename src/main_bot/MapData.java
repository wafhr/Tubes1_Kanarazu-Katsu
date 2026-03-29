package main_bot;

import battlecode.common.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

public class MapData {

    private final RobotController rc;
    public final int width;
    public final int height;

    public final MapInfo[][] mapInfos;
    public final int[][] expectedPaint;

    public final ArrayList<MapLocation> ruins = new ArrayList<>();
    private final HashSet<MapLocation> ruinsSet = new HashSet<>();
    public final HashMap<MapLocation, Integer> visitedRuins = new HashMap<>();
    private final HashSet<MapLocation> builtRuins = new HashSet<>();
    public final ArrayList<RobotInfo> friendlyTowerList = new ArrayList<>();
    private final HashMap<MapLocation, Integer> friendlyTowerIdx = new HashMap<>();

    public int paintTowers   = 0;
    public int moneyTowers   = 0;
    public int defenseTowers = 0;

    public final ArrayList<RobotInfo> enemyTowerList = new ArrayList<>();
    private final HashMap<MapLocation, Integer> enemyTowerIdx = new HashMap<>();

    public MapData(RobotController rc, int width, int height) {
        this.rc     = rc;
        this.width  = width;
        this.height = height;
        mapInfos      = new MapInfo[width][height];
        expectedPaint = new int[width][height];
    }

    public void update(MapInfo[] visible) throws GameActionException {
        for (int i = visible.length; --i >= 0;) {
            MapInfo info = visible[i];
            MapLocation loc = info.getMapLocation();
            mapInfos[loc.x][loc.y] = info;
        }
    }

    public MapInfo getMapInfo(MapLocation loc) {
        if (loc.x < 0 || loc.x >= width || loc.y < 0 || loc.y >= height) return null;
        return mapInfos[loc.x][loc.y];
    }

    public boolean addRuin(MapLocation ruin) {
        if (ruinsSet.add(ruin)) {
            ruins.add(ruin);
            return true;
        }
        return false;
    }

    public void markBuilt(MapLocation ruin) {
        builtRuins.add(ruin);
    }

    public boolean isBuilt(MapLocation ruin) {
        return builtRuins.contains(ruin);
    }

    public void stampTowerPattern(MapLocation ruinCenter, UnitType towerType)
            throws GameActionException {
        boolean[][] pattern = rc.getTowerPattern(towerType);
        for (int dx = -2; dx <= 2; dx++) {
            int px = ruinCenter.x + dx;
            if (px < 0 || px >= width) continue;
            for (int dy = -2; dy <= 2; dy++) {
                int py = ruinCenter.y + dy;
                if (py < 0 || py >= height) continue;
                expectedPaint[px][py] = pattern[dx + 2][dy + 2] ? 2 : 1;
            }
        }
    }

    public PaintType getExpectedPaint(int x, int y) {
        return switch (expectedPaint[x][y]) {
            case 1  -> PaintType.ALLY_PRIMARY;
            case 2  -> PaintType.ALLY_SECONDARY;
            default -> PaintType.EMPTY;
        };
    }

    public PaintType getExpectedPaint(MapLocation loc) {
        return getExpectedPaint(loc.x, loc.y);
    }

    public void addFriendlyTower(RobotInfo tower) {
        Integer idx = friendlyTowerIdx.get(tower.location);
        if (idx != null) {
            decrementCount(friendlyTowerList.get(idx).type.getBaseType());
            friendlyTowerList.set(idx, tower);
        } else {
            friendlyTowerIdx.put(tower.location, friendlyTowerList.size());
            friendlyTowerList.add(tower);
        }
        incrementCount(tower.type.getBaseType());
        markBuilt(tower.location);
    }

    public MapLocation nearestPaintTower(MapLocation from) {
        MapLocation best = null;
        int bestDist = Integer.MAX_VALUE;
        for (int i = friendlyTowerList.size(); --i >= 0;) {
            RobotInfo t = friendlyTowerList.get(i);
            if (t.type.getBaseType() != UnitType.LEVEL_ONE_PAINT_TOWER) continue;
            int d = from.distanceSquaredTo(t.location);
            if (d < bestDist) { bestDist = d; best = t.location; }
        }
        return best;
    }

    public MapLocation nearestAnyTower(MapLocation from) {
        MapLocation best = null;
        int bestDist = Integer.MAX_VALUE;
        for (int i = friendlyTowerList.size(); --i >= 0;) {
            RobotInfo t = friendlyTowerList.get(i);
            int d = from.distanceSquaredTo(t.location);
            if (d < bestDist) { bestDist = d; best = t.location; }
        }
        return best;
    }

    private void incrementCount(UnitType base) {
        if      (base == UnitType.LEVEL_ONE_PAINT_TOWER)   paintTowers++;
        else if (base == UnitType.LEVEL_ONE_MONEY_TOWER)   moneyTowers++;
        else if (base == UnitType.LEVEL_ONE_DEFENSE_TOWER) defenseTowers++;
    }

    private void decrementCount(UnitType base) {
        if      (base == UnitType.LEVEL_ONE_PAINT_TOWER)   { if (paintTowers   > 0) paintTowers--;   }
        else if (base == UnitType.LEVEL_ONE_MONEY_TOWER)   { if (moneyTowers   > 0) moneyTowers--;   }
        else if (base == UnitType.LEVEL_ONE_DEFENSE_TOWER) { if (defenseTowers > 0) defenseTowers--; }
    }

    public void addEnemyTower(RobotInfo tower) {
        Integer idx = enemyTowerIdx.get(tower.location);
        if (idx != null) {
            enemyTowerList.set(idx, tower);
        } else {
            enemyTowerIdx.put(tower.location, enemyTowerList.size());
            enemyTowerList.add(tower);
        }
    }

}