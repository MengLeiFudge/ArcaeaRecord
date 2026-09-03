package arc.record.aff.judge;

import arc.record.aff.note.Note;

/**
 * 必须产生按下边沿的 Tap、Arctap 或 Hold 起点需求。
 *
 * @param source   来源 Tap、Arctap 或 Hold
 * @param time     精确按下时间，单位为毫秒
 * @param position AFF 坐标
 */
public record PressDemand(Note source, double time, AffPoint position) {
}
