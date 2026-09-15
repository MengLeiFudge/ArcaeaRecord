package arc.record.record.plan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import arc.record.aff.Aff;
import arc.record.aff.judge.AffPoint;
import arc.record.aff.judge.ArcJudgementRange;
import arc.record.aff.judge.ChartJudgementModel;
import arc.record.aff.judge.CoverageDemand;
import arc.record.aff.judge.HitOpportunity;
import arc.record.aff.judge.InputJudgementRange;
import arc.record.aff.judge.LongNoteDemand;
import arc.record.aff.judge.PressDemand;
import arc.record.aff.note.Arc;
import arc.record.aff.note.ArcTap;
import arc.record.aff.note.Hold;
import arc.record.aff.note.Note;
import arc.record.record.model.Resolution;

import static arc.record.Settings.TOUCH_MOVE_DISTANCE_RATIO;
import static arc.record.Settings.TOUCH_SAMPLE_FREQUENCY;

/**
 * 先确定连续 Arc 的物理归属，再接入普通按键及 Hold 的覆盖区间。
 * 原谱物件和判定窗口始终保留；省操作只改变触控路径与生命周期。
 */
public final class TouchScheduler {
    /** 所有操作共同提前或延后的规划余量，单位为毫秒。 */
    private static final int TIMING_MARGIN = 20;
    /** 大 Pure 的 25 ms 中扣除整体时移后，头部共享仍可使用的时间近域。 */
    private static final int HEAD_NEAR_MILLIS = 5;
    /** 持续近域共享至少覆盖 100 ms；短暂交叉不合轨。 */
    private static final int SHARED_DURATION_MILLIS = 100;
    /** 大幅短段才可简化；普通转圈与细小位移继续跟踪原曲线。 */
    private static final int FAST_DURATION_MILLIS = 100;
    private static final double EPSILON = 1e-7;
    /** AFF 空间余量覆盖距离采样、屏幕取整及曲线插值误差。 */
    private static final double X_MARGIN = Math.max(0.003, 4 * TOUCH_MOVE_DISTANCE_RATIO);
    private static final double Y_MARGIN = Math.max(0.003, 5 * TOUCH_MOVE_DISTANCE_RATIO);

    /**
     * 为当前变体生成完整触控计划。
     *
     * @param aff 原谱、输入拓扑和投影变化
     * @param model 当前 Miss/小 Pure 变体的需求
     * @return 按 DOWN 时间排序的连续触控
     */
    public List<TouchStroke> schedule(Aff aff, ChartJudgementModel model) {
        return schedule(aff, model, planArcs(aff, model));
    }

    /**
     * 缓存与普通按键变体无关的 Arc 路径；不提前决定零时长入口的按键归属。
     *
     * @param aff 原谱及严格首尾连接
     * @param model 原始长键判定需求
     * @return 调用方只读的几何模板
     */
    public List<TouchStroke> planArcs(Aff aff, ChartJudgementModel model) {
        List<List<LongNoteDemand>> groups = groupArcs(aff, model.longNoteDemands());
        List<TouchStroke> result = new ArrayList<>();
        for (List<LongNoteDemand> group : groups) {
            List<Arc> arcs = arcsOf(group);
            Set<Integer> simplified = new HashSet<>();
            for (Arc arc : arcs) {
                if (fastLocal(arc)) simplified.add(arc.getSourceId());
            }
            Set<Integer> tried = new HashSet<>(simplified);
            List<CoverageDemand> windows = group.stream().flatMap(d -> d.demands().stream()).toList();
            TouchStroke stroke;
            while (true) {
                stroke = trackArcs(aff, arcs, simplified, false);
                CoverageDemand missing = uncovered(aff, stroke, windows);
                if (missing != null && missing.window().endTime() > missing.point().source().getT2()
                        && missing.point().source().getT2() == arcs.stream().mapToInt(Arc::getT2).max().orElseThrow()) {
                    // 快速尾段在提前执行时可能一直领先曲线；在尾部仍可判的区间保留端点，
                    // 让 ±20 ms 两端都拥有持续覆盖，而不是为通过检查缩小采样余量。
                    stroke.holdUntil(missing.point().source().getT2() + 2 * TIMING_MARGIN + 1);
                    missing = uncovered(aff, stroke, windows);
                }
                if (missing == null) break;
                // 只撤销未能覆盖原窗口的局部简化，不切换普通蛇的全组跟踪策略。
                int sourceId = missing.point().source().getSourceId();
                if (simplified.remove(sourceId)) continue;
                Arc source = (Arc) missing.point().source();
                double span = source.getT2() - source.getT1();
                AffPoint displacement = new AffPoint(Math.abs(source.getX2() - source.getX1()) * TIMING_MARGIN / span,
                        Math.abs(source.getY2() - source.getY1()) * TIMING_MARGIN / span);
                if (tried.add(sourceId) && !ArcJudgementRange.covers(new AffPoint(0, 0), displacement, X_MARGIN, Y_MARGIN)) {
                    // 100 ms 是初始策略门槛；更长但移动同样迅速的局部也按真实覆盖需求处理。
                    simplified.add(sourceId);
                    continue;
                }
                throw unsatisfied(aff, missing, "连续轨迹在整体 ±20 ms 下无法覆盖");
            }
            annotate(aff, stroke, windows);
            double bodyEnd = arcs.stream().mapToInt(Arc::getT2).max().orElseThrow();
            double release = bodyEnd - TIMING_MARGIN - 1;
            if (release >= stroke.startTime() + TouchStroke.MIN_PRESS_DURATION_MILLIS) {
                boolean clearRelease = false;
                for (Arc last : arcs) {
                    if (last.getT2() != bodyEnd) continue;
                    for (Arc other : aff.getArcList()) {
                        if (last.getColor() == other.getColor()) continue;
                        if (ArcColorStateMachine.colorClearIntervals(last, other).stream().anyMatch(interval ->
                                interval.startTime() <= release - TIMING_MARGIN
                                        && interval.endTime() >= release + TIMING_MARGIN)) {
                            clearRelease = true;
                            break;
                        }
                    }
                }
                if (clearRelease) {
                    TouchStroke trimmed = stroke.trimmedTo(release);
                    if (uncovered(aff, trimmed, windows) == null) {
                        trimmed.replaceArcOpportunities(windows.stream().map(window -> firstHit(aff, trimmed, window, 0)).toList());
                        stroke = trimmed;
                    }
                }
            }
            result.add(stroke);
        }
        return List.copyOf(result);
    }

    /**
     * 复制几何模板，按本变体的真实按键确定 DOWN、Hold 接管和最终 UP。
     *
     * @param aff 与模板一致的原谱
     * @param model 当前普通按键和长键需求
     * @param arcs 本谱面的只读 Arc 模板
     * @return 当前变体独立可变状态形成的最终计划
     */
    public List<TouchStroke> schedule(Aff aff, ChartJudgementModel model, List<TouchStroke> arcs) {
        List<TouchStroke> strokes = new ArrayList<>();
        for (TouchStroke arc : arcs) strokes.add(new TouchStroke(arc));
        continuePaddedArcs(aff, strokes);
        Set<Integer> claimed = new HashSet<>();
        attachHeads(aff, model.pressDemands(), strokes, claimed, true);
        for (LongNoteDemand demand : model.longNoteDemands()) {
            if (demand.source() instanceof Hold hold) {
                planHold(aff, hold, demand.demands(), model.pressDemands(), strokes, claimed);
            }
        }
        continueHoldTails(aff, model, strokes);
        attachHeads(aff, model.pressDemands(), strokes, claimed, false);
        Set<Integer> inputIds = aff.getArcList().stream().map(Arc::getSourceId)
                .collect(java.util.stream.Collectors.toSet());
        ArcColorStateMachine colors = new ArcColorStateMachine(aff, inputIds);
        secureEntries(aff, model.pressDemands(), strokes, colors.clearTimeline());
        // 初染/重染选点可能改变离轨边界；按最终 Arc 路径重建 Hold 接管，保留已接续的物理前缀。
        strokes.removeIf(stroke -> stroke.kind() == TouchStroke.Kind.HOLD);
        claimed.clear();
        for (TouchStroke stroke : strokes) {
            stroke.clearHoldOpportunities();
            if (stroke.hasAbsorbedPress()) claimed.add(stroke.pressSourceId());
        }
        for (LongNoteDemand demand : model.longNoteDemands()) {
            if (demand.source() instanceof Hold hold) {
                planHold(aff, hold, demand.demands(), model.pressDemands(), strokes, claimed);
            }
        }
        for (PressDemand demand : model.pressDemands()) {
            if (claimed.contains(demand.source().getSourceId())) continue;
            AffPoint position = pressPosition(aff, demand, model.pressDemands(), null);
            TouchStroke press = new TouchStroke(TouchStroke.Kind.CLICK, demand.time(),
                    demand.source().getT2(), position, demand.source().getSourceId());
            press.absorbPress(demand.source().getSourceId());
            strokes.add(press);
        }
        colors.validate(strokes);
        strokes.sort(Comparator.comparingDouble(TouchStroke::startTime)
                .thenComparingDouble(TouchStroke::endTime)
                .thenComparing(stroke -> stroke.kind().ordinal()));
        return List.copyOf(strokes);
    }

