package main-bot;

import battlecode.common.*;
import main-bot.*;

public class Tower extends Robot {

    protected MapLocation enemySpawnEstimate;

    protected Direction enemyDir;
    protected MapLocation[] spawnSquares;
    protected final boolean isStartingTower;

    protected int spawnCount = 0;
    protected int lastEmergencyMopperRound = -1000;

    protected int nearbySoldiers = 0;
    protected int nearbyMoppers  = 0;
    protected int nearbySplashers = 0;

    protected int prevChips = 0;
    protected int currChips = 0;

    protected int lastTowerDeathRound = -100;

    private final java.util.HashMap<Integer, Integer> relayIndex = new java.util.HashMap<>();

    public Tower(RobotController rc) throws GameActionException {
        super(rc);

        isStartingTower = rc.getRoundNum() < 8;

        enemySpawnEstimate = new MapLocation(
            width  - 1 - rc.getLocation().x,
            height - 1 - rc.getLocation().y
        );
        enemyDir = rc.getLocation().directionTo(enemySpawnEstimate);

        spawnSquares = rc.getAllLocationsWithinRadiusSquared(
            rc.getLocation(),
            GameConstants.BUILD_ROBOT_RADIUS_SQUARED
        );

        mapData.update(rc.senseNearbyMapInfos(-1));

        if (rc.canBroadcastMessage()) {
            rc.broadcastMessage(comms.encodeLocExtra(
                Constants.MSG_TOWER,
                rc.getLocation(),
                Comms.encodeTowerType(rc.getType())
            ));
        }
    }

    @Override
    public void turn() throws GameActionException {
        prevChips = currChips;
        currChips = rc.getChips();

        if (rc.getNumberTowers() < mapData.friendlyTowerList.size()) {
            lastTowerDeathRound = rc.getRoundNum();
        }

        comms.parseMessages();

        RobotInfo[] allies  = rc.senseNearbyRobots(-1, rc.getTeam());
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());

        nearbySoldiers = nearbyMoppers = nearbySplashers = 0;
        for (RobotInfo a : allies) {
            if (a.type.isTowerType()) {
                mapData.addFriendlyTower(a);
            } else {
                switch (a.type) {
                    case SOLDIER  -> nearbySoldiers++;
                    case MOPPER   -> nearbyMoppers++;
                    case SPLASHER -> nearbySplashers++;
                }
            }
        }
        for (RobotInfo e : enemies) {
            if (e.type.isTowerType()) mapData.addEnemyTower(e);
        }

        tryUpgrade();
        if (rc.getRoundNum() < 10) {
            comms.broadcastToTowers(comms.encodeLocExtra(
                Constants.MSG_TOWER,
                rc.getLocation(),
                Comms.encodeTowerType(rc.getType())
            ));
        }

