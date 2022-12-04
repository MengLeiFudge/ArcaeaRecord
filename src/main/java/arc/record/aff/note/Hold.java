package arc.record.aff.note;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * @author MengLeiFudge
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class Hold extends Note {
    /**
     * 所在轨道（0-5）.
     */
    final int lane;

    public Hold(String line) {
        String[] data = line.substring("hold(".length(), line.length() - 2).split(",");
        t1 = Integer.parseInt(data[0]);
        t2 = Integer.parseInt(data[1]);
        lane = Integer.parseInt(data[2]);
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

    @Override
    public double[] getAffPoint(int time) {
        if (time < t1 || time > t2) {
            throw new IllegalArgumentException("时间 " + time + " 不在 [" + t1 + ", " + t2 + "] 区间内");
        }
        return new double[]{lane * 0.5 - 0.75, 0};
    }
}
