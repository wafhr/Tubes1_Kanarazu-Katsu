package alternative-bots-2;

import battlecode.common.*;

public class Soldier {

    final RobotController rc;

    static final int MODE_EXPLORE      = 0;
    static final int MODE_BUILD_RUIN   = 1;
    static final int MODE_ATTACK_TOWER = 2;
    static final int MODE_BUILD_SRP    = 3;
    static final int MODE_EXPAND_SRP   = 4;
    static final int MODE_RETREAT      = 5;

    static int         mode        = MODE_EXPLORE;
    static MapLocation modeTarget  = null;

    static MapLocation chipWaitRuin  = null;
    static int         chipWaitTurns = 0;

    static MapLocation[] srpCheckLocs = null;
    static int           srpCheckIdx  = 0;

    static MapLocation returnLoc = null;

    Soldier(RobotController rc) {
        this.rc = rc;
        Pathfinding.init(rc);
    }

    public void run() throws GameActionException {
        MapLocation myLoc    = rc.getLocation();
        int paint    = rc.getPaint();
        int paintMax = rc.getType().paintCapacity;

        Comms.parseMessages(rc);

        MapLocation nearbyPT = Utils.findNearestPaintTower(rc);
        if (nearbyPT != null) Utils.cachedPaintTowerLoc = nearbyPT;

        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo a : allies) {
            if (a.getType().isTowerType()) {
                Utils.addKnownFriendlyTower(a.getLocation(), a.getType());
            }
        }

        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        for (RobotInfo e : enemies) {
            if (e.getType().isTowerType()) {
                Utils.cachedEnemyTowerLoc  = e.getLocation();
                Utils.cachedEnemyTowerType = e.getType();
                Comms.sendToNearestTower(rc,
                    Comms.encodeLocExtra(Comms.MSG_ENEMY, e.getLocation(),
                        Comms.encodeTowerType(e.getType())));
            }
        }

        if ((double) paint / paintMax < Utils.RETREAT_THRESHOLD) {
            doRetreat(myLoc, paint, paintMax);
            return;
        }

        if (mode == MODE_EXPLORE || mode == MODE_EXPAND_SRP) {
            checkForImportantTargets();
        }

