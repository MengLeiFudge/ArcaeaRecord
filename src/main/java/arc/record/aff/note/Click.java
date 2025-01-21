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
public class Click extends Note {
    /**
     * 所在轨道，0-5.
     */
    final int track;

    public Click(String line) {
        String[] data = line.substring("(".length(), line.length() - 2).split(",");
        super.t1 = Integer.parseInt(data[0]);
        super.t2 = super.t1 + CLICK_TIME;
        this.track = Integer.parseInt(data[1]);
    }

    @Override
    public int getNoteCount() {
        return 1;
    }

    @Override
    public double[] getAffPoint(int time) {
        return new double[]{track * 0.5 - 0.75, 0};
    }

    @Override
    public String toString() {
        return "click " +
                " [" + dfTime.format(t1) + ", " + dfTime.format(t2) + "]" +
                " (" + dfXY.format(getAffPoint()[0]) + ", " + dfXY.format(getAffPoint()[1]) + ")";
    }
}
