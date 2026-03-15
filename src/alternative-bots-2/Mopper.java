package alternative-bots-2;

import battlecode.common.*;

public class Mopper {

    final RobotController rc;

    static final int STATE_EXPLORE   = 0;
    static final int STATE_REFILLING = 1;
    static final int STATE_MOPPING   = 2;

    static int state = STATE_EXPLORE;

    static MapLocation targetEnemyPaint = null;
    static MapLocation refillTarget     = null;

    Mopper(RobotController rc) {
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
        for (RobotInfo a : allies) {
            if (a.getType().isTowerType())
                Utils.addKnownFriendlyTower(a.getLocation(), a.getType());
        }
        for (RobotInfo e : enemies) {
            if (e.getType().isTowerType()) {
                Utils.cachedEnemyTowerLoc = e.getLocation();
                Comms.sendToNearestTower(rc,
                    Comms.encodeLocExtra(Comms.MSG_ENEMY, e.getLocation(),
                        Comms.encodeTowerType(e.getType())));
            }
        }

        state = determineState(paintLevel, paintMax);

        switch (state) {
            case STATE_REFILLING: doRefill(myLoc);  break;
            case STATE_MOPPING:   doMopping(myLoc); break;
            case STATE_EXPLORE:   doExplore(myLoc, allies); break;
        }
    }

    int determineState(int paint, int paintMax) throws GameActionException {
        if (state != STATE_REFILLING && (double) paint / paintMax < Utils.RETREAT_THRESHOLD) {
            refillTarget = Utils.findRefillTower(rc);
            return STATE_REFILLING;
        }
        if (state == STATE_REFILLING && (double) paint / paintMax < Utils.REFILL_FULL_RATIO) {
            return STATE_REFILLING;
        }

        if (Utils.needMopperAt != null) {
            targetEnemyPaint = Utils.needMopperAt;
            return STATE_MOPPING;
        }

        targetEnemyPaint = findEnemyPaintTarget();
        if (targetEnemyPaint != null) return STATE_MOPPING;

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

    void doMopping(MapLocation myLoc) throws GameActionException {
        transferToLowAlly();

        if (targetEnemyPaint == null) { state = STATE_EXPLORE; return; }

        if (myLoc.distanceSquaredTo(targetEnemyPaint) > 2) {
            Pathfinding.moveTo(rc, targetEnemyPaint);
        }

        if (rc.isActionReady() && rc.canAttack(targetEnemyPaint)) {
            rc.attack(targetEnemyPaint);
            targetEnemyPaint = null;
            Utils.needMopperAt = null;
            return;
        }

        tryMopSwing();
    }

    void doExplore(MapLocation myLoc, RobotInfo[] allies) throws GameActionException {
        transferToLowAlly();

        Direction moveDir = directionTowardMostEnemyPaint();
        if (moveDir != null) {
            Pathfinding.safeMove(rc, moveDir);
        } else if (Utils.needMopperAt != null) {
            Pathfinding.moveTo(rc, Utils.needMopperAt);
        } else {
            Pathfinding.moveTo(rc, Utils.currentExploreTarget(rc));
        }
    }

    void transferToLowAlly() throws GameActionException {
        if (!rc.isActionReady() || rc.getPaint() < 30) return;
        RobotInfo[] allies = rc.senseNearbyRobots(2, rc.getTeam());
        for (RobotInfo ally : allies) {
            if (ally.getType().isTowerType()) continue;
            if (ally.getPaintAmount() < 15) {
                int give = Math.min(rc.getPaint() - 20, 30);
                if (give > 0 && rc.canTransferPaint(ally.getLocation(), give)) {
                    rc.transferPaint(ally.getLocation(), give);
                    return;
                }
            }
        }
    }

    void tryMopSwing() throws GameActionException {
        if (!rc.isActionReady()) return;
        Direction[] cardinals = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        Direction best = null;
        int bestCount = 0;

        for (Direction d : cardinals) {
            if (!rc.canMopSwing(d)) continue;
            MapLocation step1 = rc.getLocation().add(d);
            MapLocation step2 = step1.add(d);
            int count = 0;
            for (Direction perp : new Direction[]{d.rotateLeft(), d, d.rotateRight()}) {
                MapLocation t1 = step1.add(perp);
                MapLocation t2 = step2.add(perp);
                try {
                    if (rc.canSenseLocation(t1) && rc.senseMapInfo(t1).getPaint().isEnemy()) count++;
                    if (rc.canSenseLocation(t2) && rc.senseMapInfo(t2).getPaint().isEnemy()) count++;
                } catch (Exception ignored) {}
            }
            RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
            for (RobotInfo e : enemies) {
                if (e.getLocation().distanceSquaredTo(step1) <= 2
                        || e.getLocation().distanceSquaredTo(step2) <= 2) count++;
            }
            if (count > bestCount) { bestCount = count; best = d; }
        }

        if (best != null && bestCount > 0 && rc.canMopSwing(best)) {
            rc.mopSwing(best);
        }
    }

    Direction directionTowardMostEnemyPaint() throws GameActionException {
        Direction best = null;
        int bestCount = 0;
        for (Direction d : Utils.directions) {
            if (!rc.canMove(d)) continue;
            MapLocation next = rc.getLocation().add(d);
            int count = 0;
            for (MapInfo tile : rc.senseNearbyMapInfos(next, 4)) {
                if (tile.getPaint().isEnemy()) count++;
            }
            if (count > bestCount) { bestCount = count; best = d; }
        }
        return bestCount > 0 ? best : null;
    }

    MapLocation findEnemyPaintTarget() throws GameActionException {
        MapLocation best = null;
        boolean bestNearRuin = false;
        int bestDist = Integer.MAX_VALUE;

        for (MapInfo info : rc.senseNearbyMapInfos(-1)) {
            PaintType p = info.getPaint();
            if (!p.isEnemy()) continue;

            MapLocation loc = info.getMapLocation();
            boolean nearRuin = false;
            for (MapInfo rinfo : rc.senseNearbyMapInfos(loc, 8)) {
                if (rinfo.hasRuin()) { nearRuin = true; break; }
            }

            int d = rc.getLocation().distanceSquaredTo(loc);
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