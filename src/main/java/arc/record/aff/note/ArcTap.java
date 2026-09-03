package arc.record.aff.note;

import lombok.Data;
import lombok.EqualsAndHashCode;

import static arc.record.Settings.CLICK_TIME;
import static arc.record.Utils.dfTime;
import static arc.record.Utils.dfXY;

/**
 * Arc 上的独立点击物件。
 *
 * @author MengLeiFudge
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ArcTap extends Note {
    final double x;
    final double y;
    /** 来源 Arc 的稳定 sourceId。 */
    final int sourceArcId;
    /** designant Arctap 需要点击但不计入物量。 */
    final boolean contributesToNoteCount;

    ArcTap(double[] xy, int time, int sourceArcId, boolean contributesToNoteCount) {
        this.x = xy[0];
        this.y = xy[1];
        this.sourceArcId = sourceArcId;
        this.contributesToNoteCount = contributesToNoteCount;
        super.t1 = time;
        super.t2 = time + CLICK_TIME;
    }

    @Override
    public int getNoteCount() {
        return contributesToNoteCount ? 1 : 0;
    }

    @Override
    public double[] getAffPoint(int time) {
        return new double[]{x, y};
    }

    @Override
    public String toString() {
        return "arctap"
                + " [" + dfTime.format(t1) + ", " + dfTime.format(t2) + "]"
                + " (" + dfXY.format(x) + ", " + dfXY.format(y) + ")";
    }
}
