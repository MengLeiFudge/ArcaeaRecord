package arc.record.aff.note;

import arc.record.aff.judge.LongNoteJudgement;
import lombok.Data;
import lombok.EqualsAndHashCode;

import static arc.record.Utils.dfTime;
import static arc.record.Utils.dfXY;

/**
 * @author MengLeiFudge
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class Hold extends Note {
    /**
     * 所在轨道（0-5）.
     */
    final int track;

    public Hold(String line) {
        String[] data = line.substring("hold(".length(), line.length() - 2).split(",");
        super.t1 = Integer.parseInt(data[0]);
        super.t2 = Integer.parseInt(data[1]);
        this.track = Integer.parseInt(data[2]);
    }

    /**
     * 返回由统一长键公式得到的物量。
     *
     * @return 名义判定点数量
     */
    @Override
    public int getNoteCount() {
        return LongNoteJudgement.nominalTimes(this).size();
    }

    @Override
    public double[] getAffPoint(int time) {
        if (time < t1 || time > t2) {
            throw new IllegalArgumentException("时间 " + time + " 不在 [" + t1 + ", " + t2 + "] 区间内");
        }
        return new double[]{track * 0.5 - 0.75, 0};
    }

    /**
     * 返回最后一个判定点的时机.
     *
     * @return 最后一个判定点的时机
     */
    public int getLastNoteTime() {
        int beatCount = (int) ((t2 - t1) / beatTime);
        if (beatCount <= 2) {
            return (t1 + t2) / 2;
        } else {
            return t1 + (int) (beatTime * beatCount);
        }
    }

    @Override
    public String toString() {
        return "hold  " +
                " [" + dfTime.format(t1) + ", " + dfTime.format(t2) + "]" +
                " (" + dfXY.format(getAffPoint()[0]) + ", " + dfXY.format(getAffPoint()[1]) + ")";
    }
}