    /** 按严格连接构建路径；相同源几何共享输出，不删除任何需求身份。 */
    private static List<List<LongNoteDemand>> groupArcs(Aff aff, List<LongNoteDemand> demands) {
        List<List<LongNoteDemand>> groups = new ArrayList<>();
        for (LongNoteDemand demand : demands) {
            if (!(demand.source() instanceof Arc next)) continue;
            List<LongNoteDemand> selected = null;
            for (List<LongNoteDemand> group : groups) {
                Arc last = (Arc) group.getLast().source();
                if (sameGeometry(last, next) || connectedHandoff(aff, last, next)) {
                    // 分支只能在共同区间也能贴线时共享，不能仅凭同一拓扑分量强合。
                    if (canShareDuringOverlap(aff, arcsOf(group), List.of(next), false)) {
                        selected = group;
                        break;
                    }
                }
            }
            if (selected == null) {
                selected = new ArrayList<>();
                groups.add(selected);
            }
            selected.add(demand);
        }
        for (int i = 0; i < groups.size(); i++) {
            for (int j = groups.size() - 1; j > i; j--) {
                List<Arc> first = arcsOf(groups.get(i)), second = arcsOf(groups.get(j));
                if (!canShareDuringOverlap(aff, first, second, true)) continue;
                groups.get(i).addAll(groups.remove(j));
                groups.get(i).sort(Comparator.comparingInt((LongNoteDemand d) -> d.source().getT1())
                        .thenComparingInt(d -> d.source().getSourceId()));
            }
        }
        return groups;
    }

    /** 完整共同区间都处于近域才共享，后续仍分开的路径会在此被拒绝。 */
    private static boolean canShareDuringOverlap(
            Aff aff, List<Arc> first, List<Arc> second, boolean requireLongOverlap) {
        double from = Math.max(first.getFirst().getT1(), second.getFirst().getT1());
        double to = Math.min(first.stream().mapToInt(Arc::getT2).max().orElseThrow(),
                second.stream().mapToInt(Arc::getT2).max().orElseThrow());
        if (requireLongOverlap && to - from < SHARED_DURATION_MILLIS) return false;
        if (requireLongOverlap && first.stream().anyMatch(a -> second.stream().anyMatch(b -> a.getColor() != b.getColor()))
                && (first.getFirst().getT1() != second.getFirst().getT1()
                || first.stream().mapToInt(Arc::getT2).max().orElseThrow()
                != second.stream().mapToInt(Arc::getT2).max().orElseThrow())) return false;
        for (Arc a : first) {
            for (Arc b : second) {
                if (connectedHandoff(aff, a, b) || connectedHandoff(aff, b, a)) continue;
                double begin = Math.max(a.getT1(), b.getT1());
                double end = Math.min(a.getT2(), b.getT2());
                for (double t = begin; t <= end; t++) {
                    AffPoint p = pointAt(a, t), q = pointAt(b, t);
                    AffPoint middle = new AffPoint((p.x() + q.x()) / 2, (p.y() + q.y()) / 2);
                    if (!ArcColorStateMachine.areClose(p, q)
                            || !ArcJudgementRange.covers(p, middle, X_MARGIN, Y_MARGIN)
                            || !ArcJudgementRange.covers(q, middle, X_MARGIN, Y_MARGIN)) return false;
                    for (int offset : new int[]{-TIMING_MARGIN, TIMING_MARGIN}) {
                        AffPoint pa = pointAt(a, t + offset), pb = pointAt(b, t + offset);
                        AffPoint moved = new AffPoint((pa.x() + pb.x()) / 2, (pa.y() + pb.y()) / 2);
                        if (!ArcJudgementRange.covers(p, moved, X_MARGIN, Y_MARGIN)
                                || !ArcJudgementRange.covers(q, moved, X_MARGIN, Y_MARGIN)) return false;
                    }
                }
            }
        }
        return true;
    }

    /** 完全相同的颜色和缓动轨迹可共用一条路径，原 BPM/物量仍各自保存。 */
    private static boolean sameGeometry(Arc first, Arc second) {
        return first.getColor() == second.getColor()
                && first.getT1() == second.getT1() && first.getT2() == second.getT2()
                && first.getX1() == second.getX1() && first.getX2() == second.getX2()
                && first.getY1() == second.getY1() && first.getY2() == second.getY2()
                && first.getEasing().equals(second.getEasing());
    }

    /** 沿严格后继和零时长连接寻路；时间接近本身不创建拓扑连接。 */
    private static boolean connectedHandoff(Aff aff, Arc from, Arc to) {
        if (from.getSourceId() == to.getSourceId() || from.getColor() != to.getColor()
                || to.getT1() < from.getT1()) return false;
        ArrayDeque<Arc> pending = new ArrayDeque<>(aff.getArcTopology().successorsOf(from.getSourceId()));
        Set<Integer> seen = new HashSet<>();
        while (!pending.isEmpty()) {
            Arc next = pending.removeFirst();
            if (next.getSourceId() == to.getSourceId()) return true;
            if (next.getT1() == next.getT2() && seen.add(next.getSourceId())) {
                pending.addAll(aff.getArcTopology().successorsOf(next.getSourceId()));
            }
        }
        return false;
    }

    /** 保留原曲线边界和按 BPM 采样的细节；只有指定极速局部采用自身中点。 */
    private static TouchStroke trackArcs(Aff aff, List<Arc> arcs, Set<Integer> simplified, boolean handoff) {
        TreeMap<Double, Boolean> times = new TreeMap<>();
        for (Arc arc : arcs) {
            times.put((double) arc.getT1(), true);
            times.put((double) arc.getT2(), true);
            double bpm = arc.getJudgeBpm() != 0 ? arc.getJudgeBpm() : arc.getTimingBpm();
            double step = Math.max(1, Math.min(8, 30000.0 / bpm / TOUCH_SAMPLE_FREQUENCY));
            for (double t = arc.getT1() + step; t < arc.getT2(); t += step) {
                times.putIfAbsent((double) Math.round(t), false);
            }
        }
        List<Arc> leading = leadingZeros(aff, arcs);
        Arc first = arcs.getFirst();
        double start = leading.stream().mapToInt(Arc::getT1).min().orElse(first.getT1());
        start = Math.min(start, first.getT1());
        double bodyEnd = arcs.stream().mapToInt(Arc::getT2).max().orElseThrow();
        double end = bodyEnd;
        for (Arc arc : arcs) {
            if (arc.getT2() == bodyEnd && arc.getT2() - arc.getT1() < FAST_DURATION_MILLIS) {
                end = Math.max(end, Math.max(bodyEnd + TIMING_MARGIN,
                        arc.getT1() + TouchStroke.MIN_PRESS_DURATION_MILLIS));
            }
        }
        AffPoint initial = trackingPosition(aff, arcs, start, simplified, handoff);
        if (!leading.isEmpty()) {
            // 无头部按键时从最后一个前导连接的尾部起按，不走它的空起点。
            Arc tail = leading.stream().filter(zero -> aff.getArcTopology()
                            .successorsOf(zero.getSourceId()).stream()
                            .anyMatch(next -> next.getSourceId() == first.getSourceId()))
                    .min(Comparator.comparingInt(Arc::getSourceId)).orElse(leading.getLast());
            initial = new AffPoint(tail.getX2(), tail.getY2());
        }
        TouchStroke stroke = new TouchStroke(TouchStroke.Kind.ARC, start, end,
                onScreen(initial, aff.getRatio46k(start)), first.getSourceId());
        times.putIfAbsent(start + 1, false);
        for (Map.Entry<Double, Boolean> marker : times.entrySet()) {
            double t = marker.getKey();
            if (t <= start || t > bodyEnd) continue;
            AffPoint position = trackingPosition(aff, arcs, t, simplified, handoff);
            boolean step = arcs.stream().anyMatch(arc -> arc.getT1() == t);
            stroke.addAnchor(new TouchAnchor(t, onScreen(position, aff.getRatio46k(t)), marker.getValue(),
                    step ? TouchAnchor.Transition.STEP : TouchAnchor.Transition.LINEAR));
        }
        return stroke;
    }

