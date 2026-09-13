package arc.record.record.plan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeMap;

import arc.record.aff.Aff;
import arc.record.aff.judge.AffPoint;
import arc.record.aff.judge.ArcJudgementRange;
import arc.record.aff.judge.ChartJudgementModel;
import arc.record.aff.judge.CoverageDemand;
import arc.record.aff.judge.HitOpportunity;
import arc.record.aff.judge.InputJudgementRange;
import arc.record.aff.judge.JudgePoint;
import arc.record.aff.judge.JudgeWindow;
import arc.record.aff.judge.LongNoteDemand;
import arc.record.aff.judge.PressDemand;
import arc.record.aff.note.Arc;
import arc.record.aff.note.ArcTap;
import arc.record.aff.note.Click;
import arc.record.aff.note.Hold;
import arc.record.aff.note.Note;
import arc.record.record.model.Resolution;

import static arc.record.Settings.TOUCH_SAMPLE_FREQUENCY;
import static arc.record.Settings.TOUCH_MOVE_DISTANCE_RATIO;

/**
 * 将全部普通点击和长键窗口分配为连续触控段落。
 */
public final class TouchScheduler {
    private static final int LOOKAHEAD_WINDOWS = 8;
    private static final int CANDIDATE_SAMPLES_PER_WINDOW = 12;
    /** 同色碎蛇尾头串接及 Hold/Tap 接蛇头的时间近域，单位为毫秒。 */
    private static final double CONTINUATION_GAP_MILLIS = 100.0;
    private static final double TIME_EPSILON = 1e-7;
    private static final double PATH_TIME_EPSILON = 1e-6;
    /** 覆盖16:9全部屏幕及4K/6K投影下的距离采样误差，单位为AFF坐标；实际判定矩形不变。 */
    private static final double X_MARGIN = Math.max(0.002, 4 * TOUCH_MOVE_DISTANCE_RATIO);
    private static final double Y_MARGIN = Math.max(0.002, 5 * TOUCH_MOVE_DISTANCE_RATIO);
    /** 命中后保留10 ms提前偏移及3 ms稳定覆盖，再移向下个不同位置；原判定窗口不延长。 */
    private static final double WINDOW_HOLD_MILLIS = 13;
    /** 四种颜色位之外，标记该路径仍存在尚未染色的合法可能。 */
    private static final int UNBOUND_COLOR = 1 << 4;

    /**
     * 生成与输出分辨率无关的连续触控方案。
     *
     * @param aff   输入谱面及实体 Arc 拓扑
     * @param model 当前 miss/小 Pure 变体的判定需求
     * @return 按开始时间排序的连续触控段落
     */
    public List<TouchStroke> schedule(Aff aff, ChartJudgementModel model) {
        return schedule(aff, model, planArcs(aff, model));
    }

    /**
     * 求解不随普通点击Miss/小Pure调整而变化的Arc路径，结果可在同一谱面的这些变体间复用。
     *
     * @param aff 原始谱面及输入拓扑
     * @param model 包含本谱面全部原始长键需求的模型
     * @return 尚未接入Hold或普通点击的Arc路径模板，调用方不得修改
     */
    public List<TouchStroke> planArcs(Aff aff, ChartJudgementModel model) {
        List<TouchStroke> strokes = new ArrayList<>();
        List<LongNoteDemand> arcDemands = model.longNoteDemands().stream()
                .filter(demand -> demand.source() instanceof Arc).toList();
        List<List<LongNoteDemand>> groups = groupArcDemands(aff, arcDemands);
        List<ColorClearEvidence> clear = colorClearEvidence(aff.getArcList(), false);
        // 分组准入已经求出了可行路径，同一调度过程直接复用，不再重算所有候选。
        Map<List<LongNoteDemand>, TouchStroke> planned = new IdentityHashMap<>();
        splitUntrackableGroups(aff, groups, clear, planned);
        for (List<LongNoteDemand> demands : groups) {
            TouchStroke stroke = planned.get(demands);
            strokes.add(stroke == null ? planArcGroup(aff, demands, clear) : stroke);
        }
        mergeCoveredArcStrokes(aff, strokes, clear);
        mergeFragmentStrokes(aff, strokes, clear);
        return List.copyOf(strokes);
    }

    /**
     * 复制Arc模板后接入当前分数版本的Hold和普通点击，各版本拥有独立可变触控状态。
     *
     * @param aff 与模板相同的原始谱面
     * @param model 仅普通点击发生Miss/小Pure调整的当前需求
     * @param arcs 由同一原谱planArcs生成、长键需求保持相同的模板
     * @return 当前版本完整的按下至抬起路径
     */
    public List<TouchStroke> schedule(Aff aff, ChartJudgementModel model, List<TouchStroke> arcs) {
        List<TouchStroke> strokes = new ArrayList<>();
        for (LongNoteDemand demand : model.longNoteDemands()) {
            if (demand.source() instanceof Hold hold) strokes.add(planHold(hold, demand));
        }
        for (TouchStroke arc : arcs) strokes.add(new TouchStroke(arc));
        mergeCompatibleHoldAndArcStrokes(aff, strokes);
        avoidCompetingHoldPositions(aff, strokes);
        addOrdinaryPresses(aff, model.pressDemands(), strokes);
        trimTrailingPresses(aff, strokes);
        Set<Integer> inputArcIds = strokes.stream()
                .flatMap(stroke -> stroke.arcColorContacts().stream())
                .map(contact -> contact.source().getSourceId())
                .collect(java.util.stream.Collectors.toSet());
        new ArcColorStateMachine(aff, inputArcIds).validate(strokes);
        strokes.sort(Comparator
                .comparingDouble(TouchStroke::startTime)
                .thenComparingDouble(TouchStroke::endTime)
                .thenComparing(stroke -> stroke.kind().ordinal()));
        return List.copyOf(strokes);
    }

    private List<List<LongNoteDemand>> groupArcDemands(
            Aff aff, List<LongNoteDemand> source) {
        Map<Integer, List<LongNoteDemand>> demandsByColor = new LinkedHashMap<>();
        for (LongNoteDemand demand : source) {
            demandsByColor.computeIfAbsent(demand.color(), ignored -> new ArrayList<>()).add(demand);
        }

        List<List<LongNoteDemand>> result = new ArrayList<>();
        for (List<LongNoteDemand> sameColor : demandsByColor.values()) {
            List<LongNoteDemand> sorted = sameColor.stream()
                    .sorted(Comparator
                            .comparingInt((LongNoteDemand demand) -> demand.source().getT1())
                            .thenComparingInt(demand -> demand.source().getT2())
                            .thenComparingInt(demand -> demand.source().getSourceId()))
                    .toList();
            int[] parent = new int[sorted.size()];
            Map<Integer, Integer> firstByComponent = new LinkedHashMap<>();
            int active = -1;
            int activeEnd = Integer.MIN_VALUE;
            for (int i = 0; i < sorted.size(); i++) {
                parent[i] = i;
                LongNoteDemand demand = sorted.get(i);
                Integer first = firstByComponent.putIfAbsent(demand.componentId(), i);
                if (first != null) {
                    union(parent, first, i);
                }
                if (active >= 0 && demand.source().getT1() <= activeEnd) {
                    union(parent, active, i);
                    activeEnd = Math.max(activeEnd, physicalEnd(aff, demand));
                } else {
                    active = i;
                    activeEnd = physicalEnd(aff, demand);
                }
            }
            Map<Integer, List<LongNoteDemand>> groups = new LinkedHashMap<>();
            for (int i = 0; i < sorted.size(); i++) {
                groups.computeIfAbsent(find(parent, i), ignored -> new ArrayList<>()).add(sorted.get(i));
            }
            result.addAll(groups.values());
        }

        splitColorBridgeGroups(aff, result);
        mergeColorBridgeGroups(aff, result);
        result.sort(Comparator
                .comparingInt((List<LongNoteDemand> demands) -> demands.getFirst().source().getT1())
                .thenComparingInt(demands -> demands.getFirst().source().getSourceId()));
        return result;
    }

    /**
     * 窗口路径和连续跟踪都不能覆盖颜色分组时，按可共同覆盖的物理路径重新分配。
     * 同色短蛇可在各自窗口中轮流接住，不能仅因不能同时贴线就拆成抢同一颜色的多个触点。
     */
    private void splitUntrackableGroups(
            Aff aff, List<List<LongNoteDemand>> groups, List<ColorClearEvidence> clear,
            Map<List<LongNoteDemand>, TouchStroke> planned) {
        List<LongNoteDemand> pending = new ArrayList<>();
        groups.removeIf(group -> {
            List<Arc> arcs = arcsOf(group);
            boolean idle = false;
            for (int i = 1; i < arcs.size(); i++) {
                if (canReleaseBetween(aff, arcs.subList(0, i), arcs.get(i), clear)) {
                    idle = true;
                    break;
                }
            }
            if (!idle) {
                try {
                    planned.put(group, planArcGroup(aff, group, clear));
                    return false;
                } catch (UnsatisfiedWindowException | ArcColorStateMachine.UnsatisfiedColorException e) {
                    // 两种组内路径均不满足规划约束，接下来分配物理路径；不消费回放结果。
                }
            }
            pending.addAll(group);
            return true;
        });
        sortDemands(pending);
        List<List<LongNoteDemand>> paths = new ArrayList<>();
        for (LongNoteDemand demand : pending) {
            Arc next = (Arc) demand.source();
            List<LongNoteDemand> best = null;
            double bestDistance = Double.POSITIVE_INFINITY;
            for (List<LongNoteDemand> path : paths) {
                // 已被完整窗口/颜色约束拒绝的组合按颜色拆开，不在物理分配阶段重新合回同一混色组。
                if (path.getFirst().color() != demand.color()) continue;
                List<Arc> combined = new ArrayList<>(arcsOf(path));
                combined.add(next);
                if (!canTrackTogether(aff, combined) || !canColorTrackTogether(combined)
                        || canReleaseBetween(aff, arcsOf(path), next, clear)) {
                    continue;
                }
                Arc previous = (Arc) path.getLast().source();
                double at = Math.clamp(next.getT1(), previous.getT1(), previous.getT2());
                double distance = ArcColorStateMachine.worldDistanceSquared(
                        pointAt(previous, at), pointAt(next, next.getT1()));
                if (distance < bestDistance) {
                    best = path;
                    bestDistance = distance;
                }
            }
            if (best == null) {
                best = new ArrayList<>();
                paths.add(best);
            }
            best.add(demand);
        }
        groups.addAll(paths);
        groups.sort(Comparator.comparingInt(group -> group.getFirst().source().getT1()));
    }

    /** 空闲段只有在后继不受释放冷却影响时才拆开，保留相连短蛇的一次完整按下。 */
    private static boolean canReleaseBetween(
            Aff aff, List<Arc> previous, Arc next, List<ColorClearEvidence> clear) {
        double end = previous.stream().mapToInt(Arc::getT2).max().orElseThrow();
        if (next.getT1() - end < CONTINUATION_GAP_MILLIS) return false;
        double componentEnd = previous.stream().flatMap(arc -> aff.getArcTopology()
                        .component(aff.getArcTopology().componentIdOf(arc.getSourceId())).stream())
                .mapToInt(Arc::getT2).max().orElseThrow();
        // 组结束解除冷却；否则须覆盖1000 ms冷却、25 ms移动拖尾、10 ms时移和1 ms边界。
        return componentEnd < next.getT1() - 10 || next.getT1() - end >= 1036
                || clear.stream().anyMatch(interval -> interval.startTime() <= end - 10
                && interval.graceEnd() >= end + 35);
    }

    /**
     * 若一条既有低位移路径能原样覆盖另一组的全部窗口和颜色接触，删除冗余触点。
     * 尝试失败不修改任一 Stroke，因而左右不兼容主路径仍保持独立。
     */
    private void mergeCoveredArcStrokes(Aff aff, List<TouchStroke> strokes, List<ColorClearEvidence> clear) {
        List<TouchStroke> arcs = new ArrayList<>(strokes.stream()
                .filter(stroke -> stroke.kind() == TouchStroke.Kind.ARC)
                .toList());
        for (int i = arcs.size() - 1; i >= 0; i--) {
            TouchStroke first = arcs.get(i);
            if (!strokes.contains(first) || first.hasAbsorbedPress()) {
                continue;
            }
            for (int j = 0; j < i; j++) {
                TouchStroke second = arcs.get(j);
                if (!strokes.contains(second) || second.hasAbsorbedPress()
                        || sameArcColors(first, second)) {
                    continue;
                }
                TouchStroke preferred = planCost(first).compareTo(planCost(second)) <= 0
                        ? first : second;
                TouchStroke other = preferred == first ? second : first;
                if (!absorbCoveredArcStroke(preferred, other)) {
                    if (!absorbCoveredArcStroke(other, preferred)) {
                        TouchStroke shared = lowerCost(
                                shareWindowPath(aff, preferred, other, clear),
                                shareWindowPath(aff, other, preferred, clear));
                        if (shared == null) continue;
                        strokes.remove(first);
                        strokes.remove(second);
                        strokes.add(shared);
                        break;
                    }
                    TouchStroke swap = preferred;
                    preferred = other;
                    other = swap;
                }
                strokes.remove(other);
                break;
            }
        }
    }

