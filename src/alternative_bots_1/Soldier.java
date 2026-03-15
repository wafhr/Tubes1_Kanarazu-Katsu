package alternative_bots_1;

import battlecode.common.*;
import alternative_bots_1.*;

public class Soldier extends Unit {

    Direction exploreDirection;
    MapLocation exploreLocation;
    static final int MIN_EXPLORE_DIST = 10;

    static final int ATTACK_HEALTH_THRESHOLD = 40;

    boolean connected = false;
    boolean currentlyConnectedToSpawnTower = false;
    boolean shouldFillAround = true;
    boolean shouldBuildTower = false;
    int numSoldiers = 0;
    MapLocation returnLoc = null;

    public Soldier(RobotController rc) throws GameActionException {
        super(rc);
        state = State.EXPLORE;
        exploreDirection = spawnTower != null
                ? spawnTower.directionTo(rc.getLocation())
                : randomDirection();
        exploreLocation = extendLocToEdge(rc.getLocation(), exploreDirection);
    }

    @Override
    public void turn() throws GameActionException {
        if (!connected && spawnTower != null && rc.canSenseRobotAtLocation(spawnTower)) {
            currentlyConnectedToSpawnTower = rc.canSendMessage(spawnTower);
            if (currentlyConnectedToSpawnTower) connected = true;
        }

        senseNearby();
        prevState = state;
        state = determineState();

        shouldFillAround = state != State.REFILLING && state != State.COMBAT
                && (mapData.paintTowers >= 2
                    || state == State.BUILD);


        switch (state) {
            case CONNECTING -> doConnecting();
            case EXPLORE    -> doExplore();
            case COMBAT     -> doCombat();
            case REFILLING  -> doRefill();
            case BUILD      -> doBuild();
            default         -> doExplore();
        }

        stateInvariant();
    }

    private State determineState() throws GameActionException {
        if (state == State.BUILD && closestCompletableRuin != null
                && paintLeftForCompletableRuin * 5 + 1 < rc.getPaint()) {
            return State.BUILD;
        }

        if ((state != State.REFILLING && shouldRefill())
                || (state == State.REFILLING && rc.getPaint() < rc.getType().paintCapacity * 0.75)) {
            if (returnLoc == null && state != State.EXPLORE) returnLoc = rc.getLocation();
            return State.REFILLING;
        }

        if (spawnTower != null && !connected) return State.CONNECTING;

        numSoldiers = 0;
        if (closestEnemyTower != null && rc.getHealth() > ATTACK_HEALTH_THRESHOLD) {
            for (RobotInfo ally : allies) {
                if (ally.type == UnitType.SOLDIER
                        && ally.location.distanceSquaredTo(closestEnemyTower.location)
                            <= GameConstants.VISION_RADIUS_SQUARED) {
                    numSoldiers++;
                }
            }

            boolean isEnemyDefense = closestEnemyTower.type.getBaseType()
                    == UnitType.LEVEL_ONE_DEFENSE_TOWER;

            if (!isEnemyDefense) {
                return State.COMBAT;
            }

            boolean haveOwnDefense = mapData.defenseTowers >= 1;
            boolean haveSoldierSupport = numSoldiers >= Constants.SOLDIERS_FOR_DEFENSE_ATTACK;
            boolean healthOk = rc.getHealth() > Constants.SOLDIER_COMBAT_HP_VS_DEFENSE;

            if (haveOwnDefense && haveSoldierSupport && healthOk) {
                return State.COMBAT;
            }
        }

        shouldBuildTower = rc.getChips() >= 700 || rc.getNumberTowers() < 5;
        if (closestCompletableRuin != null && shouldBuildTower) return State.BUILD;

        return State.EXPLORE;
    }

    private void doConnecting() throws GameActionException {
        MapLocation loc = rc.getLocation();
        MapInfo info;
        for (int i = 3; i-- >= 0;) {
            info = mapData.getMapInfo(loc);
            if (info != null && info.getPaint() == PaintType.EMPTY) {
                tryAttackLoc(loc);
            } else if (info != null && info.getPaint().isEnemy()) {
                connected = true;
                return;
            }
            if (loc.x < spawnTower.x)      loc = loc.translate(1,  0);
            else if (loc.x > spawnTower.x) loc = loc.translate(-1, 0);
            else if (loc.y < spawnTower.y) loc = loc.translate(0,  1);
            else                            loc = loc.translate(0, -1);
        }
        connected = currentlyConnectedToSpawnTower;
    }

