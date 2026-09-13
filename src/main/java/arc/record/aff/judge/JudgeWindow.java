package arc.record.aff.judge;

import java.util.OptionalDouble;

import arc.record.aff.note.Arc;
import arc.record.aff.note.Hold;
import arc.record.aff.note.Note;

/**
 * 长键判定点能够被成功覆盖的时间和空间约束。
 */
public final class JudgeWindow {
    private static final double POSITION_EPSILON_SQUARED = 1e-12;
    private static final int SEARCH_SEGMENTS = 48;
    private static final int SEARCH_ITERATIONS = 48;

    private final JudgePoint point;
    private final double startTime;
    private final double endTime;

    /**
     * 创建判定窗口。
     *
     * @param point     判定身份
     * @param startTime 实际可满足起始时间，单位为毫秒
     * @param endTime   实际可满足结束时间，单位为毫秒且包含端点
     */
    public JudgeWindow(JudgePoint point, double startTime, double endTime) {
        if (endTime < startTime) {
            throw new IllegalArgumentException("判定窗口结束时间早于起始时间：" + startTime + " > " + endTime);
        }
        this.point = point;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    public JudgePoint point() {
        return point;
    }

    public double startTime() {
        return startTime;
    }

    public double endTime() {
        return endTime;
    }

    /**
     * 计算窗口在指定时刻用于生成候选的中心位置。
     *
     * <p>Arc 的追加头判与普通持续判定都使用当前曲线中心；追加点只定义名义时间与窗口。
     * 零时长物理连接入口由调度器单独保留，不把整段窗口固定在蛇头位置。</p>
     *
     * @param time 窗口内谱面时间，单位为毫秒
     * @return AFF 坐标
     */
    public AffPoint positionAt(double time) {
        if (time < startTime - 1e-7 || time > endTime + 1e-7) {
            throw new IllegalArgumentException("时间不在判定窗口内：" + time);
        }
        Note source = point.source();
        if (source instanceof Arc arc) {
            double[] xy = arc.getAffPoint(time);
            return new AffPoint(xy[0], xy[1]);
        }
        if (source instanceof Hold hold) {
            double[] xy = hold.getAffPoint(hold.getT1());
            return new AffPoint(xy[0], xy[1]);
        }
        throw new IllegalStateException("判定窗口来源不是长键：" + source.getClass().getSimpleName());
    }

    /**
     * 查找指定触点在窗口内首次覆盖来源物件的时刻。
     *
     * <p>Hold 保留规划轨道中心；所有 Arc 均使用随当前曲线中心移动的矩形范围。</p>
     *
     * @param position  希望保持的触点位置
     * @param notBefore 不早于该时刻寻找，单位为毫秒
     * @return 存在覆盖机会时返回对应的最早时刻
     */
    public OptionalDouble firstTimeAt(AffPoint position, double notBefore) {
        return firstTimeAt(position, notBefore, 0, 0);
    }

    /** 在原时间窗口内寻找保留空间余量的命中机会，供窗口规划选择可可靠输出的位置。 */
    public OptionalDouble firstTimeAt(AffPoint position, double notBefore, double xMargin, double yMargin) {
        double from = Math.max(startTime, notBefore);
        if (from > endTime + 1e-7) {
            return OptionalDouble.empty();
        }
        Note source = point.source();
        if (source instanceof Hold) {
            return positionAt(from).samePosition(position)
                    ? OptionalDouble.of(from)
                    : OptionalDouble.empty();
        }
        AffPoint fromPoint = positionAt(from);
        if (ArcJudgementRange.covers(fromPoint, position, xMargin, yMargin)) {
            return OptionalDouble.of(from);
        }

        // Arc各轴在本窗口内单调；整个包围矩形都够不到时，无须执行采样、三分和二分搜索。
        AffPoint endPoint = positionAt(endTime);
        AffPoint nearest = new AffPoint(
                Math.clamp(position.x(), Math.min(fromPoint.x(), endPoint.x()), Math.max(fromPoint.x(), endPoint.x())),
                Math.clamp(position.y(), Math.min(fromPoint.y(), endPoint.y()), Math.max(fromPoint.y(), endPoint.y())));
        if (ArcJudgementRange.distanceSquared(nearest, position, xMargin, yMargin) > POSITION_EPSILON_SQUARED) {
            return OptionalDouble.empty();
        }

        double span = endTime - from;
        double bestDistance = ArcJudgementRange.distanceSquared(fromPoint, position, xMargin, yMargin);
        int bestIndex = 0;
        for (int i = 1; i <= SEARCH_SEGMENTS; i++) {
            double time = from + span * i / SEARCH_SEGMENTS;
            double distance = ArcJudgementRange.distanceSquared(positionAt(time), position, xMargin, yMargin);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = i;
            }
        }
        double left = from + span * Math.max(0, bestIndex - 1) / SEARCH_SEGMENTS;
        double right = from + span * Math.min(SEARCH_SEGMENTS, bestIndex + 1) / SEARCH_SEGMENTS;
        for (int i = 0; i < SEARCH_ITERATIONS; i++) {
            double first = left + (right - left) / 3.0;
            double second = right - (right - left) / 3.0;
            if (ArcJudgementRange.distanceSquared(positionAt(first), position, xMargin, yMargin)
                    <= ArcJudgementRange.distanceSquared(positionAt(second), position, xMargin, yMargin)) {
                right = second;
            } else {
                left = first;
            }
        }
        double refinedTime = (left + right) / 2.0;
        double refinedDistance = ArcJudgementRange.distanceSquared(
                positionAt(refinedTime), position, xMargin, yMargin);
        if (refinedDistance > POSITION_EPSILON_SQUARED) {
            return OptionalDouble.empty();
        }

        // 二分到首次进入数值容差的位置，保证多个合法时刻时选择最早一个。
        left = from;
        right = refinedTime;
        for (int i = 0; i < SEARCH_ITERATIONS; i++) {
            double middle = (left + right) / 2.0;
            if (ArcJudgementRange.distanceSquared(positionAt(middle), position, xMargin, yMargin)
                    <= POSITION_EPSILON_SQUARED) {
                right = middle;
            } else {
                left = middle;
            }
        }
        return OptionalDouble.of(Math.max(from, right));
    }
}
