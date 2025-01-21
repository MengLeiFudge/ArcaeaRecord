package arc.record.aff.note;

import lombok.Data;
import lombok.EqualsAndHashCode;

import static arc.record.Settings.CLICK_TIME;
import static arc.record.Utils.dfTime;
import static arc.record.Utils.dfXY;

/**
 * @author MengLeiFudge
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ArcTap extends Note {
    final double[] xy;

    ArcTap(double[] xy, int time) {
        this.xy = xy;
        super.t1 = time;
        super.t2 = super.t1 + CLICK_TIME;
    }

    @Override
    public int getNoteCount() {
        return 1;
    }

    @Override
    public double[] getAffPoint(int time) {
        return xy;
    }

    @Override
    public String toString() {
        return "arctap" +
                " [" + dfTime.format(t1) + ", " + dfTime.format(t2) + "]" +
                " (" + dfXY.format(xy[0]) + ", " + dfXY.format(xy[1]) + ")";
    }
}
