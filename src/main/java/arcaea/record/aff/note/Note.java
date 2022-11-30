package arcaea.record.aff.note;

import lombok.Data;

/**
 * @author MengLeiFudge
 */
@Data
public abstract class Note implements Comparable<Note> {
    /**
     * 起始时间.
     */
    int t1;

    /**
     * 结束时间.
     */
    int t2;

    /**
     * 根据按键所在timing的bpm计算的判定块间隔.
     */
    float beatTime;

    /**
     * 返回该键型对应的总note数.
     */
    public abstract int getNoteCount();

    /**
     * 根据传入的时间戳，计算出在谱面上的 xy 坐标.
     *
     * @param time 要计算坐标的谱面时间戳
     * @return 转换后的谱面坐标 x, y
     */
    public abstract double[] getAffPoint(int time);
}
