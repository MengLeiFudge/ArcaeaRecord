package arc.record.aff.judge;

/**
 * Arc 在 AFF 坐标系中的轴对齐判定范围。
 *
 * <p>横向半径由世界坐标判定宽度 1.9 除以 AFF 到世界坐标的横向倍率 8.5；
 * 纵向半径由世界坐标判定高度 2.5 除以纵向倍率 4.5。边界包含在有效范围内。</p>
 */
public final class ArcJudgementRange {
    private static final double X_RADIUS = 19.0 / 85.0;
    private static final double Y_RADIUS = 5.0 / 9.0;

    private ArcJudgementRange() {
    }

    /**
     * 判断触点是否覆盖指定 Arc 中心。
     *
     * @param arcPosition Arc 在当前时刻的 AFF 中心坐标
     * @param touchPosition 触点的 AFF 坐标
     * @return 两轴距离都位于判定半径内时返回 true
     */
    public static boolean covers(AffPoint arcPosition, AffPoint touchPosition) {
        return Math.abs(arcPosition.x() - touchPosition.x()) <= X_RADIUS
                && Math.abs(arcPosition.y() - touchPosition.y()) <= Y_RADIUS;
    }

    /**
     * 返回触点到 Arc 判定矩形的欧氏距离平方。
     *
     * @param arcPosition Arc 在当前时刻的 AFF 中心坐标
     * @param touchPosition 触点的 AFF 坐标
     * @return 触点位于矩形内时为 0，否则为到最近边界的距离平方
     */
    public static double distanceSquared(AffPoint arcPosition, AffPoint touchPosition) {
        double dx = Math.max(0, Math.abs(arcPosition.x() - touchPosition.x()) - X_RADIUS);
        double dy = Math.max(0, Math.abs(arcPosition.y() - touchPosition.y()) - Y_RADIUS);
        return dx * dx + dy * dy;
    }
}
