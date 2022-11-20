package arcaea.record.aff.note;

import java.io.Serializable;

/**
 * @author MengLeiFudge
 */
public class Click extends Note implements Serializable {
    int t;
    int lane;

    public Click(String line) {
        String[] data = line.substring("(".length(), line.length() - 2).split(",");
        t = Integer.parseInt(data[0]);
        lane = Integer.parseInt(data[1]);
    }
}