    /** 从原路径起点共同求解两组窗口，减少完整按下次数，同时不增加两条原路径的总移动。 */
    private TouchStroke shareWindowPath(
            Aff aff, TouchStroke base, TouchStroke other, List<ColorClearEvidence> clear) {
        if (base.startTime() > other.startTime() || base.endTime() < other.endTime()) return null;
        List<Arc> all = java.util.stream.Stream.concat(base.arcColorContacts().stream(), other.arcColorContacts().stream())
                .map(TouchStroke.ArcColorContact::source).distinct()
                .sorted(Comparator.comparingInt(Arc::getT1).thenComparingInt(Arc::getSourceId)).toList();
        List<Arc> arcs = all.stream().filter(arc -> arc.getT2() > arc.getT1()).toList();
        boolean bridge = base.arcColorContacts().stream().anyMatch(a -> other.arcColorContacts().stream()
                .anyMatch(b -> a.source().getColor() != b.source().getColor()
                        && !ArcColorStateMachine.colorBridgeIntervals(a.source(), b.source()).isEmpty()));
        if (!bridge || !hasCompatibleConnectionEntry(aff, arcs)) return null;
        List<CoverageDemand> windows = java.util.stream.Stream.concat(
                        base.hitOpportunities().stream(), other.hitOpportunities().stream())
                .map(HitOpportunity::demand).distinct()
                .sorted(Comparator.comparingDouble((CoverageDemand demand) -> demand.window().endTime())
                        .thenComparingDouble(demand -> demand.window().startTime())
                        .thenComparingInt(demand -> demand.point().id())).toList();
        ArcGroupPlan plan = new ArcGroupPlan(aff, arcs,
                all.stream().filter(arc -> arc.getT2() == arc.getT1()).toList(), windows,
                findConnectionEntry(aff, arcs), base.startTime(), base.endTime());
        TouchStroke shared;
        try {
            shared = prepareArcCandidate(planWindowArcGroup(plan, base), plan.zeroDurationArcs());
        } catch (UnsatisfiedWindowException e) {
            return null;
        }
        if (planCost(shared).worldTravel() > planCost(base).worldTravel() + planCost(other).worldTravel() + 1e-9)
            return null;
        return ownershipConflict(plan, shared, clear) == null ? shared : null;
    }

    private static boolean sameArcColors(TouchStroke first, TouchStroke second) {
        Set<Integer> firstColors = first.arcColorContacts().stream()
                .map(contact -> contact.source().getColor())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<Integer> secondColors = second.arcColorContacts().stream()
                .map(contact -> contact.source().getColor())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return firstColors.equals(secondColors);
    }

    private static boolean absorbCoveredArcStroke(
            TouchStroke target, TouchStroke source) {
        TreeMap<Double, TouchAnchor> markers = new TreeMap<>();
        for (TouchAnchor anchor : target.anchors()) {
            markers.put(anchor.time(), anchor);
        }
        for (HitOpportunity opportunity : source.hitOpportunities()) {
            JudgeWindow window = opportunity.demand().window();
            addHandoffMarker(markers, target, window.startTime());
            addHandoffMarker(markers, target, opportunity.hitTime());
            addHandoffMarker(markers, target, window.endTime());
        }

        Map<Long, TouchAnchor> requiredByMillis = new LinkedHashMap<>();
        for (TouchAnchor anchor : markers.values()) {
            if (anchor.required()) {
                requiredByMillis.put(Math.round(anchor.time()), anchor);
            }
        }
        for (HitOpportunity opportunity : target.hitOpportunities()) {
            if (opportunity.hitTime() < target.startTime()
                    || opportunity.hitTime() > target.endTime()) {
                return false;
            }
            TouchAnchor occupied = new TouchAnchor(
                    opportunity.hitTime(), target.positionAt(opportunity.hitTime()), true,
                    TouchAnchor.Transition.LINEAR);
            TouchAnchor previous = requiredByMillis.putIfAbsent(
                    Math.round(opportunity.hitTime()), occupied);
            if (previous != null && !previous.position().samePosition(occupied.position())) {
                return false;
            }
        }

        TrackedPath path = new TrackedPath(List.copyOf(markers.values()));
        List<HitOpportunity> selected = new ArrayList<>();
        for (HitOpportunity opportunity : source.hitOpportunities()) {
            HitOpportunity hit = firstTrackedHit(path, opportunity.demand(), requiredByMillis);
            if (hit == null) {
                return false;
            }
            selected.add(hit);
            TouchAnchor anchor = markers.get(hit.hitTime());
            requiredByMillis.put(Math.round(hit.hitTime()), anchor);
        }

        List<TouchStroke.ArcColorContact> contacts = new ArrayList<>(target.arcColorContacts());
        for (HitOpportunity hit : selected) {
            if (hit.demand().point().source() instanceof Arc arc) {
                contacts.add(new TouchStroke.ArcColorContact(arc, hit.hitTime()));
            }
        }
        for (TouchStroke.ArcColorContact contact : source.arcColorContacts()) {
            if (contact.source().getT1() != contact.source().getT2()) {
                continue;
            }
            if (contact.time() < target.startTime() || contact.time() > target.endTime()
                    || contact.time() < source.startTime() || contact.time() > source.endTime()
                    || !ArcJudgementRange.covers(
                            source.positionAt(contact.time()), target.positionAt(contact.time()))) {
                return false;
            }
            contacts.add(contact);
        }
        if (!colorsCanShareTouch(contacts)
                || !coversWithTimingMargin(target, source.hitOpportunities().stream()
                .map(HitOpportunity::demand).toList())) {
            return false;
        }

        for (HitOpportunity hit : selected) {
            target.addCoveredOpportunity(hit);
        }
        for (TouchStroke.ArcColorContact contact : source.arcColorContacts()) {
            if (contact.source().getT1() == contact.source().getT2()) {
                target.addArcColorContact(contact.source(), contact.time());
            }
        }
        return true;
    }

    private static boolean colorsCanShareTouch(
            List<TouchStroke.ArcColorContact> contacts) {
        Map<Integer, Arc> arcsById = new LinkedHashMap<>();
        for (TouchStroke.ArcColorContact contact : contacts) {
            arcsById.putIfAbsent(contact.source().getSourceId(), contact.source());
        }
        List<Arc> arcs = List.copyOf(arcsById.values());
        List<TouchStroke.ArcColorContact> sorted = contacts.stream()
                .sorted(Comparator.comparingDouble(TouchStroke.ArcColorContact::time)
                        .thenComparingInt(contact -> contact.source().getSourceId()))
                .toList();
        List<ColorClearEvidence> clears = colorClearEvidence(arcs, true);
        Integer boundColor = null;
        double graceUntil = Double.NEGATIVE_INFINITY;
        int clearIndex = 0;
        for (TouchStroke.ArcColorContact contact : sorted) {
            while (clearIndex < clears.size()
                    && clears.get(clearIndex).startTime() <= contact.time() + TIME_EPSILON) {
                ColorClearEvidence clear = clears.get(clearIndex++);
                boundColor = null;
                graceUntil = Math.max(graceUntil, clear.graceEnd());
            }
            if (contact.time() <= graceUntil + TIME_EPSILON) {
                continue;
            }
            int color = contact.source().getColor();
            if (boundColor != null && boundColor != color) {
                return false;
            }
            boundColor = color;
        }
        return true;
    }

    /** 将候选涉及的局部清色区间预计算为按开始时刻排序的事件。 */
    private static List<ColorClearEvidence> colorClearEvidence(List<Arc> arcs, boolean bridge) {
        List<ColorClearEvidence> result = new ArrayList<>();
        for (int i = 0; i < arcs.size(); i++) {
            for (int j = i + 1; j < arcs.size(); j++) {
                if (arcs.get(i).getColor() == arcs.get(j).getColor()) {
                    continue;
                }
                List<ArcColorStateMachine.ColorBridgeInterval> intervals = bridge
                        ? ArcColorStateMachine.colorBridgeIntervals(arcs.get(i), arcs.get(j))
                        : ArcColorStateMachine.colorClearIntervals(arcs.get(i), arcs.get(j));
                for (ArcColorStateMachine.ColorBridgeInterval interval : intervals) {
                    result.add(new ColorClearEvidence(
                            interval.startTime(),
                            interval.endTime() + ArcColorStateMachine.COLOR_GRACE_MILLIS));
                }
            }
        }
        result.sort(Comparator.comparingDouble(ColorClearEvidence::startTime));
        return List.copyOf(result);
    }

    /**
     * 串接已完成空间分组和轨迹规划的同色碎蛇，保留各段零时长入口及判定身份。
     * 空白间隙内提前移向下一入口，避免晚偏移时触点仍停在上一尾点。
     *
     * @param strokes 包含独立 Arc 路径的触控列表，尚未关联前置 Hold/Tap
     */
    private static void mergeFragmentStrokes(Aff aff, List<TouchStroke> strokes, List<ColorClearEvidence> clear) {
        Map<Integer, TouchStroke> previousByColor = new LinkedHashMap<>();
        List<TouchStroke> arcs = strokes.stream()
                .filter(stroke -> stroke.kind() == TouchStroke.Kind.ARC)
                .sorted(Comparator.comparingDouble(TouchStroke::startTime))
                .toList();
        for (TouchStroke arc : arcs) {
            int color = arc.arcColorContacts().getFirst().source().getColor();
            if (arc.arcColorContacts().stream().anyMatch(contact -> contact.source().getColor() != color)) {
                continue;
            }
            TouchStroke previous = previousByColor.get(color);
            if (previous != null) {
                int end = previous.arcColorContacts().stream()
                        .mapToInt(contact -> contact.source().getT2()).max().orElseThrow();
                double gap = arc.startTime() - end;
                if (gap >= 0 && gap < CONTINUATION_GAP_MILLIS) {
                    TouchStroke merged = new TouchStroke(
                            TouchStroke.Kind.ARC, previous.startTime(),
                            Math.max(previous.endTime(), arc.endTime()), previous.initialPosition(),
                            previous.sourceIds().stream().mapToInt(Integer::intValue).min().orElseThrow());
                    for (TouchAnchor anchor : previous.anchors()) {
                        merged.addAnchor(anchor);
                    }
                    if (merged.anchors().getLast().time() < end) {
                        // 窗口路径可能早已命中最后一点；空隙中的移动必须从实际蛇尾开始。
                        merged.addAnchor(new TouchAnchor(end, previous.positionAt(end), true,
                                TouchAnchor.Transition.LINEAR));
                    }
                    List<TouchAnchor> anchors = arc.anchors();
                    TouchAnchor first = anchors.getFirst();
                    if (gap > 1) {
                        Set<Integer> own = new LinkedHashSet<>(previous.sourceIds());
                        own.addAll(arc.sourceIds());
                        List<Arc> foreign = aff.getArcList().stream()
                                .filter(source -> !own.contains(source.getSourceId())
                                        && source.getT1() <= first.time() + 11 && source.getT2() >= end - 11).toList();
                        AffPoint from = previous.positionAt(end);
                        List<TouchAnchor> bridge = new ArrayList<>();
                        boolean detour = false;
                        for (double time = end + 1; time < first.time(); time++) {
                            double ratio = (time - end) / (first.time() - end);
                            AffPoint center = new AffPoint(from.x() + (first.position().x() - from.x()) * ratio,
                                    from.y() + (first.position().y() - from.y()) * ratio);
                            AffPoint point = separatedArcPosition(aff, foreign, clear, time, center, false);
                            detour |= !point.samePosition(center);
                            bridge.add(new TouchAnchor(time, point, true, TouchAnchor.Transition.STEP));
                        }
                        if (detour) bridge.forEach(merged::addAnchor);
                    }
                    TouchAnchor.Transition transition = gap > 0
                            ? TouchAnchor.Transition.LINEAR : TouchAnchor.Transition.STEP;
                    merged.addAnchor(new TouchAnchor(
                            first.time(), first.position(), first.required(), transition));
                    for (int i = 1; i < anchors.size(); i++) {
                        merged.addAnchor(anchors.get(i));
                    }
                    merged.mergeLogicalDemands(previous);
                    merged.mergeLogicalDemands(arc);
                    if (!coversWithTimingMargin(merged, merged.hitOpportunities().stream()
                            .map(HitOpportunity::demand).toList())) {
                        previousByColor.put(color, arc);
                        continue;
                    }
                    strokes.remove(previous);
                    strokes.remove(arc);
                    strokes.add(merged);
                    previousByColor.put(color, merged);
                    continue;
                }
            }
            previousByColor.put(color, arc);
        }
    }

    private static int physicalEnd(Aff aff, LongNoteDemand demand) {
        int componentEnd = aff.getArcTopology().component(demand.componentId()).stream()
                .mapToInt(Arc::getT2)
                .max()
                .orElse(demand.source().getT2());
        return Math.max(
                componentEnd + TouchStroke.MIN_PRESS_DURATION_MILLIS,
                demand.source().getT1() + TouchStroke.MIN_PRESS_DURATION_MILLIS);
    }

    /**
     * 仅在两个同色基础组都由互不相连的 Arc 组成、且能逐条匹配异色路径时拆分。
     * 这样 Dread Area 的左右路径可以分别建立 Stroke，同时保留普通同色窗口组的
     * 单一颜色 owner。
     */
    private static void splitColorBridgeGroups(
            Aff aff, List<List<LongNoteDemand>> groups) {
        for (int firstIndex = 0; firstIndex < groups.size(); firstIndex++) {
            List<LongNoteDemand> first = groups.get(firstIndex);
            for (int secondIndex = firstIndex + 1; secondIndex < groups.size(); secondIndex++) {
                List<LongNoteDemand> second = groups.get(secondIndex);
                if (first.getFirst().color() == second.getFirst().color()
                        || canTrackTogether(aff, combinedArcs(first, second))) {
                    continue;
                }
                List<DemandPair> pairs = matchColorBridgeDemands(aff, first, second);
                if (pairs == null) {
                    continue;
                }
                groups.remove(secondIndex);
                groups.remove(firstIndex);
                for (DemandPair pair : pairs) {
                    List<LongNoteDemand> replacement = new ArrayList<>();
                    replacement.add(pair.first());
                    replacement.add(pair.second());
                    sortDemands(replacement);
                    groups.add(replacement);
                }
                firstIndex = -1;
                break;
            }
        }
    }

