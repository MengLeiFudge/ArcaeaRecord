package arc.record.aff.note;

import lombok.Data;
import lombok.EqualsAndHashCode;

import static arc.record.SettingsAndUtils.CLICK_TIME;

/**
 * @author MengLeiFudge
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class Click extends Note {
    /**
     * 所在轨道，0-5.
     */
    final int lane;

    public Click(String line) {
        String[] data = line.substring("(".length(), line.length() - 2).split(",");
        super.t1 = Integer.parseInt(data[0]);
        super.t2 = super.t1 + CLICK_TIME;
        this.lane = Integer.parseInt(data[1]);
    }

    @Override
    public int getNoteCount() {
        return 1;
    }

    @Override
    public double[] getAffPoint(int time) {
        return new double[]{lane * 0.5 - 0.75, 0};
    }
}
