package alternative-bots-1;

import battlecode.common.*;

public class Explore {

    private final RobotController rc;
    private final MapData mapData;
    private final int width;
    private final int height;

    private MapLocation exploreLoc = null;

    private int targetRound = -100;

    private final MapLocation[] checkLocs;

    public Explore(RobotController rc, MapData mapData) {
        this.rc      = rc;
        this.mapData = mapData;
        this.width   = rc.getMapWidth();
        this.height  = rc.getMapHeight();

        checkLocs = new MapLocation[]{
            new MapLocation(width / 2, height / 2),   
            new MapLocation(0, 0),                     
            new MapLocation(width - 1, 0),            
            new MapLocation(0, height - 1),           
            new MapLocation(width - 1, height - 1),  
        };

        exploreLoc = checkLocs[rc.getID() % checkLocs.length];
        targetRound = rc.getRoundNum();
    }

    public MapLocation getTarget() {
        int round = rc.getRoundNum();

        boolean expired  = (round - targetRound) > 40;
        boolean explored = (exploreLoc != null && mapData.getMapInfo(exploreLoc) != null);

        if (exploreLoc == null || expired || explored) {
            getCheckerTarget(15);
        }

        return exploreLoc;
    }

    private void getCheckerTarget(int tries) {
        while (tries-- > 0) {
            int idx = (int)(Math.random() * checkLocs.length);
            MapLocation candidate = checkLocs[idx];
            if (mapData.getMapInfo(candidate) != null) continue;
            if (exploreLoc != null
                    && rc.getLocation().distanceSquaredTo(exploreLoc)
                     > rc.getLocation().distanceSquaredTo(candidate)) continue;
            exploreLoc = candidate;
            targetRound = rc.getRoundNum();
        }
        if (exploreLoc == null || mapData.getMapInfo(exploreLoc) != null) {
            getEmergencyTarget(15);
        }
    }

    private void getEmergencyTarget(int tries) {
        while (tries-- > 0) {
            MapLocation candidate = new MapLocation(
                (int)(Math.random() * width),
                (int)(Math.random() * height)
            );
            if (mapData.getMapInfo(candidate) != null) continue;
            if (exploreLoc != null
                    && rc.getLocation().distanceSquaredTo(exploreLoc)
                     > rc.getLocation().distanceSquaredTo(candidate)) continue;
            exploreLoc = candidate;
            targetRound = rc.getRoundNum();
        }
        if (exploreLoc == null) {
            exploreLoc = new MapLocation(
                (int)(Math.random() * width),
                (int)(Math.random() * height)
            );
            targetRound = rc.getRoundNum();
        }
    }
}