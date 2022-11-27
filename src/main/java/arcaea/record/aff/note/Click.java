package arcaea.record.aff.note;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

import static arcaea.record.SettingsAndUtils.CLICK_TIME;

/**
 * @author MengLeiFudge
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class Click extends Note implements Serializable, Comparable<Note> {
    /**
     * 所在轨道，0-5.
     */
    int lane;

    public Click(String line) {
        String[] data = line.substring("(".length(), line.length() - 2).split(",");
        t1 = Integer.parseInt(data[0]);
        t2 = t1 + CLICK_TIME;
        lane = Integer.parseInt(data[1]);
    }

    @Override
    public int compareTo(Note o) {
        if (o instanceof Click oClick) {
            if (t1 != oClick.t1) {
                return t1 - oClick.t1;
            }
            return lane - oClick.lane;
        }
        if (o instanceof Hold oHold) {
            if (t1 != oHold.t1) {
                return t1 - oHold.t1;
            }
            return -1;
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
    public int getNoteCount() {
        return 1;
    }
}
