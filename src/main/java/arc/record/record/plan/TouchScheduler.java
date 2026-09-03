package arc.record.record.plan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

import arc.record.aff.Aff;
import arc.record.aff.judge.AffPoint;
import arc.record.aff.judge.ChartJudgementModel;
import arc.record.aff.judge.CoverageDemand;
import arc.record.aff.judge.HitOpportunity;
import arc.record.aff.judge.JudgeWindow;
import arc.record.aff.judge.LongNoteDemand;
import arc.record.aff.judge.PressDemand;
import arc.record.aff.note.Arc;
import arc.record.aff.note.Hold;

/**
 * 将全部普通点击和长键窗口分配为连续触控段落。
 */
public final class TouchScheduler {
    private static final int LOOKAHEAD_WINDOWS = 8;
    private static final int CANDIDATE_SAMPLES_PER_WINDOW = 12;
    private static final double TIME_EPSILON = 1e-7;

    /**
     * 生成与输出分辨率无关的连续触控方案。
     *
     * @param aff   完整谱面及 Arc 拓扑
     * @param model 当前 miss/小 Pure 变体的判定需求
     * @return 按开始时间排序的连续触控段落
     */
    public List<TouchStroke> schedule(Aff aff, ChartJudgementModel model) {
        List<TouchStroke> strokes = new ArrayList<>();
        List<LongNoteDemand> arcDemands = new ArrayList<>();
        for (LongNoteDemand demand : model.longNoteDemands()) {
            if (demand.source() instanceof Hold hold) {
                strokes.add(planHold(hold, demand));
            } else {
                arcDemands.add(demand);
            }
        }
        for (List<LongNoteDemand> demands : groupArcDemands(aff, arcDemands)) {
            strokes.add(planArcGroup(aff, demands));
        }
        mergeCompatibleHoldAndArcStrokes(strokes);
        mergeIdenticalArcStrokes(strokes);
        Set<Integer> inputArcIds = arcDemands.stream()
                .map(demand -> demand.source().getSourceId())
                .collect(java.util.stream.Collectors.toSet());
        new ArcColorStateMachine(aff, inputArcIds).validate(strokes);
        addOrdinaryPresses(model.pressDemands(), strokes);
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
        result.sort(Comparator
                .comparingInt((List<LongNoteDemand> demands) -> demands.getFirst().source().getT1())
                .thenComparingInt(demands -> demands.getFirst().source().getSourceId()));
        return result;
    }

