package arc.record.record.plan;

import arc.record.aff.judge.AffPoint;

/**
 * 连续触控段落中的路径位置及其到达方式。
 *
 * @param time       到达该位置的谱面时间，单位为毫秒
 * @param position   AFF 坐标
 * @param required   是否必须在量化后的 record 中保留该位置
 * @param transition 从前一锚点移动到当前位置的方式
 */
public record TouchAnchor(double time, AffPoint position, boolean required,
                          Transition transition) {
    /**
     * 创建必须保留且从前一位置线性移动到达的锚点。
     *
     * @param time     到达时间，单位为毫秒
     * @param position AFF 坐标
     */
    public TouchAnchor(double time, AffPoint position) {
        this(time, position, true, Transition.LINEAR);
    }

    /** 相邻锚点间的触控位置变化方式。 */
    public enum Transition {
        /** 沿相邻路径检查点之间的线段移动。 */
        LINEAR,
        /** 保持前一位置，到当前锚点时刻再更新坐标。 */
        STEP
    }
}
