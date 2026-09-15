package arc.record.record.plan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import arc.record.aff.Aff;
import arc.record.aff.judge.AffPoint;
import arc.record.aff.judge.ArcTopology;
import arc.record.aff.note.Arc;
import arc.record.aff.note.ArcType;

/**
 * 按已规划颜色接触验证 Arc 染色、即时清色/重染和抬起冷却。
 *
 * <p>该状态机只约束触点归属，不生成位置锚点或判定。异色清色使用世界坐标
 * 距离，触点命中范围仍由判定模型独立处理。</p>
 */
public final class ArcColorStateMachine {
    private static final double MAX_COOLDOWN_MILLIS = 1000.0;
    private static final double TIME_EPSILON = 1e-7;

    private final Aff aff;
    private final ArcTopology topology;
    private final Set<Integer> inputArcIds;
    private final List<ColorEvent> clearEvents = new ArrayList<>();
    private final NavigableMap<Double, Integer> clearTimeline;
    private final Map<TouchStroke, Set<Integer>> colorsByTouch = new IdentityHashMap<>();
    private final Map<Integer, Map<Integer, Cooldown>> cooldownUntilByComponent = new HashMap<>();
    /** 各颜色正在相近的 Arc 对数量，重叠区间结束时不能过早恢复颜色。 */
    private final int[] clearDepth = new int[4];

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
        List<Arc> arcs = topology.arcs().stream()
                .filter(arc -> inputArcIds.contains(arc.getSourceId())).toList();
        for (int i = 0; i < arcs.size(); i++) {
            for (int j = i + 1; j < arcs.size(); j++) {
                if (arcs.get(i).getColor() != arcs.get(j).getColor()) {
                    addColorClearEvents(arcs.get(i), arcs.get(j), clearEvents);
                }
            }
        }
        clearEvents.sort(Comparator.comparingDouble(ColorEvent::time));
        TreeMap<Double, Integer> timeline = new TreeMap<>();
        timeline.put(Double.NEGATIVE_INFINITY, 0);
        int[] depth = new int[4];
        for (ColorEvent event : clearEvents) {
            int mask = 0;
            for (int color = 0; color < depth.length; color++) {
                if ((event.colors() & (1 << color)) != 0) {
                    depth[color] += event.type() == EventType.CLEAR_START ? 1 : -1;
                }
                if (depth[color] > 0) mask |= 1 << color;
            }
            timeline.put(event.time(), mask);
        }
        clearTimeline = Collections.unmodifiableNavigableMap(timeline);
    }

    /** 只读的真实清色边界，供初染/重染选点复用，值为当前无色的颜色位。 */
    NavigableMap<Double, Integer> clearTimeline() {
        return clearTimeline;
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

        events.addAll(clearEvents);

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
                case CLEAR_START -> changeClearColors(event.colors(), 1);
                case CLEAR_END -> changeClearColors(event.colors(), -1);
                case HIT -> hit(event.stroke(), event.arc(), event.time());
                case RELEASE -> release(event.stroke(), event.time());
                case GROUP_END -> cooldownUntilByComponent.remove(event.componentId());
            }
        }
    }

    /** 清色只覆盖真实相近区间，分离后的第一个时刻立即恢复染色。 */
    private void addColorClearEvents(Arc first, Arc second, List<ColorEvent> events) {
        int colors = (1 << first.getColor()) | (1 << second.getColor());
        for (ColorBridgeInterval interval : colorClearIntervals(first, second)) {
            events.add(ColorEvent.clear(interval.startTime(), colors, true));
            events.add(ColorEvent.clear(Math.nextUp(interval.endTime()), colors, false));
        }
    }

    /** 真实清色包含零时长连接与相接端点，不附加持续时长或分离宽限。 */
    static List<ColorBridgeInterval> colorClearIntervals(Arc first, Arc second) {
        if (first.getArcType() != ArcType.FALSE || second.getArcType() != ArcType.FALSE) {
            return List.of();
        }
        double from = Math.max(first.getT1(), second.getT1());
        double to = Math.min(first.getT2(), second.getT2());
        if (to < from) {
            return List.of();
        }
        // 各轴单调，包围矩形已经相隔 2 世界单位时无须逐毫秒检查。
        double[] a = first.getAffPoint(from), b = first.getAffPoint(to);
        double[] c = second.getAffPoint(from), d = second.getAffPoint(to);
        double dx = Math.max(0, Math.max(Math.min(a[0], b[0]), Math.min(c[0], d[0]))
                - Math.min(Math.max(a[0], b[0]), Math.max(c[0], d[0])));
        double dy = Math.max(0, Math.max(Math.min(a[1], b[1]), Math.min(c[1], d[1]))
                - Math.min(Math.max(a[1], b[1]), Math.max(c[1], d[1])));
        double worldX = 8.5 * dx, worldY = 4.5 * dy;
        if (worldX * worldX + worldY * worldY >= 4) return List.of();

        TreeSet<Double> probes = new TreeSet<>();
        probes.add(from);
        for (double time = Math.ceil(from - TIME_EPSILON);
             time < to - TIME_EPSILON; time++) {
            probes.add(time);
        }
        if (to > from) probes.add(Math.nextDown(to));
        probes.add(to);

        List<ColorBridgeInterval> result = new ArrayList<>();
        boolean previousClose = areClose(first, second, from);
        double closeFrom = previousClose ? from : Double.NaN;
        double previousTime = from;
        for (double time : probes) {
            boolean close = areClose(first, second, time);
            if (close != previousClose) {
                // 收紧相邻毫秒内的真实分离边界，不能把一毫秒取整误差当成清色宽限。
                double left = previousTime, right = time;
                for (int iteration = 0; iteration < 48; iteration++) {
                    double middle = (left + right) / 2;
                    if (areClose(first, second, middle) == previousClose) left = middle;
                    else right = middle;
                }
                if (close) closeFrom = right;
                else result.add(new ColorBridgeInterval(closeFrom, left));
            }
            previousClose = close;
            previousTime = time;
        }
        if (previousClose) result.add(new ColorBridgeInterval(closeFrom, to));
        return List.copyOf(result);
    }

    /** 只清除参与接近的颜色，第三种颜色已持有的触点不受影响。 */
    private void changeClearColors(int colors, int delta) {
        for (int color = 0; color < clearDepth.length; color++) {
            if ((colors & (1 << color)) == 0) continue;
            clearDepth[color] += delta;
            if (delta > 0) {
                for (Set<Integer> held : colorsByTouch.values()) held.remove(color);
                for (Map<Integer, Cooldown> cooldowns : cooldownUntilByComponent.values()) cooldowns.remove(color);
            }
        }
    }

    private void hit(TouchStroke stroke, Arc arc, double time) {
        if (clearDepth[arc.getColor()] > 0) {
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
        double dx = (first.x() - second.x()) * 8.5;
        double dy = (first.y() - second.y()) * 4.5;
        return dx * dx + dy * dy;
    }

    private UnsatisfiedColorException diagnostic(String reason, Arc arc, double time) {
        return new UnsatisfiedColorException(
                reason + "：" + aff.getAffFile().getAbsolutePath()
                        + "，sourceId=" + arc.getSourceId()
                        + "，timing=" + time);
    }

    /** 当前路径的颜色归属不可行，保留原谱及触控时刻的诊断信息。 */
    public static final class UnsatisfiedColorException extends IllegalStateException {
        UnsatisfiedColorException(String message) {
            super(message);
        }
    }

    /** 一次仍有效的颜色释放限制，保留原触控段落供不可行候选诊断。 */
    private record Cooldown(double until, TouchStroke released) {
    }

    private enum EventType {
        CLEAR_START(0),
        CLEAR_END(1),
        HIT(2),
        RELEASE(3),
        GROUP_END(4);

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

    /** 清色开始/结束使用颜色位，物件命中和释放保留原来源身份。 */
    private record ColorEvent(double time, EventType type, TouchStroke stroke,
                              Arc arc, int componentId, int colors) {
        static ColorEvent clear(double time, int colors, boolean start) {
            return new ColorEvent(time, start ? EventType.CLEAR_START : EventType.CLEAR_END,
                    null, null, 0, colors);
        }

        static ColorEvent hit(double time, TouchStroke stroke, Arc arc) {
            return new ColorEvent(time, EventType.HIT, stroke, arc, 0, 0);
        }

        static ColorEvent release(double time, TouchStroke stroke) {
            return new ColorEvent(time, EventType.RELEASE, stroke, null, 0, 0);
        }

        static ColorEvent groupEnd(double time, int componentId) {
            return new ColorEvent(time, EventType.GROUP_END, null, null, componentId, 0);
        }
    }
}
