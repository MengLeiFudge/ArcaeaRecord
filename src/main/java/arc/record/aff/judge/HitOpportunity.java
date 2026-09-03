package arc.record.aff.judge;

/**
 * 调度器为一个长键判定选择的实际覆盖机会。
 *
 * @param demand  被满足的需求
 * @param hitTime 实际覆盖时刻，单位为毫秒
 * @param position 触点应保持或到达的 AFF 坐标
 */
public record HitOpportunity(CoverageDemand demand, double hitTime, AffPoint position) {
}
