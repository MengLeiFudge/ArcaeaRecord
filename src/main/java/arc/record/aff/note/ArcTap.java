package arc.record.aff.note;

import lombok.Data;
import lombok.EqualsAndHashCode;

import static arc.record.SettingsAndUtils.CLICK_TIME;

/**
 * @author MengLeiFudge
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ArcTap extends Note {
    final double[] xy;

    ArcTap(double[] xy, int time) {
        this.xy = xy;
        t1 = time;
        t2 = t1 + CLICK_TIME;
    }

    @Override
    public int getNoteCount() {
        return 1;
    }

    @Override
    public double[] getAffPoint(int time) {
//        if (time < t1 || time > t2) {
//            throw new IllegalArgumentException("时间 " + time + " 不在 [" + t1 + ", " + t2 + "] 区间内");
//        }
        return xy;
    }
}
