package arcaea.record.aff.note;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * @author MengLeiFudge
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class Hold extends Note implements Serializable, Comparable<Note> {
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
            if (t1 != oClick.t1) {
                return t1 - oClick.t1;
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

    /**
     * 返回 note 总数.
     * <p>
     * 计算规则如下：
     * <ul>
     *     <li>按 beatTime 分割为多个判定块，最后一个判定块长度为[1判定块,2判定块)</li>
     *     <li>除第一个判定块外，其余判定块头+1combo</li>
     *     <li>至少有1combo</li>
     * </ul>
     *
     * @return 该长条的 note 总数
     */
    @Override
    public int getNoteCount() {
        int beatCount = (int) ((t2 - t1) / beatTime);
        return Math.max(beatCount - 1, 1);
    }
}
