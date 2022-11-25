package arcaea.record.aff.note;

import arcaea.record.aff.timing.Timing;

import java.io.Serializable;
import java.util.List;

/**
 * @author MengLeiFudge
 */
public abstract class Note implements Serializable, Comparable<Note> {
    /**
     * 返回该键型对应的总 note 数.
     */
    public abstract int getNote(List<Timing> timingList, double timingPointDensityFactor);

    /**
     * 返回对于指定长条/蛇，每个判定块对应的时长.
     * <p>
     * PS: 长条/蛇的判定块时长是固定的，以键型开始时间所在 timing 对应的 bpm 计算.
     */
    protected static double getBeatTime(List<Timing> timingList, double timingPointDensityFactor, int time) {
        double bpm = 0;
        for (var timing : timingList) {
            if (timing.getT() <= time) {
                bpm = timing.getBpm();
            }
        }
        if (bpm == 0) {
            return Double.MAX_VALUE;
        }
        bpm = Math.abs(bpm);
        double beatTime = bpm >= 255 ? 60000 / bpm : 30000 / bpm;
        return beatTime / timingPointDensityFactor;
    }
}