    /** 当前仍活动的并行路径取交集中心；严格前驱已交接后不再拉回旧尾。 */
    private static AffPoint trackingPosition(Aff aff, List<Arc> arcs, double time, Set<Integer> simplified, boolean handoff) {
        List<Arc> active = arcs.stream().filter(arc -> time >= arc.getT1() && time <= arc.getT2())
                .filter(arc -> arcs.stream().noneMatch(next -> next.getT1() <= time
                        && connectedHandoff(aff, arc, next))).toList();
        if (active.isEmpty()) {
            Arc before = arcs.stream().filter(arc -> arc.getT2() < time)
                    .max(Comparator.comparingInt(Arc::getT2)).orElse(null);
            Arc after = arcs.stream().filter(arc -> arc.getT1() > time)
                    .min(Comparator.comparingInt(Arc::getT1)).orElse(null);
            if (before == null) return plannedPoint(arcs.getFirst(), time, simplified);
            if (after == null) return plannedPoint(before, time, simplified);
            AffPoint a = plannedPoint(before, before.getT2(), simplified);
            AffPoint b = plannedPoint(after, after.getT1(), simplified);
            double ratio = (time - before.getT2()) / (after.getT1() - before.getT2());
            return new AffPoint(a.x() + (b.x() - a.x()) * ratio, a.y() + (b.y() - a.y()) * ratio);
        }
        if (handoff) return plannedPoint(active.getLast(), time, simplified);
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (Arc arc : active) {
            AffPoint p = plannedPoint(arc, time, simplified);
            minX = Math.min(minX, p.x());
            maxX = Math.max(maxX, p.x());
            minY = Math.min(minY, p.y());
            maxY = Math.max(maxY, p.y());
        }
        return new AffPoint((minX + maxX) / 2, (minY + maxY) / 2);
    }

    /** 短段在整体时移下的位移已超出空间余量时，也需要作为极速局部处理。 */
    private static boolean fastLocal(Arc arc) {
        double span = arc.getT2() - arc.getT1();
        double dx = Math.abs(arc.getX2() - arc.getX1()), dy = Math.abs(arc.getY2() - arc.getY1());
        return span > 0 && span <= FAST_DURATION_MILLIS && (dx >= 0.5 || dy >= 1.0
                || !ArcJudgementRange.covers(new AffPoint(0, 0),
                new AffPoint(dx * TIMING_MARGIN / span, dy * TIMING_MARGIN / span), X_MARGIN, Y_MARGIN));
    }

    /** 中点简化仅改变该来源的短局部，不改变其窗口，也不借用另一颜色触点。 */
    private static AffPoint plannedPoint(Arc arc, double time, Set<Integer> simplified) {
        return pointAt(arc, simplified.contains(arc.getSourceId())
                ? ((double) arc.getT1() + arc.getT2()) / 2 : time);
    }

    /** 首尾余量期间维持端点，避免向曲线公式传入物件范围外的时间。 */
    private static AffPoint pointAt(Arc arc, double time) {
        double[] xy = arc.getAffPoint(Math.clamp(time, arc.getT1(), arc.getT2()));
        return new AffPoint(xy[0], xy[1]);
    }

    /** 对超出可输出画面的源坐标只取最近屏幕边界，随后仍验证原判定区域。 */
    private static AffPoint onScreen(AffPoint point, double ratio) {
        Resolution resolution = Resolution.R16_9_1280_720;
        int[] xy = resolution.convertToXY(point.x(), point.y(), ratio);
        if (xy[0] >= 0 && xy[0] <= resolution.getMaxX()
                && xy[1] >= 0 && xy[1] <= resolution.getMaxY()) return point;
        return resolution.convertToAffPoint(Math.clamp(xy[0], 0, resolution.getMaxX()),
                Math.clamp(xy[1], 0, resolution.getMaxY()), ratio);
    }

    /** 查找前导零时长连接，保留方向并防止同刻连接环重复访问。 */
    private static List<Arc> leadingZeros(Aff aff, List<Arc> arcs) {
        Map<Integer, Arc> result = new LinkedHashMap<>();
        int firstTime = arcs.getFirst().getT1();
        ArrayDeque<Arc> pending = new ArrayDeque<>(arcs.stream()
                .filter(arc -> arc.getT1() == firstTime).toList());
        while (!pending.isEmpty()) {
            Arc current = pending.removeFirst();
            for (Arc previous : aff.getArcTopology().predecessorsOf(current.getSourceId())) {
                if (previous.getT1() == previous.getT2()
                        && result.putIfAbsent(previous.getSourceId(), previous) == null) pending.addLast(previous);
            }
        }
        return result.values().stream().sorted(Comparator.comparingInt(Arc::getT1)
                .thenComparingInt(Arc::getSourceId)).toList();
    }

    /** 从模板恢复真实蛇头及前导连接起点，普通按键在本变体内再认领这些入口。 */
    private static List<ConnectionEntry> entries(Aff aff, TouchStroke stroke) {
        List<Arc> arcs = sourceArcs(stroke);
        List<Arc> leading = leadingZeros(aff, arcs);
        Set<Integer> ids = leading.stream().map(Arc::getSourceId)
                .collect(java.util.stream.Collectors.toSet());
        List<ConnectionEntry> entries = new ArrayList<>();
        for (Arc zero : leading) {
            if (aff.getArcTopology().predecessorsOf(zero.getSourceId()).stream()
                    .noneMatch(previous -> ids.contains(previous.getSourceId()))) {
                entries.add(new ConnectionEntry(zero.getT1(), new AffPoint(zero.getX1(), zero.getY1())));
            }
        }
        if (entries.isEmpty()) {
            Arc first = arcs.getFirst();
            entries.add(new ConnectionEntry(first.getT1(), new AffPoint(first.getX1(), first.getY1())));
        }
        entries.add(new ConnectionEntry(stroke.startTime(), stroke.initialPosition()));
        return entries;
    }