    private static List<Arc> combinedArcs(
            List<LongNoteDemand> first, List<LongNoteDemand> second) {
        List<Arc> arcs = new ArrayList<>(arcsOf(first));
        arcs.addAll(arcsOf(second));
        return arcs;
    }

    private static List<DemandPair> matchColorBridgeDemands(
            Aff aff, List<LongNoteDemand> first, List<LongNoteDemand> second) {
        if (first.size() != second.size()
                || !hasDistinctComponents(first)
                || !hasDistinctComponents(second)) {
            return null;
        }
        boolean[] used = new boolean[second.size()];
        List<DemandPair> pairs = new ArrayList<>();
        for (LongNoteDemand firstDemand : first) {
            DemandPair match = null;
            int matchIndex = -1;
            for (int i = 0; i < second.size(); i++) {
                if (used[i]) {
                    continue;
                }
                LongNoteDemand secondDemand = second.get(i);
                Arc firstArc = (Arc) firstDemand.source();
                Arc secondArc = (Arc) secondDemand.source();
                if (colorBridgeTime(firstArc, secondArc).isEmpty()
                        || !canTrackTogether(aff, List.of(firstArc, secondArc))
                        || !canColorTrackTogether(List.of(firstArc, secondArc))) {
                    continue;
                }
                match = new DemandPair(firstDemand, secondDemand);
                matchIndex = i;
                break;
            }
            if (match == null) {
                return null;
            }
            used[matchIndex] = true;
            pairs.add(match);
        }
        return pairs;
    }

    private static boolean hasDistinctComponents(List<LongNoteDemand> demands) {
        Set<Integer> components = new LinkedHashSet<>();
        return demands.stream().allMatch(demand -> components.add(demand.componentId()));
    }

    /**
     * 合并满足异色清色准入且能由一条连续路径覆盖的纯色 Arc 组。
     */
    private static void mergeColorBridgeGroups(
            Aff aff, List<List<LongNoteDemand>> groups) {
        for (int sourceIndex = groups.size() - 1; sourceIndex >= 0; sourceIndex--) {
            List<LongNoteDemand> source = groups.get(sourceIndex);
            if (!isSingleColorGroup(source)) {
                continue;
            }
            for (int targetIndex = 0; targetIndex < groups.size(); targetIndex++) {
                if (targetIndex == sourceIndex) {
                    continue;
                }
                List<LongNoteDemand> target = groups.get(targetIndex);
                if (!isSingleColorGroup(target)
                        || target.getFirst().color() == source.getFirst().color()
                        || !hasColorBridge(target, source)) {
                    continue;
                }
                List<Arc> combined = combinedArcs(target, source);
                if (!canTrackTogether(aff, combined) || !canColorTrackTogether(combined)) {
                    continue;
                }
                target.addAll(source);
                sortDemands(target);
                groups.remove(sourceIndex);
                break;
            }
        }
    }

    /** 完整跟踪须连同尾部实际接触检查颜色，不能漏掉末次判定之后重新染色的区间。 */
    private static boolean canColorTrackTogether(List<Arc> arcs) {
        return colorsCanShareTouch(arcs.stream()
                .flatMap(arc -> java.util.stream.Stream.of(
                        new TouchStroke.ArcColorContact(arc, arc.getT1()),
                        new TouchStroke.ArcColorContact(arc, arc.getT2()))).toList());
    }

    private static boolean isSingleColorGroup(List<LongNoteDemand> demands) {
        return demands.stream().allMatch(
                demand -> demand.color() == demands.getFirst().color());
    }

