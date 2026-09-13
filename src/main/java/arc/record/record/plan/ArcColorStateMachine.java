package arc.record.record.plan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import arc.record.aff.Aff;
import arc.record.aff.judge.AffPoint;
import arc.record.aff.judge.ArcTopology;
import arc.record.aff.note.Arc;
import arc.record.aff.note.ArcType;

/**
 * 按已规划颜色接触验证 Arc 染色、异色清色、临时放行和抬起冷却。
 *
 * <p>该状态机只约束触点归属，不生成位置锚点或判定。异色清色使用世界坐标
 * 距离，触点命中范围仍由判定模型独立处理。</p>
 */
public final class ArcColorStateMachine {
    private static final double COLOR_BRIDGE_MIN_OVERLAP_MILLIS = 17.0;
    /** 局部清色结束后继续允许异色接触的时长，单位为毫秒。 */
    static final double COLOR_GRACE_MILLIS = 500.0;
    private static final double MAX_COOLDOWN_MILLIS = 1000.0;
    private static final double TIME_EPSILON = 1e-7;

    private final Aff aff;
    private final ArcTopology topology;
    private final Set<Integer> inputArcIds;
    private final Map<TouchStroke, Set<Integer>> colorsByTouch = new IdentityHashMap<>();
    private final Map<Integer, Map<Integer, Cooldown>> cooldownUntilByComponent = new HashMap<>();
    private double graceUntil = Double.NEGATIVE_INFINITY;

    /**
     * 创建当前谱面变体的染色状态机。
     *
     * @param aff         谱面和输入实体 Arc 拓扑
     * @param inputArcIds 当前变体中会产生颜色接触的 Arc 来源身份
     */
    public ArcColorStateMachine(Aff aff, Set<Integer> inputArcIds) {
        this.aff = aff;
        this.topology = aff.getArcTopology();
        this.inputArcIds = Set.copyOf(inputArcIds);
    }

    /**
     * 按时间顺序验证全部 Arc 触控段落的颜色状态。
     *
     * @param strokes 已完成窗口求解的触控段落
     */
    public void validate(List<TouchStroke> strokes) {
        List<ColorEvent> events = new ArrayList<>();
        Set<Integer> componentIds = new LinkedHashSet<>();
        for (TouchStroke stroke : strokes) {
            for (TouchStroke.ArcColorContact contact : stroke.arcColorContacts()) {
                Arc source = contact.source();
                events.add(ColorEvent.hit(contact.time(), stroke, source));
                componentIds.add(topology.componentIdOf(source.getSourceId()));
            }
            if (!stroke.arcColorContacts().isEmpty()) {
                events.add(ColorEvent.release(stroke.endTime(), stroke));
            }
        }

        List<Arc> inputArcs = topology.arcs().stream()
                .filter(arc -> inputArcIds.contains(arc.getSourceId()))
                .filter(arc -> arc.getArcType() == ArcType.FALSE)
                .toList();
        for (int i = 0; i < inputArcs.size(); i++) {
            for (int j = i + 1; j < inputArcs.size(); j++) {
                Arc first = inputArcs.get(i);
                Arc second = inputArcs.get(j);
                if (first.getColor() != second.getColor()) {
                    addColorClearEvents(first, second, events);
                }
            }
        }

        for (int componentId : componentIds) {
            double endTime = topology.component(componentId).stream()
                    .mapToInt(Arc::getT2)
                    .max()
                    .orElse(Integer.MIN_VALUE);
            events.add(ColorEvent.groupEnd(endTime, componentId));
        }
        events.sort(Comparator
                .comparingDouble(ColorEvent::time)
                .thenComparingInt(event -> event.type().order())
                .thenComparingInt(event -> event.arc() == null
                        ? Integer.MAX_VALUE
                        : event.arc().getSourceId()));

        for (ColorEvent event : events) {
            switch (event.type()) {
                case CLEAR -> clearColors(event.graceEnd());
                case HIT -> hit(event.stroke(), event.arc(), event.time());
                case RELEASE -> release(event.stroke(), event.time());
                case GROUP_END -> cooldownUntilByComponent.remove(event.componentId());
            }
        }
    }

