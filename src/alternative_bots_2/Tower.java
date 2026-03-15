package alternative_bots_2;

import battlecode.common.*;

public class Tower {

    final RobotController rc;

    static int spawnPhase = 0;
    static int lastEmergencyMopperRound = -100;

    static int nearbySoldiers  = 0;
    static int nearbyMoppers   = 0;
    static int nearbySplashers = 0;

    static int currChips = 0;

    static int relayIdx = 0;

    Tower(RobotController rc) throws GameActionException {
        this.rc = rc;

        int myMsg = Comms.encodeLocExtra(Comms.MSG_TOWER,
                rc.getLocation(), Comms.encodeTowerType(rc.getType()));
        Comms.broadcastToTowers(rc, myMsg);

        Utils.addKnownFriendlyTower(rc.getLocation(), rc.getType());
    }

    public void run() throws GameActionException {
        currChips = rc.getChips();

        Comms.parseMessages(rc);

        RobotInfo[] allies  = rc.senseNearbyRobots(-1, rc.getTeam());
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());

        nearbySoldiers = nearbyMoppers = nearbySplashers = 0;
        for (RobotInfo a : allies) {
            if (a.getType().isTowerType()) {
                Utils.addKnownFriendlyTower(a.getLocation(), a.getType());
            } else {
                switch (a.getType()) {
                    case SOLDIER:  nearbySoldiers++;  break;
                    case MOPPER:   nearbyMoppers++;   break;
                    case SPLASHER: nearbySplashers++; break;
                }
            }
        }
        for (RobotInfo e : enemies) {
            if (e.getType().isTowerType()) {
                Utils.cachedEnemyTowerLoc  = e.getLocation();
                Utils.cachedEnemyTowerType = e.getType();
            }
        }

        tryUpgrade();

