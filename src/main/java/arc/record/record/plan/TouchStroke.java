package arc.record.record.plan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import arc.record.aff.judge.AffPoint;
import arc.record.aff.judge.HitOpportunity;
import arc.record.aff.note.Arc;
import arc.record.aff.note.Hold;

/**
 * 从一次按下到对应抬起的完整连续触控段落。
 */
public final class TouchStroke {
    /** 完整按下至少保持 50 ms，为整体 ±20 ms 执行偏移保留持续覆盖。 */
    static final int MIN_PRESS_DURATION_MILLIS = 50;

    public enum Kind {
        CLICK,
        HOLD,
        ARC
    }

    private final Kind kind;
    /** 创建物理 DOWN 的来源，不能从之后合入的持续判定身份中推断。 */
    private final int initialSourceId;
    private final double startTime;
    private double endTime;
    private final List<TouchAnchor> anchors = new ArrayList<>();
    private final List<HitOpportunity> hitOpportunities = new ArrayList<>();
    private final List<ArcColorContact> arcColorContacts = new ArrayList<>();
    private final Set<Integer> sourceIds = new LinkedHashSet<>();
    /** 唯一消费本次 DOWN 的普通按键身份；持续覆盖的其他来源不占用该边沿。 */
    private Integer pressSourceId;

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
        this.initialSourceId = sourceId;
        this.startTime = startTime;
        this.endTime = Math.max(endTime, startTime + MIN_PRESS_DURATION_MILLIS);
        anchors.add(new TouchAnchor(startTime, initialPosition));
        sourceIds.add(sourceId);
    }

    /** 复制模板的可变容器；锚点、窗口和未修改的长键来源仅作为只读数据共享。 */
    TouchStroke(TouchStroke source) {
        kind = source.kind;
        initialSourceId = source.initialSourceId;
        startTime = source.startTime;
        endTime = source.endTime;
        anchors.addAll(source.anchors);
        hitOpportunities.addAll(source.hitOpportunities);
        arcColorContacts.addAll(source.arcColorContacts);
        sourceIds.addAll(source.sourceIds);
        pressSourceId = source.pressSourceId;
    }

    public Kind kind() {
        return kind;
    }

    /** 返回实际创建触点的来源，后续共享窗口不会改变这个身份。 */
    int initialSourceId() {
        return initialSourceId;
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

    List<ArcColorContact> arcColorContacts() {
        return List.copyOf(arcColorContacts);
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

    /** 保持当前位置至指定谱面时间，只延长本次按下的结束边界，不增加移动。 */
    void holdUntil(double time) {
        endTime = Math.max(endTime, time);
    }

    /**
     * 求已规划路径在指定时刻的实际位置，用于交接后重选判定机会。
     *
     * @param time 处于当前按下到抬起区间的谱面时间，单位为毫秒
     * @return 按 LINEAR/STEP 语义插值的位置；末锚点之后保持不动
     */
    AffPoint positionAt(double time) {
        if (time < startTime || time > endTime) {
            throw new IllegalArgumentException("时间不在触控生命周期内：" + time);
        }
        int index = Collections.binarySearch(
                anchors, new TouchAnchor(time, initialPosition()), Comparator.comparingDouble(TouchAnchor::time));
        if (index >= 0) {
            return anchors.get(index).position();
        }
        int nextIndex = -index - 1;
        TouchAnchor previous = anchors.get(nextIndex - 1);
        if (nextIndex == anchors.size()) {
            return previous.position();
        }
        TouchAnchor next = anchors.get(nextIndex);
        if (next.transition() == TouchAnchor.Transition.STEP) {
            return previous.position();
        }
        double ratio = (time - previous.time()) / (next.time() - previous.time());
        return new AffPoint(
                previous.position().x() + ratio * (next.position().x() - previous.position().x()),
                previous.position().y() + ratio * (next.position().y() - previous.position().y()));
    }

    /**
     * 判断当前物理按下是否已经分配给一个 Hold、地键或 Arctap。
     *
     * @return 已认领时返回 true，禁止另一个独立按键共用该边沿
     */
    boolean hasAbsorbedPress() {
        return pressSourceId != null;
    }

    /** 返回实际消费 DOWN 的来源，用于接续及重新分配 Hold 覆盖时保留头部身份。 */
    Integer pressSourceId() {
        return pressSourceId;
    }

    /**
     * 添加必须保留且从前一位置线性到达的路径锚点。
     *
     * @param time     到达时间，单位为毫秒
     * @param position AFF 坐标
     */
    public void addAnchor(double time, AffPoint position) {
        addAnchor(new TouchAnchor(time, position));
    }

    /**
     * 添加具有完整输出和过渡语义的路径锚点。
     *
     * @param anchor 待追加锚点；时间不得早于当前最后锚点
     */
    public void addAnchor(TouchAnchor anchor) {
        TouchAnchor previous = anchors.getLast();
        if (anchor.time() < previous.time() - 1e-7) {
            throw new IllegalArgumentException("触控锚点时间倒退");
        }
        if (Math.abs(anchor.time() - previous.time()) <= 1e-7) {
            if (!previous.position().samePosition(anchor.position())) {
                throw new IllegalStateException(
                        "同一触控在同一时刻需要两个不同位置：timing=" + anchor.time()
                                + "，previous=" + previous.position()
                                + "，target=" + anchor.position()
                                + "，sourceIds=" + sourceIds);
            }
            boolean required = previous.required() || anchor.required();
            TouchAnchor.Transition transition = previous.transition() == TouchAnchor.Transition.STEP
                    || anchor.transition() == TouchAnchor.Transition.STEP
                    ? TouchAnchor.Transition.STEP
                    : TouchAnchor.Transition.LINEAR;
            if (required != previous.required() || transition != previous.transition()) {
                anchors.set(anchors.size() - 1,
                        new TouchAnchor(previous.time(), previous.position(), required, transition));
            }
            return;
        }
        anchors.add(anchor);
        if (!previous.position().samePosition(anchor.position())) {
            // 50 ms 约束完整按下时长；移动只需与抬起落在不同的整数毫秒。
            double afterMove = Math.ceil(Math.nextUp((double) Math.round(anchor.time())));
            endTime = Math.max(endTime, afterMove);
        }
    }

    public void addHitOpportunity(HitOpportunity opportunity) {
        addAnchor(opportunity.hitTime(), opportunity.position());
        addCoveredOpportunity(opportunity);
    }

    /**
     * 记录已由现有连续路径覆盖的判定机会，不额外创建位置锚点。
     *
     * @param opportunity 已验证处于当前路径判定范围内的机会
     */
    public void addCoveredOpportunity(HitOpportunity opportunity) {
        hitOpportunities.add(opportunity);
        int sourceId = opportunity.demand().point().source().getSourceId();
        sourceIds.add(sourceId);
        if (opportunity.demand().point().source() instanceof Arc arc) {
            addArcColorContact(arc, opportunity.hitTime());
        }
    }

    /**
     * 保留原路径和判定身份，用按颜色约束重新选定的机会替换Arc规划注记。
     *
     * @param opportunities 同一组原始Arc窗口在当前路径中的合规命中机会
     */
    void replaceArcOpportunities(List<HitOpportunity> opportunities) {
        hitOpportunities.removeIf(hit -> hit.demand().point().source() instanceof Arc);
        arcColorContacts.removeIf(contact -> contact.source().getT1() != contact.source().getT2());
        opportunities.forEach(this::addCoveredOpportunity);
    }

    /** 清理接管重分配前的 Hold 覆盖注记，保留来源身份和唯一头部 DOWN。 */
    void clearHoldOpportunities() {
        hitOpportunities.removeIf(hit -> hit.demand().point().source() instanceof Hold);
    }

    /**
     * 登记不必产生判定或路径锚点的 Arc 染色接触。
     *
     * @param source 提供颜色身份的实体 Arc
     * @param time   接触时间，单位为毫秒
     */
    void addArcColorContact(Arc source, double time) {
        arcColorContacts.add(new ArcColorContact(source, time));
        sourceIds.add(source.getSourceId());
    }

    /**
     * 标记一个按下边沿已由该长键的初始按下承接。
     *
     * @param sourceId 按下需求的来源身份
     * @return 此前未承接其他按下需求时返回 true
     */
    public boolean absorbPress(int sourceId) {
        if (pressSourceId != null) {
            return false;
        }
        pressSourceId = sourceId;
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
                    || !first.position().samePosition(second.position())
                    || first.required() != second.required()
                    || first.transition() != second.transition()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 复制完整按下身份和原路径前缀，以更早的整数毫秒抬起。
     * 调用方须先保证全部判定与颜色入口在新尾部之前完成；最后移动与抬起保留独立时刻。
     *
     * @param time 新的抬起时刻，须晚于初始按下且不晚于当前尾部
     * @return 保留需求及已吸收按下标记的候选，不修改当前轨迹
     */
    public TouchStroke trimmedTo(double time) {
        if (time >= endTime) return this;
        if (time < startTime + MIN_PRESS_DURATION_MILLIS)
            throw new IllegalArgumentException("触点裁剪后不足最短按下时长");
        TouchStroke result = new TouchStroke(kind, startTime, time, initialPosition(), initialSourceId);
        double lastTime = Math.floor(Math.nextDown(time));
        for (TouchAnchor anchor : anchors) {
            if (anchor.time() <= lastTime) result.addAnchor(anchor);
        }
        result.addAnchor(new TouchAnchor(lastTime, positionAt(lastTime), false, TouchAnchor.Transition.LINEAR));
        result.mergeLogicalDemands(this);
        result.pressSourceId = pressSourceId;
        return result;
    }

    public void mergeLogicalDemands(TouchStroke other) {
        hitOpportunities.addAll(other.hitOpportunities);
        arcColorContacts.addAll(other.arcColorContacts);
        sourceIds.addAll(other.sourceIds);
    }

    /**
     * 一次实体 Arc 对当前物理触点产生的颜色接触。
     *
     * @param source Arc 稳定来源
     * @param time   接触时间，单位为毫秒
     */
    record ArcColorContact(Arc source, double time) {
    }
}
