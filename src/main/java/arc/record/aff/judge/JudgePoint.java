package arc.record.aff.judge;

import arc.record.aff.note.Note;

/**
 * 一个物量身份对应的名义判定点。
 *
 * @param id          模型内稳定编号
 * @param nominalTime 名义判定时间，单位为毫秒
 * @param source      来源 Note
 * @param countsCombo 是否计入谱面物量
 */
public record JudgePoint(int id, double nominalTime, Note source, boolean countsCombo) {
}