    /** 同色物理接续保留原拓扑；短空隙或同组冷却期间不重复建立 DOWN。 */
    private static void continuePaddedArcs(Aff aff, List<TouchStroke> strokes) {
        strokes.sort(Comparator.comparingDouble(TouchStroke::startTime));
        for (int i = 0; i < strokes.size(); i++) {
            TouchStroke first = strokes.get(i);
            for (int j = i + 1; j < strokes.size(); j++) {
                TouchStroke next = strokes.get(j);
                List<Arc> before = sourceArcs(first), after = sourceArcs(next);
                double bodyEnd = before.stream().mapToInt(Arc::getT2).max().orElseThrow();
                double gap = next.startTime() - bodyEnd;
                if (gap >= 1000) break;
                boolean sameComponent = before.stream().anyMatch(a -> after.stream().anyMatch(b ->
                        aff.getArcTopology().componentIdOf(a.getSourceId())
                                == aff.getArcTopology().componentIdOf(b.getSourceId())));
                boolean groupContinues = before.stream().anyMatch(a -> aff.getArcTopology()
                        .component(aff.getArcTopology().componentIdOf(a.getSourceId())).stream()
                        .anyMatch(member -> member.getT2() >= next.startTime() - TIMING_MARGIN));
                if (gap > 100 && !sameComponent && !groupContinues
                        || before.stream().anyMatch(a -> after.stream().anyMatch(b -> a.getColor() != b.getColor()))) {
                    continue;
                }
                TouchStroke merged = new TouchStroke(TouchStroke.Kind.ARC, first.startTime(),
                        Math.max(first.endTime(), next.endTime()), first.initialPosition(),
                        before.getFirst().getSourceId());
                for (TouchAnchor anchor : first.anchors()) {
                    if (anchor.time() > first.startTime() && anchor.time() < next.startTime()) merged.addAnchor(anchor);
                }
                if (next.startTime() > merged.startTime()) {
                    merged.addAnchor(new TouchAnchor(next.startTime(), next.initialPosition(), true,
                            TouchAnchor.Transition.STEP));
                }
                for (TouchAnchor anchor : next.anchors()) {
                    if (anchor.time() > next.startTime()) merged.addAnchor(anchor);
                }
                List<CoverageDemand> windows = new ArrayList<>(first.hitOpportunities().stream()
                        .map(HitOpportunity::demand).toList());
                windows.addAll(next.hitOpportunities().stream().map(HitOpportunity::demand).toList());
                CoverageDemand missing = uncovered(aff, merged, windows);
                if (missing != null && gap < 0) {
                    // 同色短物件插入持续路径时尝试按出现顺序交接，保留每个原窗口。
                    // 这里跟随当前交接物件的真实位置，不将分开的并行曲线取平均。
                    List<Arc> ordered = java.util.stream.Stream.concat(before.stream(), after.stream()).distinct()
                            .sorted(Comparator.comparingInt(Arc::getT1).thenComparingInt(Arc::getSourceId)).toList();
                    merged = trackArcs(aff, ordered, Set.of(), true);
                    merged.holdUntil(Math.max(first.endTime(), next.endTime()));
                    missing = uncovered(aff, merged, windows);
                    Set<Integer> delayed = new HashSet<>();
                    while (missing != null) {
                        Arc source = (Arc) missing.point().source();
                        if (source.getX1() != source.getX2() || source.getY1() != source.getY2()
                                || !delayed.add(source.getSourceId())) break;
                        Arc nextArc = ordered.stream().filter(arc -> arc.getT1() > source.getT1()).findFirst().orElse(null);
                        if (nextArc == null) break;
                        double switchTime = nextArc.getT1();
                        double until = Math.ceil(missing.window().startTime()) + TIMING_MARGIN + 1;
                        if (until <= switchTime || until > switchTime + TIMING_MARGIN || until >= merged.endTime()) break;
                        AffPoint held = merged.positionAt(Math.nextDown(switchTime));
                        if (!ArcJudgementRange.covers(pointAt(source, source.getT1()), held, X_MARGIN, Y_MARGIN)) break;
                        // 静止短段的最后一个名义点可能比下一次交接晚半毫秒；保留提前偏移所需的停留。
                        TreeMap<Double, TouchAnchor> adjusted = new TreeMap<>();
                        for (TouchAnchor anchor : merged.anchors()) adjusted.put(anchor.time(), anchor);
                        adjusted.subMap(switchTime, true, until, true).clear();
                        adjusted.put(switchTime, new TouchAnchor(switchTime, held, true, TouchAnchor.Transition.STEP));
                        adjusted.put(until, new TouchAnchor(until, merged.positionAt(until), true, TouchAnchor.Transition.STEP));
                        TouchStroke extended = new TouchStroke(TouchStroke.Kind.ARC, merged.startTime(), merged.endTime(),
                                merged.initialPosition(), merged.initialSourceId());
                        for (TouchAnchor anchor : adjusted.values()) {
                            if (anchor.time() > extended.startTime()) extended.addAnchor(anchor);
                        }
                        merged = extended;
                        missing = uncovered(aff, merged, windows);
                    }
                }
                if (missing != null) {
                    if (gap < 0) continue;
                    throw unsatisfied(aff, missing, "短尾占用与后继接续冲突");
                }
                annotate(aff, merged, windows);
                strokes.set(i, merged);
                first = merged;
                strokes.remove(j--);
            }
        }
    }

    /** 共同头部只有一个 DOWN；后续整条 Arc 从该边沿继承物理身份。 */
    private static void attachHeads(Aff aff, List<PressDemand> demands, List<TouchStroke> strokes,
                                    Set<Integer> claimed, boolean holdsOnly) {
        Set<Integer> holdIds = demands.stream().filter(d -> d.source() instanceof Hold)
                .map(d -> d.source().getSourceId()).collect(java.util.stream.Collectors.toSet());
        for (int index = 0; index < strokes.size(); index++) {
            TouchStroke arc = strokes.get(index);
            if (arc.kind() != TouchStroke.Kind.ARC || arc.hasAbsorbedPress()
                    || holdIds.contains(arc.initialSourceId())) continue;
            List<ConnectionEntry> entries = entries(aff, arc);
            PressDemand best = null;
            AffPoint position = null;
            double distance = Double.POSITIVE_INFINITY;
            for (PressDemand demand : demands) {
                if ((demand.source() instanceof Hold) != holdsOnly
                        || claimed.contains(demand.source().getSourceId())) continue;
                for (ConnectionEntry entry : entries) {
                    // 提前存在的短点击在晚偏移时仍可能染色，按其按住区间加时移余量接续。
                    double near = holdsOnly ? HEAD_NEAR_MILLIS : 100.0 + TIMING_MARGIN;
                    if (Math.abs(demand.time() - entry.time()) > near
                            || demand.time() > arc.startTime() + HEAD_NEAR_MILLIS
                            || demand.time() + 1 >= arc.endTime()) continue;
                    AffPoint candidate = pressPosition(aff, demand, demands, entry.position());
                    if (candidate == null) continue;
                    double d = ArcColorStateMachine.worldDistanceSquared(candidate, entry.position());
                    if (d < distance) {
                        best = demand;
                        position = candidate;
                        distance = d;
                    }
                }
            }
            if (best == null) continue;
            TouchStroke merged = new TouchStroke(TouchStroke.Kind.ARC, best.time(),
                    Math.max(arc.endTime(), best.time()), position, best.source().getSourceId());
            double switchTime = Math.max(arc.startTime(), best.time() + 1);
            merged.addAnchor(new TouchAnchor(switchTime, arc.positionAt(switchTime), true,
                    TouchAnchor.Transition.STEP));
            for (TouchAnchor anchor : arc.anchors()) {
                if (anchor.time() > switchTime) merged.addAnchor(anchor);
            }
            List<CoverageDemand> windows = arc.hitOpportunities().stream()
                    .filter(hit -> hit.demand().point().source() instanceof Arc).map(HitOpportunity::demand).toList();
            CoverageDemand missing = uncovered(aff, merged, windows);
            if (missing != null) throw unsatisfied(aff, missing, "共同头部接续无法保持原窗口覆盖");
            annotate(aff, merged, windows);
            merged.absorbPress(best.source().getSourceId());
            claimed.add(best.source().getSourceId());
            strokes.set(index, merged);
        }
    }

    /**
     * Hold 已结束时可把其尾部触点交给 Arc；输入仍只有一次 DOWN，避免晚偏移下释放已染色触点。
     * 该接续不重定义原 Hold 生命周期，头部共用和离轨接管仍由各自原需求决定。
     */
    private static void continueHoldTails(Aff aff, ChartJudgementModel model, List<TouchStroke> strokes) {
        Map<Integer, Hold> holds = new LinkedHashMap<>();
        for (LongNoteDemand demand : model.longNoteDemands()) {
            if (demand.source() instanceof Hold hold) holds.put(hold.getSourceId(), hold);
        }
        List<TouchStroke> arcs = strokes.stream().filter(stroke -> stroke.kind() == TouchStroke.Kind.ARC).toList();
        for (TouchStroke arc : arcs) {
            if (holds.containsKey(arc.initialSourceId())) continue;
            for (TouchStroke prefix : List.copyOf(strokes)) {
                if (prefix.kind() != TouchStroke.Kind.HOLD) continue;
                Hold hold = holds.get(prefix.initialSourceId());
                if (prefix.startTime() >= arc.startTime()
                        || prefix.endTime() < arc.startTime() - 100
                        || hold.getT2() < arc.startTime() - 100
                        || hold.getT2() > arc.startTime() + HEAD_NEAR_MILLIS
                        && prefix.endTime() > arc.startTime() + HEAD_NEAR_MILLIS
                        || !ArcColorStateMachine.areClose(prefix.finalPosition(), arc.initialPosition())
                        && !coversHold(aff, hold, arc.initialPosition(), arc.startTime())
                        && !ArcJudgementRange.covers(arc.positionAt(Math.clamp(prefix.endTime() + TIMING_MARGIN,
                                arc.startTime(), arc.endTime())), prefix.finalPosition())) continue;
                double switchTime = Math.max(Math.min(hold.getT2(), prefix.endTime()), arc.startTime());
                if (switchTime >= arc.endTime()) continue;
                TouchStroke merged = new TouchStroke(TouchStroke.Kind.ARC, prefix.startTime(),
                        Math.max(prefix.endTime(), arc.endTime()), prefix.initialPosition(), hold.getSourceId());
                for (TouchAnchor anchor : prefix.anchors()) {
                    if (anchor.time() > prefix.startTime() && anchor.time() < switchTime) merged.addAnchor(anchor);
                }
                merged.addAnchor(new TouchAnchor(switchTime, arc.positionAt(switchTime), true,
                        TouchAnchor.Transition.STEP));
                for (TouchAnchor anchor : arc.anchors()) {
                    if (anchor.time() > switchTime) merged.addAnchor(anchor);
                }
                List<CoverageDemand> windows = new ArrayList<>(prefix.hitOpportunities().stream()
                        .map(HitOpportunity::demand).toList());
                windows.addAll(arc.hitOpportunities().stream().map(HitOpportunity::demand).toList());
                if (uncovered(aff, merged, windows) != null) continue;
                annotate(aff, merged, windows);
                if (prefix.hasAbsorbedPress()) merged.absorbPress(hold.getSourceId());
                strokes.remove(prefix);
                strokes.set(strokes.indexOf(arc), merged);
                break;
            }
        }
    }

