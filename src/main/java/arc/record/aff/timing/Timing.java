package arc.record.aff.timing;

import lombok.Data;

import java.io.Serializable;

/**
 * @author MengLeiFudge
 */
@Data
public class Timing implements Serializable, Comparable<Timing> {
    final int t;
    final float bpm;

    public Timing(String line) {
        String[] data = line.substring("timing(".length(), line.length() - 2).split(",");
        t = Integer.parseInt(data[0]);
        bpm = Float.parseFloat(data[1]);
        // 第二个是 beats，没用
    }

    @Override
    public int compareTo(Timing o) {
        return t - o.t;
    }
}