    private static int physicalEnd(Aff aff, LongNoteDemand demand) {
        int componentEnd = aff.getArcTopology().component(demand.componentId()).stream()
                .mapToInt(Arc::getT2)
                .max()
                .orElse(demand.source().getT2());
        return Math.max(
                componentEnd,
                demand.source().getT1() + TouchStroke.MIN_PRESS_DURATION_MILLIS);
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

    private TouchStroke planArcGroup(Aff aff, List<LongNoteDemand> demands) {
        List<Arc> arcs = demands.stream()
                .map(LongNoteDemand::source)
                .map(Arc.class::cast)
                .sorted(Comparator
                        .comparingInt(Arc::getT1)
                        .thenComparingInt(Arc::getT2)
                        .thenComparingInt(Arc::getSourceId))
                .toList();
        Arc firstArc = arcs.getFirst();
        double startTime = arcs.stream().mapToInt(Arc::getT1).min().orElseThrow();
        double endTime = demands.stream()
                .flatMap(demand -> aff.getArcTopology().component(demand.componentId()).stream())
                .mapToInt(Arc::getT2)
                .max()
                .orElseThrow();
        double[] initialXy = firstArc.getAffPoint(firstArc.getT1());
        AffPoint currentPosition = new AffPoint(initialXy[0], initialXy[1]);
        TouchStroke stroke = new TouchStroke(
                TouchStroke.Kind.ARC, startTime, endTime, currentPosition, firstArc.getSourceId());

        List<CoverageDemand> windows = new ArrayList<>(demands.stream()
                .flatMap(demand -> demand.demands().stream())
                .sorted(Comparator
                        .comparingDouble((CoverageDemand demand) -> demand.window().endTime())
                        .thenComparingDouble(demand -> demand.window().startTime())
                        .thenComparingInt(demand -> demand.point().id()))
                .toList());
        double lastHit = startTime;
        while (!windows.isEmpty()) {
            StationaryHit stationary = selectStationaryHit(windows, currentPosition, lastHit);
            CoverageDemand demand = stationary == null ? windows.getFirst() : stationary.demand();
            AffPoint hitPosition = currentPosition;
            double hitTime;
            if (stationary != null) {
                hitTime = stationary.hitTime();
            } else {
                Candidate candidate = chooseCandidate(windows, 0, currentPosition, lastHit);
                if (candidate == null) {
                    throw unsatisfiedWindow(aff, demand);
                }
                hitPosition = candidate.position();
                hitTime = candidate.hitTime();
                stroke.addAnchor(lastHit, currentPosition);
                stroke.addAnchor(hitTime, hitPosition);
                currentPosition = hitPosition;
            }
            lastHit = Math.max(lastHit, hitTime);
            stroke.addHitOpportunity(new HitOpportunity(demand, hitTime, hitPosition));
            windows.remove(demand);
        }
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

        Candidate best = null;
        JudgeWindow currentWindow = windows.get(index).window();
        for (AffPoint position : positions) {
            double earliest = position.samePosition(currentPosition)
                    ? notBefore
                    : nextRoundedMillis(notBefore);
            OptionalDouble firstHit = position.samePosition(currentPosition)
                    ? firstRepresentableHitAt(currentWindow, position, notBefore)
                    : currentWindow.firstTimeAt(position, earliest);
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
        double movedHitTime = Math.max(window.startTime(), nextRoundedMillis(notBefore));
        return movedHitTime <= window.endTime() + TIME_EPSILON;
    }

    private static OptionalDouble firstRepresentableHitAt(
            JudgeWindow window, AffPoint position, double notBefore) {
        OptionalDouble firstHit = window.firstTimeAt(position, notBefore);
        if (firstHit.isEmpty()) {
            return firstHit;
        }
        double hitTime = firstHit.getAsDouble();
        if (hitTime <= notBefore + TIME_EPSILON
                || Math.round(hitTime) != Math.round(notBefore)) {
            return firstHit;
        }
        OptionalDouble separatedHit = window.firstTimeAt(
                position, nextRoundedMillis(notBefore));
        return separatedHit.isPresent() ? separatedHit : firstHit;
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

    private static IllegalStateException unsatisfiedWindow(Aff aff, CoverageDemand demand) {
        return new IllegalStateException(
                "无法满足长键判定窗口：" + aff.getAffFile().getAbsolutePath()
                        + "，sourceId=" + demand.point().source().getSourceId()
                        + "，nominal=" + demand.point().nominalTime()
                        + "，deadline=" + demand.window().endTime());
    }

    private void mergeCompatibleHoldAndArcStrokes(List<TouchStroke> strokes) {
        List<TouchStroke> holds = strokes.stream()
                .filter(stroke -> stroke.kind() == TouchStroke.Kind.HOLD)
                .toList();
        for (TouchStroke hold : holds) {
            if (!strokes.contains(hold)) {
                continue;
            }
            for (TouchStroke arc : List.copyOf(strokes)) {
                if (arc.kind() != TouchStroke.Kind.ARC || !canMergeHoldAndArc(hold, arc)) {
                    continue;
                }
                TouchStroke merged = mergeHoldAndArc(hold, arc);
                strokes.remove(hold);
                strokes.remove(arc);
                strokes.add(merged);
                break;
            }
        }
    }

    private static boolean canMergeHoldAndArc(TouchStroke hold, TouchStroke arc) {
        if (hold.startTime() > arc.startTime()
                || hold.endTime() < arc.startTime()
                || !hold.initialPosition().samePosition(arc.initialPosition())) {
            return false;
        }
        AffPoint holdPosition = hold.initialPosition();
        for (TouchAnchor anchor : arc.anchors()) {
            if (anchor.time() <= hold.endTime() + 1e-7
                    && !anchor.position().samePosition(holdPosition)) {
                return false;
            }
        }
        return arc.endTime() >= hold.endTime()
                || arc.finalPosition().samePosition(holdPosition);
    }

    private static TouchStroke mergeHoldAndArc(TouchStroke hold, TouchStroke arc) {
        List<TouchAnchor> anchors = new ArrayList<>();
        anchors.addAll(hold.anchors());
        anchors.addAll(arc.anchors());
        anchors.sort(Comparator.comparingDouble(TouchAnchor::time));
        TouchStroke merged = new TouchStroke(
                TouchStroke.Kind.ARC,
                hold.startTime(), Math.max(hold.endTime(), arc.endTime()),
                anchors.getFirst().position(),
                Math.min(hold.sourceIds().stream().mapToInt(Integer::intValue).min().orElseThrow(),
                        arc.sourceIds().stream().mapToInt(Integer::intValue).min().orElseThrow()));
        for (int i = 1; i < anchors.size(); i++) {
            merged.addAnchor(anchors.get(i).time(), anchors.get(i).position());
        }
        merged.mergeLogicalDemands(hold);
        merged.mergeLogicalDemands(arc);
        return merged;
    }

    private void mergeIdenticalArcStrokes(List<TouchStroke> strokes) {
        for (int i = 0; i < strokes.size(); i++) {
            TouchStroke first = strokes.get(i);
            if (first.kind() != TouchStroke.Kind.ARC) {
                continue;
            }
            for (int j = strokes.size() - 1; j > i; j--) {
                TouchStroke second = strokes.get(j);
                if (first.hasSameTrajectory(second)) {
                    first.mergeLogicalDemands(second);
                    strokes.remove(j);
                }
            }
        }
    }

    private void addOrdinaryPresses(List<PressDemand> demands, List<TouchStroke> strokes) {
        List<TouchStroke> longStrokes = strokes.stream()
                .filter(stroke -> stroke.kind() != TouchStroke.Kind.CLICK)
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

        for (PressDemand demand : demands) {
            if (absorbedSourceIds.contains(demand.source().getSourceId())) {
                continue;
            }
            boolean absorbed = false;
            for (TouchStroke stroke : longStrokes) {
                boolean compatibleArcPress = stroke.kind() == TouchStroke.Kind.ARC
                        && Double.compare(stroke.startTime(), demand.time()) == 0
                        && stroke.initialPosition().samePosition(demand.position());
                if (compatibleArcPress && stroke.absorbPress(demand.source().getSourceId())) {
                    absorbed = true;
                    break;
                }
            }
            if (!absorbed) {
                strokes.add(new TouchStroke(
                        TouchStroke.Kind.CLICK,
                        demand.time(), demand.source().getT2(),
                        demand.position(), demand.source().getSourceId()));
            }
        }
    }

    private record StationaryHit(CoverageDemand demand, double hitTime) {
    }

    private record Candidate(AffPoint position, double hitTime,
                             int coveredWindows, double distanceSquared) {
    }
}
