package arc.record.aff.judge;

/**
 * AFF 判定坐标系中的不可变位置。
 *
 * @param x 横坐标
 * @param y 纵坐标
 */
public record AffPoint(double x, double y) {
    private static final double SAME_POINT_EPSILON = 1e-8;

    /**
     * 判断两个计算结果是否表示同一个目标坐标。
     *
     * @param other 另一个 AFF 坐标
     * @return 两轴误差均不超过数值计算容差时返回 true
     */
    public boolean samePosition(AffPoint other) {
        return Math.abs(x - other.x) <= SAME_POINT_EPSILON
                && Math.abs(y - other.y) <= SAME_POINT_EPSILON;
    }

    /**
     * 返回到另一点的欧氏距离平方。
     *
     * @param other 目标点
     * @return AFF 坐标距离平方
     */
    public double distanceSquared(AffPoint other) {
        double dx = x - other.x;
        double dy = y - other.y;
        return dx * dx + dy * dy;
    }
}
