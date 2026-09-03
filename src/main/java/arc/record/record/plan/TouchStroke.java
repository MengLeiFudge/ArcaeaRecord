package arc.record.record.plan;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import arc.record.aff.judge.AffPoint;
import arc.record.aff.judge.HitOpportunity;

/**
 * 从一次按下到对应抬起的完整连续触控段落。
 */
public final class TouchStroke {
    /** record 触点从按下到抬起的最短持续时间，单位为毫秒。 */
    static final int MIN_PRESS_DURATION_MILLIS = 25;

    public enum Kind {
        CLICK,
        HOLD,
        ARC
    }

    private final Kind kind;
    private final double startTime;
    private double endTime;
    private final List<TouchAnchor> anchors = new ArrayList<>();
    private final List<HitOpportunity> hitOpportunities = new ArrayList<>();
    private final Set<Integer> sourceIds = new LinkedHashSet<>();
    private boolean absorbedPress;

    /**
     * 创建连续触控段落并写入唯一必需的初始按下位置。
     *
     * @param kind            触控来源类别
     * @param startTime       按下时间，单位为毫秒
     * @param endTime         抬起时间，单位为毫秒
     * @param initialPosition 按下时 AFF 坐标
     * @param sourceId        首个来源对象稳定身份
     */
    public TouchStroke(Kind kind, double startTime, double endTime,
                       AffPoint initialPosition, int sourceId) {
        if (endTime < startTime) {
            throw new IllegalArgumentException("触控结束时间早于开始时间");
        }
        this.kind = kind;
        this.startTime = startTime;
        this.endTime = Math.max(endTime, startTime + MIN_PRESS_DURATION_MILLIS);
        anchors.add(new TouchAnchor(startTime, initialPosition));
        sourceIds.add(sourceId);
    }

    public Kind kind() {
        return kind;
    }

    public double startTime() {
        return startTime;
    }

    public double endTime() {
        return endTime;
    }

    public List<TouchAnchor> anchors() {
        return List.copyOf(anchors);
    }

    public List<HitOpportunity> hitOpportunities() {
        return List.copyOf(hitOpportunities);
    }

    public Set<Integer> sourceIds() {
        return Set.copyOf(sourceIds);
    }

    public AffPoint initialPosition() {
        return anchors.getFirst().position();
    }

    public AffPoint finalPosition() {
        return anchors.getLast().position();
    }

    /**
     * 添加确实改变目标坐标的位置锚点。
     *
     * @param time     最迟到达时间
     * @param position 新 AFF 坐标
     */
    public void addAnchor(double time, AffPoint position) {
        TouchAnchor previous = anchors.getLast();
        if (time < previous.time() - 1e-7) {
            throw new IllegalArgumentException("触控锚点时间倒退");
        }
        if (Math.abs(time - previous.time()) <= 1e-7) {
            if (previous.position().samePosition(position)) {
                return;
            }
            throw new IllegalStateException("同一触控在同一时刻需要两个不同位置");
        }
        anchors.add(new TouchAnchor(time, position));
        if (!previous.position().samePosition(position)) {
            endTime = Math.max(endTime, time + MIN_PRESS_DURATION_MILLIS);
        }
    }

    public void addHitOpportunity(HitOpportunity opportunity) {
        addAnchor(opportunity.hitTime(), opportunity.position());
        hitOpportunities.add(opportunity);
        sourceIds.add(opportunity.demand().point().source().getSourceId());
    }

    /**
     * 标记一个按下边沿已由该长键的初始按下承接。
     *
     * @param sourceId 按下需求的来源身份
     * @return 此前未承接其他按下需求时返回 true
     */
    public boolean absorbPress(int sourceId) {
        if (absorbedPress) {
            return false;
        }
        absorbedPress = true;
        sourceIds.add(sourceId);
        return true;
    }

    /**
     * 判断两个段落是否能在不增加任何操作的情况下合并。
     *
     * @param other 另一段落
     * @return 起止时间和全部必要锚点一致时返回 true
     */
    public boolean hasSameTrajectory(TouchStroke other) {
        if (kind != other.kind
                || Double.compare(startTime, other.startTime) != 0
                || Double.compare(endTime, other.endTime) != 0
                || anchors.size() != other.anchors.size()) {
            return false;
        }
        for (int i = 0; i < anchors.size(); i++) {
            TouchAnchor first = anchors.get(i);
            TouchAnchor second = other.anchors.get(i);
            if (Double.compare(first.time(), second.time()) != 0
                    || !first.position().samePosition(second.position())) {
                return false;
            }
        }
        return true;
    }

    public void mergeLogicalDemands(TouchStroke other) {
        hitOpportunities.addAll(other.hitOpportunities);
        sourceIds.addAll(other.sourceIds);
    }
}
