package alternative-bots-2;

import battlecode.common.*;

public class Splasher {

    final RobotController rc;

    static final int STATE_EXPLORE   = 0;
    static final int STATE_REFILLING = 1;
    static final int STATE_COMBAT    = 2;

    static int state = STATE_EXPLORE;
    static MapLocation refillTarget = null;

    Splasher(RobotController rc) {
        this.rc = rc;
        Pathfinding.init(rc);
    }

    public void run() throws GameActionException {
        MapLocation myLoc      = rc.getLocation();
        int         paintLevel = rc.getPaint();
        int         paintMax   = rc.getType().paintCapacity;

        Comms.parseMessages(rc);

        RobotInfo[] allies  = rc.senseNearbyRobots(-1, rc.getTeam());
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());

        RobotInfo closestEnemyTower = null;
        int minTowerDist = Integer.MAX_VALUE;

        for (RobotInfo a : allies) {
            if (a.getType().isTowerType())
                Utils.addKnownFriendlyTower(a.getLocation(), a.getType());
        }
        for (RobotInfo e : enemies) {
            if (e.getType().isTowerType()) {
                Utils.cachedEnemyTowerLoc = e.getLocation();
                int d = myLoc.distanceSquaredTo(e.getLocation());
                if (d < minTowerDist) { minTowerDist = d; closestEnemyTower = e; }
                Comms.sendToNearestTower(rc,
                    Comms.encodeLocExtra(Comms.MSG_ENEMY, e.getLocation(),
                        Comms.encodeTowerType(e.getType())));
            }
        }

        state = determineState(paintLevel, paintMax, closestEnemyTower);


        switch (state) {
            case STATE_REFILLING: doRefill(myLoc);                break;
            case STATE_COMBAT:    doCombat(myLoc, closestEnemyTower); break;
            case STATE_EXPLORE:   doExplore(myLoc);               break;
        }
    }

    int determineState(int paint, int paintMax, RobotInfo closestEnemyTower) {
        if (state != STATE_REFILLING && (double) paint / paintMax < Utils.RETREAT_THRESHOLD) {
            refillTarget = null;
            return STATE_REFILLING;
        }
        if (state == STATE_REFILLING && (double) paint / paintMax < Utils.REFILL_FULL_RATIO) {
            return STATE_REFILLING;
        }

        if (closestEnemyTower != null
                && closestEnemyTower.getType().getBaseType() != UnitType.LEVEL_ONE_DEFENSE_TOWER
                && rc.getLocation().distanceSquaredTo(closestEnemyTower.getLocation()) <= 8) {
            return STATE_COMBAT;
        }

        return STATE_EXPLORE;
    }

    void doRefill(MapLocation myLoc) throws GameActionException {
        if (refillTarget == null) refillTarget = Utils.findRefillTower(rc);
        if (refillTarget == null) {
            Pathfinding.moveTo(rc, Utils.currentExploreTarget(rc));
            return;
        }

        if (myLoc.distanceSquaredTo(refillTarget) > 2) {
            Pathfinding.moveTo(rc, refillTarget);
        }

        if (myLoc.distanceSquaredTo(refillTarget) <= 2) {
            int need = (int)(rc.getType().paintCapacity * Utils.REFILL_FULL_RATIO) - rc.getPaint();
            if (need > 0 && rc.canTransferPaint(refillTarget, -need)) {
                rc.transferPaint(refillTarget, -need);
            }
            if (rc.getPaint() >= rc.getType().paintCapacity * Utils.REFILL_FULL_RATIO) {
                refillTarget = null;
                state = STATE_EXPLORE;
            }
        }
    }

    void doCombat(MapLocation myLoc, RobotInfo closestEnemyTower) throws GameActionException {
        if (closestEnemyTower == null) { state = STATE_EXPLORE; return; }
        MapLocation target = closestEnemyTower.getLocation();
        if (rc.canAttack(target)) {
            rc.attack(target);
        } else {
            Pathfinding.moveTo(rc, target);
            if (rc.canAttack(target)) rc.attack(target);
        }
    }

    void doExplore(MapLocation myLoc) throws GameActionException {
        MapLocation splashTarget = findSplashTarget();

        if (splashTarget != null && rc.isActionReady() && rc.canAttack(splashTarget)) {
            rc.attack(splashTarget);
        }

        MapLocation enemyPaintArea = findAreaWithMostEnemyPaint();
        if (enemyPaintArea != null) {
            Pathfinding.safeMove(rc, enemyPaintArea, true);
            return;
        }

        if (Utils.cachedEnemyTowerLoc != null) {
            Pathfinding.moveTo(rc, Utils.cachedEnemyTowerLoc);
            return;
        }

        Pathfinding.moveTo(rc, Utils.currentExploreTarget(rc));
    }

    MapLocation findSplashTarget() throws GameActionException {
        MapInfo[] tiles = rc.senseNearbyMapInfos(-1);
        MapLocation best = null;
        int bestScore = 3;

        for (MapInfo center : tiles) {
            MapLocation cLoc = center.getMapLocation();
            if (rc.getLocation().distanceSquaredTo(cLoc) > 4) continue;
            if (!rc.canAttack(cLoc)) continue;
            if (!isSafeToSplashAt(cLoc)) continue;

            int score = 0;
            for (MapInfo t : rc.senseNearbyMapInfos(cLoc, 2)) {
                int dSq = cLoc.distanceSquaredTo(t.getMapLocation());
                PaintType p = t.getPaint();
                if (p == PaintType.EMPTY)  score += 2;
                else if (p.isEnemy())      score += (dSq <= 2) ? 4 : 3;
            }
            if (score > bestScore) { bestScore = score; best = cLoc; }
        }
        return best;
    }

    boolean isSafeToSplashAt(MapLocation center) throws GameActionException {
        int allyPaintCount = 0;
        for (MapInfo t : rc.senseNearbyMapInfos(center, 2)) {
            if (t.hasRuin()) return false;
            if (t.getPaint().isAlly()) allyPaintCount++;
        }
        if (allyPaintCount > 7) return false;

        for (RobotInfo r : rc.senseNearbyRobots(center, 2, rc.getTeam())) {
            if (r.getType().isTowerType()) return false;
        }
        return true;
    }

    MapLocation findAreaWithMostEnemyPaint() throws GameActionException {
        MapInfo[] tiles = rc.senseNearbyMapInfos(-1);
        MapLocation best = null;
        int bestCount = 2;

        for (MapInfo t : tiles) {
            if (!t.getPaint().isEnemy()) continue;
            int count = 0;
            for (MapInfo nearby : rc.senseNearbyMapInfos(t.getMapLocation(), 4)) {
                if (nearby.getPaint().isEnemy()) count++;
            }
            if (count > bestCount) { bestCount = count; best = t.getMapLocation(); }
        }
        return best;
    }

}