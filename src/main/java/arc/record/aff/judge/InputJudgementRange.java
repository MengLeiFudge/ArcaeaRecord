package arc.record.aff.judge;

import arc.record.aff.note.Note;

/** 固定物件的 AFF 空间规则；生成与回放共用矩形公式，不共享触点归属或命中结论。 */
public final class InputJudgementRange {
    /** 地键、Hold 和天键的横纵半范围，单位为 AFF 坐标，边界包含在内。 */
    public static final double FIXED_RADIUS = 0.2;

    private InputJudgementRange() {
    }

    /**
     * 判断实际触点是否处于固定物件中心的横纵 ±0.2 范围。
     *
     * @param note 原谱地键、Hold 或天键，其原中心不随触控移动
     * @param position 按当前投影反解的实际 AFF 触点位置
     * @return 两个坐标轴同时位于矩形内时为 true；时间、DOWN 和消费顺序另行判断
     */
    public static boolean coversPress(Note note, AffPoint position) {
        double[] center = note.getAffPoint(note.getT1());
        return Math.abs(position.x() - center[0]) <= FIXED_RADIUS
                && Math.abs(position.y() - center[1]) <= FIXED_RADIUS;
    }

    /** Arc 的上下越界触点按原天空边界处理，不用于固定物件的 ±0.2 矩形。 */
    public static AffPoint skyPosition(AffPoint position, double noteY, double ratio46k) {
        double top = 1 + 0.61 * ratio46k;
        double y = Math.max(0, position.y());
        if (noteY <= top) y = Math.min(top, y);
        return new AffPoint(position.x(), y);
    }
}