    private void doExplore() throws GameActionException {
        if (!rc.isMovementReady()) return;

        if (returnLoc != null) {
            if (distTo(returnLoc) <= 4) returnLoc = null;
            else { bugNav(returnLoc); return; }
        }

        if (shouldBuildTower) {
            for (MapLocation ruin : mapData.ruins) {
                if (ruin == null) continue;
                boolean hasTower = false;
                for (RobotInfo t : mapData.friendlyTowerList) {
                    if (t.location.equals(ruin)) { hasTower = true; break; }
                }
                if (hasTower) continue;
                int roundVisited = mapData.visitedRuins.getOrDefault(ruin, -100);
                if (rc.getRoundNum() - roundVisited > 50) {
                    closestCompletableRuin = ruin;
                    break;
                }
            }
        }

        MapLocation closestEmpty = null;
        int closestDist = Integer.MAX_VALUE;
        for (MapInfo info : mapInfo) {
            if (info.getPaint() == PaintType.EMPTY && !info.hasRuin() && !info.isWall()) {
                int d = distTo(info.getMapLocation());
                if (d < closestDist) { closestDist = d; closestEmpty = info.getMapLocation(); }
            }
        }
        if (closestEmpty != null && closestDist > 4 && shouldFillAround
                && rc.getNumberTowers() > 5 && rc.isActionReady()) {
            safeMove(closestEmpty, enemies, true);
        }

        if (distTo(exploreLocation) <= 8 || tooCloseToEnemyTower(exploreLocation)) {
            for (int tries = 15; --tries >= 0;) {
                exploreDirection = randomDirection();
                exploreLocation = extendInDir(rc.getLocation(), exploreDirection,
                        Math.max(MIN_EXPLORE_DIST, width / 3));
                if (mapData.getMapInfo(exploreLocation) == null) break;
            }
        }

        if (rc.isMovementReady()) {
            bugNav(exploreLocation);
        }
    }

    private void doBuild() throws GameActionException {
        MapLocation ruin = closestCompletableRuin;
        if (ruin == null) { state = State.EXPLORE; return; }

        Direction markDir = null;
        for (Direction dir : DIRECTIONS) {
            MapLocation adj = ruin.add(dir);
            if (!rc.onTheMap(adj)) continue;
            MapInfo info = mapData.getMapInfo(adj);
            if (info != null && info.getMark() == PaintType.ALLY_SECONDARY) {
                markDir = dir;
                break;
            }
        }

        if (markDir == null) {
            UnitType towerType = determineTowerPattern(ruin);
            if (rc.canSenseLocation(ruin)) {
                mapData.stampTowerPattern(ruin, towerType);
            }
            Direction markDirection = towerTypeToMarkDirection(towerType);
            MapLocation markLoc = ruin.add(markDirection);
            if (rc.canMark(markLoc)) {
                rc.mark(markLoc, true);
                markDir = markDirection;
            }
            if (rc.isMovementReady()) {
                if (distTo(ruin) <= 8) safeMove(ruin, enemies, true);
                else bugNav(ruin);
            }
        }

        if (markDir != null && rc.canSenseLocation(ruin)) {
            UnitType towerType = markDirectionToTowerType(markDir);
            mapData.stampTowerPattern(ruin, towerType);

            if (rc.isMovementReady()) {
                for (MapLocation loc : rc.getAllLocationsWithinRadiusSquared(ruin, 8)) {
                    MapInfo info = mapData.getMapInfo(loc);
                    if (info == null || info.getPaint().isAlly() || info.hasRuin()) continue;
                    if (distTo(loc) > 8)      { bugNav(loc);              break; }
                    else if (distTo(loc) > 2) { moveToward(loc);           break; }
                    else                      { tryMoveIntoRange(loc, 2); break; }
                }
            }

            if (rc.isMovementReady()) {
                if (distTo(ruin) > 8) bugNav(ruin);
                else {
                    Direction best = Direction.CENTER;
                    int bestScore = distTo(ruin) <= 2 ? tileScore(rc.getLocation(), false) : -9999;
                    for (Direction d : nearbyDirs(dirTo(ruin))) {
                        MapLocation next = rc.getLocation().add(d);
                        if (next.isWithinDistanceSquared(ruin, 2) && rc.canMove(d)) {
                            int score = tileScore(next, false);
                            if (score >= bestScore) { best = d; bestScore = score; }
                        }
                    }
                    if (best != Direction.CENTER) rc.move(best);
                    else if (rc.isMovementReady()) moveToward(ruin);
                }
            }
        }
    }

