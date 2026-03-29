package main_bot;

import battlecode.common.*;
import main_bot.*;

public abstract class Unit extends Robot {

    protected enum State {
        EXPLORE,          
        REFILLING,        
        BUILD,            
        COMBAT,           
        CONNECTING,       
        MOPPING,          
    }

    protected MapLocation closestAnyRuin = null;
    protected int paintLeftForCompletableRuin = 0;

    private MapLocation[] prevPositions = new MapLocation[8];
    private int prevPosIdx = 0;
    private MapLocation lastSafeMoveTarget = null;

    protected State state     = State.EXPLORE;
    protected State prevState = State.EXPLORE;

    protected MapInfo[]   mapInfo  = null;
    protected RobotInfo[] allies   = null;
    protected RobotInfo[] enemies  = null;

    protected RobotInfo closestEnemyTower = null;
    protected MapLocation closestCompletableRuin = null;

    protected MapLocation refillTarget = null;
    protected MapLocation spawnTower = null;
    protected boolean connectedToTower = false;

    private static final int BUG_STACK_SIZE = 64;
    private Direction[] bugStack    = new Direction[BUG_STACK_SIZE];
    private int         bugStackIdx = 0;
    private MapLocation bugLastTarget = null;
    private MapLocation bugLastLoc    = null;
    private MapLocation bugCurLoc     = null;

    public Unit(RobotController rc) throws GameActionException {
        super(rc);

        for (RobotInfo r : rc.senseNearbyRobots(GameConstants.BUILD_ROBOT_RADIUS_SQUARED, rc.getTeam())) {
            if (r.type.isTowerType()) {
                spawnTower = r.location;
                break;
            }
        }

        if (rc.getType() == UnitType.SOLDIER) {
            MapInfo here = rc.senseMapInfo(rc.getLocation());
            if (here.getPaint() == PaintType.EMPTY && rc.canAttack(rc.getLocation())) {
                rc.attack(rc.getLocation());
            }
        }
    }

    protected void senseNearby() throws GameActionException {
        mapInfo = rc.senseNearbyMapInfos();
        mapData.update(mapInfo);
        comms.parseMessages();

        MapLocation[] visibleRuins = rc.senseNearbyRuins(-1);
        for (MapLocation ruin : visibleRuins) {
            mapData.addRuin(ruin);
            if (mapData.friendlyTowerList.size() > 0) {
                comms.sendToNearestTower(
                    comms.encodeLocation(Constants.MSG_RUIN, ruin));
            }
        }

        allies  = rc.senseNearbyRobots(-1, rc.getTeam());
        enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());

        closestEnemyTower = null;
        int minTowerDist = Integer.MAX_VALUE;
        for (RobotInfo e : enemies) {
            if (e.type.isTowerType()) {
                mapData.addEnemyTower(e);
                int d = distTo(e.location);
                if (d < minTowerDist) { minTowerDist = d; closestEnemyTower = e; }
            }
        }
        for (RobotInfo a : allies) {
            if (a.type.isTowerType()) {
                mapData.addFriendlyTower(a);
            }
        }

        if (!connectedToTower && spawnTower != null
                && rc.canSenseRobotAtLocation(spawnTower)
                && rc.canSendMessage(spawnTower)) {
            connectedToTower = true;
        }

        sendMapInfoToTower();

        closestCompletableRuin = findClosestCompletableRuin(visibleRuins);

