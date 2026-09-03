package arc.record.aff.judge;

/**
 * 一个长键判定身份及其可满足窗口。
 *
 * @param point  判定身份
 * @param window 可满足时间与位置
 */
public record CoverageDemand(JudgePoint point, JudgeWindow window) {
}