        if (rc.getRoundNum() % 5 == 0) {
            int msg = Comms.encodeLocExtra(Comms.MSG_TOWER,
                    rc.getLocation(), Comms.encodeTowerType(rc.getType()));
            Comms.broadcastToTowers(rc, msg);
        }
        relayToUnits(allies);
        trySpawn(allies, enemies);
        tryAttack(enemies);
    }


    void tryUpgrade() throws GameActionException {
        if (!rc.canUpgradeTower(rc.getLocation())) return;
        if (rc.getNumberTowers() < 6) return;

        boolean isPaint = (rc.getType().getBaseType() == UnitType.LEVEL_ONE_PAINT_TOWER);
        int threshold = isPaint ? 6000 : 8000;
        int forceThresh = 12000;

        int nearbyAllies = nearbySoldiers + nearbyMoppers + nearbySplashers;
        if (currChips > threshold && nearbyAllies >= 3) {
            rc.upgradeTower(rc.getLocation());
        } else if (currChips > forceThresh) {
            rc.upgradeTower(rc.getLocation());
        }
    }

    void relayToUnits(RobotInfo[] allies) throws GameActionException {
        int msgBudget = 20;
        if (Utils.needMopperAt != null) {
            for (RobotInfo ally : allies) {
                if (ally.getType() == UnitType.MOPPER && msgBudget > 0) {
                    int msg = Comms.encodeLocation(Comms.MSG_NEED_PAINT, Utils.needMopperAt);
                    if (Comms.sendTo(rc, ally.getLocation(), msg)) {
                        Utils.needMopperAt = null;
                        msgBudget--;
                    }
                }
            }
        }

        for (RobotInfo ally : allies) {
            if (ally.getType().isTowerType() || msgBudget <= 0) continue;
            if (!rc.canSendMessage(ally.getLocation())) continue;

            if (relayIdx < Utils.knownTowerCount && Utils.knownTowerLocs[relayIdx] != null) {
                int msg = Comms.encodeLocExtra(Comms.MSG_TOWER,
                        Utils.knownTowerLocs[relayIdx],
                        Comms.encodeTowerType(Utils.knownTowerTypes[relayIdx]));
                rc.sendMessage(ally.getLocation(), msg);
                relayIdx = (relayIdx + 1) % Math.max(1, Utils.knownTowerCount);
                msgBudget--;
            }
        }
    }

    void trySpawn(RobotInfo[] allies, RobotInfo[] enemies) throws GameActionException {
        if (!rc.isActionReady()) return;
        if (currChips < Utils.MIN_CHIPS_TO_SPAWN) return;

        boolean isPaint = (rc.getType().getBaseType() == UnitType.LEVEL_ONE_PAINT_TOWER);
        boolean isMoney = (rc.getType().getBaseType() == UnitType.LEVEL_ONE_MONEY_TOWER);
        boolean isStarting = rc.getRoundNum() < 8;

        if (!isPaint && !(isStarting && isMoney)) {
            if (rc.getNumberTowers() < 4 && currChips < 2000) return;
            if (currChips < 1100) return;
        }

        int enemySoldiers = 0;
        RobotInfo closestEnemySoldier = null;
        for (RobotInfo e : enemies) {
            if (e.getType() == UnitType.SOLDIER) {
                enemySoldiers++;
                if (closestEnemySoldier == null ||
                        rc.getLocation().distanceSquaredTo(e.getLocation()) <
                        rc.getLocation().distanceSquaredTo(closestEnemySoldier.getLocation())) {
                    closestEnemySoldier = e;
                }
            }
        }

        if (enemySoldiers > 0 && nearbyMoppers < enemySoldiers
                && (rc.getRoundNum() - lastEmergencyMopperRound) > 20) {
            Direction spawnDir = closestEnemySoldier != null
                    ? rc.getLocation().directionTo(closestEnemySoldier.getLocation())
                    : Direction.NORTH;
            if (trySpawnUnit(UnitType.MOPPER, spawnDir)) {
                lastEmergencyMopperRound = rc.getRoundNum();
                return;
            }
        }

        if (Utils.needMopperAt != null) {
            Direction toward = rc.getLocation().directionTo(Utils.needMopperAt);
            if (trySpawnUnit(UnitType.MOPPER, toward)) return;
        }
        UnitType toSpawn = pickSpawnType();

        Direction enemyDir = rc.getLocation().directionTo(
                Utils.predictEnemyLoc(rc, rc.getLocation()));
        trySpawnUnit(toSpawn, enemyDir);
    }

    UnitType pickSpawnType() {
        int round = rc.getRoundNum();
        if (round < 50) {
            return (spawnPhase % 3 == 2) ? UnitType.MOPPER : UnitType.SOLDIER;
        }

        int mod = spawnPhase % 6;
        if (currChips > 2000) {
            if (mod < 2)      return UnitType.SOLDIER;
            else if (mod < 4) return UnitType.MOPPER;
            else              return UnitType.SPLASHER;
        } else {
            if (mod < 3)      return UnitType.SOLDIER;
            else if (mod < 5) return UnitType.MOPPER;
            else              return UnitType.SOLDIER;
        }
    }

    boolean trySpawnUnit(UnitType type, Direction preferred) throws GameActionException {
        if (rc.getPaint() < type.paintCost) return false;

        int W = rc.getMapWidth(), H = rc.getMapHeight();
        MapLocation best = null;
        int bestScore = Integer.MIN_VALUE;

        for (Direction d : Utils.directions) {
            MapLocation loc = rc.getLocation().add(d);
            if (!rc.canBuildRobot(type, loc)) continue;

            int score = dirSimilarity(d, preferred) * 3;
            if (loc.x < 3 || loc.x > W - 4 || loc.y < 3 || loc.y > H - 4) score -= 5;

            if (score > bestScore) { bestScore = score; best = loc; }
        }

        if (best != null) {
            rc.buildRobot(type, best);
            spawnPhase++;

            int msg = Comms.encodeLocExtra(Comms.MSG_TOWER,
                    rc.getLocation(), Comms.encodeTowerType(rc.getType()));
            Comms.sendTo(rc, best, msg);

            return true;
        }
        return false;
    }

    int dirSimilarity(Direction a, Direction b) {
        if (a == b) return 2;
        if (a == b.rotateLeft() || a == b.rotateRight()) return 1;
        if (a == b.opposite()) return -1;
        return 0;
    }

    void tryAttack(RobotInfo[] enemies) throws GameActionException {
        rc.attack(null);
        if (enemies.length == 0) return;

        RobotInfo target = null;
        int minHP = Integer.MAX_VALUE;
        boolean sawSoldier = false;

        for (RobotInfo e : enemies) {
            if (!rc.canAttack(e.getLocation())) continue;
            boolean isSoldier = (e.getType() == UnitType.SOLDIER);
            if (!sawSoldier && isSoldier) {
                target = e; minHP = e.getHealth(); sawSoldier = true;
            } else if (sawSoldier == isSoldier && e.getHealth() < minHP) {
                target = e; minHP = e.getHealth();
            }
        }

        if (target != null && rc.canAttack(target.getLocation())) {
            rc.attack(target.getLocation());
        }
    }
}
