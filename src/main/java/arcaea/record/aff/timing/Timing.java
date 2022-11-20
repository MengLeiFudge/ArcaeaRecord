package arcaea.record.aff.timing;

import java.io.Serializable;

/**
 * @author MengLeiFudge
 */
public class Timing implements Serializable {
    int t;
    double bpm;
    double beats;

    public Timing(String line) {
        String[] data = line.substring("timing(".length(), line.length() - 2).split(",");
        t = Integer.parseInt(data[0]);
        bpm = Double.parseDouble(data[1]);
        beats = Double.parseDouble(data[2]);
    }
}
