package arcaea.record.aff.note;

import java.io.Serializable;

/**
 * @author MengLeiFudge
 */
public class Hold extends Note implements Serializable {
    int t1;
    int t2;
    int lane;

    public Hold(String line) {
        String[] data = line.substring("hold(".length(), line.length() - 2).split(",");
        t1 = Integer.parseInt(data[0]);
        t2 = Integer.parseInt(data[1]);
        lane = Integer.parseInt(data[2]);
    }
}
