package alternative_bots_1;

import battlecode.common.*;
import alternative_bots_1.*;

public class Splasher extends Unit {

    private MapLocation splashTarget = null;

    public Splasher(RobotController rc) throws GameActionException {
        super(rc);
        state = State.EXPLORE;
    }

    @Override
    public void turn() throws GameActionException {
        senseNearby();
        prevState = state;
        state = determineState();


        switch (state) {
            case REFILLING -> doRefill();
            case COMBAT    -> doCombat();
            case EXPLORE   -> doExplore();
            default        -> doExplore();
        }
    }

    private State determineState() throws GameActionException {

        if ((state != State.REFILLING && shouldRefill())
                || (state == State.REFILLING && !isFullyRefilled())) {
            refillTarget = findRefillTower();
            return State.REFILLING;
        }

        if (closestEnemyTower != null
                && closestEnemyTower.type.getBaseType() != UnitType.LEVEL_ONE_DEFENSE_TOWER
                && distTo(closestEnemyTower.location) <= 8) {
            return State.COMBAT;
        }

        return State.EXPLORE;
    }

    private void doRefill() throws GameActionException {
        if (refillTarget == null) refillTarget = findRefillTower();
        if (refillTarget == null) { if (spawnTower != null) moveTo(spawnTower); return; }
        if (!rc.getLocation().isWithinDistanceSquared(refillTarget,2)) { 
            moveTo(refillTarget);
        }
        if (tryRefill(refillTarget)) {
            if (isFullyRefilled()) { refillTarget = null; state = State.EXPLORE; }
        } else {
            refillTarget = findRefillTower();
        }
    }

    private void doCombat() throws GameActionException {
        if (closestEnemyTower == null) { state = State.EXPLORE; return; }
        MapLocation target = closestEnemyTower.location;
        if (rc.canAttack(target)) {
            rc.attack(target);
        } else {
            moveTo(target);
        }
    }

    private void doExplore() throws GameActionException {
        splashTarget = findSplashTarget();

        if (splashTarget != null && rc.isActionReady() && rc.canAttack(splashTarget)) {
            rc.attack(splashTarget);
        }

        MapLocation target = explorer.getTarget();
        moveTo(target);
    }

    private MapLocation findSplashTarget() throws GameActionException {
        MapLocation here = rc.getLocation();
        MapLocation best = null;
        int bestScore = 0;

        for (MapInfo info : mapInfo) {
            MapLocation loc = info.getMapLocation();
            if (!rc.canAttack(loc)) continue;
            if (here.distanceSquaredTo(loc) > 4) continue;

            int score = 0;
            for (MapInfo t : rc.senseNearbyMapInfos(loc, 4)) { 
                PaintType p = t.getPaint();
                if (p == PaintType.EMPTY) score += 2;
                else if (p.isEnemy()) score += 3;
            }
            if (score > bestScore) { bestScore = score; best = loc; }
        }
        return best;
    }
}