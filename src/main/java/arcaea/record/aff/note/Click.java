package arcaea.record.aff.note;

import arcaea.record.aff.timing.Timing;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.util.List;

/**
 * @author MengLeiFudge
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class Click extends Note implements Serializable, Comparable<Note> {
    /**
     * 按键时间.
     */
    int t;
    /**
     * 所在轨道，0-5.
     */
    int lane;

    public Click(String line) {
        String[] data = line.substring("(".length(), line.length() - 2).split(",");
        t = Integer.parseInt(data[0]);
        lane = Integer.parseInt(data[1]);
    }

    @Override
    public int compareTo(Note o) {
        if (o instanceof Click oClick) {
            if (t != oClick.t) {
                return t - oClick.t;
            }
            return lane - oClick.lane;
        }
        if (o instanceof Hold oHold) {
            if (t != oHold.t1) {
                return t - oHold.t1;
            }
            return -1;
        }
        if (o instanceof Arc oArc) {
            if (t != oArc.t1) {
                return t - oArc.t1;
            }
            return -1;
        }
        throw new IllegalArgumentException("无法比较 " + this.getClass() + " 与 " + o.getClass());
    }

    @Override
    public int getNote(List<Timing> timingList, double timingPointDensityFactor) {
        return 1;
    }
}
