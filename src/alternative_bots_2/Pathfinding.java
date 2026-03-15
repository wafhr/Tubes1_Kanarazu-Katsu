package alternative_bots_2;

import battlecode.common.*;

public class Pathfinding {

    private static final int BUG_STACK_SIZE = 64;

    static Direction[] bugStack    = new Direction[BUG_STACK_SIZE];
    static int         bugStackIdx = 0;
    static MapLocation bugLastTarget = null;
    static MapLocation bugLastLoc    = null;
    static MapLocation bugCurLoc     = null;
    static boolean     bugRight      = true;

    private static MapLocation[] prevPositions = new MapLocation[8];
    private static int           prevPosIdx    = 0;
    private static MapLocation   lastSafeMoveTarget = null;

    public static void init(RobotController rc) {
        bugRight = (rc.getID() % 2 == 0);
        resetBugNav(null);
        prevPositions = new MapLocation[8];
        prevPosIdx = 0;
        lastSafeMoveTarget = null;
    }

    public static Direction[] nearbyDirs(Direction dir) {
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

    public static int tileScore(RobotController rc, MapLocation loc, boolean ignoreEmpty) {
        try {
            if (!rc.onTheMap(loc)) return -9999;

            int score = 0;

            if (inEnemyTowerRange(rc, loc)) score -= 20;

            if (!rc.canSenseLocation(loc)) return score - (ignoreEmpty ? 0 : 1);

            MapInfo info = rc.senseMapInfo(loc);
            if (!info.isPassable()) return -9999;

            PaintType paint = info.getPaint();
            if (paint.isAlly())              score += 0;
            else if (paint == PaintType.EMPTY) score -= (ignoreEmpty ? 0 : 1);
            else                               score -= 2;
            return score;
        } catch (Exception e) {
            return -9999;
        }
    }

    public static int tileScore(RobotController rc, MapLocation loc) {
        return tileScore(rc, loc, false);
    }

    public static boolean inEnemyTowerRange(RobotController rc, MapLocation loc) {
        try {
            RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
            for (RobotInfo e : enemies) {
                if (e.getType().isTowerType() &&
                        loc.isWithinDistanceSquared(e.getLocation(),
                                e.getType().actionRadiusSquared)) {
                    return true;
                }
            }
            if (Utils.cachedEnemyTowerLoc != null &&
                    loc.isWithinDistanceSquared(Utils.cachedEnemyTowerLoc, 20)) {
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    public static void moveTo(RobotController rc, MapLocation target)
            throws GameActionException {
        if (!rc.isMovementReady()) return;
        if (rc.getLocation().equals(target)) return;
        bugNav(rc, target);
    }

    public static void bugNav(RobotController rc, MapLocation target)
            throws GameActionException {
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
            boolean passable = rc.canMove(top) && !inEnemyTowerRange(rc, next);
            if (passable) {
                bugStackIdx--;
            } else {
                break;
            }
        }

        if (bugStackIdx == 0) {
            Direction dirToTarget = rc.getLocation().directionTo(target);
            Direction best = null;
            int bestScore = -9999;

            for (Direction d : new Direction[]{dirToTarget,
                    dirToTarget.rotateLeft(), dirToTarget.rotateRight()}) {
                if (!rc.canMove(d)) continue;
                MapLocation next = rc.getLocation().add(d);
                if (bugLastLoc != null && next.equals(bugLastLoc)) continue;
                if (inEnemyTowerRange(rc, next)) continue;
                int score = tileScore(rc, next);
                if (score > bestScore) { bestScore = score; best = d; }
            }

            if (best != null && bestScore > -20) {
                rc.move(best);
                return;
            }

            Direction wallDir = bugRight
                    ? dirToTarget.rotateLeft()
                    : dirToTarget.rotateRight();
            bugStack[bugStackIdx++] = wallDir;
        }

        Direction wallDir = bugStack[bugStackIdx - 1];
        Direction tryDir  = bugRight
                ? wallDir.rotateRight()
                : wallDir.rotateLeft();

        for (int i = 0; i < 8; i++) {
            MapLocation next = rc.getLocation().add(tryDir);
            if (!rc.onTheMap(next)) {
                resetBugNav(target);
                safeMove(rc, target);
                return;
            }
            if (rc.canMove(tryDir) && !inEnemyTowerRange(rc, next)) {
                rc.move(tryDir);
                return;
            }
            bugStack[bugStackIdx++] = tryDir;
            tryDir = bugRight ? tryDir.rotateRight() : tryDir.rotateLeft();
        }
    }

    private static void resetBugNav(MapLocation target) {
        bugStack    = new Direction[BUG_STACK_SIZE];
        bugStackIdx = 0;
        bugLastTarget = target;
        bugLastLoc    = null;
    }

    public static boolean safeMove(RobotController rc, MapLocation target)
            throws GameActionException {
        return safeMove(rc, target, false);
    }

    public static boolean safeMove(RobotController rc, MapLocation target,
            boolean ignoreEmpty) throws GameActionException {
        if (!rc.isMovementReady()) return false;

        if (lastSafeMoveTarget == null || lastSafeMoveTarget.distanceSquaredTo(target) > 4) {
            prevPositions = new MapLocation[8];
            prevPosIdx = 0;
        }
        lastSafeMoveTarget = target;

        Direction dir = rc.getLocation().directionTo(target);
        return safeMoveDir(rc, dir, ignoreEmpty);
    }

    public static boolean safeMove(RobotController rc, Direction dir)
            throws GameActionException {
        return safeMoveDir(rc, dir, false);
    }

    private static boolean safeMoveDir(RobotController rc, Direction dir,
            boolean ignoreEmpty) throws GameActionException {
        if (!rc.isMovementReady()) return false;

        Direction bestDir  = null;
        int       bestScore = -1000;
        int       deviation = 0;

        for (Direction d : nearbyDirs(dir)) {
            deviation++;
            MapLocation next = rc.getLocation().add(d);
            if (!rc.canMove(d)) continue;
            if (isPrevPosition(next)) continue;
            int score = tileScore(rc, next, ignoreEmpty) - deviation / 2;
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

    private static boolean isPrevPosition(MapLocation loc) {
        for (MapLocation p : prevPositions) {
            if (p != null && p.equals(loc)) return true;
        }
        return false;
    }

    public static boolean tryMoveIntoRange(RobotController rc, MapLocation target, int distSq)
            throws GameActionException {
        if (!rc.isMovementReady()) return false;
        MapLocation myLoc = rc.getLocation();
        Direction dirTo = myLoc.directionTo(target);

        Direction best = Direction.CENTER;
        int bestScore = myLoc.distanceSquaredTo(target) <= distSq
                ? tileScore(rc, myLoc) : -9999;

        for (Direction d : nearbyDirs(dirTo)) {
            MapLocation next = myLoc.add(d);
            if (next.isWithinDistanceSquared(target, distSq) && rc.canMove(d)) {
                int score = tileScore(rc, next);
                if (score >= bestScore) { best = d; bestScore = score; }
            }
        }

        if (best != Direction.CENTER && bestScore > -1000) {
            rc.move(best);
            return true;
        }
        return myLoc.distanceSquaredTo(target) <= distSq;
    }

    public static boolean tryMoveOutOfRange(RobotController rc, MapLocation avoidLoc, int distSq)
            throws GameActionException {
        if (!rc.isMovementReady()) return false;
        MapLocation myLoc = rc.getLocation();
        Direction away = avoidLoc.directionTo(myLoc);

        Direction best = null;
        int bestScore = -9999;

        for (Direction d : nearbyDirs(away)) {
            MapLocation next = myLoc.add(d);
            if (!next.isWithinDistanceSquared(avoidLoc, distSq) && rc.canMove(d)) {
                int score = tileScore(rc, next);
                if (score > bestScore) { best = d; bestScore = score; }
            }
        }

        if (best != null) { rc.move(best); return true; }
        return false;
    }

}