    private static boolean hasColorBridge(
            List<LongNoteDemand> first, List<LongNoteDemand> second) {
        for (Arc firstArc : arcsOf(first)) {
            for (Arc secondArc : arcsOf(second)) {
                if (firstArc.getColor() == secondArc.getColor()
                        || colorBridgeTime(firstArc, secondArc).isEmpty()) {
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    private static OptionalDouble colorBridgeTime(Arc first, Arc second) {
        List<ArcColorStateMachine.ColorBridgeInterval> intervals =
                ArcColorStateMachine.colorBridgeIntervals(first, second);
        return intervals.isEmpty()
                ? OptionalDouble.empty()
                : OptionalDouble.of(intervals.getFirst().startTime());
    }

    /**
     * 在完整跟踪和窗口求解都可行时按边沿、位置更新数和世界位移稳定择优。
     * 成本完全相同时保留先生成的跟踪方案。
     */
    private static TouchStroke lowerCost(TouchStroke stable, TouchStroke candidate) {
        if (stable == null) {
            return candidate;
        }
        if (candidate == null) {
            return stable;
        }
        return planCost(candidate).compareTo(planCost(stable)) < 0 ? candidate : stable;
    }

    private static PlanCost planCost(TouchStroke stroke) {
        int moves = 0;
        double travel = 0;
        List<TouchAnchor> anchors = stroke.anchors();
        TouchAnchor previous = anchors.getFirst();
        for (int i = 1; i < anchors.size(); i++) {
            TouchAnchor current = anchors.get(i);
            if (!previous.position().samePosition(current.position())) {
                moves++;
                travel += Math.sqrt(ArcColorStateMachine.worldDistanceSquared(
                        previous.position(), current.position()));
            }
            previous = current;
        }
        return new PlanCost(2, moves, travel);
    }

    private static List<Arc> arcsOf(List<LongNoteDemand> demands) {
        return demands.stream()
                .map(LongNoteDemand::source)
                .map(Arc.class::cast)
                .sorted(Comparator
                        .comparingInt(Arc::getT1)
                        .thenComparingInt(Arc::getT2)
                        .thenComparingInt(Arc::getSourceId))
                .toList();
    }

    private static void sortDemands(List<LongNoteDemand> demands) {
        demands.sort(Comparator
                .comparingInt((LongNoteDemand demand) -> demand.source().getT1())
                .thenComparingInt(demand -> demand.source().getT2())
                .thenComparingInt(demand -> demand.source().getSourceId()));
    }

    private static int find(int[] parent, int index) {
        int root = index;
        while (parent[root] != root) {
            root = parent[root];
        }
        while (parent[index] != index) {
            int next = parent[index];
            parent[index] = root;
            index = next;
        }
        return root;
    }

    private static void union(int[] parent, int first, int second) {
        int a = find(parent, first);
        int b = find(parent, second);
        if (a != b) {
            parent[Math.max(a, b)] = Math.min(a, b);
        }
    }

    private TouchStroke planHold(Hold hold, LongNoteDemand demand) {
        double[] xy = hold.getAffPoint(hold.getT1());
        AffPoint position = new AffPoint(xy[0], xy[1]);
        TouchStroke stroke = new TouchStroke(
                TouchStroke.Kind.HOLD, hold.getT1(), hold.getT2(), position, hold.getSourceId());
        double lastHit = hold.getT1();
        for (CoverageDemand coverage : demand.demands()) {
            OptionalDouble hitTime = firstRepresentableHitAt(
                    coverage.window(), position, lastHit);
            if (hitTime.isEmpty()) {
                throw new IllegalStateException("固定 Hold 位置无法覆盖自身判定窗口，sourceId="
                        + hold.getSourceId());
            }
            lastHit = hitTime.getAsDouble();
            stroke.addHitOpportunity(new HitOpportunity(coverage, lastHit, position));
        }
        return stroke;
    }

    private TouchStroke planArcGroup(
            Aff aff, List<LongNoteDemand> demands, List<ColorClearEvidence> clear) {
        List<Arc> arcs = arcsOf(demands);
        List<Arc> zeroDurationArcs = relatedZeroDurationArcs(aff, arcs);
        ConnectionEntry connectionEntry = findConnectionEntry(aff, arcs);
        double startTime = connectionEntry == null
                ? arcs.stream().mapToInt(Arc::getT1).min().orElseThrow()
                : connectionEntry.time();
        double endTime = java.util.stream.Stream.concat(arcs.stream(), zeroDurationArcs.stream())
                .mapToInt(Arc::getT2).max().orElseThrow();
        List<CoverageDemand> windows = demands.stream()
                .flatMap(demand -> demand.demands().stream())
                .sorted(Comparator
                        .comparingDouble((CoverageDemand demand) -> demand.window().endTime())
                        .thenComparingDouble(demand -> demand.window().startTime())
                        .thenComparingInt(demand -> demand.point().id()))
                .toList();
        ArcGroupPlan plan = new ArcGroupPlan(
                aff, arcs, zeroDurationArcs, windows, connectionEntry, startTime, endTime);
        TouchStroke centered = prepareArcCandidate(
                planTrackedArcGroup(plan, false, clear), plan.zeroDurationArcs());
        TouchStroke tracked = prepareArcCandidate(
                planTrackedArcGroup(plan, true, clear), plan.zeroDurationArcs());
        String centeredConflict = centered == null ? "无连续路径" : ownershipConflict(plan, centered, clear);
        String trackedConflict = tracked == null ? "无避色路径" : ownershipConflict(plan, tracked, clear);
        if (centeredConflict != null) centered = null;
        if (trackedConflict != null) tracked = null;
        TouchStroke window;
        UnsatisfiedWindowException windowFailure = null;
        try {
            window = prepareArcCandidate(
                    planWindowArcGroup(plan, null), plan.zeroDurationArcs());
        } catch (UnsatisfiedWindowException e) {
            windowFailure = e;
            window = null;
        }
        String windowConflict = window == null ? "无窗口路径" : ownershipConflict(plan, window, clear);
        TouchStroke stroke = lowerCost(lowerCost(centered, tracked), windowConflict == null ? window : null);
        if (stroke != null) {
            return stroke;
        }
        if (windowFailure != null) {
            throw windowFailure;
        }
        throw new ArcColorStateMachine.UnsatisfiedColorException(
                "无法规划满足窗口与触点约束的 Arc 路径：" + aff.getAffFile().getAbsolutePath()
                        + "，arcs=" + arcs + "，跟踪=" + centeredConflict
                        + "，避色=" + trackedConflict + "，窗口=" + windowConflict);
    }

    /**
     * 候选必须满足实际窗口、按下时的点击竞争，以及首次接触/清色结束时的颜色唯一性。
     * 触点已经获得所需颜色后，接近其他颜色不会重新染色；不把整段接近都误判成冲突。
     */
    private static String ownershipConflict(
            ArcGroupPlan plan, TouchStroke stroke, List<ColorClearEvidence> clear) {
        AffPoint initial = stroke.initialPosition();
        double ratio = plan.aff().getRatio46k(stroke.startTime());
        Arc first = plan.arcs().getFirst();
        boolean head = ArcJudgementRange.covers(pointAt(first, first.getT1()), initial);
        Set<Integer> ownIds = stroke.sourceIds();
        boolean continued = plan.aff().getArcList().stream().anyMatch(arc ->
                !ownIds.contains(arc.getSourceId()) && arc.getColor() == first.getColor()
                && arc.getT2() <= stroke.startTime()
                && stroke.startTime() - arc.getT2() < CONTINUATION_GAP_MILLIS);
        boolean consumesPress = plan.aff().getNoteList().stream().anyMatch(press ->
                (press instanceof Click || press instanceof ArcTap)
                        && press.getT1() == stroke.startTime()
                        && pressEntryIsStable(press, initial, ratio));
        for (Note press : plan.aff().getNoteList()) {
            if (!(press instanceof Click || press instanceof ArcTap || press instanceof Hold)) continue;
            double gap = press.getT1() - stroke.startTime();
            if (gap > 130 || gap < 0 || !InputJudgementRange.coversPress(press, initial, ratio)) continue;
            if (!continued && !consumesPress && !(press instanceof Hold)
                    && gap > (press.getNoteCount() == 0 ? 100 : 25) - 10
                    || press instanceof Hold && gap == 0 && !head)
                return "起按会与普通按键竞争，sourceId=" + press.getSourceId() + "，gap=" + gap;
        }
        boolean firstInGrace = clear.stream().anyMatch(interval -> interval.startTime() <= first.getT1()
                && interval.graceEnd() >= first.getT2());
        if (!head && plan.connectionEntry() == null && !firstInGrace) {
            for (Note press : plan.aff().getNoteList()) {
                if (!(press instanceof Click || press instanceof ArcTap || press instanceof Hold)
                        || press.getT1() > first.getT2() + 10 || press.getT2() < first.getT1() - 10) continue;
                double[] xy = press.getAffPoint();
                AffPoint position = new AffPoint(xy[0], xy[1]);
                AffPoint entry = pointAt(first, first.getT1());
                if (Math.abs(press.getT1() - first.getT1()) <= CONTINUATION_GAP_MILLIS
                        && (ArcColorStateMachine.areClose(position, entry)
                        || pressEntryIsStable(press, entry, ratio))) continue;
                if (arcContactCount(plan.aff(), List.of(first), press.getT1() - 10, press.getT2() + 10,
                        position, false) > 0)
                    return "首次触及音弧晚于竞争按键，sourceId=" + press.getSourceId();
            }
        }
        List<Arc> arcs = plan.aff().getArcList().stream()
                .filter(arc -> arc.getT1() <= stroke.endTime() + 35
                        && arc.getT2() >= stroke.startTime() - 11).toList();
        List<TouchAnchor> anchors = stroke.anchors();
        List<HitOpportunity> hits = new ArrayList<>();
        for (int shift : new int[]{-10, 0, 10}) {
            TreeMap<Double, Integer> bindings = new TreeMap<>();
            int index = 0;
            double graceEnd = Double.NEGATIVE_INFINITY;
            boolean bound = false;
            int possible = 0;
            double time = stroke.startTime() + shift;
            bindings.put(time, 0);
            while (time <= stroke.endTime() + shift) {
                while (index < clear.size() && clear.get(index).startTime() <= time) {
                    graceEnd = Math.max(graceEnd, clear.get(index++).graceEnd());
                    bound = false;
                    possible = 0;
                }
                if (time <= graceEnd) {
                    bindings.put(time, -1);
                    time = Math.nextUp(graceEnd);
                    bindings.put(time, 0);
                    continue;
                }
                if (bound) {
                    if (index == clear.size()) break;
                    time = Math.max(time, clear.get(index).startTime());
                    continue;
                }
                AffPoint point = stroke.positionAt(Math.clamp(time - shift, stroke.startTime(), stroke.endTime()));
                int colors = 0, owned = 0, borrowed = 0;
                boolean certain = false;
                boolean atDown = time == stroke.startTime() + shift
                        && plan.aff().getRatio46k(time) == plan.aff().getRatio46k(stroke.startTime());
                boolean precise = atDown || stationaryPosition(plan.aff(), anchors, time - shift, time);
                double xMargin = precise ? 0.002 : X_MARGIN;
                double yMargin = precise ? 0.002 : Y_MARGIN;
                for (Arc arc : arcs) {
                    if (arc.getT1() > time || arc.getT2() < time) continue;
                    AffPoint center = pointAt(arc, time);
                    AffPoint position = InputJudgementRange.skyPosition(point, center.y(), plan.aff().getRatio46k(time));
                    AffPoint near = new AffPoint(position.x() - Math.clamp(position.x() - center.x(), -xMargin, xMargin),
                            position.y() - Math.clamp(position.y() - center.y(), -yMargin, yMargin));
                    if (ArcJudgementRange.covers(center, near)) {
                        int color = 1 << arc.getColor();
                        colors |= color;
                        if (ownIds.contains(arc.getSourceId())) owned |= color;
                        else borrowed |= color;
                    }
                    if (ArcJudgementRange.covers(center, position, xMargin, yMargin)) certain = true;
                }
                if (colors != 0) {
                    if ((borrowed & ~owned) != 0 && time > first.getT2())
                        return "会占用其他路径的颜色，time=" + time + "，offset=" + shift;
                    possible |= colors;
                    // 误差外包只说明可能接触；真正进入内部矩形前仍须保留未染色和后续抢色的可能。
                    bound = certain;
                    bindings.put(time, bound ? possible : possible | UNBOUND_COLOR);
                }
                time = Math.floor(time) + 1;
            }
            CoverageDemand missing = uncoveredAtOffset(plan.aff(), stroke, plan.windows(), shift,
                    bindings, shift == 0 ? hits : null);
            if (missing != null) return "持色或空间覆盖不足，offset=" + shift
                    + "，sourceId=" + missing.point().source().getSourceId()
                    + "，nominal=" + missing.point().nominalTime();
        }
        stroke.replaceArcOpportunities(hits);
        return null;
    }

    /**
     * 在完整窗口中检查路径的时间余量，防止省操作或吸收另一条路径后只剩边界上的瞬时覆盖。
     * 这里仅约束原谱几何与计划位置；颜色归属和最终整数 JSON 仍由独立回放验收。
     */
    private static boolean coversWithTimingMargin(TouchStroke stroke, List<CoverageDemand> demands) {
        for (int shift : new int[]{-10, 0, 10}) {
            if (uncoveredAtOffset(null, stroke, demands, shift, null, null) != null) return false;
        }
        return true;
    }

    /**
     * 检查一次时移下每个原窗口的连续空间覆盖；可额外约束该段路径所有可能的持色结果。
     * bindings为null时仅检查几何；值-1表示放行，0表示尚未染色，其他值含可能颜色位及未染色标记。
     * 返回第一个无法覆盖的原需求，全部满足时返回null。
     */
    private static CoverageDemand uncoveredAtOffset(
            Aff aff, TouchStroke stroke, List<CoverageDemand> demands, int shift,
            TreeMap<Double, Integer> bindings, List<HitOpportunity> hits) {
        List<TouchAnchor> anchors = stroke.anchors();
        for (CoverageDemand demand : demands) {
            if (!(demand.point().source() instanceof Arc arc)) continue;
            double from = Math.max(demand.window().startTime(), stroke.startTime() + shift);
            double until = Math.min(demand.window().endTime(), stroke.endTime() + shift);
            int covered = 0;
            // 两个相隔1 ms的覆盖时刻排除无法输出的瞬时穿越，空间误差由判定矩形内部余量承担。
            int required = (int) Math.min(2, Math.floor(until - from) + 1);
            for (double time = from; time <= until; time++) {
                Integer colors = bindings == null ? -1 : bindings.floorEntry(time).getValue();
                if (colors != -1 && colors != (1 << arc.getColor())) {
                    covered = 0;
                    continue;
                }
                AffPoint center = pointAt(arc, time);
                AffPoint point = stroke.positionAt(Math.clamp(time - shift, stroke.startTime(), stroke.endTime()));
                // 必需坐标稳定输出后只剩整数误差，移动段还须容纳距离采样误差。
                boolean precise = stationaryPosition(aff, anchors, time - shift, time);
                covered = ArcJudgementRange.covers(center, point, precise ? 0.002 : X_MARGIN,
                        precise ? 0.002 : Y_MARGIN) ? covered + 1 : 0;
                if (covered >= required) {
                    if (hits != null) {
                        double hit = time - covered + 1;
                        hits.add(new HitOpportunity(demand, hit, stroke.positionAt(hit - shift)));
                    }
                    break;
                }
            }
            if (required <= 0 || covered < required) return demand;
        }
        return null;
    }

    /** 必需锚点已落入整数输出，且当前位置及4K/6K投影保持稳定时，不存在移动抽样误差。 */
    private static boolean stationaryPosition(Aff aff, List<TouchAnchor> anchors, double time, double chartTime) {
        if (aff == null) return false;
        int index = Collections.binarySearch(anchors, new TouchAnchor(time, anchors.getFirst().position()),
                Comparator.comparingDouble(TouchAnchor::time));
        if (index < 0) index = -index - 2;
        if (index < 0) return false;
        TouchAnchor from = anchors.get(index);
        if (!from.required() || time < Math.ceil(from.time()) + 1
                || aff.getRatio46k(chartTime) != aff.getRatio46k(from.time())) return false;
        if (index + 1 == anchors.size()) return true;
        TouchAnchor to = anchors.get(index + 1);
        return from.position().samePosition(to.position())
                || to.transition() == TouchAnchor.Transition.STEP && time < Math.floor(to.time()) - 1;
    }

    /** 补齐零时长颜色接触；具体命中机会由完整窗口和颜色约束共同选定。 */
    private static TouchStroke prepareArcCandidate(
            TouchStroke stroke, List<Arc> zeroDurationArcs) {
        if (stroke == null) {
            return null;
        }
        for (Arc arc : zeroDurationArcs) {
            stroke.addArcColorContact(arc, arc.getT1());
        }
        return stroke;
    }

    private static List<Arc> relatedZeroDurationArcs(Aff aff, List<Arc> sourceArcs) {
        Map<Integer, Arc> result = new LinkedHashMap<>();
        ArrayDeque<Arc> pending = new ArrayDeque<>(sourceArcs);
        while (!pending.isEmpty()) {
            Arc current = pending.removeFirst();
            collectAdjacentZeroDurationArcs(
                    aff.getArcTopology().predecessorsOf(current.getSourceId()),
                    current.getColor(), result, pending);
            collectAdjacentZeroDurationArcs(
                    aff.getArcTopology().successorsOf(current.getSourceId()),
                    current.getColor(), result, pending);
        }
        return result.values().stream()
                .sorted(Comparator
                        .comparingInt(Arc::getT1)
                        .thenComparingInt(Arc::getSourceId))
                .toList();
    }

    private static void collectAdjacentZeroDurationArcs(
            List<Arc> candidates, int color, Map<Integer, Arc> result,
            ArrayDeque<Arc> pending) {
        for (Arc candidate : candidates) {
            if (candidate.getT1() != candidate.getT2()
                    || candidate.getColor() != color
                    || result.putIfAbsent(candidate.getSourceId(), candidate) != null) {
                continue;
            }
            pending.addLast(candidate);
        }
    }

    private static ConnectionEntry findConnectionEntry(Aff aff, List<Arc> arcs) {
        int firstArcTime = arcs.stream().mapToInt(Arc::getT1).min().orElseThrow();
        Map<Integer, Arc> leading = new LinkedHashMap<>();
        ArrayDeque<Arc> pending = new ArrayDeque<>();
        arcs.stream()
                .filter(arc -> arc.getT1() == firstArcTime)
                .forEach(pending::addLast);
        while (!pending.isEmpty()) {
            Arc current = pending.removeFirst();
            for (Arc predecessor : aff.getArcTopology().predecessorsOf(current.getSourceId())) {
                if (predecessor.getT1() != predecessor.getT2()
                        || predecessor.getColor() != current.getColor()
                        || leading.putIfAbsent(predecessor.getSourceId(), predecessor) != null) {
                    continue;
                }
                pending.addLast(predecessor);
            }
        }
        if (leading.isEmpty()) {
            return null;
        }

        Set<Integer> leadingIds = leading.keySet();
        List<Arc> roots = leading.values().stream()
                .filter(arc -> aff.getArcTopology().predecessorsOf(arc.getSourceId()).stream()
                        .noneMatch(predecessor -> leadingIds.contains(predecessor.getSourceId())))
                .toList();
        if (roots.isEmpty()) {
            roots = List.copyOf(leading.values());
        }
        Arc first = roots.getFirst();
        AffPoint position = new AffPoint(first.getX1(), first.getY1());
        for (Arc root : roots) {
            AffPoint candidate = new AffPoint(root.getX1(), root.getY1());
            if (root.getT1() != first.getT1() || !candidate.samePosition(position)) {
                throw new IllegalStateException(
                        "同一 Arc 触控组存在不一致的零时长连接入口："
                                + aff.getAffFile().getAbsolutePath()
                                + "，sourceId=" + first.getSourceId()
                                + "/" + root.getSourceId());
            }
        }
        int sourceId = roots.stream().mapToInt(Arc::getSourceId).min().orElseThrow();
        return new ConnectionEntry(first.getT1(), position, sourceId);
    }

    /**
     * 为不存在正时长空间冲突的 Arc 组建立从蛇头到蛇尾的连续覆盖路径。
     *
     * @return 可连续跟踪时返回完整触控；否则返回 null 交由窗口求解器处理
     */
    private TouchStroke planTrackedArcGroup(
            ArcGroupPlan plan, boolean separateContacts, List<ColorClearEvidence> clear) {
        if (!canTrackTogether(plan.aff(), plan.arcs())) {
            return null;
        }
        TrackedPath path = buildTrackedPath(plan, separateContacts, clear);
        if (path == null) {
            return null;
        }

        TouchAnchor first = path.anchors().getFirst();
        int sourceId = plan.connectionEntry() == null
                ? plan.arcs().getFirst().getSourceId()
                : plan.connectionEntry().sourceId();
        TouchStroke stroke = new TouchStroke(
                TouchStroke.Kind.ARC, plan.startTime(), plan.endTime(),
                first.position(), sourceId);
        for (int i = 1; i < path.anchors().size(); i++) {
            try {
                stroke.addAnchor(path.anchors().get(i));
            } catch (IllegalStateException e) {
                throw new IllegalStateException(
                        e.getMessage() + "：" + plan.aff().getAffFile().getAbsolutePath(), e);
            }
        }
        for (CoverageDemand demand : plan.windows()) {
            HitOpportunity opportunity = firstTrackedHit(path, demand, Map.of());
            if (opportunity == null) {
                return null;
            }
            stroke.addCoveredOpportunity(opportunity);
        }
        return stroke;
    }

    /**
     * 在原窗口内选择实际命中锚点，交接重算时不得占用另一必需锚点的整数毫秒。
     *
     * @param path             已规划的连续路径
     * @param demand           名义时间和截止时间保持不变的判定需求
     * @param requiredByMillis 交接路径已保留的必需锚点；普通跟踪传空表
     * @return 首个覆盖需求且可输出的命中机会，无可用锚点时返回 null
     */
    private static HitOpportunity firstTrackedHit(
            TrackedPath path, CoverageDemand demand, Map<Long, TouchAnchor> requiredByMillis) {
        JudgeWindow window = demand.window();
        for (TouchAnchor anchor : path.anchors()) {
            if (anchor.time() < window.startTime() - TIME_EPSILON
                    || anchor.time() > window.endTime() + TIME_EPSILON) {
                continue;
            }
            TouchAnchor occupied = requiredByMillis.get(Math.round(anchor.time()));
            if (occupied != null && Double.compare(occupied.time(), anchor.time()) != 0) {
                continue;
            }
            OptionalDouble firstHit;
            if (demand.point().kind() == JudgePoint.Kind.ARC_HEAD
                    && demand.point().source() instanceof Arc arc) {
                // 追加点保留名义窗口，实际空间判定沿当前曲线；零时长入口不必同时等于实体头中心。
                firstHit = anchor.time() >= arc.getT1() && anchor.time() <= arc.getT2()
                        && ArcJudgementRange.covers(pointAt(arc, anchor.time()), anchor.position())
                        ? OptionalDouble.of(anchor.time()) : OptionalDouble.empty();
            } else {
                firstHit = window.firstTimeAt(anchor.position(), anchor.time());
            }
            if (firstHit.isPresent()
                    && firstHit.getAsDouble() <= anchor.time() + TIME_EPSILON) {
                return new HitOpportunity(demand, anchor.time(), anchor.position());
            }
        }
        return null;
    }

    private static boolean canTrackTogether(Aff aff, List<Arc> arcs) {
        if (aff != null && !hasCompatibleConnectionEntry(aff, arcs)) {
            return false;
        }
        for (int i = 0; i < arcs.size(); i++) {
            for (int j = i + 1; j < arcs.size(); j++) {
                Arc first = arcs.get(i);
                Arc second = arcs.get(j);
                double from = Math.max(first.getT1(), second.getT1());
                double to = Math.min(first.getT2(), second.getT2());
                if (to - from <= TIME_EPSILON || connectedHandoff(aff, first, second)
                        || connectedHandoff(aff, second, first)) {
                    continue;
                }
                double step = Math.min(trackingInterval(first), trackingInterval(second));
                for (long sample = 0; ; sample++) {
                    double time = from + sample * step;
                    if (time >= to - TIME_EPSILON) {
                        break;
                    }
                    if (!rangesOverlap(first, second, time)) {
                        return false;
                    }
                }
                double finalProbe = Math.nextDown(to);
                if (finalProbe > from && !rangesOverlap(first, second, finalProbe)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean hasCompatibleConnectionEntry(Aff aff, List<Arc> arcs) {
        try {
            findConnectionEntry(aff, arcs);
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /** 合轨须为双方都保留屏幕采样余量；只有极窄交集时保留独立路径。 */
    private static boolean rangesOverlap(Arc first, Arc second, double time) {
        AffPoint a = pointAt(first, time), b = pointAt(second, time);
        AffPoint middle = new AffPoint((a.x() + b.x()) / 2, (a.y() + b.y()) / 2);
        AffPoint towardB = new AffPoint(middle.x() + Math.copySign(X_MARGIN, b.x() - a.x()),
                middle.y() + Math.copySign(Y_MARGIN, b.y() - a.y()));
        AffPoint towardA = new AffPoint(middle.x() + Math.copySign(X_MARGIN, a.x() - b.x()),
                middle.y() + Math.copySign(Y_MARGIN, a.y() - b.y()));
        return ArcJudgementRange.covers(a, towardB) && ArcJudgementRange.covers(b, towardA);
    }

    private static double trackingInterval(Arc arc) {
        double bpm = arc.getJudgeBpm() != 0 ? arc.getJudgeBpm() : arc.getTimingBpm();
        if (bpm == 0) {
            return Math.max(1.0, arc.getT2() - arc.getT1());
        }
        return Math.max(1.0, 30000.0 / bpm / TOUCH_SAMPLE_FREQUENCY);
    }

    private static TrackedPath buildTrackedPath(
            ArcGroupPlan plan, boolean separateContacts, List<ColorClearEvidence> clear) {
        Set<Integer> sourceIds = plan.arcs().stream().map(Arc::getSourceId)
                .collect(java.util.stream.Collectors.toSet());
        List<Arc> foreign = separateContacts ? plan.aff().getArcList().stream()
                .filter(arc -> !sourceIds.contains(arc.getSourceId())).toList() : List.of();
        TreeMap<Double, PathMarker> markers = new TreeMap<>();
        if (plan.connectionEntry() != null
                && !addPathMarker(
                        markers, plan.connectionEntry().time(), true,
                        plan.connectionEntry().position())) {
            return null;
        }
        for (Arc arc : plan.arcs()) {
            if (!addPathMarker(markers, arc.getT1(), true, null)
                    || !addPathMarker(markers, arc.getT2(), true, null)) {
                return null;
            }
            double step = trackingInterval(arc);
            for (long sample = 1; ; sample++) {
                double time = arc.getT1() + sample * step;
                if (time >= arc.getT2() - TIME_EPSILON) {
                    break;
                }
                if (!addPathMarker(markers, time, false, null)) {
                    return null;
                }
            }
        }
        for (CoverageDemand demand : plan.windows()) {
            JudgeWindow window = demand.window();
            if (!addPathMarker(markers, window.startTime(), false, null)) {
                return null;
            }
        }

        if (separateContacts) {
            // 放行边界可能位于两个安全的稀疏采样点之间，须显式检查刚离开放行的各个时移位置。
            for (ColorClearEvidence interval : clear) {
                for (double boundary : new double[]{interval.startTime(), interval.graceEnd()}) {
                    for (int shift : new int[]{-11, 0, 11}) {
                        double time = Math.floor(boundary + shift) + 1;
                        if (time >= plan.startTime() && time <= plan.endTime()
                                && primaryArcAt(plan.arcs(), time) != null)
                            addPathMarker(markers, time, false, null);
                    }
                }
            }
            int end = plan.arcs().getFirst().getT2();
            for (Arc arc : plan.arcs()) {
                if (arc.getT1() > end) {
                    // 不能释放的空闲段也需避色；把真实尾至下一头的过渡纳入逐毫秒规划。
                    for (double time = end + 1; time < arc.getT1(); time++)
                        addPathMarker(markers, time, false, null);
                }
                end = Math.max(end, arc.getT2());
            }
            List<Double> times = List.copyOf(markers.keySet());
            for (int index = 0; index < times.size(); index++) {
                double time = times.get(index);
                Arc primary = primaryArcAt(plan.arcs(), time);
                if (primary == null || markers.get(time).fixedPosition() != null
                        || isConnectionEntry(plan.connectionEntry(), time)) continue;
                AffPoint center = trackingPosition(plan.aff(), plan.arcs(), time, primary);
                if (separatedArcPosition(plan.aff(), foreign, clear, time, center,
                        time == plan.startTime()).samePosition(center)) continue;
                // 绕开其他判定区时保留逐毫秒路径，避免稀疏锚点插值又穿回抢色区域。
                double from = index == 0 ? time : times.get(index - 1);
                double to = index + 1 == times.size() ? time : times.get(index + 1);
                for (double t = Math.ceil(from); t < to; t++) {
                    if (primaryArcAt(plan.arcs(), t) != null)
                        addPathMarker(markers, t, false, null);
                }
            }
        }

        List<TouchAnchor> anchors = new ArrayList<>(markers.size());
        TouchAnchor previous = null;
        for (Map.Entry<Double, PathMarker> entry : markers.entrySet()) {
            double time = entry.getKey();
            if (time < plan.startTime() - PATH_TIME_EPSILON) {
                continue;
            }
            if (time < plan.startTime()) {
                time = plan.startTime();
            }
            PathMarker marker = entry.getValue();
            boolean connectionEntry = isConnectionEntry(plan.connectionEntry(), time);
            Arc primary = primaryArcAt(plan.arcs(), time);
            if (primary == null && !connectionEntry && !separateContacts) {
                return null;
            }
            AffPoint position = marker.fixedPosition() != null
                    ? marker.fixedPosition()
                    : trackingPosition(plan.aff(), plan.arcs(), time, primary);
            if (marker.fixedPosition() == null && !connectionEntry && separateContacts)
                position = separatedArcPosition(plan.aff(), foreign, clear, time, position, time == plan.startTime());
            if (!connectionEntry && !separateContacts
                    && !coversActiveArcs(plan.aff(), plan.arcs(), time, position)) {
                return null;
            }
            boolean detourStep = false;
            if (separateContacts && previous != null && !connectionEntry
                    && !isConnectionEntry(plan.connectionEntry(), previous.time())) {
                Arc before = primaryArcAt(plan.arcs(), previous.time());
                AffPoint a = trackingPosition(plan.aff(), plan.arcs(), previous.time(), before);
                AffPoint b = trackingPosition(plan.aff(), plan.arcs(), time, primary);
                double dx = (position.x() - b.x()) - (previous.position().x() - a.x());
                double dy = (position.y() - b.y()) - (previous.position().y() - a.y());
                detourStep = Math.abs(dx) > 1e-7 || Math.abs(dy) > 1e-7;
                if (detourStep && time != Math.rint(time)) {
                    // 浮点判定时刻仍保留；避让方向只在可独立输出的整数毫秒切换。
                    position = previous.position();
                    detourStep = false;
                }
            }
            TouchAnchor.Transition transition = detourStep || previous != null
                    && !isConnectionEntry(plan.connectionEntry(), previous.time())
                    && requiresStep(plan.aff(), plan.arcs(), previous, time, position)
                    ? TouchAnchor.Transition.STEP
                    : TouchAnchor.Transition.LINEAR;
            TouchAnchor anchor = new TouchAnchor(
                    time, position, marker.required() || detourStep, transition);
            if (previous != null && Math.abs(time - previous.time()) <= PATH_TIME_EPSILON) {
                if (!previous.position().samePosition(position)) {
                    return null;
                }
                if (marker.required() && !previous.required()) {
                    anchor = new TouchAnchor(
                            previous.time(), previous.position(), true, previous.transition());
                    anchors.set(anchors.size() - 1, anchor);
                }
                continue;
            }
            anchors.add(anchor);
            previous = anchor;
        }
        return new TrackedPath(List.copyOf(anchors));
    }

    /** 活动Arc矩形交集的中心为各方向留出相同余量，避免贴一侧曲线时另一侧只剩边界覆盖。 */
    private static AffPoint trackingPosition(Aff aff, List<Arc> arcs, double time, Arc primary) {
        if (primary == null) {
            Arc before = arcs.stream().filter(arc -> arc.getT2() < time)
                    .max(Comparator.comparingInt(Arc::getT2)).orElseThrow();
            Arc after = arcs.stream().filter(arc -> arc.getT1() > time)
                    .min(Comparator.comparingInt(Arc::getT1)).orElseThrow();
            double ratio = (time - before.getT2()) / (after.getT1() - before.getT2());
            AffPoint a = pointAt(before, before.getT2()), b = pointAt(after, after.getT1());
            return new AffPoint(a.x() + (b.x() - a.x()) * ratio, a.y() + (b.y() - a.y()) * ratio);
        }
        AffPoint center = pointAt(primary, time);
        double minX = center.x(), maxX = center.x(), minY = center.y(), maxY = center.y();
        for (Arc arc : arcs) {
            if (arc == primary || time < arc.getT1() || time >= arc.getT2()
                    || connectedHandoff(aff, arc, primary)) continue;
            AffPoint point = pointAt(arc, time);
            minX = Math.min(minX, point.x());
            maxX = Math.max(maxX, point.x());
            minY = Math.min(minY, point.y());
            maxY = Math.max(maxY, point.y());
        }
        return new AffPoint((minX + maxX) / 2, (minY + maxY) / 2);
    }

    /**
     * 在原判定窗口仍可覆盖的前提下绕开其他Arc；必要时短暂离开自身范围，随后逐项重算窗口。
     * 首次按下还须避免提前消费普通点击；整个偏移范围均在清色放行期时无需避色。
     * 原判定区域、名义时间和清色规则不变。
     */
    private static AffPoint separatedArcPosition(
            Aff aff, List<Arc> foreign, List<ColorClearEvidence> clear,
            double time, AffPoint center, boolean starting) {
        boolean inGrace = clear.stream().anyMatch(
                interval -> interval.startTime() <= time - 11 && interval.graceEnd() >= time + 11);
        List<Arc> nearby = inGrace ? List.of() : foreign.stream()
                .filter(arc -> arc.getT1() <= time + 11 && arc.getT2() >= time - 11).toList();
        List<Note> presses = starting ? aff.getNoteList().stream()
                .filter(note -> note instanceof Click || note instanceof ArcTap)
                .filter(note -> note.getT1() - time > (note.getNoteCount() == 0 ? 100 : 25) - 10
                        && note.getT1() <= time + 120 + 10).toList() : List.of();
        double ratio = aff.getRatio46k(time);
        // 16:9各分辨率使用同一归一化投影；6K可输入的横向范围必须随实际投影变化。
        Resolution resolution = Resolution.R16_9_1280_720;
        int[] xy = resolution.convertToXY(center.x(), center.y(), ratio);
        AffPoint home = xy[0] >= 0 && xy[0] <= resolution.getMaxX()
                && xy[1] >= 0 && xy[1] <= resolution.getMaxY() ? center
                : resolution.convertToAffPoint(Math.clamp(xy[0], 0, resolution.getMaxX()),
                Math.clamp(xy[1], 0, resolution.getMaxY()), ratio);
        int bestPressCount = (int) presses.stream()
                .filter(note -> InputJudgementRange.coversPress(note, home, ratio)).count();
        int bestCount = arcContactCount(aff, nearby, time - 11, time + 11, home, true);
        if (bestCount == 0 && bestPressCount == 0) return home;
        AffPoint best = home;
        double bestDistance = ArcColorStateMachine.worldDistanceSquared(center, home);
        for (double dx : new double[]{0, -0.15, 0.15, -0.3, 0.3}) {
            for (double dy : new double[]{0, -0.35, 0.35, -0.7, 0.7}) {
                AffPoint point = new AffPoint(center.x() + dx, center.y() + dy);
                int[] screen = resolution.convertToXY(point.x(), point.y(), ratio);
                if (screen[0] < 0 || screen[0] > resolution.getMaxX()
                        || screen[1] < 0 || screen[1] > resolution.getMaxY()) continue;
                int pressCount = (int) presses.stream()
                        .filter(note -> InputJudgementRange.coversPress(note, point, ratio)).count();
                int count = arcContactCount(aff, nearby, time - 11, time + 11, point, true);
                double distance = ArcColorStateMachine.worldDistanceSquared(center, point);
                if (pressCount < bestPressCount || pressCount == bestPressCount
                        && (count < bestCount || count == bestCount && distance < bestDistance - 1e-9)) {
                    best = point;
                    bestPressCount = pressCount;
                    bestCount = count;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    /** 固定按下仅含整数坐标误差；移动路径另含距离采样误差，两者使用相同的接触区间计算。 */
    private static int arcContactCount(
            Aff aff, List<Arc> arcs, double start, double end, AffPoint position, boolean moving) {
        double xMargin = moving ? X_MARGIN : 0.002;
        double yMargin = moving ? Y_MARGIN : 0.002;
        int count = 0;
        for (Arc arc : arcs) {
            double from = Math.max(arc.getT1(), start);
            double to = Math.min(arc.getT2(), end);
            for (double t = from; t <= to; t++) {
                AffPoint point = pointAt(arc, t);
                AffPoint touch = InputJudgementRange.skyPosition(position, point.y(), aff.getRatio46k(t));
                AffPoint near = new AffPoint(touch.x() - Math.clamp(touch.x() - point.x(), -xMargin, xMargin),
                        touch.y() - Math.clamp(touch.y() - point.y(), -yMargin, yMargin));
                if (ArcJudgementRange.covers(point, near)) count++;
            }
        }
        return count;
    }

    private static boolean isConnectionEntry(ConnectionEntry entry, double time) {
        return entry != null && Math.abs(entry.time() - time) <= TIME_EPSILON;
    }

    private static boolean addPathMarker(
            TreeMap<Double, PathMarker> markers, double time,
            boolean required, AffPoint fixedPosition) {
        PathMarker current = markers.get(time);
        if (current == null) {
            markers.put(time, new PathMarker(required, fixedPosition));
            return true;
        }
        if (current.fixedPosition() != null && fixedPosition != null
                && !current.fixedPosition().samePosition(fixedPosition)) {
            return false;
        }
        markers.put(time, new PathMarker(
                current.required() || required,
                current.fixedPosition() != null ? current.fixedPosition() : fixedPosition));
        return true;
    }

    private static Arc primaryArcAt(List<Arc> arcs, double time) {
        Arc primary = null;
        for (Arc arc : arcs) {
            boolean active = time >= arc.getT1() - TIME_EPSILON
                    && time < arc.getT2() - TIME_EPSILON;
            boolean finalEndpoint = Math.abs(time - arc.getT2()) <= TIME_EPSILON;
            if (!active && !finalEndpoint) {
                continue;
            }
            if (primary == null
                    || arc.getT1() > primary.getT1()
                    || arc.getT1() == primary.getT1()
                    && arc.getSourceId() > primary.getSourceId()) {
                primary = arc;
            }
        }
        return primary;
    }

    /** 原拓扑允许小于10 ms的端点误差，零时长转折可在前一段尾部尚存时开始下一段。 */
    private static boolean connectedHandoff(Aff aff, Arc from, Arc to) {
        if (aff == null || from == to || from.getColor() != to.getColor()
                || to.getT1() < from.getT1() || Math.abs(from.getT2() - to.getT1()) >= 10) return false;
        ArrayDeque<Arc> pending = new ArrayDeque<>(aff.getArcTopology().successorsOf(from.getSourceId()));
        Set<Integer> seen = new LinkedHashSet<>();
        while (!pending.isEmpty()) {
            Arc next = pending.removeFirst();
            if (next.getSourceId() == to.getSourceId()) return true;
            if (next.getT1() == next.getT2() && next.getColor() == from.getColor()
                    && seen.add(next.getSourceId()))
                pending.addAll(aff.getArcTopology().successorsOf(next.getSourceId()));
        }
        return false;
    }

    private static boolean coversActiveArcs(
            Aff aff, List<Arc> arcs, double time, AffPoint position) {
        for (Arc arc : arcs) {
            if (time >= arc.getT1() - TIME_EPSILON
                    && time < arc.getT2() - TIME_EPSILON
                    && !ArcJudgementRange.covers(pointAt(arc, time), position)
                    && arcs.stream().noneMatch(next -> next.getT1() <= time
                    && connectedHandoff(aff, arc, next))) {
                return false;
            }
        }
        return true;
    }

    private static boolean requiresStep(
            Aff aff, List<Arc> arcs, TouchAnchor previous, double time, AffPoint target) {
        if (previous.position().samePosition(target)) {
            return false;
        }
        for (Arc arc : arcs) {
            if (arc.getT2() > arc.getT1()
                    && (Math.abs(time - arc.getT2()) <= TIME_EPSILON
                    || time < arc.getT2() && arcs.stream().anyMatch(next -> next.getT1() == time
                    && connectedHandoff(aff, arc, next)))
                    && !ArcJudgementRange.covers(pointAt(arc, time), target)) {
                return true;
            }
        }
        double middle = (previous.time() + time) / 2.0;
        return arcs.stream().noneMatch(arc -> middle >= arc.getT1()
                && middle < arc.getT2());
    }

    private static AffPoint pointAt(Arc arc, double time) {
        double[] xy = arc.getAffPoint(time);
        return new AffPoint(xy[0], xy[1]);
    }

    private TouchStroke planWindowArcGroup(ArcGroupPlan plan, TouchStroke guide) {
        Arc firstArc = plan.arcs().getFirst();
        List<CoverageDemand> windows = new ArrayList<>(plan.windows());
        AffPoint currentPosition;
        if (plan.connectionEntry() == null) {
            JudgeWindow initialWindow = windows.getFirst().window();
            double initialTime = (initialWindow.startTime() + initialWindow.endTime()) / 2.0;
            currentPosition = guide == null ? initialWindow.positionAt(initialTime) : guide.initialPosition();
        } else {
            currentPosition = plan.connectionEntry().position();
        }
        int sourceId = plan.connectionEntry() == null
                ? firstArc.getSourceId()
                : plan.connectionEntry().sourceId();
        TouchStroke stroke = new TouchStroke(
                TouchStroke.Kind.ARC, plan.startTime(), plan.endTime(),
                currentPosition, sourceId);

        double lastHit = plan.startTime();
        if (plan.connectionEntry() != null) {
            // 零时长入口与实体蛇头分别保留：入口之后立即接入实体，不能从入口缓慢追向后续命中点。
            double time = Math.max(nextRoundedMillis(plan.startTime()), firstArc.getT1());
            Arc primary = primaryArcAt(plan.arcs(), time);
            if (primary == null) throw new UnsatisfiedWindowException(plan.aff(), plan.windows().getFirst());
            currentPosition = trackingPosition(plan.aff(), plan.arcs(), time, primary);
            stroke.addAnchor(new TouchAnchor(time, currentPosition, true, TouchAnchor.Transition.STEP));
            lastHit = time;
        }
        double holdUntil = lastHit;
        while (!windows.isEmpty()) {
            StationaryHit stationary = selectStationaryHit(windows, currentPosition, lastHit);
            CoverageDemand demand = stationary == null ? windows.getFirst() : stationary.demand();
            AffPoint hitPosition = currentPosition;
            double hitTime;
            if (stationary != null) {
                hitTime = stationary.hitTime();
            } else {
                double moveStart = Math.max(lastHit, holdUntil);
                Candidate candidate = chooseCandidate(windows, 0, currentPosition, moveStart);
                if (candidate == null) {
                    throw new UnsatisfiedWindowException(plan.aff(), demand);
                }
                hitPosition = candidate.position();
                hitTime = candidate.hitTime();
                stroke.addAnchor(moveStart, currentPosition);
                // 新位置提前准备，整体晚10 ms时仍能在原命中机会到达；名义判定时间保持不变。
                double arrival = hitPosition.samePosition(currentPosition)
                        ? hitTime : Math.max(nextRoundedMillis(moveStart), hitTime - 10);
                stroke.addAnchor(arrival, hitPosition);
                currentPosition = hitPosition;
            }
            lastHit = Math.max(lastHit, hitTime);
            holdUntil = Math.max(holdUntil, hitTime + WINDOW_HOLD_MILLIS);
            stroke.addHitOpportunity(new HitOpportunity(demand, hitTime, hitPosition));
            windows.remove(demand);
        }
        // 最后一个窗口同样需要提前偏移保护，不能在尾点刚进入范围前就结束整次按下。
        stroke.holdUntil(holdUntil);
        return stroke;
    }

    private static StationaryHit selectStationaryHit(
            List<CoverageDemand> windows, AffPoint position, double notBefore) {
        double earliestDeadline = windows.getFirst().window().endTime();
        StationaryHit best = null;
        for (CoverageDemand demand : windows) {
            if (demand.window().endTime() > earliestDeadline + TIME_EPSILON) {
                break;
            }
            OptionalDouble hitTime = firstRepresentableHitAt(
                    demand.window(), position, notBefore);
            if (hitTime.isEmpty() || hitTime.getAsDouble() > earliestDeadline + TIME_EPSILON) {
                continue;
            }
            double candidateHitTime = hitTime.getAsDouble();
            if (!preservesNextWindowAfter(
                    windows, demand, position, candidateHitTime)) {
                continue;
            }
            StationaryHit candidate = new StationaryHit(demand, candidateHitTime);
            if (isBetterStationaryHit(candidate, best)) {
                best = candidate;
            }
        }
        return best;
    }

    private static boolean isBetterStationaryHit(StationaryHit candidate, StationaryHit currentBest) {
        if (currentBest == null) {
            return true;
        }
        int hitTimeComparison = Double.compare(candidate.hitTime(), currentBest.hitTime());
        if (hitTimeComparison != 0) {
            return hitTimeComparison < 0;
        }
        JudgeWindow candidateWindow = candidate.demand().window();
        JudgeWindow currentWindow = currentBest.demand().window();
        int deadlineComparison = Double.compare(candidateWindow.endTime(), currentWindow.endTime());
        if (deadlineComparison != 0) {
            return deadlineComparison < 0;
        }
        int startComparison = Double.compare(candidateWindow.startTime(), currentWindow.startTime());
        return startComparison != 0
                ? startComparison < 0
                : candidate.demand().point().id() < currentBest.demand().point().id();
    }

    private Candidate chooseCandidate(List<CoverageDemand> windows, int index,
                                      AffPoint currentPosition, double notBefore) {
        Set<AffPoint> positions = new LinkedHashSet<>();
        int limit = Math.min(windows.size(), index + LOOKAHEAD_WINDOWS);
        for (int i = index; i < limit; i++) {
            JudgeWindow window = windows.get(i).window();
            for (int sample = 0; sample <= CANDIDATE_SAMPLES_PER_WINDOW; sample++) {
                double time = window.startTime()
                        + (window.endTime() - window.startTime())
                        * sample / CANDIDATE_SAMPLES_PER_WINDOW;
                positions.add(window.positionAt(time));
            }
        }

        // 相邻窗口的中点提供带余量的共同覆盖位置，避免只能从单条曲线的采样中心选择。
        List<AffPoint> centers = windows.subList(index, limit).stream()
                .map(demand -> demand.window().positionAt(
                        (demand.window().startTime() + demand.window().endTime()) / 2)).toList();
        for (int i = 0; i < centers.size(); i++) {
            for (int j = i + 1; j < centers.size(); j++) {
                AffPoint a = centers.get(i), b = centers.get(j);
                positions.add(new AffPoint((a.x() + b.x()) / 2, (a.y() + b.y()) / 2));
            }
        }
        Candidate best = null;
        CoverageDemand currentDemand = windows.get(index);
        JudgeWindow currentWindow = currentDemand.window();
        for (AffPoint position : positions) {
            double earliest = position.samePosition(currentPosition)
                    ? notBefore
                    : nextRoundedMillis(notBefore);
            OptionalDouble firstHit = position.samePosition(currentPosition)
                    ? firstRepresentableHitAt(currentWindow, position, notBefore)
                    : firstStableHitAt(currentWindow, position, earliest);
            if (firstHit.isEmpty()) {
                continue;
            }
            double probeTime = firstHit.getAsDouble();
            int covered = 1;
            for (int i = index + 1; i < limit; i++) {
                OptionalDouble nextHit = firstRepresentableHitAt(
                        windows.get(i).window(), position, probeTime);
                if (nextHit.isEmpty()
                        || !preservesNextWindow(
                                windows, i + 1, position, nextHit.getAsDouble())) {
                    break;
                }
                probeTime = nextHit.getAsDouble();
                covered++;
            }
            if (!preservesNextWindow(windows, index + covered, position, probeTime)) {
                continue;
            }
            Candidate candidate = new Candidate(
                    position, firstHit.getAsDouble(), covered,
                    currentPosition.distanceSquared(position));
            if (isBetter(candidate, best)) {
                best = candidate;
            }
        }
        return best;
    }

    private static boolean preservesNextWindowAfter(
            List<CoverageDemand> windows, CoverageDemand completed,
            AffPoint position, double notBefore) {
        for (CoverageDemand demand : windows) {
            if (demand != completed) {
                return canReachWindow(demand.window(), position, notBefore);
            }
        }
        return true;
    }

    private static boolean preservesNextWindow(
            List<CoverageDemand> windows, int nextIndex, AffPoint position, double notBefore) {
        return nextIndex >= windows.size()
                || canReachWindow(windows.get(nextIndex).window(), position, notBefore);
    }

    private static boolean canReachWindow(
            JudgeWindow window, AffPoint position, double notBefore) {
        if (firstRepresentableHitAt(window, position, notBefore).isPresent()) {
            return true;
        }
        // 改换位置须先保留当前窗口的覆盖，再容纳新位置晚10 ms到达及至少1 ms稳定输入。
        double movedHitTime = Math.max(window.startTime(), nextRoundedMillis(notBefore + WINDOW_HOLD_MILLIS) + 10);
        double stableTime = Math.min(1, window.endTime() - window.startTime());
        return movedHitTime + stableTime <= window.endTime() + TIME_EPSILON;
    }

    private static OptionalDouble firstRepresentableHitAt(
            JudgeWindow window, AffPoint position, double notBefore) {
        OptionalDouble firstHit = firstStableHitAt(window, position, notBefore);
        if (firstHit.isEmpty()) {
            return firstHit;
        }
        double hitTime = firstHit.getAsDouble();
        if (hitTime <= notBefore + TIME_EPSILON
                || Math.round(hitTime) != Math.round(notBefore)) {
            return firstHit;
        }
        OptionalDouble separatedHit = firstStableHitAt(window, position, nextRoundedMillis(notBefore));
        return separatedHit.isPresent() ? separatedHit : firstHit;
    }

    /** 单条Arc曲线各轴单调；首次进入之后不足1 ms就离开的候选不能提供稳定输出覆盖。 */
    private static OptionalDouble firstStableHitAt(JudgeWindow window, AffPoint position, double notBefore) {
        OptionalDouble first = window.firstTimeAt(position, notBefore, X_MARGIN, Y_MARGIN);
        if (first.isEmpty() || !(window.point().source() instanceof Arc)) return first;
        double duration = Math.min(1, window.endTime() - window.startTime());
        double next = first.getAsDouble() + duration;
        if (next > window.endTime()) return OptionalDouble.empty();
        return ArcJudgementRange.covers(window.positionAt(next), position, X_MARGIN, Y_MARGIN)
                ? first : OptionalDouble.empty();
    }

    private static double nextRoundedMillis(double time) {
        return Math.floor(time + 0.5) + 0.500001;
    }

    private static boolean isBetter(Candidate candidate, Candidate currentBest) {
        if (currentBest == null) {
            return true;
        }
        if (candidate.coveredWindows() != currentBest.coveredWindows()) {
            return candidate.coveredWindows() > currentBest.coveredWindows();
        }
        int distanceComparison = Double.compare(candidate.distanceSquared(), currentBest.distanceSquared());
        if (distanceComparison != 0) {
            return distanceComparison < 0;
        }
        int timeComparison = Double.compare(candidate.hitTime(), currentBest.hitTime());
        if (timeComparison != 0) {
            return timeComparison < 0;
        }
        int xComparison = Double.compare(candidate.position().x(), currentBest.position().x());
        return xComparison != 0
                ? xComparison < 0
                : Double.compare(candidate.position().y(), currentBest.position().y()) < 0;
    }

    /** 明确表示窗口求解候选不可行，允许稳定回退到完整跟踪方案。 */
    public static final class UnsatisfiedWindowException extends IllegalStateException {
        private UnsatisfiedWindowException(Aff aff, CoverageDemand demand) {
            super("无法满足长键判定窗口：" + aff.getAffFile().getAbsolutePath()
                    + "，sourceId=" + demand.point().source().getSourceId()
                    + "，nominal=" + demand.point().nominalTime()
                    + "，deadline=" + demand.window().endTime());
        }
    }

    /**
     * 优先合并共同有效的 Hold/Arc 起点；否则保留原有 Hold 尾接蛇头语义。
     * 两种路径都必须重新覆盖全部原判定，且每条 Arc 只与一个 Hold 共用边沿。
     *
     * @param aff     提供零时长连接入口的谱面
     * @param strokes 尚未认领普通按下边沿的触控列表
     */
    private void mergeCompatibleHoldAndArcStrokes(Aff aff, List<TouchStroke> strokes) {
        List<TouchStroke> holds = new ArrayList<>(strokes.stream()
                .filter(stroke -> stroke.kind() == TouchStroke.Kind.HOLD)
                .toList());
        List<TouchStroke> arcs = strokes.stream()
                .filter(stroke -> stroke.kind() == TouchStroke.Kind.ARC)
                .sorted(Comparator.comparingDouble(TouchStroke::startTime))
                .toList();
        for (TouchStroke arc : arcs) {
            if (!strokes.contains(arc)) {
                continue;
            }
            ConnectionEntry entry = arcEntry(aff, arc);
            List<TouchStroke> sharedStarts = holds.stream()
                    .filter(hold -> !retainsArcColor(aff, hold, arc))
                    .filter(hold -> canShareHoldAndArcStart(hold, arc, entry))
                    .sorted(Comparator.comparingDouble(hold -> ArcColorStateMachine.worldDistanceSquared(
                            hold.initialPosition(), entry.position())))
                    .toList();
            boolean mergedAtStart = false;
            for (TouchStroke hold : sharedStarts) {
                TouchStroke merged = shareHoldAndArcStart(hold, arc);
                if (merged == null) {
                    continue;
                }
                strokes.remove(hold);
                strokes.remove(arc);
                strokes.add(merged);
                holds.remove(hold);
                mergedAtStart = true;
                break;
            }
            if (mergedAtStart) {
                continue;
            }

            List<TouchStroke> candidates = holds.stream()
                    .filter(hold -> !retainsArcColor(aff, hold, arc))
                    .filter(hold -> canMergeHoldAndArc(hold, entry))
                    .sorted(Comparator.comparingDouble(hold -> ArcColorStateMachine.worldDistanceSquared(
                            hold.initialPosition(), entry.position())))
                    .toList();
            for (TouchStroke hold : candidates) {
                double holdEnd = hold.hitOpportunities().getFirst().demand().point().source().getT2();
                TouchStroke merged = continueWithArc(hold, arc, holdEnd);
                if (merged == null) {
                    continue;
                }
                strokes.remove(hold);
                strokes.remove(arc);
                strokes.add(merged);
                holds.remove(hold);
                break;
            }
        }
    }

    /** Hold不能延长已结束Arc的染色占用并阻塞自身位置无法覆盖的同色后继。 */
    private static boolean retainsArcColor(Aff aff, TouchStroke hold, TouchStroke stroke) {
        double end = stroke.arcColorContacts().stream()
                .mapToInt(contact -> contact.source().getT2()).max().orElseThrow();
        if (hold.endTime() <= end) return false;
        Set<Integer> colors = stroke.arcColorContacts().stream().map(contact -> contact.source().getColor())
                .collect(java.util.stream.Collectors.toSet());
        for (Arc arc : aff.getArcList()) {
            if (!colors.contains(arc.getColor()) || stroke.sourceIds().contains(arc.getSourceId())
                    || arc.getT1() < end || arc.getT1() >= hold.endTime() || arc.getT2() <= arc.getT1()) continue;
            double until = Math.min(arc.getT2(), hold.endTime());
            double step = Math.min(10, trackingInterval(arc));
            for (double time = arc.getT1(); ; time = Math.min(until, time + step)) {
                if (!ArcJudgementRange.covers(pointAt(arc, time), hold.initialPosition())) return true;
                if (time == until) break;
            }
        }
        return false;
    }

    private static boolean canShareHoldAndArcStart(
            TouchStroke hold, TouchStroke arc, ConnectionEntry entry) {
        return Math.abs(hold.startTime() - entry.time()) <= CONTINUATION_GAP_MILLIS
                && hold.startTime() <= arc.endTime()
                && arc.startTime() <= hold.endTime()
                && ArcColorStateMachine.areClose(hold.initialPosition(), entry.position());
    }

    /**
     * 共享起点后由 Arc 路径主导移动和抬起；Hold 不会在自身尾部产生释放。
     */
    private static TouchStroke shareHoldAndArcStart(
            TouchStroke hold, TouchStroke arc) {
        if (hold.startTime() <= arc.startTime()) {
            return continueWithArc(hold, arc, hold.startTime());
        }
        return absorbCoveredArcStroke(arc, hold) ? arc : null;
    }

    /**
     * 筛选真实 Hold 尾与蛇头，不将最短按压造成的延长计入 100 ms 时间近域。
     *
     * @param hold  独立 Hold 触控
     * @param entry 后续 Arc 的真实物理入口
     * @return 时间差不超过 100 ms 且满足共用清色距离时返回 true
     */
    private static boolean canMergeHoldAndArc(TouchStroke hold, ConnectionEntry entry) {
        double holdEnd = hold.hitOpportunities().getFirst().demand().point().source().getT2();
        return Math.abs(holdEnd - entry.time()) <= CONTINUATION_GAP_MILLIS
                && ArcColorStateMachine.areClose(hold.initialPosition(), entry.position());
    }

    /**
     * 从来源 Arc 恢复物理蛇头，窗口回退时也不将首个命中位置误当作蛇头。
     *
     * @param aff    提供严格连接关系的谱面
     * @param stroke 尚未接入前置按键的 Arc 触控
     * @return 前导零时长入口，或最早正时长 Arc 自身的头
     */
    private static ConnectionEntry arcEntry(Aff aff, TouchStroke stroke) {
        List<Arc> arcs = stroke.hitOpportunities().stream()
                .map(opportunity -> opportunity.demand().point().source())
                .filter(Arc.class::isInstance)
                .map(Arc.class::cast)
                .distinct()
                .sorted(Comparator.comparingInt(Arc::getT1).thenComparingInt(Arc::getSourceId))
                .toList();
        ConnectionEntry entry = findConnectionEntry(aff, arcs);
        if (entry != null) {
            return entry;
        }
        Arc first = arcs.getFirst();
        return new ConnectionEntry(
                first.getT1(), new AffPoint(first.getX1(), first.getY1()), first.getSourceId());
    }

    /**
     * 保留前置按键的按下边沿，接入后续 Arc，并在实际新路径上重选原判定机会。
     *
     * @param preceding       前置 Hold、地键或 Arctap 的触控
     * @param arc             待接入的独立 Arc 触控
     * @param stationaryUntil 前置触点必须保持原位置到此时刻，Hold 取真实尾，单点取按下时刻
     * @return 全部原判定仍可满足的连续触控；不兼容时返回 null，保留原方案
     */
    private static TouchStroke continueWithArc(
            TouchStroke preceding, TouchStroke arc, double stationaryUntil) {
        double switchTime = Math.max(stationaryUntil, arc.startTime());
        int sourceId = preceding.sourceIds().stream().mapToInt(Integer::intValue).min().orElseThrow();
        TouchStroke path = new TouchStroke(
                TouchStroke.Kind.ARC, preceding.startTime(),
                Math.max(preceding.endTime(), arc.endTime()), preceding.initialPosition(), sourceId);
        for (TouchAnchor anchor : preceding.anchors()) {
            if (anchor.time() > path.startTime()
                    && (Math.round(anchor.time()) < Math.round(switchTime) || switchTime > arc.endTime())) {
                path.addAnchor(anchor);
            }
        }
        if (Math.round(switchTime) > Math.round(path.startTime()) && switchTime <= arc.endTime()) {
            // 交接之前保持 Hold 位置，到边界才接入 Arc，不从上一判定点提前移动。
            path.addAnchor(new TouchAnchor(
                    switchTime, arc.positionAt(switchTime), true, TouchAnchor.Transition.STEP));
        }
        for (TouchAnchor anchor : arc.anchors()) {
            if (Math.round(anchor.time()) > Math.round(switchTime)) {
                path.addAnchor(anchor);
            }
        }

        List<HitOpportunity> original = new ArrayList<>(preceding.hitOpportunities());
        original.addAll(arc.hitOpportunities());
        TreeMap<Double, TouchAnchor> markers = new TreeMap<>();
        for (TouchAnchor anchor : path.anchors()) {
            markers.put(anchor.time(), anchor);
        }
        for (HitOpportunity opportunity : original) {
            JudgeWindow window = opportunity.demand().window();
            addHandoffMarker(markers, path, window.startTime());
            addHandoffMarker(markers, path, opportunity.hitTime());
            addHandoffMarker(markers, path, window.endTime());
        }
        Map<Long, TouchAnchor> requiredByMillis = new LinkedHashMap<>();
        for (TouchAnchor anchor : markers.values()) {
            if (!anchor.required()) {
                continue;
            }
            TouchAnchor occupied = requiredByMillis.putIfAbsent(Math.round(anchor.time()), anchor);
            if (occupied != null && !occupied.position().samePosition(anchor.position())) {
                return null;
            }
        }
        TrackedPath tracked = new TrackedPath(List.copyOf(markers.values()));
        List<HitOpportunity> hits = new ArrayList<>(original.size());
        for (HitOpportunity opportunity : original) {
            HitOpportunity hit = firstTrackedHit(tracked, opportunity.demand(), requiredByMillis);
            if (hit == null) {
                return null;
            }
            hits.add(hit);
            TouchAnchor anchor = markers.get(hit.hitTime());
            requiredByMillis.put(Math.round(hit.hitTime()), anchor);
            markers.put(hit.hitTime(), new TouchAnchor(
                    anchor.time(), anchor.position(), true, anchor.transition()));
        }
        for (TouchStroke.ArcColorContact contact : arc.arcColorContacts()) {
            if (contact.source().getT1() == contact.source().getT2()
                    && (contact.time() < path.startTime() || contact.time() > path.endTime())) {
                return null;
            }
        }

        TouchStroke merged = new TouchStroke(
                TouchStroke.Kind.ARC, path.startTime(), path.endTime(), path.initialPosition(), sourceId);
        for (TouchAnchor anchor : markers.values()) {
            if (anchor.time() > merged.startTime()) {
                merged.addAnchor(anchor);
            }
        }
        for (HitOpportunity hit : hits) {
            merged.addCoveredOpportunity(hit);
        }
        for (TouchStroke.ArcColorContact contact : arc.arcColorContacts()) {
            if (contact.source().getT1() == contact.source().getT2()) {
                merged.addArcColorContact(contact.source(), contact.time());
            }
        }
        return colorsCanShareTouch(merged.arcColorContacts()) ? merged : null;
    }

    /**
     * 在交接路径中加入窗口检查时刻，位置仍由实际路径决定，不移动或延迟原窗口。
     *
     * @param markers 路径和窗口检查点，同一 record 毫秒内已有位置优先
     * @param path    交接后的连续路径
     * @param time    原窗口边界或原命中时刻
     */
    private static void addHandoffMarker(
            TreeMap<Double, TouchAnchor> markers, TouchStroke path, double time) {
        if (time < path.startTime() || time > path.endTime()) {
            return;
        }
        Double floor = markers.floorKey(time);
        Double ceiling = markers.ceilingKey(time);
        if (floor != null && (time - floor <= TIME_EPSILON || Math.round(time) == Math.round(floor))
                || ceiling != null && (ceiling - time <= TIME_EPSILON || Math.round(time) == Math.round(ceiling))) {
            return;
        }
        markers.put(time, new TouchAnchor(
                time, path.positionAt(time), false, TouchAnchor.Transition.LINEAR));
    }

    /**
     * 仍须独立按住的Hold在自身轨道内选择较少触及Arc的位置，避免在放行结束时抢色。
     * 已与Arc共享生命周期的Hold不经过此路径；原名义判定和按下/抬起时间均保留。
     */
    private static void avoidCompetingHoldPositions(Aff aff, List<TouchStroke> strokes) {
        for (int index = 0; index < strokes.size(); index++) {
            TouchStroke hold = strokes.get(index);
            List<Hold> sources = hold.hitOpportunities().stream()
                    .map(hit -> hit.demand().point().source()).filter(Hold.class::isInstance)
                    .map(Hold.class::cast).distinct().toList();
            if (sources.size() != 1) continue;
            Hold source = sources.getFirst();
            // 也处理“长Hold尾接后续Arc”的固定前缀；已由Arc主导移动的共同起点不移位。
            if (hold.startTime() != source.getT1()
                    || hold.anchors().stream().anyMatch(anchor -> anchor.time() < source.getT2()
                    && !anchor.position().samePosition(hold.initialPosition()))
                    || hold.hitOpportunities().stream().anyMatch(hit -> hit.demand().point().source() instanceof Arc
                    && hit.hitTime() < source.getT2())) continue;
            List<Arc> active = aff.getArcList().stream()
                    .filter(arc -> arc.getT1() <= source.getT2() && arc.getT2() >= source.getT1())
                    .toList();
            AffPoint center = hold.initialPosition();
            AffPoint best = center;
            double bestContact = holdArcContact(active, source, center);
            // 半轨宽0.25内保留0.01余量，避免屏幕整数反解越过轨道边界。
            for (double dx : new double[]{-0.24, 0.24, -0.12, 0.12}) {
                AffPoint point = new AffPoint(center.x() + dx, center.y());
                double contact = holdArcContact(active, source, point);
                if (contact < bestContact) {
                    bestContact = contact;
                    best = point;
                }
            }
            if (best.samePosition(center)) continue;
            TouchStroke moved = new TouchStroke(hold.kind(),
                    hold.startTime(), hold.endTime(), best, source.getSourceId());
            for (TouchAnchor anchor : hold.anchors()) {
                if (anchor.time() <= moved.startTime()) continue;
                moved.addAnchor(new TouchAnchor(anchor.time(),
                        anchor.time() < source.getT2() ? best : anchor.position(),
                        anchor.required(), anchor.transition()));
            }
            for (HitOpportunity hit : hold.hitOpportunities()) {
                moved.addCoveredOpportunity(new HitOpportunity(hit.demand(), hit.hitTime(),
                        hit.demand().point().source() instanceof Hold ? best : hit.position()));
            }
            for (TouchStroke.ArcColorContact contact : hold.arcColorContacts())
                moved.addArcColorContact(contact.source(), contact.time());
            strokes.set(index, moved);
        }
    }

    /** 估计固定Hold触点落入活动Arc判定区的总时长，仅用于同一轨道候选择优。 */
    private static double holdArcContact(List<Arc> arcs, Hold hold, AffPoint position) {
        double total = 0;
        for (Arc arc : arcs) {
            double from = Math.max(arc.getT1(), hold.getT1());
            double until = Math.min(arc.getT2(), hold.getT2());
            double step = Math.min(10, trackingInterval(arc));
            for (double time = from; time <= until; time += step) {
                if (ArcJudgementRange.covers(pointAt(arc, time), position)) total += step;
            }
        }
        return total;
    }

    /** 独立短按可在原最短时长之后提前抬起，避开晚偏移下下一条Arc的染色入口。 */
    private static void trimTrailingPresses(Aff aff, List<TouchStroke> strokes) {
        for (int index = 0; index < strokes.size(); index++) {
            TouchStroke press = strokes.get(index);
            if (press.kind() != TouchStroke.Kind.CLICK) continue;
            double end = press.endTime();
            for (Arc arc : aff.getArcList()) {
                if (arc.getT1() > end + 10 || arc.getT2() < press.startTime() - 10) continue;
                double from = Math.max(arc.getT1(), press.startTime() - 10);
                double until = Math.min(arc.getT2(), end + 10);
                for (double time = from; time <= until; time++) {
                    AffPoint point = pointAt(arc, time);
                    if (!ArcJudgementRange.covers(point, InputJudgementRange.skyPosition(
                            press.initialPosition(), point.y(), aff.getRatio46k(time)))) continue;
                    // 严格在接触之前抬起，取该时间边界前的最大整数毫秒。
                    double safeEnd = Math.floor(Math.nextDown(time - 10));
                    if (safeEnd >= press.startTime() + TouchStroke.MIN_PRESS_DURATION_MILLIS)
                        end = Math.min(end, safeEnd);
                    break;
                }
            }
            if (end < press.endTime()) strokes.set(index, press.trimmedTo(end));
        }
    }

    /** 同押按下避免消费其他物件；共享蛇头时还须落在可接续近域及其判定矩形内。 */
    private static AffPoint pressPosition(
            Aff aff, PressDemand demand, List<PressDemand> demands, AffPoint entry) {
        AffPoint center = demand.position();
        List<AffPoint> positions = new ArrayList<>(List.of(center));
        if (entry != null) {
            for (double dx : new double[]{0, -0.12, 0.12}) {
                for (double dy : new double[]{0, -0.35, 0.35})
                    positions.add(new AffPoint(entry.x() + dx, entry.y() + dy));
            }
        }
        if (demand.source() instanceof ArcTap) {
            for (double dx : new double[]{0, -0.25, 0.25}) {
                for (double dy : new double[]{0, -0.5, 0.5}) {
                    AffPoint point = new AffPoint(center.x() + dx, center.y() + dy);
                    if (point.x() >= -0.25 && point.x() <= 1.25 && point.y() >= -0.2 && point.y() <= 1.61)
                        positions.add(point);
                }
            }
        } else {
            for (double dx : new double[]{-0.24, 0.24, -0.12, 0.12})
                positions.add(new AffPoint(center.x() + dx, center.y()));
        }
        List<Arc> arcs = aff.getArcList().stream()
                .filter(arc -> arc.getT1() <= demand.source().getT2() + 10
                        && arc.getT2() >= demand.time() - 10).toList();
        double ratio = aff.getRatio46k(demand.time());
        AffPoint best = center;
        int bestCount = Integer.MAX_VALUE;
        int bestArcCount = Integer.MAX_VALUE;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (AffPoint point : positions) {
            if (!InputJudgementRange.coversPress(demand.source(), point, ratio)
                    || entry != null && (!ArcColorStateMachine.areClose(point, entry)
                    || !ArcJudgementRange.covers(entry, point))) continue;
            int count = 0;
            for (PressDemand other : demands) {
                if (other != demand && other.time() == demand.time()
                        && InputJudgementRange.coversPress(other.source(), point, ratio)) count++;
            }
            int arcCount = arcContactCount(aff, arcs, demand.time() - 10,
                    demand.source().getT2() + 10, point, false);
            double distance = center.distanceSquared(point);
            if (count < bestCount || count == bestCount
                    && (arcCount < bestArcCount || arcCount == bestArcCount && distance < bestDistance)) {
                best = point;
                bestCount = count;
                bestArcCount = arcCount;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** 接续选点保留AFF反解余量，不把轨道/天空判定的精确边界当作稳健按下位置。 */
    private static boolean pressEntryIsStable(Note source, AffPoint point, double ratio) {
        return InputJudgementRange.coversPress(source, new AffPoint(point.x() - 0.001, point.y() - 0.001), ratio)
                && InputJudgementRange.coversPress(source, new AffPoint(point.x() + 0.001, point.y() + 0.001), ratio);
    }

    /**
     * 为 Hold 保留其头部按下，再由邻近独立蛇头一对一认领地键或 Arctap。
     *
     * @param aff     提供真实蛇头入口的谱面
     * @param demands 仍须保留按下边沿的判定需求
     * @param strokes 已完成 Hold 接续的触控列表
     */
    private void addOrdinaryPresses(Aff aff, List<PressDemand> demands, List<TouchStroke> strokes) {
        List<TouchStroke> longStrokes = strokes.stream()
                .filter(stroke -> stroke.kind() != TouchStroke.Kind.CLICK)
                .sorted(Comparator.comparingDouble(TouchStroke::startTime))
                .toList();
        Set<Integer> absorbedSourceIds = new LinkedHashSet<>();
        for (PressDemand demand : demands) {
            if (!(demand.source() instanceof Hold)) {
                continue;
            }
            for (TouchStroke stroke : longStrokes) {
                if (stroke.sourceIds().contains(demand.source().getSourceId())
                        && stroke.absorbPress(demand.source().getSourceId())) {
                    absorbedSourceIds.add(demand.source().getSourceId());
                    break;
                }
            }
        }

        for (TouchStroke arc : longStrokes) {
            if (arc.kind() != TouchStroke.Kind.ARC || arc.hasAbsorbedPress()) {
                continue;
            }
            ConnectionEntry entry = arcEntry(aff, arc);
            List<PressDemand> candidates = demands.stream()
                    .filter(demand -> demand.source() instanceof Click || demand.source() instanceof ArcTap)
                    .filter(demand -> !absorbedSourceIds.contains(demand.source().getSourceId()))
                    .filter(demand -> Math.abs(demand.time() - entry.time()) <= CONTINUATION_GAP_MILLIS)
                    .filter(demand -> ArcColorStateMachine.areClose(demand.position(), entry.position())
                            || pressEntryIsStable(demand.source(), entry.position(),
                            aff.getRatio46k(demand.time())))
                    .sorted(Comparator.comparingDouble(demand -> ArcColorStateMachine.worldDistanceSquared(
                            demand.position(), entry.position())))
                    .toList();
            for (PressDemand demand : candidates) {
                AffPoint position = pressPosition(aff, demand, demands, entry.position());
                if (!ArcColorStateMachine.areClose(position, entry.position())) continue;
                TouchStroke press = new TouchStroke(
                        TouchStroke.Kind.CLICK, demand.time(), demand.source().getT2(),
                        position, demand.source().getSourceId());
                TouchStroke merged = continueWithArc(press, arc, demand.time());
                if (merged == null) {
                    continue;
                }
                merged.absorbPress(demand.source().getSourceId());
                absorbedSourceIds.add(demand.source().getSourceId());
                strokes.remove(arc);
                strokes.add(merged);
                break;
            }
        }
        for (PressDemand demand : demands) {
            if (!absorbedSourceIds.contains(demand.source().getSourceId())) {
                strokes.add(new TouchStroke(
                        TouchStroke.Kind.CLICK,
                        demand.time(), demand.source().getT2(),
                        pressPosition(aff, demand, demands, null), demand.source().getSourceId()));
            }
        }
    }

    private record ArcGroupPlan(Aff aff, List<Arc> arcs,
                                List<Arc> zeroDurationArcs,
                                List<CoverageDemand> windows,
                                ConnectionEntry connectionEntry,
                                double startTime, double endTime) {
    }

    private record ConnectionEntry(double time, AffPoint position, int sourceId) {
    }

    private record TrackedPath(List<TouchAnchor> anchors) {
    }

    private record PathMarker(boolean required, AffPoint fixedPosition) {
    }

    private record DemandPair(LongNoteDemand first, LongNoteDemand second) {
    }

    private record StationaryHit(CoverageDemand demand, double hitTime) {
    }

    /** 候选内一次局部清色及其持续放行截止时刻。 */
    private record ColorClearEvidence(double startTime, double graceEnd) {
    }

    private record PlanCost(int edgeCount, int moveCount, double worldTravel)
            implements Comparable<PlanCost> {
        @Override
        public int compareTo(PlanCost other) {
            int edges = Integer.compare(edgeCount, other.edgeCount);
            if (edges != 0) {
                return edges;
            }
            int moves = Integer.compare(moveCount, other.moveCount);
            return moves != 0 ? moves : Double.compare(worldTravel, other.worldTravel);
        }
    }

    private record Candidate(AffPoint position, double hitTime,
                             int coveredWindows, double distanceSquared) {
    }
}