        relayToUnits(allies, enemies);
        trySpawn(allies, enemies);
        tryAttack(enemies);
    }

    protected void tryUpgrade() throws GameActionException {
        if (!rc.canUpgradeTower(rc.getLocation())) return;
        if (rc.getNumberTowers() < 6) return;

        boolean isPaint = rc.getType().getBaseType() == UnitType.LEVEL_ONE_PAINT_TOWER;
        int threshold   = isPaint ? Constants.CHIPS_TO_UPGRADE_PAINT
                                  : Constants.CHIPS_TO_UPGRADE_MONEY;
        int forceThresh = Constants.CHIPS_TO_UPGRADE_FORCE;

        int nearbyAllies = nearbySoldiers + nearbyMoppers + nearbySplashers;
        if (currChips > threshold && nearbyAllies >= 3) {
            rc.upgradeTower(rc.getLocation());
        } else if (currChips > forceThresh) {
            rc.upgradeTower(rc.getLocation());
        }
    }

    protected void relayToUnits(RobotInfo[] allies, RobotInfo[] enemies) throws GameActionException {
        int msgBudget = 20;

        if (needMopperAt != null) {
            for (RobotInfo ally : allies) {
                if (ally.type == UnitType.MOPPER && msgBudget > 0) {
                    if (comms.sendTo(ally.location,
                            comms.encodeLocation(Constants.MSG_NEED_PAINT, needMopperAt))) {
                        needMopperAt = null;
                        msgBudget--;
                    }
                }
            }
        }

        int towerCount = mapData.friendlyTowerList.size();

        for (RobotInfo ally : allies) {
            if (ally.type.isTowerType() || msgBudget <= 0) continue;
            if (!rc.canSendMessage(ally.location)) continue;

            int startIdx = relayIndex.getOrDefault(ally.ID, 0);
            if (startIdx < towerCount) {
                RobotInfo t = mapData.friendlyTowerList.get(startIdx);
                rc.sendMessage(ally.location,
                    comms.encodeLocExtra(
                        Constants.MSG_TOWER,
                        t.location,
                        Comms.encodeTowerType(t.type)
                    )
                );
                relayIndex.put(ally.ID, startIdx + 1);
                msgBudget--;
            }
        }

        if (rc.canBroadcastMessage() && rc.getRoundNum() % 5 == 0) {
            rc.broadcastMessage(comms.encodeLocExtra(
                Constants.MSG_TOWER, rc.getLocation(), Comms.encodeTowerType(rc.getType())));
        }
    }

    protected void trySpawn(RobotInfo[] allies, RobotInfo[] enemies) throws GameActionException {
        if (!rc.isActionReady()) return;
        if (currChips < Constants.MIN_CHIPS_TO_SPAWN) return;

        boolean isPaint  = rc.getType().getBaseType() == UnitType.LEVEL_ONE_PAINT_TOWER;
        boolean isMoney  = rc.getType().getBaseType() == UnitType.LEVEL_ONE_MONEY_TOWER;
        int totalTowers  = rc.getNumberTowers();

        if (!isPaint && !(isStartingTower && isMoney)) {
            if (totalTowers < 4 && currChips < Constants.CHIPS_SPAWN_FREELY) return;
            if (currChips < 1100) return;
        }

        int enemySoldiers = 0;
        RobotInfo closestEnemySoldier = null;
        for (RobotInfo e : enemies) {
            if (e.type == UnitType.SOLDIER) {
                enemySoldiers++;
                if (closestEnemySoldier == null ||
                        distTo(e.location) < distTo(closestEnemySoldier.location)) {
                    closestEnemySoldier = e;
                }
            }
        }

        boolean emergencyMopperNeeded = enemySoldiers > 0 && nearbyMoppers < enemySoldiers
                && (rc.getRoundNum() - lastEmergencyMopperRound) > Constants.EMERGENCY_MOPPER_COOLDOWN;

        if (emergencyMopperNeeded) {
            Direction spawnDir = closestEnemySoldier != null
                    ? rc.getLocation().directionTo(closestEnemySoldier.location)
                    : enemyDir;
            if (trySpawnUnit(UnitType.MOPPER, spawnDir)) {
                lastEmergencyMopperRound = rc.getRoundNum();
                return;
            }
        }

        if (needMopperAt != null) {
            Direction toward = rc.getLocation().directionTo(needMopperAt);
            if (trySpawnUnit(UnitType.MOPPER, toward)) {
                return;
            }
        }

        UnitType toSpawn = pickSpawnType();
        trySpawnUnit(toSpawn, enemyDir);
    }

    protected UnitType pickSpawnType() {
        int round = rc.getRoundNum();

        if (round < 50) {
            return (spawnCount % 3 == 2) ? UnitType.MOPPER : UnitType.SOLDIER;
        }

        int total = nearbySoldiers + nearbyMoppers + nearbySplashers;

        if (needMopperAt != null && nearbyMoppers < 2) {
            return UnitType.MOPPER;
        }

        int mod = spawnCount % 6;
        if (currChips > Constants.CHIPS_SPAWN_FREELY) {
            if (mod < 2)      return UnitType.SOLDIER;
            else if (mod < 4) return UnitType.MOPPER;
            else              return UnitType.SPLASHER;
        } else {
            if (mod < 3)      return UnitType.SOLDIER;
            else if (mod < 5) return UnitType.MOPPER;
            else              return UnitType.SOLDIER;
        }
    }

    protected boolean trySpawnUnit(UnitType type, Direction preferred) throws GameActionException {
        if (rc.getPaint() < type.paintCost) return false;

        MapLocation best = null;
        int bestScore = Integer.MIN_VALUE;

        for (MapLocation sq : spawnSquares) {
            if (!rc.canBuildRobot(type, sq)) continue;
            Direction d = rc.getLocation().directionTo(sq);
            int score = dirSimilarity(d, preferred);
            if (sq.x < 3 || sq.x > width - 4 || sq.y < 3 || sq.y > height - 4) score -= 5;
            if (score > bestScore) { bestScore = score; best = sq; }
        }

        if (best != null) {
            rc.buildRobot(type, best);
            spawnCount++;
            return true;
        }
        return false;
    }

    private int dirSimilarity(Direction a, Direction b) {
        if (a == b) return 2;
        if (a == b.rotateLeft() || a == b.rotateRight()) return 1;
        if (a == b.opposite()) return -1;
        return 0;
    }

    protected void tryAttack(RobotInfo[] enemies) throws GameActionException {
        rc.attack(null);
        if (enemies.length == 0) return;

        RobotInfo target = null;
        int minHP = Integer.MAX_VALUE;
        boolean sawSoldier = false;

        for (RobotInfo e : enemies) {
            if (!rc.canAttack(e.location)) continue;
            boolean isSoldier = (e.type == UnitType.SOLDIER);
            if (!sawSoldier && isSoldier) {
                target = e; 
                minHP = e.getHealth(); 
                sawSoldier = true;
            } else if (sawSoldier == isSoldier && e.getHealth() < minHP) {
                target = e; 
                minHP = e.getHealth();
            }
        }

        if (target != null && rc.canAttack(target.location)) {
            rc.attack(target.location);
        }
    }

    @Override
    public void onFriendlyTowerMessage(MapLocation loc, UnitType type) throws GameActionException {
        RobotInfo placeholder = new RobotInfo(0, rc.getTeam(), type, 2000, loc, 500);
        mapData.addFriendlyTower(placeholder);
    }

    @Override
    public void onEnemyTowerMessage(MapLocation loc, UnitType type) throws GameActionException {
        int msg = comms.encodeLocExtra(Constants.MSG_ENEMY, loc,
                Comms.encodeTowerType(type));
        comms.broadcastToNearbyAllies(msg);
    }
}