    /**
     * 按 Arc 的真实离轨时刻补接管触点，保留原 Hold 的头部和全部原窗口。
     * 覆盖状态按整数输出时刻求并集；提前一毫秒交接避免边界取整出现空隙。
     */
    private static void planHold(Aff aff, Hold hold, List<CoverageDemand> windows,
                                 List<PressDemand> presses, List<TouchStroke> strokes, Set<Integer> claimed) {
        PressDemand head = presses.stream().filter(p -> p.source().getSourceId() == hold.getSourceId())
                .findFirst().orElseThrow();
        List<TouchStroke> providers = new ArrayList<>(strokes.stream()
                .filter(stroke -> stroke.kind() == TouchStroke.Kind.ARC
                        && stroke.startTime() <= hold.getT2() && stroke.endTime() > hold.getT1()).toList());
        double earliest = providers.stream().filter(stroke -> stroke.pressSourceId() != null
                        && stroke.pressSourceId() == hold.getSourceId())
                .mapToDouble(stroke -> stroke.startTime() + 1).max().orElse(hold.getT1());
        List<TimeSpan> needed = new ArrayList<>();
        double from = Double.NaN;
        for (double t = hold.getT1(); t < hold.getT2(); t++) {
            boolean covered = false;
            for (TouchStroke provider : providers) {
                if (t >= provider.startTime() && t < provider.endTime()
                        && coversHold(aff, hold, provider.positionAt(t), t)) {
                    covered = true;
                    break;
                }
            }
            if (!covered && Double.isNaN(from)) from = Math.max(earliest, Math.max(hold.getT1(), t - 1));
            if (covered && !Double.isNaN(from)) {
                needed.add(new TimeSpan(from, t));
                from = Double.NaN;
            }
        }
        if (!Double.isNaN(from)) needed.add(new TimeSpan(from, hold.getT2()));
        if (!claimed.contains(hold.getSourceId())) {
            needed.add(new TimeSpan(hold.getT1(), hold.getT1() + TouchStroke.MIN_PRESS_DURATION_MILLIS));
        }
        // 补时后重叠的 Hold 片段合成一次按住，不制造多余的 UP/DOWN。
        needed.sort(Comparator.comparingDouble(TimeSpan::from));
        List<TimeSpan> spans = new ArrayList<>();
        for (TimeSpan span : needed) {
            double until = Math.max(span.until(), span.from() + TouchStroke.MIN_PRESS_DURATION_MILLIS);
            if (span.until() >= hold.getT2()) {
                double last = windows.stream().mapToDouble(window -> window.window().startTime()).max().orElse(hold.getT1());
                until = Math.max(until, Math.ceil(last) + 2 * TIMING_MARGIN);
                if (span.until() - span.from() < FAST_DURATION_MILLIS) until = Math.max(until, hold.getT2() + TIMING_MARGIN);
            }
            if (!spans.isEmpty() && span.from() <= spans.getLast().until()) {
                TimeSpan previous = spans.removeLast();
                spans.add(new TimeSpan(previous.from(), Math.max(previous.until(), until)));
            } else {
                spans.add(new TimeSpan(span.from(), until));
            }
        }
        AffPoint position = pressPosition(aff, head, presses, null);
        for (TimeSpan span : spans) {
            TouchStroke stroke = new TouchStroke(TouchStroke.Kind.HOLD, span.from(), span.until(),
                    position, hold.getSourceId());
            if (span.from() == hold.getT1() && claimed.add(hold.getSourceId())) stroke.absorbPress(hold.getSourceId());
            strokes.add(stroke);
            providers.add(stroke);
        }
        // 原窗口已经由其他触点覆盖时，不再为没有尾判的剩余图形制造接管 DOWN。
        for (TouchStroke candidate : List.copyOf(providers)) {
            if (candidate.kind() != TouchStroke.Kind.HOLD || candidate.hasAbsorbedPress()) continue;
            boolean necessary = false;
            for (CoverageDemand window : windows) {
                for (int offset = -TIMING_MARGIN; offset <= TIMING_MARGIN; offset++) {
                    boolean covered = false;
                    for (TouchStroke other : providers) {
                        if (other != candidate && firstHit(aff, other, window, offset) != null) {
                            covered = true;
                            break;
                        }
                    }
                    if (!covered) { necessary = true; break; }
                }
                if (necessary) break;
            }
            if (!necessary) {
                providers.remove(candidate);
                strokes.remove(candidate);
            }
        }
        for (CoverageDemand window : windows) {
            for (int offset = -TIMING_MARGIN; offset <= TIMING_MARGIN; offset++) {
                boolean hit = false;
                for (TouchStroke provider : providers) {
                    HitOpportunity opportunity = firstHit(aff, provider, window, offset);
                    if (opportunity == null) continue;
                    if (offset == 0) provider.addCoveredOpportunity(opportunity);
                    hit = true;
                    break;
                }
                if (!hit) throw unsatisfied(aff, window, "Hold 接管后仍有未覆盖窗口，offset=" + offset);
            }
        }
    }

    /** Hold 的空间包含轨道宽度与天空中线，保留少量屏幕取整余量。 */
    private static boolean coversHold(Aff aff, Hold hold, AffPoint p, double time) {
        double ratio = aff.getRatio46k(time);
        return InputJudgementRange.coversLane(hold.getTrack(), new AffPoint(p.x() - 0.003, p.y() + 0.003), ratio)
                && InputJudgementRange.coversLane(hold.getTrack(), new AffPoint(p.x() + 0.003, p.y() + 0.003), ratio);
    }

    /** 选点仅处理首次按下的竞争；持色后的 Arc 路径不为其他物件绕行。 */
    private static AffPoint pressPosition(Aff aff, PressDemand demand, List<PressDemand> demands, AffPoint entry) {
        AffPoint center = demand.position();
        List<AffPoint> candidates = new ArrayList<>(List.of(center));
        if (entry != null) candidates.add(entry);
        if (demand.source() instanceof ArcTap) {
            for (double dx : new double[]{-0.25, 0, 0.25}) {
                for (double dy : new double[]{-0.5, 0, 0.5}) {
                    candidates.add(new AffPoint(center.x() + dx, center.y() + dy));
                }
            }
        } else {
            for (double dx : new double[]{-0.24, 0.24, -0.12, 0.12}) {
                candidates.add(new AffPoint(center.x() + dx, center.y()));
            }
        }
        AffPoint best = null;
        int bestPresses = Integer.MAX_VALUE;
        int bestContacts = Integer.MAX_VALUE;
        double bestDistance = Double.POSITIVE_INFINITY;
        double ratio = aff.getRatio46k(demand.time());
        for (AffPoint candidate : candidates) {
            AffPoint p = onScreen(candidate, aff.getRatio46k(demand.time()));
            if (!InputJudgementRange.coversPress(demand.source(), new AffPoint(p.x() - 0.003, p.y() - 0.003), ratio)
                    || !InputJudgementRange.coversPress(demand.source(), new AffPoint(p.x() + 0.003, p.y() + 0.003), ratio)
                    || entry != null && (!ArcColorStateMachine.areClose(p, entry)
                    || !ArcJudgementRange.covers(entry, p, 0.003, 0.003))) continue;
            int pressCount = 0;
            for (PressDemand other : demands) {
                if (other != demand && Math.abs(other.time() - demand.time()) <= TIMING_MARGIN * 2
                        && InputJudgementRange.coversPress(other.source(), p, ratio)) pressCount++;
            }
            int contacts = 0;
            if (entry == null) {
                double until = Math.max(demand.source().getT2(), demand.time() + TouchStroke.MIN_PRESS_DURATION_MILLIS);
                for (Arc arc : aff.getArcList()) {
                    double from = Math.max(arc.getT1(), demand.time() - TIMING_MARGIN);
                    double end = Math.min(arc.getT2(), until + TIMING_MARGIN);
                    for (double t = from; t <= end; t += 8) {
                        AffPoint q = pointAt(arc, t);
                        if (ArcJudgementRange.covers(q, InputJudgementRange.skyPosition(p, q.y(), aff.getRatio46k(t)))) {
                            contacts++;
                        }
                    }
                }
            }
            double distance = center.distanceSquared(p);
            if (pressCount < bestPresses || pressCount == bestPresses
                    && (contacts < bestContacts || contacts == bestContacts && distance < bestDistance)) {
                best = p;
                bestPresses = pressCount;
                bestContacts = contacts;
                bestDistance = distance;
            }
        }
        if (best == null && entry == null) {
            throw new IllegalStateException("普通按键没有可输出的稳定位置：" + aff.getAffFile().getAbsolutePath()
                    + "，sourceId=" + demand.source().getSourceId());
        }
        return best;
    }

