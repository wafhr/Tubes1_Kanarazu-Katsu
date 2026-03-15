package alternative_bots_2;

import battlecode.common.*;

public class RobotPlayer {

    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
        Utils.rngState = rc.getID();
        Comms.spawnRound = rc.getRoundNum();

        while (true) {
            Utils.turnCount++;
            try {
                switch (rc.getType()) {
                    case SOLDIER:  new Soldier(rc).run();  break;
                    case MOPPER:   new Mopper(rc).run();   break;
                    case SPLASHER: new Splasher(rc).run(); break;
                    default:       new Tower(rc).run();    break;
                }
            } catch (GameActionException e) {
                System.out.println("GAE: " + e.getMessage());
                e.printStackTrace();
            } catch (Exception e) {
                System.out.println("E: " + e.getMessage());
                e.printStackTrace();
            } finally {
                Clock.yield();
            }
        }
    }
}