        runStateMachine(myLoc);
    }


    void doRetreat(MapLocation myLoc, int paint, int paintMax) throws GameActionException {
        mode = MODE_RETREAT;

        MapLocation pt = Utils.findRefillTower(rc);
        if (pt != null) {
            if (myLoc.distanceSquaredTo(pt) > 2) {
                Pathfinding.moveTo(rc, pt);
            }
            if (myLoc.distanceSquaredTo(pt) <= 2) {
                int need = (int)(paintMax * Utils.REFILL_FULL_RATIO) - paint;
                if (need > 0 && rc.canTransferPaint(pt, -need)) {
                    rc.transferPaint(pt, -need);
                }
                if (rc.getPaint() >= (int)(paintMax * Utils.REFILL_FULL_RATIO)) {
                    mode = MODE_EXPLORE;
                    if (returnLoc != null && rc.getLocation().distanceSquaredTo(returnLoc) > 4) {
                    } else {
                        returnLoc = null;
                    }
                }
            }
        } else {
            Pathfinding.moveTo(rc, Utils.currentExploreTarget(rc));
        }
    }


    void checkForImportantTargets() throws GameActionException {
        RobotInfo et = Utils.findNearestEnemyTower(rc);
        if (et != null && et.getType().getBaseType() != UnitType.LEVEL_ONE_DEFENSE_TOWER
                && rc.getHealth() > 40) {
            mode       = MODE_ATTACK_TOWER;
            modeTarget = et.getLocation();
            return;
        }

        MapInfo ruin = Utils.findBestRuin(rc);
        if (ruin != null && (rc.getChips() >= 700 || rc.getNumberTowers() < 5)) {
            mode       = MODE_BUILD_RUIN;
            modeTarget = ruin.getMapLocation();
            Comms.sendToNearestTower(rc,
                Comms.encodeLocation(Comms.MSG_RUIN, modeTarget));
            return;
        }

        MapLocation incompleteSRP = findIncompleteSRP();
        if (incompleteSRP != null && rc.getNumberTowers() >= 4
                && Utils.countKnownPaintTowers() > 0) {
            mode       = MODE_BUILD_SRP;
            modeTarget = incompleteSRP;
        }
    }

    MapLocation findIncompleteSRP() throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        MapLocation best  = null;
        int bestDist      = Integer.MAX_VALUE;
        boolean[][] pattern = rc.getResourcePattern();

        for (MapInfo tile : rc.senseNearbyMapInfos(-1)) {
            if (tile.getMark() != PaintType.ALLY_SECONDARY) continue;
            MapLocation center = tile.getMapLocation();
            if (!isResourcePatternCenter(center)) continue;

            if (!isPatternFullyPainted(center, pattern)) {
                int d = myLoc.distanceSquaredTo(center);
                if (d < bestDist) { bestDist = d; best = center; }
            }
        }
        return best;
    }

    void runStateMachine(MapLocation myLoc) throws GameActionException {
        switch (mode) {

            case MODE_BUILD_RUIN:
                if (!buildTower(myLoc)) {
                    mode         = MODE_EXPLORE;
                    modeTarget   = null;
                    chipWaitRuin = null;
                }
                break;

            case MODE_ATTACK_TOWER:
                RobotInfo tower = Utils.findNearestEnemyTower(rc);
                if (tower != null) towerMicro(myLoc, tower);
                else { mode = MODE_EXPLORE; modeTarget = null; }
                break;

            case MODE_BUILD_SRP:
                if (!buildSRP(myLoc)) mode = MODE_EXPAND_SRP;
                break;

            case MODE_EXPAND_SRP:
                runExpandSRP(myLoc);
                break;

            case MODE_EXPLORE:
            default:
                if (returnLoc != null) {
                    if (rc.getLocation().distanceSquaredTo(returnLoc) <= 4) {
                        returnLoc = null;
                    } else {
                        Pathfinding.bugNav(rc, returnLoc);
                        paintCurrentTile(myLoc);
                        break;
                    }
                }

                MapLocation srpCandidate = snapToSRPGrid(myLoc);
                if (srpCandidate.distanceSquaredTo(myLoc) <= 2 && canStartSRP(srpCandidate)) {
                    mode       = MODE_BUILD_SRP;
                    modeTarget = srpCandidate;
                    buildSRP(myLoc);
                    break;
                }

                MapLocation exploreTarget = Utils.currentExploreTarget(rc);
                Pathfinding.bugNav(rc, exploreTarget);
                paintCurrentTile(myLoc);
                break;
        }
    }

    boolean buildTower(MapLocation myLoc) throws GameActionException {
        MapLocation ruinLoc = modeTarget;
        if (ruinLoc == null) return false;

        if (rc.canSenseLocation(ruinLoc)) {
            RobotInfo existing = rc.senseRobotAtLocation(ruinLoc);
            if (existing != null && existing.getTeam() == rc.getTeam()) {
                Comms.sendToNearestTower(rc,
                    Comms.encodeLocExtra(Comms.MSG_TOWER, ruinLoc,
                        Comms.encodeTowerType(existing.getType())));
                return false;
            }
        }

        if (!rc.canSenseLocation(ruinLoc)) {
            Pathfinding.moveTo(rc, ruinLoc);
            return true;
        }

        if (!ensureRuinClaimed(ruinLoc)) return true;

        UnitType towerType = Utils.getNewTowerType(rc);

        if (rc.canCompleteTowerPattern(towerType, ruinLoc)) {
            rc.completeTowerPattern(towerType, ruinLoc);
            Comms.sendToNearestTower(rc,
                Comms.encodeLocExtra(Comms.MSG_TOWER, ruinLoc,
                    Comms.encodeTowerType(towerType)));
            return false;
        }

        if (rc.isMovementReady()) {
            if (myLoc.distanceSquaredTo(ruinLoc) > 8) {
                Pathfinding.bugNav(rc, ruinLoc);
            } else {
                Pathfinding.tryMoveIntoRange(rc, ruinLoc, 2);
            }
        }

        if (rc.isActionReady() && rc.getPaint() >= 5) {
            boolean[][] pattern  = rc.getTowerPattern(towerType);
            MapLocation enemyRef = resolveEnemyLocation();

            if (!trySelfPaint(myLoc, ruinLoc, pattern)) {
                paintBestPatternTile(ruinLoc, pattern, enemyRef);
            }

            if (rc.canCompleteTowerPattern(towerType, ruinLoc)) {
                rc.completeTowerPattern(towerType, ruinLoc);
                Comms.sendToNearestTower(rc,
                    Comms.encodeLocExtra(Comms.MSG_TOWER, ruinLoc,
                        Comms.encodeTowerType(towerType)));
                return false;
            }
        }

        if (myLoc.distanceSquaredTo(ruinLoc) <= 8) {
            boolean[][] pattern = rc.getTowerPattern(towerType);
            if (isPatternFullyPainted(ruinLoc, pattern)) {
                if (chipWaitRuin == null || !chipWaitRuin.equals(ruinLoc)) {
                    chipWaitRuin  = ruinLoc;
                    chipWaitTurns = 35;
                }
                chipWaitTurns--;
                if (chipWaitTurns <= 0) return false;
                return true;
            }
        }

        return true;
    }

    boolean ensureRuinClaimed(MapLocation ruinLoc) throws GameActionException {
        MapLocation northLoc = ruinLoc.add(Direction.NORTH);

        for (Direction d : Direction.values()) {
            MapLocation adj = ruinLoc.add(d);
            if (rc.canSenseLocation(adj) &&
                    rc.senseMapInfo(adj).getMark() != PaintType.EMPTY) {
                return true;
            }
        }

        if (rc.canMark(northLoc)) {
            rc.mark(northLoc, false);
            return true;
        }

        Pathfinding.moveTo(rc, ruinLoc);
        return false;
    }

    boolean trySelfPaint(MapLocation myLoc, MapLocation ruinLoc, boolean[][] pattern)
            throws GameActionException {
        int dx = myLoc.x - ruinLoc.x + 2;
        int dy = myLoc.y - ruinLoc.y + 2;
        if (dx < 0 || dx > 4 || dy < 0 || dy > 4) return false;

        boolean   wantSec   = pattern[dx][dy];
        PaintType wantPaint = wantSec ? PaintType.ALLY_SECONDARY : PaintType.ALLY_PRIMARY;
        PaintType curPaint  = rc.senseMapInfo(myLoc).getPaint();

        if (curPaint == wantPaint || curPaint.isEnemy()) return false;
        if (rc.canAttack(myLoc)) { rc.attack(myLoc, wantSec); return true; }
        return false;
    }

    void paintBestPatternTile(MapLocation ruinLoc, boolean[][] pattern, MapLocation enemyRef)
            throws GameActionException {
        MapLocation bestLoc = null;
        boolean     bestSec = false;
        int bestEnemyDist   = Integer.MAX_VALUE;
        int offset = Utils.rand() % 25;

        for (int i = 25; --i >= 0; ) {
            int dx = (i + offset) % 5;
            int dy = ((i + offset) % 25) / 5;

            MapLocation tileLoc = ruinLoc.translate(dx - 2, dy - 2);
            if (!rc.canAttack(tileLoc) || (dx == 2 && dy == 2)) continue;

            MapInfo   tileInfo  = rc.senseMapInfo(tileLoc);
            if (tileInfo.getPaint().isEnemy()) continue;

            boolean   wantSec   = pattern[dx][dy];
            PaintType wantPaint = wantSec ? PaintType.ALLY_SECONDARY : PaintType.ALLY_PRIMARY;
            if (tileInfo.getPaint() == wantPaint) continue;

            int d = tileLoc.distanceSquaredTo(enemyRef);
            if (d < bestEnemyDist) { bestEnemyDist = d; bestLoc = tileLoc; bestSec = wantSec; }
        }

        if (bestLoc != null) {
            rc.attack(bestLoc, bestSec);
        }
    }

    boolean isPatternFullyPainted(MapLocation ruinLoc, boolean[][] pattern)
            throws GameActionException {
        for (int dx = 0; dx < 5; dx++) {
            for (int dy = 0; dy < 5; dy++) {
                MapLocation loc = ruinLoc.translate(dx - 2, dy - 2);
                if (!rc.canSenseLocation(loc)) continue;
                PaintType want = pattern[dx][dy]
                    ? PaintType.ALLY_SECONDARY : PaintType.ALLY_PRIMARY;
                if (rc.senseMapInfo(loc).getPaint() != want) return false;
            }
        }
        return true;
    }

    MapLocation resolveEnemyLocation() throws GameActionException {
        if (Utils.cachedEnemyTowerLoc != null) return Utils.cachedEnemyTowerLoc;

        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo a : allies) {
            if (a.getType().isTowerType()) {
                return Utils.predictEnemyLoc(rc, a.getLocation());
            }
        }
        return new MapLocation(rc.getMapWidth() / 2, rc.getMapHeight() / 2);
    }

    boolean buildSRP(MapLocation myLoc) throws GameActionException {
        MapLocation srp = modeTarget;
        if (srp == null) { srp = myLoc; modeTarget = myLoc; }

        if (rc.canMark(srp)) rc.mark(srp, true);

        boolean[][] pattern    = rc.getResourcePattern();
        MapLocation paintedLoc = null;
        boolean isComplete     = true;

        if (rc.canSenseLocation(srp) && rc.isActionReady() && rc.getPaint() >= 5) {
            MapInfo   c       = rc.senseMapInfo(srp);
            boolean   wantSec = pattern[2][2];
            PaintType want    = wantSec ? PaintType.ALLY_SECONDARY : PaintType.ALLY_PRIMARY;
            if (c.getPaint() != want && !c.getPaint().isEnemy()) {
                isComplete = false;
                if (rc.canAttack(srp)) { rc.attack(srp, wantSec); paintedLoc = srp; }
            }
        }
        if (paintedLoc == null && rc.isActionReady() && rc.getPaint() >= 5) {
            int offset = Utils.rand() % 25;
            for (int i = 25; --i >= 0; ) {
                int dx = (i + offset) % 5;
                int dy = ((i + offset) % 25) / 5;
                MapLocation loc = srp.translate(dx - 2, dy - 2);
                if (!rc.canSenseLocation(loc)) { isComplete = false; continue; }

                MapInfo   info    = rc.senseMapInfo(loc);
                boolean   wantSec = pattern[dx][dy];
                PaintType want    = wantSec ? PaintType.ALLY_SECONDARY : PaintType.ALLY_PRIMARY;
                if (info.getPaint() == want) continue;

                isComplete = false;
                if (!info.getPaint().isEnemy() && rc.canAttack(loc)) {
                    rc.attack(loc, wantSec); paintedLoc = loc; break;
                }
            }
        }

        if (isComplete) {
            if (rc.canCompleteResourcePattern(srp)) {
                rc.completeResourcePattern(srp);
                setupSRPExpand(srp);
            }
            return false;
        }

        if (paintedLoc == null && rc.isMovementReady()) Pathfinding.moveTo(rc, srp);
        return true;
    }

    static boolean isResourcePatternCenter(MapLocation loc) {
        return ((loc.x - 2) & 3) == 0 && ((loc.y - 2) & 3) == 0;
    }

    static MapLocation snapToSRPGrid(MapLocation loc) {
        int x = loc.x - 2;
        int y = loc.y - 2;
        x = ((x + 2) >> 2) << 2;
        y = ((y + 2) >> 2) << 2;
        return new MapLocation(x + 2, y + 2);
    }

    boolean canStartSRP(MapLocation loc) throws GameActionException {
        if (!isResourcePatternCenter(loc)) return false;
        if (!rc.canSenseLocation(loc)) return false;
        if (rc.senseMapInfo(loc).getMark() != PaintType.EMPTY) return false;
        for (MapInfo t : rc.senseNearbyMapInfos(loc, 8)) {
            if (t.hasRuin()) return false;
        }
        return rc.canMarkResourcePattern(loc);
    }

    void setupSRPExpand(MapLocation srp) {
        srpCheckLocs = new MapLocation[]{
            srp.translate( 4,  0), srp.translate(-4,  0),
            srp.translate( 0,  4), srp.translate( 0, -4),
            srp.translate( 4,  4), srp.translate( 4, -4),
            srp.translate(-4,  4), srp.translate(-4, -4),
        };
        srpCheckIdx = 0;
    }

    void runExpandSRP(MapLocation myLoc) throws GameActionException {
        if (srpCheckLocs == null || srpCheckIdx >= srpCheckLocs.length) {
            mode = MODE_EXPLORE; srpCheckLocs = null; return;
        }
        MapLocation candidate = srpCheckLocs[srpCheckIdx];
        if (!rc.onTheMap(candidate)) { srpCheckIdx++; return; }

        if (myLoc.distanceSquaredTo(candidate) <= 2) {
            if (canStartSRP(candidate)) {
                mode = MODE_BUILD_SRP; modeTarget = candidate; buildSRP(myLoc);
            } else {
                srpCheckIdx++;
            }
        } else {
            Pathfinding.moveTo(rc, candidate);
        }
    }

    void towerMicro(MapLocation myLoc, RobotInfo tower) throws GameActionException {
        MapLocation tLoc = tower.getLocation();
        int actionRadius = rc.getType().actionRadiusSquared;
        int towerRange   = tower.getType().actionRadiusSquared;

        if (myLoc.distanceSquaredTo(tLoc) <= actionRadius) {
            if (rc.canAttack(tLoc)) rc.attack(tLoc);
            if (rc.isMovementReady()) {
                Pathfinding.tryMoveOutOfRange(rc, tLoc, towerRange);
            }
        } else {
            if (myLoc.distanceSquaredTo(tLoc) > 18) {
                Pathfinding.moveTo(rc, tLoc);
            }
            if (rc.isActionReady()) {
                Pathfinding.tryMoveIntoRange(rc, tLoc, actionRadius);
            }
            if (rc.canAttack(tLoc)) rc.attack(tLoc);
        }
    }

    void paintCurrentTile(MapLocation myLoc) throws GameActionException {
        try {
            MapInfo cur = rc.senseMapInfo(myLoc);
            if (!cur.getPaint().isAlly() && rc.canAttack(myLoc)) rc.attack(myLoc);
        } catch (Exception ignored) {}
    }
}