    /**
     * 仅在尚未染色及真实清色之后选取排他的入口位置。每个整数时移独立保留持色状态，
     * 所有时移都已染色后直接恢复原轨迹，直到下一次清色；不对持色中的普通蛇持续避让。
     */
    private static void secureEntries(Aff aff, List<PressDemand> presses, List<TouchStroke> strokes,
                                      NavigableMap<Double, Integer> clear) {
        Map<Integer, PressDemand> byId = new LinkedHashMap<>();
        for (PressDemand press : presses) byId.put(press.source().getSourceId(), press);
        strokes.sort(Comparator.comparingDouble(TouchStroke::startTime));
        List<BindingHistory> histories = new ArrayList<>();
        Map<TouchStroke, List<CoverageDemand>> requirements = new LinkedHashMap<>();
        for (int index = 0; index < strokes.size(); index++) {
            TouchStroke original = strokes.get(index);
            if (original.kind() != TouchStroke.Kind.ARC) continue;
            List<Arc> own = sourceArcs(original);
            Set<Integer> ids = own.stream().map(Arc::getSourceId).collect(java.util.stream.Collectors.toSet());
            Set<Integer> components = own.stream().map(arc -> aff.getArcTopology().componentIdOf(arc.getSourceId()))
                    .collect(java.util.stream.Collectors.toSet());
            // 零时长连接属于原路径，不能作为另一触点的竞争来源。
            for (Arc arc : aff.getArcList()) {
                if (arc.getT1() == arc.getT2()
                        && components.contains(aff.getArcTopology().componentIdOf(arc.getSourceId()))
                        && own.stream().anyMatch(source -> source.getColor() == arc.getColor())) {
                    ids.add(arc.getSourceId());
                }
            }
            List<Arc> nearby = aff.getArcList().stream().filter(arc -> arc.getT1() <= original.endTime() + TIMING_MARGIN
                    && arc.getT2() >= original.startTime() - TIMING_MARGIN).toList();
            TreeSet<Double> resets = new TreeSet<>();
            for (double time : clear.keySet()) {
                if (Double.isFinite(time) && time >= original.startTime() - TIMING_MARGIN
                        && time <= original.endTime() + TIMING_MARGIN) {
                    for (int shift = -TIMING_MARGIN; shift <= TIMING_MARGIN; shift++) {
                        resets.add(Math.floor(time - shift));
                    }
                }
            }
            for (Arc arc : nearby) {
                for (int shift = -TIMING_MARGIN; shift <= TIMING_MARGIN; shift++) {
                    resets.add((double) arc.getT1() - shift);
                }
            }
            TreeMap<Double, TouchAnchor> anchors = new TreeMap<>();
            for (TouchAnchor anchor : original.anchors()) anchors.put(anchor.time(), anchor);
            int[] owners = new int[2 * TIMING_MARGIN + 1];
            Arrays.fill(owners, -1);
            List<NavigableMap<Double, Integer>> bindings = new ArrayList<>();
            for (int ignored : owners) {
                TreeMap<Double, Integer> state = new TreeMap<>();
                state.put(Double.NEGATIVE_INFINITY, -1);
                bindings.add(state);
            }
            boolean altered = false;
            double retainUntil = Double.NEGATIVE_INFINITY;
            AffPoint retainedPosition = null;
            double time = original.startTime();
            PressDemand head = byId.get(original.pressSourceId());
            // 小 Pure 只移动操作时间，按键之间的消费优先级仍使用原谱名义时间。
            int headTime = head == null ? Integer.MIN_VALUE : aff.getNoteList().stream()
                    .filter(note -> note.getSourceId() == head.source().getSourceId())
                    .mapToInt(Note::getT1).findFirst().orElseThrow();
            while (time < original.endTime()) {
                List<BindingFrame> pending = new ArrayList<>();
                boolean anyActive = false;
                for (int shift = -TIMING_MARGIN; shift <= TIMING_MARGIN; shift++) {
                    // 输入按整数毫秒保持，提前布置下一毫秒内可能发生的分离重染。
                    double at = time + shift + 1;
                    int slot = shift + TIMING_MARGIN;
                    int mask = clear.floorEntry(at).getValue();
                    if (owners[slot] >= 0 && ((mask & (1 << owners[slot])) != 0
                            || clear.subMap(bindings.get(slot).lastKey(), false, at, true).values().stream()
                            .anyMatch(value -> (value & (1 << owners[slot])) != 0))) {
                        owners[slot] = -1;
                        bindings.get(slot).put(at, -1);
                    }
                    if (owners[slot] >= 0) continue;
                    TreeSet<Double> frameTimes = new TreeSet<>();
                    frameTimes.add(at - 1);
                    frameTimes.add(Math.nextUp(at - 1));
                    frameTimes.addAll(clear.subMap(at - 1, false, at, false).keySet());
                    frameTimes.add(at);
                    for (double frameTime : frameTimes) {
                        int frameMask = clear.floorEntry(frameTime).getValue();
                        List<Arc> active = nearby.stream().filter(arc -> frameTime >= arc.getT1() && frameTime <= arc.getT2()
                                && (frameMask & (1 << arc.getColor())) == 0
                                && !knownColorOwner(histories, slot, frameTime, arc.getColor(), clear)).toList();
                        anyActive |= !active.isEmpty();
                        if (!active.isEmpty()) {
                            int clearSoon = 0;
                            List<Arc> fast = active.stream().filter(arc -> ids.contains(arc.getSourceId())
                                    && fastLocal(arc)).toList();
                            if (!fast.isEmpty()) {
                                for (int future : clear.subMap(frameTime, false,
                                        frameTime + FAST_DURATION_MILLIS, true).values()) {
                                    if (fast.stream().anyMatch(arc -> (future & (1 << arc.getColor())) != 0)) {
                                        clearSoon |= future;
                                    }
                                }
                            }
                            List<EntryArc> geometry = active.stream()
                                    .map(arc -> new EntryArc(arc, pointAt(arc, frameTime))).toList();
                            pending.add(new BindingFrame(slot, frameTime, aff.getRatio46k(frameTime), geometry, clearSoon));
                        }
                    }
                }
                AffPoint base = original.positionAt(time);
                boolean down = time == original.startTime();
                List<Note> competing = down ? aff.getNoteList().stream().filter(note -> !(note instanceof Arc)
                        && (head == null || note.getSourceId() != head.source().getSourceId())
                        && (head == null || note.getT1() <= headTime)
                        && original.startTime() >= note.getT1() - 120.0 - TIMING_MARGIN
                        && original.startTime() <= note.getT1() + 100.0 + TIMING_MARGIN
                        && (!byId.containsKey(note.getSourceId())
                        || byId.get(note.getSourceId()).time() >= original.startTime())).toList() : List.of();
                AffPoint position = time <= retainUntil ? retainedPosition : base;
                EntryCheck check = new EntryCheck(aff, time, pending, ids, competing);
                double inputRatio = check.inputRatio;
                if (check.conflicts(position)) {
                    double distance = Double.POSITIVE_INFINITY;
                    position = null;
                    // 候选取真实判定矩形的内外边界；固定偏移网格会漏掉狭窄但合法的入口。
                    Set<Double> xs = new TreeSet<>(), ys = new TreeSet<>();
                    xs.add(base.x());
                    ys.add(base.y());
                    for (double delta : new double[]{-0.5, -0.3, -0.2, -0.1, -0.05, 0.05, 0.1, 0.2, 0.3, 0.5}) {
                        xs.add(base.x() + delta);
                    }
                    for (double delta : new double[]{-0.7, -0.54, -0.3, -0.1, 0.1, 0.3, 0.54, 0.7}) {
                        ys.add(base.y() + delta);
                    }
                    for (Note press : competing) {
                        if (press instanceof ArcTap) continue;
                        // 未解锁 Hold/地键占据整条轨道，候选需包含轨道边界外的位置。
                        double x = press.getAffPoint(press.getT1())[0];
                        xs.add(x - 0.25 - 0.004);
                        xs.add(x + 0.25 + 0.004);
                        ys.add((1 + 0.61 * inputRatio) / 2 + 0.004);
                    }
                    for (BindingFrame frame : pending) {
                        for (EntryArc source : frame.arcs()) {
                            AffPoint center = source.position();
                            for (double margin : new double[]{-0.004, 0.004}) {
                                for (int side : new int[]{-1, 1}) {
                                    AffPoint edge = project(new AffPoint(
                                            center.x() + side * (ArcJudgementRange.X_RADIUS + margin),
                                            center.y() + side * (ArcJudgementRange.Y_RADIUS + margin)),
                                            frame.ratio(), inputRatio);
                                    xs.add(edge.x());
                                    ys.add(edge.y());
                                }
                            }
                        }
                    }
                    List<Double> xValues = xs.stream().sorted(Comparator.comparingDouble(x -> Math.abs(x - base.x()))).toList();
                    List<Double> yValues = ys.stream().sorted(Comparator.comparingDouble(y -> Math.abs(y - base.y()))).toList();
                    for (double x : xValues) {
                        for (double y : yValues) {
                            AffPoint p = onScreen(new AffPoint(x, y), inputRatio);
                            if (base.distanceSquared(p) >= distance) continue;
                            if (down && head != null && (!InputJudgementRange.coversPress(head.source(),
                                    new AffPoint(p.x() - 0.003, p.y() - 0.003), inputRatio)
                                    || !InputJudgementRange.coversPress(head.source(),
                                    new AffPoint(p.x() + 0.003, p.y() + 0.003), inputRatio))) continue;
                            if (check.conflicts(p)) continue;
                            // 分离瞬间未必能同时接触全部时移下的自身曲线，先取最近的排他位置。
                            // 后续随原曲线尽快染色，所有原窗口在最终路径上重新验证。
                            distance = base.distanceSquared(p);
                            position = p;
                        }
                    }
                    if (position == null) {
                        throw new ArcColorStateMachine.UnsatisfiedColorException(
                                "初染或重染入口存在无法分离的竞争：" + aff.getAffFile().getAbsolutePath()
                                        + "，timing=" + time + "，sourceIds=" + original.sourceIds());
                    }
                }
                boolean moved = !position.samePosition(base);
                if (moved || altered || !pending.isEmpty() || time <= retainUntil) {
                    // 重染检查采用的实际位置必须输出，不能让距离采样保留上一个旧位置。
                    // 修正仅在整数毫秒切换，不能把上一个已染色的普通段提前拉向入口偏移。
                    if (time > original.startTime() && !altered) {
                        double before = Math.max(original.startTime(), time - 1);
                        anchors.putIfAbsent(before, new TouchAnchor(before, original.positionAt(before),
                                false, TouchAnchor.Transition.LINEAR));
                    }
                    anchors.put(time, new TouchAnchor(time, position, true, TouchAnchor.Transition.STEP));
                }
                altered = moved;
                boolean precise = anchors.containsKey(time) && anchors.get(time).required();
                for (BindingFrame frame : pending) {
                    if (frame.time() != time + frame.index() - TIMING_MARGIN + 1 || frame.clearSoon() != 0) continue;
                    for (EntryArc arc : frame.arcs()) {
                        if (ids.contains(arc.source().getSourceId()) && ArcJudgementRange.covers(arc.position(),
                                project(position, inputRatio, frame.ratio()),
                                precise ? 0.003 : X_MARGIN, precise ? 0.003 : Y_MARGIN)) {
                            owners[frame.index()] = arc.source().getColor();
                            bindings.get(frame.index()).put(frame.time(), arc.source().getColor());
                            break;
                        }
                    }
                }
                boolean bound = Arrays.stream(owners).allMatch(color -> color >= 0);
                if (bound && !pending.isEmpty()) {
                    // 布置使用了下一毫秒的边界；保持到该时刻之后，避免复位抢在实际染色之前。
                    retainUntil = time + 1;
                    retainedPosition = position;
                }
                if ((bound || !anyActive) && !altered && time > retainUntil) {
                    Double next = resets.higher(time);
                    if (next == null || next >= original.endTime()) break;
                    time = next;
                } else {
                    time = Math.floor(time) + 1;
                }
            }
            // UP 不提供判定；移除原来恰好位于 UP 的端点，避免 addAnchor 又把触点延长一毫秒。
            anchors.tailMap(original.endTime(), true).clear();
            TouchAnchor first = anchors.firstEntry().getValue();
            TouchStroke secured = new TouchStroke(TouchStroke.Kind.ARC, original.startTime(), original.endTime(),
                    first.position(), original.initialSourceId());
            for (TouchAnchor anchor : anchors.values()) {
                if (anchor.time() > secured.startTime()) secured.addAnchor(anchor);
            }
            secured.mergeLogicalDemands(original);
            if (original.hasAbsorbedPress()) secured.absorbPress(original.pressSourceId());
            List<CoverageDemand> windows = original.hitOpportunities().stream()
                    .filter(hit -> hit.demand().point().source() instanceof Arc).map(HitOpportunity::demand).toList();
            secured.replaceArcOpportunities(windows.stream().map(window -> firstHit(aff, secured, window, 0))
                    .filter(java.util.Objects::nonNull).toList());
            requirements.put(secured, windows);
            strokes.set(index, secured);
            histories.add(new BindingHistory(secured, List.copyOf(bindings)));
        }
        for (Map.Entry<TouchStroke, List<CoverageDemand>> entry : requirements.entrySet()) {
            for (CoverageDemand window : entry.getValue()) {
                for (int offset = -TIMING_MARGIN; offset <= TIMING_MARGIN; offset++) {
                    if (firstHit(aff, entry.getKey(), window, offset) != null) continue;
                    TouchStroke covering = null;
                    HitOpportunity hit = null;
                    for (TouchStroke other : requirements.keySet()) {
                        if (other == entry.getKey()) continue;
                        hit = firstHit(aff, other, window, offset, clear);
                        if (hit != null) {
                            covering = other;
                            break;
                        }
                    }
                    if (hit == null) throw unsatisfied(aff, window, "初染或重染接续未保留原窗口");
                    // 无色区间可由任一现存触点覆盖；物件身份和窗口保留，只记录实际接力者。
                    if (offset == 0) covering.addCoveredOpportunity(hit);
                }
            }
        }
    }

