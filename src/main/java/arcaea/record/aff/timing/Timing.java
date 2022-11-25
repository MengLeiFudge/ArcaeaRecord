package arcaea.record.aff.timing;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * @author MengLeiFudge
 */
@Data
public class Timing implements Serializable, Comparable<Timing> {
    private int t;
    private double bpm;
    private double beats;

    public Timing(String line) {
        String[] data = line.substring("timing(".length(), line.length() - 2).split(",");
        t = Integer.parseInt(data[0]);
        bpm = Double.parseDouble(data[1]);
        beats = Double.parseDouble(data[2]);
    }

    @Override
    public int compareTo(Timing o) {
        return t - o.t;
    }
}
