package alternative_bots_1;

import battlecode.common.*;
import alternative_bots_1.*;

public class RobotPlayer {

    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {

        Robot robot;

        UnitType type = rc.getType();

        if (!type.isTowerType()) {
            robot = switch (type) {
                case SOLDIER  -> new Soldier(rc);
                case MOPPER   -> new Mopper(rc);
                case SPLASHER -> new Splasher(rc);
                default       -> new Soldier(rc);
            };
        } else {
            robot = new Tower(rc);
        }

        robot.run();
    }
}