    /** 只有已在更早时刻确认持色且此后未清色的触点，才会阻止其他触点取得该颜色。 */
    private static boolean knownColorOwner(List<BindingHistory> histories, int slot, double time, int color,
                                           NavigableMap<Double, Integer> clear) {
        int shift = slot - TIMING_MARGIN;
        for (BindingHistory history : histories) {
            if (time < history.stroke().startTime() + shift || time >= history.stroke().endTime() + shift) continue;
            Map.Entry<Double, Integer> state = history.states().get(slot).floorEntry(time);
            if (state.getValue() != color || state.getKey() >= time - 1) continue;
            boolean cleared = clear.subMap(state.getKey(), false, time, true).values().stream()
                    .anyMatch(mask -> (mask & (1 << color)) != 0);
            if (!cleared) return true;
        }
        return false;
    }

    /** 整体时移跨过 4K/6K 过渡时，输入保留原屏幕位置，再按当前投影反解。 */
    private static AffPoint projectedPosition(Aff aff, AffPoint p, double inputTime, double chartTime) {
        return project(p, aff.getRatio46k(inputTime), aff.getRatio46k(chartTime));
    }

    /** 同一节点的投影比例在候选搜索前确定，避免每个候选重复扫描场景控制。 */
    private static AffPoint project(AffPoint p, double inputRatio, double ratio) {
        if (ratio == inputRatio) return p;
        Resolution resolution = Resolution.R16_9_1280_720;
        int[] xy = resolution.convertToXY(p.x(), p.y(), inputRatio);
        return resolution.convertToAffPoint(xy[0], xy[1], ratio);
    }

    /** 检查整个整数毫秒时移范围，不用只通过两个极值来推断中间偏移。 */
    private static CoverageDemand uncovered(Aff aff, TouchStroke stroke, List<CoverageDemand> demands) {
        for (CoverageDemand demand : demands) {
            for (int offset = -TIMING_MARGIN; offset <= TIMING_MARGIN; offset++) {
                if (firstHit(aff, stroke, demand, offset) == null) return demand;
            }
        }
        return null;
    }

