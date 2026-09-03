package arc.record.record.plan;

import arc.record.aff.judge.AffPoint;

/**
 * 一个连续触控段落中真正需要到达的位置。
 *
 * @param time     最迟到达该位置的谱面时间，单位为毫秒
 * @param position 精确 AFF 坐标
 */
public record TouchAnchor(double time, AffPoint position) {
}