    private void doRefill() throws GameActionException {
        MapLocation targetTower = null;
        int minDist = Integer.MAX_VALUE;
        for (RobotInfo t : mapData.friendlyTowerList) {
            boolean isPaint = t.type.getBaseType() == UnitType.LEVEL_ONE_PAINT_TOWER;
            if (!isPaint && t.paintAmount < 100) continue;
            int d = distTo(t.location);
            if (d < minDist) { minDist = d; targetTower = t.location; }
        }

        if (targetTower == null) {
            if (spawnTower != null) bugNav(spawnTower);
            return;
        }

        if (distTo(targetTower) > 2) {
            tryMoveIntoRange(targetTower, 2);
        }
        if (distTo(targetTower) <= 2) {
            if (rc.canTransferPaint(targetTower, -Math.min(
                    (int)(rc.getType().paintCapacity * 0.75) - rc.getPaint(),
                    rc.senseRobotAtLocation(targetTower) != null
                        ? rc.senseRobotAtLocation(targetTower).paintAmount : 0))) {
                rc.transferPaint(targetTower, -Math.min(
                        (int)(rc.getType().paintCapacity * 0.75) - rc.getPaint(),
                        rc.senseRobotAtLocation(targetTower) != null
                            ? rc.senseRobotAtLocation(targetTower).paintAmount : 0));
            }
            if (rc.getPaint() >= rc.getType().paintCapacity * 0.75) {
                state = State.EXPLORE;
                returnLoc = null;
            }
        } else if (distTo(targetTower) > 8) {
            bugNav(targetTower);
        }
    }

    private void doCombat() throws GameActionException {
        if (closestEnemyTower == null) { state = State.EXPLORE; return; }
        MapLocation towerLoc = closestEnemyTower.location;

        if (distTo(towerLoc) <= rc.getType().actionRadiusSquared) {
            tryAttackLoc(towerLoc);
            if (!tryMoveOutOfRange(towerLoc, closestEnemyTower.type.actionRadiusSquared)) {
                moveToward(dirTo(towerLoc).opposite());
            }
        } else {
            if (distTo(towerLoc) > 18) moveToward(towerLoc);
            if ((rc.getRoundNum() % 2 == 0 || numSoldiers == 0
                    || rc.getPaint() < rc.getType().paintCapacity * 0.5)
                    && rc.isActionReady()) {
                if (!tryMoveIntoRange(towerLoc, rc.getType().actionRadiusSquared)) {
                    moveToward(towerLoc);
                }
            }
            tryAttackLoc(towerLoc);
        }
    }

    private void stateInvariant() throws GameActionException {
        if (closestCompletableRuin != null) {
            for (RobotInfo ally : allies) {
                if (ally.type == UnitType.MOPPER
                        && ally.location.distanceSquaredTo(closestCompletableRuin) <= 8) {
                    checkAndPaintTile(ally.location);
                }
            }
        }

        if (closestAnyRuin != null) {
            for (MapLocation loc : mapLocationSpiral(closestAnyRuin, 2)) {
                if (Clock.getBytecodesLeft() < 1500) break;
                if (checkAndPaintTile(loc)) break;
            }
        }

        if (shouldFillAround) {
            if (!checkAndPaintTile(rc.getLocation()) && rc.isActionReady()) {
                if (closestCompletableRuin != null) {
                    for (MapLocation loc : mapLocationSpiral(closestCompletableRuin, 2)) {
                        if (Clock.getBytecodesLeft() < 1000) break;
                        if (checkAndPaintTile(loc)) break;
                    }
                }
                if (closestCompletableRuin == null || distTo(closestCompletableRuin) > 8) {
                    for (MapLocation loc : mapLocationSpiral(rc.getLocation(), 3)) {
                        if (Clock.getBytecodesLeft() < 1000) break;
                        if (checkAndPaintTile(loc)) break;
                    }
                }
            }
        }

        if (closestAnyRuin != null) {
            for (UnitType towerType : TOWER_TYPES) {
                if (rc.canCompleteTowerPattern(towerType, closestAnyRuin)
                        && (rc.getChips() >= towerType.moneyCost
                            || mapData.paintTowers == 0)) {
                    rc.completeTowerPattern(towerType, closestAnyRuin);
                    comms.sendToNearestTower(comms.encodeLocExtra(
                        Constants.MSG_TOWER, closestAnyRuin,
                        Comms.encodeTowerType(towerType)));
                    rc.setTimelineMarker("Tower built!", 0, 255, 0);
                    state = State.EXPLORE;
                    break;
                }
            }
        }
    }

    private MapLocation extendInDir(MapLocation start, Direction dir, int steps) {
        int i = 0;
        while (rc.onTheMap(start.add(dir)) && i <= steps) {
            start = start.add(dir);
            i++;
        }
        return start;
    }

    private MapLocation extendLocToEdge(MapLocation loc, Direction dir) {
        while (rc.onTheMap(loc.add(dir))) loc = loc.add(dir);
        return loc;
    }

    private boolean tryAttackLoc(MapLocation loc) throws GameActionException {
        if (rc.canAttack(loc)) { rc.attack(loc); return true; }
        return false;
    }
}