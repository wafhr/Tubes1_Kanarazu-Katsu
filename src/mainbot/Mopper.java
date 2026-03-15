package mainbot;

import battlecode.common.*;
import mainbot.*;


public class Mopper extends Unit {

    private MapLocation targetEnemyPaint = null;

    private RobotInfo   lowPaintAlly = null;

    public Mopper(RobotController rc) throws GameActionException {
        super(rc);
        state = State.EXPLORE;
    }

    @Override
    public void turn() throws GameActionException {
        senseNearby();
        prevState = state;
        state = determineState();

        switch (state) {
            case REFILLING  -> doRefill();
            case MOPPING    -> doMopping();
            case EXPLORE    -> doExplore();
            default         -> doExplore();
        }
    }

    private State determineState() throws GameActionException {

        if ((state != State.REFILLING && shouldRefill())
                || (state == State.REFILLING && !isFullyRefilled())) {
            refillTarget = findRefillTower();
            return State.REFILLING;
        }

        lowPaintAlly = null;
        for (RobotInfo ally : allies) {
            if (ally.type.isTowerType()) continue;
            if (ally.getPaintAmount() < 15 && rc.getPaint() > 30) {
                lowPaintAlly = ally;
                break;
            }
        }

        targetEnemyPaint = findEnemyPaintTarget();

        if (needMopperAt != null) {
            targetEnemyPaint = needMopperAt;
            return State.MOPPING;
        }

        if (targetEnemyPaint != null) return State.MOPPING;

        return State.EXPLORE;
    }

    private void doRefill() throws GameActionException {
        if (refillTarget == null) refillTarget = findRefillTower();
        if (refillTarget == null) {
            if (spawnTower != null) moveTo(spawnTower);
            return;
        }

        if (!rc.getLocation().isWithinDistanceSquared(refillTarget,2)) { 
            moveTo(refillTarget);
        }

        if (tryRefill(refillTarget)) {
            if (isFullyRefilled()) {
                refillTarget = null;
                state = State.EXPLORE;
            }
        } else {
            refillTarget = findRefillTower();
        }
    }

    private void doMopping() throws GameActionException {
        if (lowPaintAlly != null
                && rc.getLocation().isWithinDistanceSquared(lowPaintAlly.location, 2)
                && rc.isActionReady()) {
            int give = Math.min(rc.getPaint() - 20, 30);
            if (give > 0 && rc.canTransferPaint(lowPaintAlly.location, give)) {
                rc.transferPaint(lowPaintAlly.location, give);
                return;
            }
        }

        if (targetEnemyPaint == null) { state = State.EXPLORE; return; }

        if (!rc.getLocation().isWithinDistanceSquared(targetEnemyPaint, 2)) {
            moveTo(targetEnemyPaint);
        }

        if (rc.isActionReady() && rc.canAttack(targetEnemyPaint)) {
            rc.attack(targetEnemyPaint);
            targetEnemyPaint = null;
            needMopperAt = null;
            return;
        }

        tryMopSwing();
    }

    private void tryMopSwing() throws GameActionException {
        if (!rc.isActionReady()) return;

        Direction bestDir = null;
        int bestHits = 0;

        for (Direction d : CARDINAL_DIRS) {
            int hits = 0;
            MapLocation step1 = rc.getLocation().add(d);
            MapLocation step2 = step1.add(d);
            for (Direction perp : new Direction[]{
                    d.rotateLeft(), d, d.rotateRight()}) {
                MapLocation t1 = step1.add(perp);
                MapLocation t2 = step2.add(perp);
                if (rc.canSenseLocation(t1)) {
                    MapInfo info = rc.senseMapInfo(t1);
                    if (info.getPaint().isEnemy()) hits++;
                }
                if (rc.canSenseLocation(t2)) {
                    MapInfo info = rc.senseMapInfo(t2);
                    if (info.getPaint().isEnemy()) hits++;
                }
            }
            for (RobotInfo e : enemies) {
                if (e.location.distanceSquaredTo(step1) <= 2
                        || e.location.distanceSquaredTo(step2) <= 2) hits++;
            }
            if (hits > bestHits) { bestHits = hits; bestDir = d; }
        }

        if (bestDir != null && bestHits > 0 && rc.canMopSwing(bestDir)) {
            rc.mopSwing(bestDir);
        }
    }

    private void doExplore() throws GameActionException {
        if (lowPaintAlly != null) {
            if (!rc.getLocation().isWithinDistanceSquared(lowPaintAlly.location, 2)) {
                moveTo(lowPaintAlly.location);
            } else if (rc.isActionReady()) {
                int give = Math.min(rc.getPaint() - 20, lowPaintAlly.type.paintCapacity
                        - lowPaintAlly.getPaintAmount());
                if (give > 0 && rc.canTransferPaint(lowPaintAlly.location, give)) {
                    rc.transferPaint(lowPaintAlly.location, give);
                    return;
                }
            }
        }

        if (needMopperAt != null) {
            moveTo(needMopperAt);
        } else {
            moveTo(explorer.getTarget());
        }
    }

    private MapLocation findEnemyPaintTarget() throws GameActionException {
        MapLocation best = null;
        boolean bestNearRuin = false;
        int bestDist = Integer.MAX_VALUE;

        for (MapInfo info : mapInfo) {
            PaintType p = info.getPaint();
            if (!p.isEnemy()) continue;

            MapLocation loc = info.getMapLocation();
            boolean nearRuin = false;
            for (int i = mapData.ruins.size(); --i >= 0;) {
                MapLocation ruin = mapData.ruins.get(i);
                if (ruin != null && loc.distanceSquaredTo(ruin) <= 8) {
                    nearRuin = true;
                    break;
                }
            }

            int d = distTo(loc);
            if ((nearRuin && !bestNearRuin)
                    || (nearRuin == bestNearRuin && d < bestDist)) {
                best = loc;
                bestDist = d;
                bestNearRuin = nearRuin;
            }
        }
        return best;
    }
}