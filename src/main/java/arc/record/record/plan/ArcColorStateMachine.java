package arc.record.record.plan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import arc.record.aff.Aff;
import arc.record.aff.judge.ArcTopology;
import arc.record.aff.judge.HitOpportunity;
import arc.record.aff.note.Arc;
import arc.record.aff.note.ArcType;

/**
 * 按已选命中机会验证 Arc 染色、临时无色、重新染色和组内冷却状态。
 *
 * <p>该状态机只约束触点归属，不生成位置锚点。Arc 曲线仅用于在命中时刻判断异色
 * Arc 是否进入严格靠近范围。</p>
 */
public final class ArcColorStateMachine {
    private static final double X_TOLERANCE_EXCLUSIVE = 0.1;
    private static final double MAX_COOLDOWN_MILLIS = 1000.0;

    private final Aff aff;
    private final ArcTopology topology;
    private final Set<Integer> inputArcIds;
    private final Map<Integer, TouchStroke> touchByColor = new HashMap<>();
    private final Map<TouchStroke, Integer> colorByTouch = new IdentityHashMap<>();
    private final Map<Integer, Double> cooldownUntilByComponent = new HashMap<>();

    /**
     * 创建当前谱面变体的染色状态机。
     *
     * @param aff         谱面和完整 Arc 拓扑
     * @param inputArcIds 当前变体中会产生 Arc 判定需求的来源身份
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
        for (TouchStroke stroke : strokes) {
            if (stroke.kind() != TouchStroke.Kind.ARC) {
                continue;
            }
            for (HitOpportunity opportunity : stroke.hitOpportunities()) {
                if (opportunity.demand().point().source() instanceof Arc arc) {
                    events.add(ColorEvent.hit(opportunity.hitTime(), stroke, arc));
                }
            }
            events.add(ColorEvent.release(colorReleaseTime(stroke), stroke));
        }
        events.sort(Comparator
                .comparingDouble(ColorEvent::time)
                .thenComparing(event -> event.release() ? 1 : 0)
                .thenComparingInt(event -> event.arc() == null
                        ? Integer.MAX_VALUE
                        : event.arc().getSourceId()));

        for (ColorEvent event : events) {
            if (event.release()) {
                release(event.stroke(), event.time());
            } else {
                hit(event.stroke(), event.arc(), event.time());
            }
        }
    }

    private double colorReleaseTime(TouchStroke stroke) {
        return stroke.hitOpportunities().stream()
                .map(HitOpportunity::demand)
                .map(demand -> demand.point().source())
                .filter(Arc.class::isInstance)
                .map(Arc.class::cast)
                .mapToDouble(arc -> topology.component(
                                topology.componentIdOf(arc.getSourceId())).stream()
                        .mapToInt(Arc::getT2)
                        .max()
                        .orElse(arc.getT2()))
                .max()
                .orElse(stroke.endTime());
    }

    private void hit(TouchStroke stroke, Arc arc, double time) {
        UncoloredComponent uncolored = findUncoloredComponent(arc, time);
        if (uncolored.uncolored()) {
            for (int color : uncolored.colors()) {
                unbindColor(color);
            }
            return;
        }

        int componentId = topology.componentIdOf(arc.getSourceId());
        Double cooldownUntil = cooldownUntilByComponent.get(componentId);
        if (cooldownUntil != null && time < cooldownUntil) {
            throw diagnostic("音弧组仍处于抬起冷却", arc, time);
        }

        int color = arc.getColor();
        TouchStroke coloredTouch = touchByColor.get(color);
        if (coloredTouch != null && coloredTouch != stroke) {
            throw diagnostic("同一 Arc 颜色同时绑定多个触点", arc, time);
        }
        Integer touchColor = colorByTouch.get(stroke);
        if (touchColor != null && touchColor != color) {
            throw diagnostic("同一触点同时绑定多个 Arc 颜色", arc, time);
        }
        touchByColor.put(color, stroke);
        colorByTouch.put(stroke, color);
    }

    private void release(TouchStroke stroke, double time) {
        Integer color = colorByTouch.remove(stroke);
        if (color != null && touchByColor.get(color) == stroke) {
            touchByColor.remove(color);
        }
        for (int sourceId : stroke.sourceIds()) {
            if (!inputArcIds.contains(sourceId)) {
                continue;
            }
            int componentId = topology.componentIdOf(sourceId);
            double componentEnd = topology.component(componentId).stream()
                    .mapToDouble(Arc::getT2)
                    .max()
                    .orElse(time);
            if (time < componentEnd) {
                cooldownUntilByComponent.merge(
                        componentId,
                        Math.min(time + MAX_COOLDOWN_MILLIS, componentEnd),
                        Math::max);
            }
        }
    }

    private void unbindColor(int color) {
        TouchStroke stroke = touchByColor.remove(color);
        if (stroke != null && colorByTouch.get(stroke) != null
                && colorByTouch.get(stroke) == color) {
            colorByTouch.remove(stroke);
        }
    }

    private UncoloredComponent findUncoloredComponent(Arc source, double time) {
        List<Arc> active = topology.arcs().stream()
                .filter(arc -> inputArcIds.contains(arc.getSourceId()))
                .filter(arc -> arc.getArcType() == ArcType.FALSE)
                .filter(arc -> time >= arc.getT1() && time <= arc.getT2())
                .toList();
        Set<Arc> component = new LinkedHashSet<>();
        component.add(source);
        boolean changed;
        do {
            changed = false;
            for (Arc candidate : active) {
                if (component.contains(candidate)) {
                    continue;
                }
                for (Arc member : component) {
                    if (member.getColor() != candidate.getColor()
                            && areClose(member, candidate, time)) {
                        component.add(candidate);
                        changed = true;
                        break;
                    }
                }
            }
        } while (changed);

        Set<Integer> colors = new LinkedHashSet<>();
        component.forEach(arc -> colors.add(arc.getColor()));
        return new UncoloredComponent(colors.size() > 1, Set.copyOf(colors));
    }

    private static boolean areClose(Arc first, Arc second, double time) {
        if (first.getArcType() != second.getArcType()) {
            return false;
        }
        double[] firstPosition = first.getAffPoint(time);
        double[] secondPosition = second.getAffPoint(time);
        return Math.abs(firstPosition[0] - secondPosition[0]) < X_TOLERANCE_EXCLUSIVE
                && Double.compare(firstPosition[1], secondPosition[1]) == 0;
    }

    private IllegalStateException diagnostic(String reason, Arc arc, double time) {
        return new IllegalStateException(
                reason + "：" + aff.getAffFile().getAbsolutePath()
                        + "，sourceId=" + arc.getSourceId()
                        + "，timing=" + time);
    }

    private record UncoloredComponent(boolean uncolored, Set<Integer> colors) {
    }

    private record ColorEvent(double time, TouchStroke stroke, Arc arc, boolean release) {
        static ColorEvent hit(double time, TouchStroke stroke, Arc arc) {
            return new ColorEvent(time, stroke, arc, false);
        }

        static ColorEvent release(double time, TouchStroke stroke) {
            return new ColorEvent(time, stroke, null, true);
        }
    }
}