    /** 仅登记由实际路径覆盖的原需求；不为判定注记额外添加 MOVE 锚点。 */
    private static void annotate(Aff aff, TouchStroke stroke, List<CoverageDemand> demands) {
        for (CoverageDemand demand : demands) {
            HitOpportunity hit = firstHit(aff, stroke, demand, 0);
            if (hit == null) throw unsatisfied(aff, demand, "计划路径缺少原判定需求");
            stroke.addCoveredOpportunity(hit);
        }
    }

    /** 在原窗口和实际按下区间的交集中查找覆盖，UP 本身不提供持续判定。 */
    private static HitOpportunity firstHit(Aff aff, TouchStroke stroke, CoverageDemand demand, int offset) {
        return firstHit(aff, stroke, demand, offset, null);
    }

    /** 其他颜色的触点仅能在该原物件确实无色时接力，不能把几何覆盖当作已持色。 */
    private static HitOpportunity firstHit(Aff aff, TouchStroke stroke, CoverageDemand demand, int offset,
                                           NavigableMap<Double, Integer> clear) {
        double from = Math.max(demand.window().startTime(), stroke.startTime() + offset);
        double to = Math.min(demand.window().endTime(), Math.nextDown(stroke.endTime() + offset));
        if (to < from) return null;
        double t = from;
        while (true) {
            double inputTime = Math.clamp(t - offset, stroke.startTime(), stroke.endTime());
            AffPoint p = projectedPosition(aff, stroke.positionAt(inputTime), inputTime, t);
            double ratio = aff.getRatio46k(t);
            boolean hit;
            if (demand.point().source() instanceof Hold hold) {
                hit = coversHold(aff, hold, p, t);
            } else {
                AffPoint q = demand.window().positionAt(t);
                hit = ArcJudgementRange.covers(q,
                        InputJudgementRange.skyPosition(p, q.y(), ratio), X_MARGIN, Y_MARGIN);
            }
            if (hit && (clear == null || (clear.floorEntry(t).getValue()
                    & (1 << ((Arc) demand.point().source()).getColor())) != 0)) {
                return new HitOpportunity(demand, t, p);
            }
            if (t == to) return null;
            t = Math.min(to, Math.floor(t) + 1);
        }
    }

    /** 从原长键需求恢复按时间排列的实体 Arc，不依赖触控操作数。 */
    private static List<Arc> arcsOf(List<LongNoteDemand> demands) {
        return demands.stream().map(d -> (Arc) d.source()).sorted(Comparator.comparingInt(Arc::getT1)
                .thenComparingInt(Arc::getSourceId)).toList();
    }

    /** 模板只用原需求身份恢复曲线，接入 Hold 后也不会把 Hold 当作 Arc。 */
    private static List<Arc> sourceArcs(TouchStroke stroke) {
        return stroke.hitOpportunities().stream().map(hit -> hit.demand().point().source())
                .filter(Arc.class::isInstance).map(Arc.class::cast).distinct()
                .sorted(Comparator.comparingInt(Arc::getT1).thenComparingInt(Arc::getSourceId)).toList();
    }

    /** 给不能满足的原窗口保留源文件、物件身份和时间边界。 */
    private static UnsatisfiedWindowException unsatisfied(Aff aff, CoverageDemand demand, String reason) {
        return new UnsatisfiedWindowException(reason + "：" + aff.getAffFile().getAbsolutePath()
                + "，sourceId=" + demand.point().source().getSourceId()
                + "，window=[" + demand.window().startTime() + "," + demand.window().endTime() + "]");
    }

    /** 原判定需求无法由当前触控区间满足，禁止伪造完成注记。 */
    public static final class UnsatisfiedWindowException extends IllegalStateException {
        UnsatisfiedWindowException(String message) {
            super(message);
        }
    }

    /** 已处理触点在各个整体时移下的持色证明，供后续触点处理最早出现优先。 */
    private record BindingHistory(TouchStroke stroke, List<NavigableMap<Double, Integer>> states) {
    }

    /** 同一入口节点的曲线位置只计算一次，供所有候选与最终染色检查复用。 */
    private record EntryArc(Arc source, AffPoint position) {
    }

    /** 某个时移场景尚未染色的时刻；极速折返中很快会被真实清色重置的颜色不提前认领。 */
    private record BindingFrame(int index, double time, double ratio, List<EntryArc> arcs, int clearSoon) {
    }

    /** 同一候选高度只筛选一次纵向可达中心，再用有序横坐标定位最近的竞争边界。 */
    private static final class EntryPlane {
        private final double ratio;
        private final List<AffPoint> centers;
        private final List<Note> presses;
        private final Map<Double, double[]> rows = new HashMap<>();

        /** 按横坐标保存原中心；高度缓存只属于当前入口，不跨时间或谱面变体复用。 */
        EntryPlane(double ratio, List<AffPoint> centers, List<Note> presses) {
            this.ratio = ratio;
            this.centers = centers.stream().sorted(Comparator.comparingDouble(AffPoint::x)).toList();
            this.presses = presses;
        }

        /** 保留原天空钳制与外扩的运算顺序，每行只检查左右最近的横向中心。 */
        boolean conflicts(AffPoint p) {
            double[] xs = rows.computeIfAbsent(p.y(), height -> {
                double top = 1 + 0.61 * ratio;
                double y = Math.max(0, height);
                return centers.stream().filter(center -> {
                    double clipped = center.y() <= top ? Math.min(top, y) : y;
                    double near = clipped - Math.clamp(clipped - center.y(), -0.003, 0.003);
                    return Math.abs(center.y() - near) <= ArcJudgementRange.Y_RADIUS;
                }).mapToDouble(AffPoint::x).toArray();
            });
            int index = Arrays.binarySearch(xs, p.x());
            if (index < 0) index = -index - 1;
            // 纵向已满足；同一侧更远的中心不可能比这两个相邻中心更早进入原命中矩形。
            for (int i = Math.max(0, index - 1); i < Math.min(xs.length, index + 1); i++) {
                double near = p.x() - Math.clamp(p.x() - xs[i], -0.003, 0.003);
                if (Math.abs(xs[i] - near) <= ArcJudgementRange.X_RADIUS) return true;
            }
            for (Note press : presses) {
                if (InputJudgementRange.coversPress(press, p, ratio)) return true;
            }
            return false;
        }
    }

    /** 在一个重染节点准备不变的竞争条件，候选只执行坐标比较，不再计算原曲线。 */
    private static final class EntryCheck {
        private final double inputRatio;
        private final List<EntryPlane> planes;

        /** 汇总所有时移的真实竞争条件；仅合并几何和投影完全相同的检查。 */
        EntryCheck(Aff aff, double time, List<BindingFrame> frames, Set<Integer> own, List<Note> presses) {
            inputRatio = aff.getRatio46k(time);
            Map<Double, Set<AffPoint>> centers = new LinkedHashMap<>();
            for (BindingFrame frame : frames) {
                for (EntryArc arc : frame.arcs()) {
                    if (own.contains(arc.source().getSourceId())
                            || (frame.clearSoon() & (1 << arc.source().getColor())) != 0) continue;
                    centers.computeIfAbsent(frame.ratio(), ignored -> new LinkedHashSet<>()).add(arc.position());
                }
            }
            Set<Double> pressRatios = new LinkedHashSet<>();
            if (!presses.isEmpty()) {
                for (int shift : new int[]{-TIMING_MARGIN, 0, TIMING_MARGIN}) {
                    double ratio = aff.getRatio46k(time + shift);
                    pressRatios.add(ratio);
                    centers.computeIfAbsent(ratio, ignored -> new LinkedHashSet<>());
                }
            }
            planes = centers.entrySet().stream().map(entry -> new EntryPlane(entry.getKey(),
                    List.copyOf(entry.getValue()), pressRatios.contains(entry.getKey()) ? presses : List.of())).toList();
        }

        /** 各投影的纵向候选集合独立缓存，不合并不同高度或不同场景控制的检查。 */
        boolean conflicts(AffPoint position) {
            for (EntryPlane plane : planes) {
                if (plane.conflicts(project(position, inputRatio, plane.ratio))) return true;
            }
            return false;
        }
    }

    /** 原谱入口时刻和位置，不代表已经存在物理 DOWN。 */
    private record ConnectionEntry(double time, AffPoint position) {
    }

    /** 待输出的 Hold 按住区间，单位为谱面毫秒。 */
    private record TimeSpan(double from, double until) {
    }
}
