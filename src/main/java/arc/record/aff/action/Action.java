package arc.record.aff.action;

/**
 * 表示一个简单动作.
 * <p>
 * 多个 Action 构成的集合可以表示一次手指操作。
 * 将该集合排序后，最后一个 Action 表示抬起，其余 Action 表示按下。
 *
 * @param x 操作对应的谱面坐标 x
 * @param y 操作对应的谱面坐标 y
 * @param t 操作对应的时间（ms）
 */
public record Action(double x, double y, int t) implements Comparable<Action> {
    @Override
    public int compareTo(Action o) {
        return t - o.t;
    }
}