        closestAnyRuin = null;
        int minRuinDist = Integer.MAX_VALUE;
        for (MapLocation ruin : visibleRuins) {
            int d = distTo(ruin);
            if (d < minRuinDist) { minRuinDist = d; closestAnyRuin = ruin; }
        }
    }

    private int towerBroadcastIdx = 0;

    protected void sendMapInfoToTower() throws GameActionException {
        for (RobotInfo ally : allies) {
            if (!ally.type.isTowerType()) continue;
            if (!rc.canSendMessage(ally.location)) continue;

            int len = mapData.friendlyTowerList.size();
            if (len > 0) {
                RobotInfo relay = mapData.friendlyTowerList.get(towerBroadcastIdx % len);
                if (relay != null) {
                    rc.sendMessage(ally.location,
                        comms.encodeLocExtra(
                            Constants.MSG_TOWER,
                            relay.location,
                            Comms.encodeTowerType(relay.type)
                        )
                    );
                }
                towerBroadcastIdx++;
            }
            break;
        }
    }

    protected boolean shouldRefill() {
        return rc.getPaint() <= rc.getType().paintCapacity * Constants.REFILL_THRESHOLD_RATIO;
    }

    protected boolean isFullyRefilled() {
        return rc.getPaint() >= rc.getType().paintCapacity * Constants.REFILL_FULL_RATIO;
    }

    protected MapLocation findRefillTower() {
        MapLocation best = null;
        int bestDist = Integer.MAX_VALUE;
        for (RobotInfo ally : allies) {
            if (!ally.type.isTowerType()) continue;
            boolean isPaint = ally.type.getBaseType() == UnitType.LEVEL_ONE_PAINT_TOWER;
            if ((isPaint && ally.getPaintAmount() > 10) ||
                (!isPaint && ally.getPaintAmount() > 50)) {
                int d = distTo(ally.location);
                if (d < bestDist) { bestDist = d; best = ally.location; }
            }
        }
        if (best != null) return best;

        return mapData.nearestPaintTower(rc.getLocation());
    }

    protected boolean tryRefill(MapLocation towerLoc) throws GameActionException {
        if (towerLoc == null) return false;
        if (!rc.getLocation().isWithinDistanceSquared(towerLoc,
                2)) return false;

        RobotInfo tower = rc.canSenseRobotAtLocation(towerLoc)
                ? rc.senseRobotAtLocation(towerLoc) : null;
        if (tower == null) return false;

        int needed = rc.getType().paintCapacity - rc.getPaint();
        int available = tower.getPaintAmount();
        int amount = Math.min(needed, available);
        if (amount <= 0) return false;

        if (rc.canTransferPaint(towerLoc, -amount)) {
            rc.transferPaint(towerLoc, -amount);
            return true;
        }
        return false;
    }

    protected MapLocation findClosestCompletableRuin(MapLocation[] visibleRuins)
            throws GameActionException {
        if (rc.getType() != UnitType.SOLDIER) return null;

        MapLocation best     = null;
        int         bestScore = Integer.MIN_VALUE;

        for (MapLocation ruin : visibleRuins) {
            if (ruin == null) continue;
            if (rc.canSenseRobotAtLocation(ruin)) continue;
            if (mapData.isBuilt(ruin)) continue;

            boolean enemyBlocking = false;
            boolean haveMopperNearby = false;
            for (RobotInfo ally : allies) {
                if (ally.type == UnitType.MOPPER &&
                        ally.location.distanceSquaredTo(ruin) <= 8) {
                    haveMopperNearby = true;
                    break;
                }
            }

            int correctTiles = 0;
            int totalTiles   = 0;
            for (int dx = -2; dx <= 2 && !enemyBlocking; dx++) {
                for (int dy = -2; dy <= 2; dy++) {
                    MapLocation tile = ruin.translate(dx, dy);
                    if (!rc.onTheMap(tile)) continue;
                    MapInfo info = mapData.getMapInfo(tile);
                    if (info == null) continue;
                    totalTiles++;
                    if (info.getPaint().isEnemy()) {
                        if (!haveMopperNearby) {
                            enemyBlocking = true;
                            needMopperAt = tile;
                            break;
                        }
                    }
                    PaintType expected = mapData.getExpectedPaint(tile);
                    PaintType actual   = info.getPaint();
                    if ((expected == PaintType.ALLY_PRIMARY   && actual == PaintType.ALLY_PRIMARY)
                     || (expected == PaintType.ALLY_SECONDARY && actual == PaintType.ALLY_SECONDARY)) {
                        correctTiles++;
                    }
                }
            }
            if (enemyBlocking) continue;

            int score = correctTiles * 100 - distTo(ruin);
            if (score > bestScore) {
                bestScore = score;
                best = ruin;
            }
        }

        return best;
    }

    protected int tileScore(MapLocation loc) throws GameActionException {
        if (!rc.onTheMap(loc)) return -9999;

        int score = 0;

        if (inEnemyTowerRange(loc)) score -= 100;

        MapInfo info = mapData.getMapInfo(loc);
        if (info == null) return score - 1;

        if (!info.isPassable()) return -9999;

        PaintType paint = info.getPaint();
        if (paint.isAlly())     score += 0;
        else if (paint == PaintType.EMPTY) score -= 1;
        else                    score -= 2;

        return score;
    }

    protected boolean safeMove(MapLocation target) throws GameActionException {
        return safeMove(dirTo(target));
    }

    protected boolean safeMove(Direction preferred) throws GameActionException {
        if (!rc.isMovementReady()) return false;

        Direction bestDir  = null;
        int       bestScore = Integer.MIN_VALUE;
        int deviation = 0;

        for (Direction d : nearbyDirs(preferred)) {
            if (!rc.canMove(d)) { deviation++; continue; }
            MapLocation next = rc.getLocation().add(d);
            int score = tileScore(next) - deviation;
            if (score > bestScore) { bestScore = score; bestDir = d; }
            deviation++;
        }

        if (bestDir != null && bestScore > -50) {
            rc.move(bestDir);
            return true;
        }
        return false;
    }

    protected void bugNav(MapLocation target) throws GameActionException {
        if (!rc.isMovementReady()) return;

        bugLastLoc = bugCurLoc;
        bugCurLoc  = rc.getLocation();

        if (bugLastTarget == null
                || bugLastTarget.distanceSquaredTo(target) > 8
                || bugStackIdx >= BUG_STACK_SIZE - 10) {
            resetBugNav(target);
        } else {
            bugLastTarget = target;
        }
        while (bugStackIdx > 0) {
            Direction top = bugStack[bugStackIdx - 1];
            MapLocation next = rc.getLocation().add(top);
            boolean passable = rc.canMove(top) && !inEnemyTowerRange(next);
            if (passable) {
                bugStackIdx--;
            } else {
                break;
            }
        }

        if (bugStackIdx == 0) {
            Direction dirToTarget = dirTo(target);
            Direction best = null;
            int bestScore  = -9999;

            for (Direction d : new Direction[]{dirToTarget,
                    dirToTarget.rotateLeft(), dirToTarget.rotateRight()}) {
                if (!rc.canMove(d)) continue;
                MapLocation next = rc.getLocation().add(d);
                if (next.equals(bugLastLoc)) continue;
                if (inEnemyTowerRange(next)) continue;
                int score = tileScore(next);
                if (score > bestScore) { bestScore = score; best = d; }
            }

            if (best != null && bestScore > -20) {
                rc.move(best);
                return;
            }

            Direction wallDir = BUG_RIGHT
                    ? dirToTarget.rotateLeft()
                    : dirToTarget.rotateRight();
            bugStack[bugStackIdx++] = wallDir;
        }

        Direction wallDir = bugStack[bugStackIdx - 1];
        Direction tryDir  = BUG_RIGHT
                ? wallDir.rotateRight()
                : wallDir.rotateLeft();

        for (int i = 0; i < 8; i++) {
            MapLocation next = rc.getLocation().add(tryDir);
            if (!rc.onTheMap(next)) {
                resetBugNav(target);
                safeMove(target);
                return;
            }
            if (rc.canMove(tryDir) && !inEnemyTowerRange(next)) {
                rc.move(tryDir);
                return;
            }
            bugStack[bugStackIdx++] = tryDir;
            tryDir = BUG_RIGHT ? tryDir.rotateRight() : tryDir.rotateLeft();
        }
    }

    private void resetBugNav(MapLocation target) {
        bugStack    = new Direction[BUG_STACK_SIZE];
        bugStackIdx = 0;
        bugLastTarget = target;
        bugLastLoc    = rc.getLocation();
    }

    protected void moveTo(MapLocation target) throws GameActionException {
        if (!rc.isMovementReady()) return;
        bugNav(target);
    }

    protected boolean checkAndPaintTile(MapLocation loc) throws GameActionException {
        if (!rc.isActionReady()) return false;
        if (!rc.onTheMap(loc)) return false;
        if (rc.getLocation().distanceSquaredTo(loc) > rc.getType().actionRadiusSquared) return false;
        if (!rc.canSenseLocation(loc)) return false;

        MapInfo info = mapData.getMapInfo(loc);
        if (info == null) return false;
        if (!info.isPassable() || info.hasRuin()) return false;
        if (info.getPaint().isEnemy()) return false;

        int targetColorInt = mapData.expectedPaint[loc.x][loc.y] - 1;
        boolean targetColor = (targetColorInt == 1);

        boolean needsPaint = false;
        if (info.getPaint() == PaintType.EMPTY) {
            needsPaint = true;
        } else if (targetColorInt != -1) {
            PaintType desired = targetColor ? PaintType.ALLY_SECONDARY : PaintType.ALLY_PRIMARY;
            needsPaint = (info.getPaint() != desired);
        }

        if (needsPaint && rc.canAttack(loc)) {
            rc.attack(loc, targetColor);
            return true;
        }
        return false;
    }

    protected void moveToward(MapLocation loc) throws GameActionException {
        if (!rc.isMovementReady()) return;
        for (Direction d : nearbyDirs(dirTo(loc))) {
            if (rc.canMove(d)) { rc.move(d); return; }
        }
    }

    protected void moveToward(Direction dir) throws GameActionException {
        if (!rc.isMovementReady()) return;
        for (Direction d : nearbyDirs(dir)) {
            if (rc.canMove(d)) { rc.move(d); return; }
        }
    }

    protected int tileScore(MapLocation loc, boolean ignoreEmpty) throws GameActionException {
        if (!rc.onTheMap(loc)) return -9999;
        MapInfo info = mapData.getMapInfo(loc);
        if (info != null && !info.isPassable()) return -9999;
        int score = 0;
        if (tooCloseToEnemyTower(loc)) score -= 20;
        if (info == null) return score - (ignoreEmpty ? 0 : 1);
        if (info.getPaint().isAlly())         score -= 0;
        else if (info.getPaint() == PaintType.EMPTY) score -= (ignoreEmpty ? 0 : 1);
        else                                  score -= 2;
        return score;
    }

    protected boolean tryMoveIntoRange(MapLocation target, int distSq) throws GameActionException {
        if (!rc.isMovementReady()) return false;
        Direction best = Direction.CENTER;
        int bestScore = distTo(target) <= distSq ? tileScore(rc.getLocation(), false) : -9999;
        for (Direction d : nearbyDirs(dirTo(target))) {
            MapLocation next = rc.getLocation().add(d);
            if (next.isWithinDistanceSquared(target, distSq) && rc.canMove(d)) {
                int score = tileScore(next, false);
                if (score >= bestScore) { best = d; bestScore = score; }
            }
        }
        if (best != Direction.CENTER && bestScore > -1000) { rc.move(best); return true; }
        return distTo(target) <= distSq;
    }

    protected boolean tryMoveOutOfRange(MapLocation avoidLoc, int distSq) throws GameActionException {
        if (!rc.isMovementReady()) return false;
        Direction best = null;
        int bestScore = -9999;
        for (Direction d : nearbyDirs(dirTo(avoidLoc).opposite())) {
            MapLocation next = rc.getLocation().add(d);
            if (!next.isWithinDistanceSquared(avoidLoc, distSq) && rc.canMove(d)) {
                int score = tileScore(next, false);
                if (score > bestScore) { best = d; bestScore = score; }
            }
        }
        if (best != null) { rc.move(best); return true; }
        return false;
    }

    protected boolean tooCloseToEnemyTower(MapLocation loc) {
        for (int i = mapData.enemyTowerList.size(); --i >= 0;) {
            RobotInfo t = mapData.enemyTowerList.get(i);
            if (t != null && loc.isWithinDistanceSquared(t.location, t.type.actionRadiusSquared))
                return true;
        }
        return false;
    }

    protected boolean safeMove(MapLocation loc, RobotInfo[] enemies, boolean ignoreEmpty)
            throws GameActionException {
        if (!rc.isMovementReady()) return false;
        if (lastSafeMoveTarget == null || lastSafeMoveTarget.distanceSquaredTo(loc) > 4) {
            prevPositions = new MapLocation[8];
            prevPosIdx = 0;
        }
        lastSafeMoveTarget = loc;
        return safeMoveDir(dirTo(loc), enemies, ignoreEmpty);
    }

    protected boolean safeMove(Direction dir, RobotInfo[] enemies, boolean ignoreEmpty)
            throws GameActionException {
        if (!rc.isMovementReady()) return false;
        return safeMoveDir(dir, enemies, ignoreEmpty);
    }

    private boolean safeMoveDir(Direction dir, RobotInfo[] enemies, boolean ignoreEmpty)
            throws GameActionException {
        Direction bestDir = null;
        int bestScore = -1000;
        int deviation = 0;
        for (Direction d : nearbyDirs(dir)) {
            deviation++;
            MapLocation next = rc.getLocation().add(d);
            if (!rc.canMove(d)) continue;
            if (isPrevPosition(next)) continue;
            int score = tileScore(next, ignoreEmpty, true) - deviation / 2;
            if (score > bestScore) { bestDir = d; bestScore = score; }
        }
        if (bestDir != null) {
            rc.move(bestDir);
            prevPositions[prevPosIdx] = rc.getLocation();
            prevPosIdx = (prevPosIdx + 1) % prevPositions.length;
            return true;
        }
        prevPositions[prevPosIdx] = rc.getLocation();
        prevPosIdx = (prevPosIdx + 1) % prevPositions.length;
        return false;
    }

    private int tileScore(MapLocation loc, boolean ignoreEmpty, boolean checkAllyBehind)
            throws GameActionException {
        if (!rc.onTheMap(loc)) return -9999;
        MapInfo info = mapData.getMapInfo(loc);
        if (info != null && !info.isPassable()) return -9999;
        int score = 0;
        if (tooCloseToEnemyTower(loc)) score -= 20;
        if (info == null) return score - (ignoreEmpty ? 0 : 1);
        if (info.getPaint().isAlly()) return score;
        if (info.getPaint() == PaintType.EMPTY) {
            if (!ignoreEmpty) score -= 1;
        } else {
            score -= 2;
        }
        return score;
    }

    private boolean isPrevPosition(MapLocation loc) {
        for (MapLocation p : prevPositions) {
            if (p != null && p.equals(loc)) return true;
        }
        return false;
    }

    protected MapLocation[] mapLocationSpiral(MapLocation loc, int radius) {
        int x = loc.x, y = loc.y;
        if (radius == 2) {
            return new MapLocation[]{
                new MapLocation(x,y),
                new MapLocation(x-1,y+1),new MapLocation(x,y+1),new MapLocation(x+1,y+1),
                new MapLocation(x+1,y),new MapLocation(x+1,y-1),new MapLocation(x,y-1),
                new MapLocation(x-1,y-1),new MapLocation(x-1,y),
                new MapLocation(x-2,y+2),new MapLocation(x-1,y+2),new MapLocation(x,y+2),
                new MapLocation(x+1,y+2),new MapLocation(x+2,y+2),new MapLocation(x+2,y+1),
                new MapLocation(x+2,y),new MapLocation(x+2,y-1),new MapLocation(x+2,y-2),
                new MapLocation(x+1,y-2),new MapLocation(x,y-2),new MapLocation(x-1,y-2),
                new MapLocation(x-2,y-2),new MapLocation(x-2,y-1),new MapLocation(x-2,y),
                new MapLocation(x-2,y+1),
            };
        }
        return new MapLocation[]{
            new MapLocation(x,y),
            new MapLocation(x-1,y+1),new MapLocation(x,y+1),new MapLocation(x+1,y+1),
            new MapLocation(x+1,y),new MapLocation(x+1,y-1),new MapLocation(x,y-1),
            new MapLocation(x-1,y-1),new MapLocation(x-1,y),
            new MapLocation(x-2,y+2),new MapLocation(x-1,y+2),new MapLocation(x,y+2),
            new MapLocation(x+1,y+2),new MapLocation(x+2,y+2),new MapLocation(x+2,y+1),
            new MapLocation(x+2,y),new MapLocation(x+2,y-1),new MapLocation(x+2,y-2),
            new MapLocation(x+1,y-2),new MapLocation(x,y-2),new MapLocation(x-1,y-2),
            new MapLocation(x-2,y-2),new MapLocation(x-2,y-1),new MapLocation(x-2,y),
            new MapLocation(x-2,y+1),
            new MapLocation(x-3,y+3),new MapLocation(x-2,y+3),new MapLocation(x-1,y+3),
            new MapLocation(x,y+3),new MapLocation(x+1,y+3),new MapLocation(x+2,y+3),
            new MapLocation(x+3,y+3),
            new MapLocation(x+3,y+2),new MapLocation(x+3,y+1),new MapLocation(x+3,y),
            new MapLocation(x+3,y-1),new MapLocation(x+3,y-2),new MapLocation(x+3,y-3),
            new MapLocation(x+2,y-3),new MapLocation(x+1,y-3),new MapLocation(x,y-3),
            new MapLocation(x-1,y-3),new MapLocation(x-2,y-3),new MapLocation(x-3,y-3),
            new MapLocation(x-3,y-2),new MapLocation(x-3,y-1),new MapLocation(x-3,y),
            new MapLocation(x-3,y+1),new MapLocation(x-3,y+2)
        };
    }

}