    /** 候选已经完成分组准入，触点状态按全部真实清色刷新，包括零时长交接。 */
    private void addColorClearEvents(Arc first, Arc second, List<ColorEvent> events) {
        for (ColorBridgeInterval interval : colorClearIntervals(first, second)) {
            events.add(ColorEvent.clear(
                    interval.startTime(), interval.endTime() + COLOR_GRACE_MILLIS));
        }
    }

    /**
     * 提取共同有效至少 17 ms 的两条 Arc 在其中实际相近的各个局部子区间。
     * 子区间自身不设持续时长门槛，并从首次相近候选时刻开始，不向前倒推。
     */
    static List<ColorBridgeInterval> colorBridgeIntervals(Arc first, Arc second) {
        double from = Math.max(first.getT1(), second.getT1());
        double to = Math.min(first.getT2(), second.getT2());
        return to - from < COLOR_BRIDGE_MIN_OVERLAP_MILLIS
                ? List.of() : colorClearIntervals(first, second);
    }

    /** 真实清色包含零时长连接与相接端点；17 ms只用于规划中的跨色合轨准入。 */
    static List<ColorBridgeInterval> colorClearIntervals(Arc first, Arc second) {
        if (first.getArcType() != ArcType.FALSE || second.getArcType() != ArcType.FALSE) {
            return List.of();
        }
        double from = Math.max(first.getT1(), second.getT1());
        double to = Math.min(first.getT2(), second.getT2());
        if (to < from) {
            return List.of();
        }

        TreeSet<Double> probes = new TreeSet<>();
        probes.add(from);
        for (double time = Math.ceil(from - TIME_EPSILON);
             time < to - TIME_EPSILON; time++) {
            probes.add(time);
        }
        if (to > from) probes.add(Math.nextDown(to));
        probes.add(to);

        List<ColorBridgeInterval> result = new ArrayList<>();
        double closeFrom = Double.NaN;
        double closeTo = Double.NaN;
        for (double time : probes) {
            if (areClose(first, second, time)) {
                if (Double.isNaN(closeFrom)) {
                    closeFrom = time;
                }
                closeTo = time;
                continue;
            }
            if (!Double.isNaN(closeFrom)) {
                result.add(new ColorBridgeInterval(closeFrom, closeTo));
            }
            closeFrom = Double.NaN;
            closeTo = Double.NaN;
        }
        if (!Double.isNaN(closeFrom)) {
            result.add(new ColorBridgeInterval(closeFrom, closeTo));
        }
        return List.copyOf(result);
    }

    private void clearColors(double graceEnd) {
        graceUntil = Math.max(graceUntil, graceEnd);
        colorsByTouch.clear();
    }

    private boolean graceActive(double time) {
        return time <= graceUntil + TIME_EPSILON;
    }

    private void hit(TouchStroke stroke, Arc arc, double time) {
        if (graceActive(time)) {
            return;
        }

        int componentId = topology.componentIdOf(arc.getSourceId());
        int color = arc.getColor();
        Cooldown cooldown = cooldownUntilByComponent.getOrDefault(componentId, Map.of()).get(color);
        if (cooldown != null && time < cooldown.until() - TIME_EPSILON) {
            TouchStroke released = cooldown.released();
            throw diagnostic("音弧组仍处于抬起冷却，until=" + cooldown.until()
                    + "，released=[" + released.startTime() + "," + released.endTime()
                    + "]，releaseSources=" + released.sourceIds(), arc, time);
        }

        Set<Integer> colors = colorsByTouch.computeIfAbsent(
                stroke, ignored -> new LinkedHashSet<>());
        if (!colors.isEmpty() && !colors.contains(color)) {
            throw diagnostic("同一触点同时绑定多个 Arc 颜色", arc, time);
        }
        colors.add(color);
    }

