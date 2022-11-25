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
public class Hold extends Note implements Serializable, Comparable<Note> {
    /**
     * 起始时间.
     */
    int t1;
    /**
     * 结束时间.
     */
    int t2;
    /**
     * 所在轨道（0-5）.
     */
    int lane;

    public Hold(String line) {
        String[] data = line.substring("hold(".length(), line.length() - 2).split(",");
        t1 = Integer.parseInt(data[0]);
        t2 = Integer.parseInt(data[1]);
        lane = Integer.parseInt(data[2]);
    }

    @Override
    public int compareTo(Note o) {
        if (o instanceof Click oClick) {
            if (t1 != oClick.t) {
                return t1 - oClick.t;
            }
            return 1;
        }
        if (o instanceof Hold oHold) {
            if (t1 != oHold.t1) {
                return t1 - oHold.t1;
            }
            if (t2 != oHold.t2) {
                return t2 - oHold.t2;
            }
            return lane - oHold.lane;
        }
        if (o instanceof Arc oArc) {
            if (t1 != oArc.t1) {
                return t1 - oArc.t1;
            }
            return -1;
        }
        throw new IllegalArgumentException("无法比较 " + this.getClass() + " 与 " + o.getClass());
    }

    @Override
    public int getNote(List<Timing> timingList, double timingPointDensityFactor) {
        double beatTime = getBeatTime(timingList, timingPointDensityFactor, t1);
        return Math.max((int) ((t2 - t1) / beatTime) - 1, 1);
    }
}
