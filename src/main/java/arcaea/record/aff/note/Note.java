package arcaea.record.aff.note;

import lombok.Data;

import java.io.Serializable;

/**
 * @author MengLeiFudge
 */
@Data
public abstract class Note implements Serializable, Comparable<Note> {
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
}
