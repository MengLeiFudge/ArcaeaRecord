package arc.record.aff.judge;

import arc.record.aff.note.Note;

/**
 * 一个物量身份对应的名义判定点。
 *
 * @param id          模型内稳定编号
 * @param nominalTime 名义判定时间，单位为毫秒
 * @param source      来源 Note
 * @param countsCombo 是否计入谱面物量
 * @param kind        判定点的空间判定类别
 */
public record JudgePoint(int id, double nominalTime, Note source,
                         boolean countsCombo, Kind kind) {
    /**
     * 判定点的空间判定类别。
     */
    public enum Kind {
        /** 按实际命中时刻读取来源长键的位置。 */
        CONTINUOUS,
        /** 连接为至少一拍的 Arc 额外追加、并保守固定在头坐标的判定点。 */
        ARC_HEAD
    }
}