    /** 抬起只冷却该触点实际持有的颜色；同一拓扑分量中的异色路径仍可继续。 */
    private void release(TouchStroke stroke, double time) {
        Set<Integer> colors = colorsByTouch.remove(stroke);
        if (colors == null || colors.isEmpty()) return;
        for (int sourceId : stroke.sourceIds()) {
            if (!inputArcIds.contains(sourceId)) {
                continue;
            }
            Arc source = topology.getArc(sourceId);
            if (!colors.contains(source.getColor())) continue;
            int componentId = topology.componentIdOf(sourceId);
            double componentEnd = topology.component(componentId).stream()
                    .mapToDouble(Arc::getT2)
                    .max()
                    .orElse(time);
            if (time + TIME_EPSILON >= componentEnd) {
                cooldownUntilByComponent.remove(componentId);
            } else {
                cooldownUntilByComponent.computeIfAbsent(componentId, ignored -> new HashMap<>()).merge(
                        source.getColor(), new Cooldown(Math.min(time + MAX_COOLDOWN_MILLIS, componentEnd), stroke),
                        (first, second) -> first.until() >= second.until() ? first : second);
            }
        }
    }

    private static boolean areClose(Arc first, Arc second, double time) {
        if (first.getArcType() != ArcType.FALSE || second.getArcType() != ArcType.FALSE) {
            return false;
        }
        double[] firstPosition = first.getAffPoint(time);
        double[] secondPosition = second.getAffPoint(time);
        return areClose(
                new AffPoint(firstPosition[0], firstPosition[1]),
                new AffPoint(secondPosition[0], secondPosition[1]));
    }

    /**
     * 判断两个 AFF 位置是否落入同一清色近域，亦用于前置按键接蛇头的候选筛选。
     *
     * @param first  第一个位置，清色时取当前 Arc 位置，接续时取前置触点位置
     * @param second 第二个位置，清色时取当前另一条 Arc 位置，接续时取真实蛇头入口
     * @return 世界坐标欧氏距离严格小于 2 时返回 true，不产生清色事件
     */
    static boolean areClose(AffPoint first, AffPoint second) {
        return worldDistanceSquared(first, second) < 4.0;
    }

    /**
     * 将 AFF 位置差换算为世界坐标距离平方，供近域筛选和最近候选排序共用。
     *
     * @param first  第一个 AFF 位置
     * @param second 第二个 AFF 位置
     * @return 横轴按 8.5、纵轴按 4.5 换算后的世界距离平方
     */
    static double worldDistanceSquared(AffPoint first, AffPoint second) {
        double dx = first.x() - second.x();
        double dy = first.y() - second.y();
        return 72.25 * dx * dx + 20.25 * dy * dy;
    }

    private UnsatisfiedColorException diagnostic(String reason, Arc arc, double time) {
        return new UnsatisfiedColorException(
                reason + "：" + aff.getAffFile().getAbsolutePath()
                        + "，sourceId=" + arc.getSourceId()
                        + "，timing=" + time);
    }

    /** 明确的候选颜色不可行；调用方可尝试另一策略，普通程序异常不属于此类型。 */
    public static final class UnsatisfiedColorException extends IllegalStateException {
        UnsatisfiedColorException(String message) {
            super(message);
        }
    }

    /** 一次仍有效的颜色释放限制，保留原触控段落供不可行候选诊断。 */
    private record Cooldown(double until, TouchStroke released) {
    }

    private enum EventType {
        CLEAR(0),
        HIT(1),
        RELEASE(2),
        GROUP_END(3);

        private final int order;

        EventType(int order) {
            this.order = order;
        }

        int order() {
            return order;
        }
    }

    record ColorBridgeInterval(double startTime, double endTime) {
    }

    private record ColorEvent(double time, EventType type, TouchStroke stroke,
                              Arc arc, int componentId, double graceEnd) {
        static ColorEvent clear(double time, double graceEnd) {
            return new ColorEvent(time, EventType.CLEAR, null, null, 0, graceEnd);
        }

        static ColorEvent hit(double time, TouchStroke stroke, Arc arc) {
            return new ColorEvent(time, EventType.HIT, stroke, arc, 0, Double.NaN);
        }

        static ColorEvent release(double time, TouchStroke stroke) {
            return new ColorEvent(time, EventType.RELEASE, stroke, null, 0, Double.NaN);
        }

        static ColorEvent groupEnd(double time, int componentId) {
            return new ColorEvent(time, EventType.GROUP_END, null, null, componentId, Double.NaN);
        }
    }
}
