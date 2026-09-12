package arc.record.record.verify;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;

import arc.record.aff.Aff;
import arc.record.aff.judge.AffPoint;
import arc.record.aff.judge.ArcJudgementRange;
import arc.record.aff.judge.ChartJudgementModel;
import arc.record.aff.judge.CoverageDemand;
import arc.record.aff.judge.InputJudgementRange;
import arc.record.aff.judge.JudgePoint;
import arc.record.aff.judge.LongNoteDemand;
import arc.record.aff.judge.PressDemand;
import arc.record.aff.note.Arc;
import arc.record.aff.note.ArcTap;
import arc.record.aff.note.Hold;
import arc.record.aff.note.Note;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;

import static arc.record.record.verify.ReplayReport.Status.FAIL;
import static arc.record.record.verify.ReplayReport.Status.INCONCLUSIVE;
import static arc.record.record.verify.ReplayReport.Status.PASS;

/**
 * 从最终 record 触控事件正向计算理论值判定，不读取调度器的触点归属或命中机会。
 *
 * <p>时间按整数毫秒及规则边界推进。同刻输入顺序和合法染色归属均分支，只有
 * 全部分支通过才能放行；等价状态合并不依赖触点未来会不会提前抬起。</p>
 */
public final class ForwardReplay {
    private static final List<Integer> OFFSETS = List.of(0, -10, 10);
    private static final int MAX_STATES = 4096;
    private static final int MAX_FRAMES = 2_000_000;
    private static final long MAX_CASE_NANOS = 15_000_000_000L;
    private static final double MAX_PURE_WINDOW = 25.0;
    private static final double COLOR_GRACE_MILLIS = 500.0;
    private static final double COLOR_COOLDOWN_MILLIS = 1000.0;
    private static final String MODEL = "理论值正向模型 v2；1 ms 加事件/判定/到期边界；"
            + "整体操作偏移 0/-10/+10 ms；全部合法同刻输入和染色分支；"
            + "每场景最多 4096 状态、2000000 时刻、15 s；"
            + "使用已校准投影、标准轨道和 Arc/Arctap 世界判定范围；所有 Arc 点按当前曲线位置命中；"
            + "不代表官方引擎、未知采样相位或任意逐操作卡顿的实机验证";

    /**
     * 对最终 JSON 独立执行三组回放。
     *
     * @param chart 未经过故意 Miss/小 Pure 修改的原始谱面
     * @param recordJson 已完成整数坐标和镜像的最终脚本
     * @param context 输出投影及脚本时钟原点
     * @return 三组结果与第一条失败分支证据，未知情况不当作通过
     */
    public ReplayReport verify(Aff chart, String recordJson, ReplayContext context) {
        List<ReplayReport.CaseResult> results = new ArrayList<>();
        if (!context.resolution().hasCalibratedProjection()) {
            return unavailable(chart, INCONCLUSIVE, "当前屏幕比例的投影尚未校准");
        }
        List<Batch> batches;
        try {
            batches = readOperations(recordJson, context);
        } catch (JSONException | ScriptFormatException e) {
            return unavailable(chart, FAIL, "最终 record 事件无效：" + e.getMessage());
        }
        Goals goals = buildGoals(chart);
        for (int offset : OFFSETS) {
            results.add(new Session(chart, context, goals, batches, offset).run());
        }
        ReplayReport.Status status = results.stream().anyMatch(result -> result.status() == FAIL)
                ? FAIL : results.stream().anyMatch(result -> result.status() == INCONCLUSIVE)
                ? INCONCLUSIVE : PASS;
        return new ReplayReport(chart.getAffFile().getAbsolutePath(), status, MODEL, results);
    }

    /** 为无法进入正常回放的输入保留三组明确结论。 */
    private ReplayReport unavailable(Aff chart, ReplayReport.Status status, String reason) {
        List<ReplayReport.CaseResult> cases = OFFSETS.stream()
                .map(offset -> new ReplayReport.CaseResult(offset, status,
                        new ReplayReport.Stats(0, 0, 0, 0, 0),
                        new ReplayReport.Failure(0, reason, List.of(), List.of(), List.of())))
                .toList();
        return new ReplayReport(chart.getAffFile().getAbsolutePath(), status, MODEL, cases);
    }

    /** 解析真实批次，不重新排序、补抬起或修正相互矛盾的 ID。 */
    private List<Batch> readOperations(String json, ReplayContext context) {
        JSONObject root = JSON.parseObject(json);
        if (root == null || !(root.get("operations") instanceof JSONArray operations)
                || !(root.get("recordInfo") instanceof JSONObject info)) {
            throw new ScriptFormatException("缺少 operations 或 recordInfo");
        }
        if (integer(info, "loopType") != 0 || integer(info, "loopTimes") != 1
                || integer(info, "accelerateTimes") != 1) {
            throw new ScriptFormatException("仅支持单次、原速播放的理论值脚本");
        }
        List<Batch> result = new ArrayList<>();
        int previous = -1;
        List<InputEvent> events = new ArrayList<>();
        Set<Integer> ids = new HashSet<>();
        for (Object item : operations) {
            if (!(item instanceof JSONObject operation)
                    || !"PutMultiTouch".equals(operation.getString("operationId"))
                    || !(operation.get("points") instanceof JSONArray points)) {
                throw new ScriptFormatException("不支持的操作类型或 points 结构");
            }
            int timing = integer(operation, "timing");
            if (timing < previous || timing < 0) {
                throw new ScriptFormatException("操作时间倒序或为负：" + timing);
            }
            if (timing != previous) {
                if (previous >= 0) {
                    result.add(new Batch(previous, List.copyOf(events)));
                }
                events.clear();
                ids.clear();
            }
            for (Object pointItem : points) {
                if (!(pointItem instanceof JSONObject point)) {
                    throw new ScriptFormatException("触点不是对象");
                }
                int id = integer(point, "id");
                int x = integer(point, "x");
                int y = integer(point, "y");
                int state = integer(point, "state");
                if (id < 0 || !ids.add(id) || (state != 0 && state != 1)) {
                    throw new ScriptFormatException("同刻 ID 重复或状态非法：timing="
                            + timing + "，id=" + id);
                }
                if (x < 0 || x > context.resolution().getMaxX()
                        || y < 0 || y > context.resolution().getMaxY()) {
                    throw new ScriptFormatException("触点屏幕坐标越界：timing=" + timing
                            + "，id=" + id + "，x=" + x + "，y=" + y);
                }
                events.add(new InputEvent(id, x, y, state == 1));
            }
            previous = timing;
        }
        if (previous >= 0) {
            result.add(new Batch(previous, List.copyOf(events)));
            if (integer(info, "circleDuration") < previous) {
                throw new ScriptFormatException("脚本播放时长小于最后一次操作时间");
            }
        }
        return List.copyOf(result);
    }

    /** JSON 数值必须真正可表示为 int，禁止把小数或字符串静默截断。 */
    private int integer(JSONObject object, String key) {
        Object value = object.get(key);
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() != number.intValue()) {
            throw new ScriptFormatException("字段必须为整数：" + key);
        }
        return number.intValue();
    }

    /** 从原谱生成名义规则需求；不调用任何触控求解器。 */
    private Goals buildGoals(Aff chart) {
        ChartJudgementModel model = ChartJudgementModel.build(chart, chart.getNoteList());
        List<Goal> all = new ArrayList<>();
        Map<Integer, Integer> holdHeads = new HashMap<>();
        for (PressDemand press : model.pressDemands()) {
            Note note = press.source();
            boolean hold = note instanceof Hold;
            double until = hold ? Math.min(note.getT2(), press.time() + 240.0)
                    : press.time() + (note.getNoteCount() == 0 ? 100.0 : MAX_PURE_WINDOW);
            Goal goal = new Goal(all.size(), note, hold ? Kind.HOLD_HEAD : Kind.TAP,
                    new Span(press.time(), press.time() - 120.0, until), -1);
            all.add(goal);
            if (hold) {
                holdHeads.put(note.getSourceId(), goal.id());
            }
        }
        for (LongNoteDemand longNote : model.longNoteDemands()) {
            for (CoverageDemand demand : longNote.demands()) {
                Kind kind = demand.point().kind() == JudgePoint.Kind.ARC_HEAD
                        ? Kind.ARC_HEAD : Kind.CONTINUOUS;
                all.add(new Goal(all.size(), longNote.source(), kind,
                        new Span(demand.point().nominalTime(), demand.window().startTime(),
                                demand.window().endTime()),
                        holdHeads.getOrDefault(longNote.source().getSourceId(), -1)));
            }
        }
        List<Goal> byStart = all.stream()
                .sorted(Comparator.comparingDouble(goal -> goal.span().from())).toList();
        return new Goals(List.copyOf(all), byStart);
    }

    /** 单个偏移场景的独立时钟、物理输入和状态集合。 */
    private static final class Session {
        private final Aff chart;
        private final ReplayContext context;
        private final Goals goals;
        private final List<Batch> batches;
        private final int offset;
        private final Map<Integer, Touch> touches = new LinkedHashMap<>();
        private final Map<Integer, AffPoint> positions = new HashMap<>();
        private final List<Arc> arcs;
        private final List<Arc> activeArcs = new ArrayList<>();
        private final List<Goal> activeGoals = new ArrayList<>();
        private final List<Double> boundaries;
        private final List<GroupEnd> groupEnds;
        private List<State> states = new ArrayList<>(List.of(new State()));
        private int batchIndex;
        private int goalIndex;
        private int arcIndex;
        private int boundaryIndex;
        private int groupIndex;
        private int frames;
        private int peakStates = 1;
        private double time;
        private double graceUntil = Double.NEGATIVE_INFINITY;
        private long started;
        private double ratio;

        /** 建立场景静态索引，所有状态都由空触点开始。 */
        private Session(Aff chart, ReplayContext context, Goals goals, List<Batch> batches, int offset) {
            this.chart = chart;
            this.context = context;
            this.goals = goals;
            this.batches = batches;
            this.offset = offset;
            this.arcs = chart.getArcList().stream()
                    .sorted(Comparator.comparingInt(Arc::getT1)).toList();
            TreeSet<Double> times = new TreeSet<>();
            for (Goal goal : goals.all()) {
                times.add(goal.span().from());
                times.add(goal.span().until());
                times.add(goal.span().nominal());
            }
            Map<Integer, GroupEnd> ends = new HashMap<>();
            for (Arc arc : arcs) {
                times.add((double) arc.getT1());
                times.add((double) arc.getT2());
                times.add(Math.nextUp((double) arc.getT2()));
                int component = chart.getArcTopology().componentIdOf(arc.getSourceId());
                GroupEnd old = ends.get(component);
                ends.put(component, new GroupEnd(
                        Math.max(arc.getT2(), old == null ? arc.getT2() : old.time()),
                        (old == null ? 0 : old.colors()) | (1 << arc.getColor())));
            }
            groupEnds = ends.values().stream().sorted(Comparator.comparingDouble(GroupEnd::time)).toList();
            boundaries = List.copyOf(times);
        }

        /** 遍历到所有输入及判定结束；成功不能提前跳过尚未抬起的触点。 */
        private ReplayReport.CaseResult run() {
            started = System.nanoTime();
            double firstInput = batches.isEmpty() ? 0 : chartTime(batches.getFirst());
            time = Math.min(firstInput, boundaries.isEmpty() ? 0 : boundaries.getFirst());
            double end = Math.max(batches.isEmpty() ? 0 : chartTime(batches.getLast()),
                    boundaries.isEmpty() ? 0 : boundaries.getLast());
            try {
                while (time <= end) {
                    checkBudget(states.size());
                    frames++;
                    ratio = chart.getRatio46k(time);
                    advanceChart();
                    List<Integer> newTouches = applyInput();
                    updatePositions();
                    updateColors();
                    judgePresses(newTouches);
                    assignColors();
                    judgeContinuous();
                    rejectExpired();
                    activeGoals.removeIf(goal -> states.stream().allMatch(state -> state.done.get(goal.id())));
                    if (time == end) {
                        break;
                    }
                    time = nextTime(end);
                }
                if (!touches.isEmpty()) {
                    throw stop(FAIL, "脚本结束后仍有触点未抬起", -1, states.getFirst());
                }
                for (State state : states) {
                    if (state.done.cardinality() != goals.all().size()) {
                        Goal goal = goals.all().get(state.done.nextClearBit(0));
                        throw unmet(goal, state, "脚本结束后需求未完成");
                    }
                }
                return result(PASS, states.getFirst(), null);
            } catch (Stop e) {
                State state = e.state == null ? states.getFirst() : e.state;
                ReplayReport.Failure failure = new ReplayReport.Failure(time, e.getMessage(),
                        e.sourceId < 0 ? List.of() : List.of(e.sourceId), snapshots(state), history(state));
                return result(e.status, state, failure);
            }
        }

        /** 原谱时间保持不变，仅平移操作时钟。 */
        private double chartTime(Batch batch) {
            return (double) batch.timing() - context.timeOriginMillis() + offset;
        }

        /** 加入已进入判定/物理存在区间的物件，并在组结束时解除对应颜色冷却。 */
        private void advanceChart() {
            while (goalIndex < goals.byStart().size()
                    && goals.byStart().get(goalIndex).span().from() <= time) {
                activeGoals.add(goals.byStart().get(goalIndex++));
            }
            activeArcs.removeIf(arc -> time > arc.getT2());
            while (arcIndex < arcs.size() && arcs.get(arcIndex).getT1() <= time) {
                Arc arc = arcs.get(arcIndex++);
                if (time <= arc.getT2()) {
                    activeArcs.add(arc);
                }
            }
            while (groupIndex < groupEnds.size() && groupEnds.get(groupIndex).time() < time) {
                GroupEnd end = groupEnds.get(groupIndex++);
                for (State state : states) {
                    for (int color = 0; color < state.owners.length; color++) {
                        if ((end.colors() & (1 << color)) == 0) {
                            continue;
                        }
                        if (state.cooldown[color] > end.time()) {
                            state.cooldown[color] = Double.NEGATIVE_INFINITY;
                        } else if (state.owners[color] >= 0) {
                            state.releaseProtected[color] = true;
                        }
                    }
                }
            }
        }

        /** state=1 对已有 ID 只移动，只有新 ID 才产生可以判定普通点击的边沿。 */
        private List<Integer> applyInput() {
            List<Integer> newTouches = new ArrayList<>();
            if (batchIndex >= batches.size() || chartTime(batches.get(batchIndex)) != time) {
                return newTouches;
            }
            for (InputEvent event : batches.get(batchIndex++).events()) {
                Touch old = touches.get(event.id());
                if (!event.down() && old == null) {
                    throw stop(FAIL, "抬起了未激活的触点：id=" + event.id(), -1, states.getFirst());
                }
                Touch touch = new Touch(event.id(), event.x(), event.y(), old == null ? time : old.began());
                AffPoint position = position(touch);
                for (State state : states) {
                    int color = state.colorOf(event.id());
                    ReplayReport.TouchSnapshot snapshot = snapshot(touch.id(), position, color);
                    state.record(new ReplayReport.TraceEvent(time,
                            !event.down() ? "UP" : old == null ? "DOWN" : "MOVE", snapshot, -1));
                    if (!event.down() && color >= 0) {
                        state.owners[color] = -1;
                        state.lastRelease[color] = new ReplayReport.TraceEvent(time,
                                "释放染色触点", snapshot, -1);
                        if (!state.releaseProtected[color]) {
                            state.cooldown[color] = time + COLOR_COOLDOWN_MILLIS;
                        }
                        state.releaseProtected[color] = false;
                    }
                }
                if (event.down()) {
                    touches.put(event.id(), touch);
                    if (old == null) {
                        newTouches.add(event.id());
                    }
                } else {
                    touches.remove(event.id());
                }
            }
            return newTouches;
        }

        /** 当前时刻的投影只计算一次，所有归属分支使用相同物理输入。 */
        private void updatePositions() {
            positions.clear();
            for (Touch touch : touches.values()) {
                positions.put(touch.id(), position(touch));
            }
        }

        /** 反解实际屏幕位置，不对齐原计划曲线。 */
        private AffPoint position(Touch touch) {
            int x = context.mirror() ? context.resolution().getMaxX() - touch.x() : touch.x();
            return context.resolution().convertToAffPoint(x, touch.y(), ratio);
        }

        /** 游戏侧每次真实近域检测都刷新放行；不采用生成器的 17 ms 准入门槛。 */
        private void updateColors() {
            boolean close = false;
            for (int i = 0; i < activeArcs.size() && !close; i++) {
                Arc a = activeArcs.get(i);
                double[] p = a.getAffPoint(time);
                for (int j = i + 1; j < activeArcs.size(); j++) {
                    Arc b = activeArcs.get(j);
                    if (a.getColor() == b.getColor()) {
                        continue;
                    }
                    double[] q = b.getAffPoint(time);
                    double dx = (p[0] - q[0]) * 8.5;
                    double dy = (p[1] - q[1]) * 4.5;
                    if (dx * dx + dy * dy < 4.0) {
                        close = true;
                        break;
                    }
                }
            }
            if (close) {
                boolean entering = time > graceUntil;
                graceUntil = time + COLOR_GRACE_MILLIS;
                for (State state : states) {
                    if (entering || Arrays.stream(state.owners).anyMatch(id -> id >= 0)) {
                        state.record(new ReplayReport.TraceEvent(time, "CLEAR，500 ms 放行", null, -1));
                    }
                    Arrays.fill(state.owners, -1);
                    Arrays.fill(state.cooldown, Double.NEGATIVE_INFINITY);
                    Arrays.fill(state.releaseProtected, false);
                }
                states = distinct(states);
            }
        }

        /** 对同刻新边沿的处理顺序分支；每个边沿最多消费一个普通点击/未解锁 Hold。 */
        private void judgePresses(List<Integer> ids) {
            if (ids.isEmpty()) {
                return;
            }
            Set<PressProgress> wave = new LinkedHashSet<>();
            for (State state : states) {
                wave.add(new PressProgress(new BitSet(), state));
            }
            for (int depth = 0; depth < ids.size(); depth++) {
                Set<PressProgress> next = new LinkedHashSet<>();
                for (PressProgress progress : wave) {
                    for (int i = 0; i < ids.size(); i++) {
                        if (progress.used().get(i)) {
                            continue;
                        }
                        int id = ids.get(i);
                        List<Goal> candidates = pressCandidates(progress.state(), id);
                        BitSet used = (BitSet) progress.used().clone();
                        used.set(i);
                        if (candidates.isEmpty()) {
                            next.add(new PressProgress(used, progress.state()));
                        }
                        for (Goal goal : candidates) {
                            double delta = time - goal.span().nominal();
                            double tapWindow = goal.source().getNoteCount() == 0 ? 100.0 : MAX_PURE_WINDOW;
                            if (goal.kind() == Kind.TAP && Math.abs(delta) > tapWindow) {
                                throw stop(FAIL, "普通点击未达到所需判定：偏差=" + delta
                                        + " ms，允许范围=+/-" + tapWindow
                                        + " ms，nominal=" + goal.span().nominal(),
                                        goal.source().getSourceId(), progress.state());
                            }
                            State state = new State(progress.state());
                            state.done.set(goal.id());
                            state.record(new ReplayReport.TraceEvent(time,
                                    goal.kind() == Kind.HOLD_HEAD ? "HOLD_UNLOCK" : "TAP_HIT",
                                    snapshot(id, positions.get(id), state.colorOf(id)), goal.source().getSourceId()));
                            next.add(new PressProgress(used, state));
                        }
                        checkBudget(next.size());
                    }
                }
                wave = next;
            }
            states = distinct(wave.stream().map(PressProgress::state).toList());
        }

        /** 按物件时间、未解锁 Hold/Arctap/Tap 优先级及几何距离选择；完全相同者保留分支。 */
        private List<Goal> pressCandidates(State state, int id) {
            List<Goal> result = new ArrayList<>();
            Goal best = null;
            double distance = Double.POSITIVE_INFINITY;
            AffPoint position = positions.get(id);
            for (Goal goal : activeGoals) {
                if ((goal.kind() != Kind.TAP && goal.kind() != Kind.HOLD_HEAD)
                        || state.done.get(goal.id()) || time > goal.span().until()
                        || !InputJudgementRange.coversPress(goal.source(), position, ratio)) {
                    continue;
                }
                double[] xy = goal.source().getAffPoint(goal.source().getT1());
                int[] screen = context.resolution().convertToXY(xy[0], xy[1], ratio);
                Touch touch = touches.get(id);
                int x = context.mirror() ? context.resolution().getMaxX() - touch.x() : touch.x();
                double d = Math.pow((double) screen[0] - x, 2) + Math.pow((double) screen[1] - touch.y(), 2);
                int order = best == null ? -1 : Double.compare(goal.span().nominal(), best.span().nominal());
                if (order == 0) {
                    order = Integer.compare(priority(goal), priority(best));
                }
                if (order == 0) {
                    order = Double.compare(d, distance);
                }
                if (order < 0) {
                    result.clear();
                    best = goal;
                    distance = d;
                }
                if (order <= 0) {
                    result.add(goal);
                }
            }
            return result;
        }

        /** 未解锁长条与同刻天键竞争时优先，其次天键，最后地键。 */
        private int priority(Goal goal) {
            return goal.kind() == Kind.HOLD_HEAD ? 0 : goal.source() instanceof ArcTap ? 1 : 2;
        }

        /** 只在实际判定区域中发现染色候选，不读取生成器的 sourceId-to-touch 对应。 */
        private void assignColors() {
            if (time <= graceUntil || touches.isEmpty() || activeArcs.isEmpty()) {
                return;
            }
            List<State> assigned = new ArrayList<>();
            for (State state : states) {
                int pending = 0;
                for (Arc arc : activeArcs) {
                    int color = arc.getColor();
                    if (state.owners[color] < 0 && time >= state.cooldown[color]) {
                        pending |= 1 << color;
                    }
                }
                expandColors(state, pending, new HashSet<>(), assigned);
                checkBudget(assigned.size());
            }
            states = distinct(assigned);
        }

        /** 枚举不同颜色先处理以及同刻触点谁先获色，不能让固定颜色顺序挑出有利匹配。 */
        private void expandColors(State state, int pending, Set<ColorProgress> seen, List<State> result) {
            if (!seen.add(new ColorProgress(pending, state))) {
                return;
            }
            checkBudget(seen.size());
            if (pending == 0) {
                result.add(state);
                return;
            }
            for (int color = 0; color < state.owners.length; color++) {
                if ((pending & (1 << color)) == 0) {
                    continue;
                }
                Map<Integer, Arc> candidates = new LinkedHashMap<>();
                double oldest = Double.POSITIVE_INFINITY;
                for (Touch touch : touches.values()) {
                    if (state.colorOf(touch.id()) >= 0 || touch.began() > oldest) {
                        continue;
                    }
                    for (Arc arc : activeArcs) {
                        if (arc.getColor() == color && arcCovers(arc, time, positions.get(touch.id()))) {
                            if (touch.began() < oldest) {
                                candidates.clear();
                                oldest = touch.began();
                            }
                            candidates.putIfAbsent(touch.id(), arc);
                            break;
                        }
                    }
                }
                if (candidates.isEmpty()) {
                    expandColors(state, pending & ~(1 << color), seen, result);
                }
                for (Map.Entry<Integer, Arc> candidate : candidates.entrySet()) {
                    State next = new State(state);
                    next.owners[color] = candidate.getKey();
                    next.releaseProtected[color] = false;
                    ReplayReport.TraceEvent event = new ReplayReport.TraceEvent(time, "DYE",
                            snapshot(candidate.getKey(), positions.get(candidate.getKey()), color),
                            candidate.getValue().getSourceId());
                    next.binding[color] = event;
                    next.record(event);
                    expandColors(next, pending & ~(1 << color), seen, result);
                }
            }
        }

        /** 从真实位置和当前颜色计算持续判定，包括已解锁 Hold 的全部窗口。 */
        private void judgeContinuous() {
            for (State state : states) {
                for (Goal goal : activeGoals) {
                    if (goal.kind() == Kind.TAP || goal.kind() == Kind.HOLD_HEAD
                            || state.done.get(goal.id()) || time > goal.span().until()
                            || (goal.unlockId() >= 0 && !state.done.get(goal.unlockId()))) {
                        continue;
                    }
                    for (Map.Entry<Integer, AffPoint> touch : positions.entrySet()) {
                        boolean hit;
                        if (goal.source() instanceof Hold hold) {
                            hit = InputJudgementRange.coversLane(hold.getTrack(), touch.getValue(), ratio);
                        } else {
                            Arc arc = (Arc) goal.source();
                            // 追加点只改变名义判定身份，固定头中心是调度器约束而非游戏空间规则。
                            hit = (time <= graceUntil || state.owners[arc.getColor()] == touch.getKey())
                                    && time >= state.cooldown[arc.getColor()]
                                    && arcCovers(arc, time, touch.getValue());
                        }
                        if (hit) {
                            state.done.set(goal.id());
                            break;
                        }
                    }
                }
            }
            states = distinct(states);
        }

        /** 使用真实 Arc 位置的标准矩形，不以触点间距离代替 Arc 命中范围。 */
        private boolean arcCovers(Arc arc, double at, AffPoint touch) {
            double[] xy = arc.getAffPoint(at);
            return ArcJudgementRange.covers(new AffPoint(xy[0], xy[1]),
                    InputJudgementRange.skyPosition(touch, xy[1], ratio));
        }

        /** 到期之后任何一个合法分支仍未完成需求，都足以否定可靠性。 */
        private void rejectExpired() {
            for (Goal goal : activeGoals) {
                if (time < goal.span().until()) {
                    continue;
                }
                for (State state : states) {
                    if (!state.done.get(goal.id())) {
                        throw unmet(goal, state, goal.kind() == Kind.TAP
                                ? "大 Pure 判定窗口结束仍未命中" : "持续判定或起按窗口结束仍未完成");
                    }
                }
            }
        }

        /** 找到下一个整数检查点或更早的精确规则边界，不改变任何原始时间。 */
        private double nextTime(double end) {
            double next = Math.min(end, Math.floor(time) + 1.0);
            while (boundaryIndex < boundaries.size() && boundaries.get(boundaryIndex) <= time) {
                boundaryIndex++;
            }
            if (boundaryIndex < boundaries.size()) {
                next = Math.min(next, boundaries.get(boundaryIndex));
            }
            if (batchIndex < batches.size()) {
                next = Math.min(next, chartTime(batches.get(batchIndex)));
            }
            if (graceUntil >= time) {
                next = Math.min(next, Math.nextUp(graceUntil));
            }
            for (State state : states) {
                for (double expiry : state.cooldown) {
                    if (expiry > time) {
                        next = Math.min(next, expiry);
                    }
                }
            }
            return next;
        }

        /** 只合并所有游戏状态完全一致的分支；历史不影响未来，只保留一条有效反例前缀。 */
        private List<State> distinct(List<State> values) {
            List<State> result = new ArrayList<>(new LinkedHashSet<>(values));
            peakStates = Math.max(peakStates, result.size());
            checkBudget(result.size());
            return result;
        }

        /** 有界检查必须失败关闭，不通过随机抽样降低状态数量。 */
        private void checkBudget(int count) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("正向回放已取消");
            }
            if (count > MAX_STATES || frames > MAX_FRAMES || System.nanoTime() - started > MAX_CASE_NANOS) {
                throw stop(INCONCLUSIVE, "回放达到状态、时刻或时间预算，不能证明全部分支通过"
                        + "：states=" + count + "，frames=" + frames, -1, states.getFirst());
            }
        }

        /** 构造带原谱判定边界的反例原因。 */
        private Stop unmet(Goal goal, State state, String reason) {
            return stop(FAIL, reason + "：kind=" + goal.kind() + "，nominal=" + goal.span().nominal()
                    + "，window=[" + goal.span().from() + "," + goal.span().until() + "]"
                    + "，source=" + goal.source(), goal.source().getSourceId(), state);
        }

        /** 显式的模型结果控制流，不捕获未知程序异常。 */
        private Stop stop(ReplayReport.Status status, String reason, int sourceId, State state) {
            return new Stop(status, reason, sourceId, state);
        }

        /** 统计当前分支及全部已检查分支的最大规模。 */
        private ReplayReport.CaseResult result(ReplayReport.Status status, State state,
                                               ReplayReport.Failure failure) {
            return new ReplayReport.CaseResult(offset, status,
                    new ReplayReport.Stats(state.done.cardinality(), goals.all().size(), peakStates,
                            frames, (System.nanoTime() - started) / 1_000_000L), failure);
        }

        /** 给失败报告保留所有真实活动触点，而不是只显示计划 Arc 触点。 */
        private List<ReplayReport.TouchSnapshot> snapshots(State state) {
            return touches.values().stream().map(touch -> snapshot(touch.id(),
                    positions.getOrDefault(touch.id(), position(touch)), state.colorOf(touch.id()))).toList();
        }

        /** 单个触点的当前坐标与颜色。 */
        private ReplayReport.TouchSnapshot snapshot(int id, AffPoint p, int color) {
            return new ReplayReport.TouchSnapshot(id, p.x(), p.y(), color);
        }

        /** 即使绑定发生在很久之前，也保留与冷却相关的染色和抬起根因。 */
        private List<ReplayReport.TraceEvent> history(State state) {
            List<ReplayReport.TraceEvent> recent = new ArrayList<>();
            for (Trace trace = state.trace; trace != null; trace = trace.previous()) {
                recent.add(trace.event());
            }
            Set<ReplayReport.TraceEvent> result = new LinkedHashSet<>(recent.reversed());
            for (ReplayReport.TraceEvent event : state.binding) {
                if (event != null) {
                    result.add(event);
                }
            }
            for (ReplayReport.TraceEvent event : state.lastRelease) {
                if (event != null) {
                    result.add(event);
                }
            }
            return result.stream().sorted(Comparator.comparingDouble(ReplayReport.TraceEvent::timing)).toList();
        }
    }

    /** 分支的可变游戏状态；物理输入与当前近域检测由场景共享。 */
    private static final class State {
        private final int[] owners;
        private final double[] cooldown;
        private final boolean[] releaseProtected;
        private final BitSet done;
        private final ReplayReport.TraceEvent[] binding;
        private final ReplayReport.TraceEvent[] lastRelease;
        private Trace trace;
        private int traceSize;

        /** 颜色尚未标记，冷却未启动，原谱没有任何已完成判定。 */
        private State() {
            owners = new int[]{-1, -1, -1, -1};
            cooldown = new double[4];
            Arrays.fill(cooldown, Double.NEGATIVE_INFINITY);
            releaseProtected = new boolean[4];
            done = new BitSet();
            binding = new ReplayReport.TraceEvent[4];
            lastRelease = new ReplayReport.TraceEvent[4];
        }

        /** 染色或普通点击竞争时复制状态，历史前缀只读共享。 */
        private State(State other) {
            owners = other.owners.clone();
            cooldown = other.cooldown.clone();
            releaseProtected = other.releaseProtected.clone();
            done = (BitSet) other.done.clone();
            binding = other.binding.clone();
            lastRelease = other.lastRelease.clone();
            trace = other.trace;
            traceSize = other.traceSize;
        }

        /** 返回触点的实际颜色，而不是该 ID 在生成计划中的用途。 */
        private int colorOf(int id) {
            for (int color = 0; color < owners.length; color++) {
                if (owners[color] == id) {
                    return color;
                }
            }
            return -1;
        }

        /** 历史有界保存；长期绑定和最近释放根因另有固定槽位，不因裁剪丢失。 */
        private void record(ReplayReport.TraceEvent event) {
            trace = new Trace(event, trace);
            if (++traceSize > 128) {
                List<ReplayReport.TraceEvent> recent = new ArrayList<>();
                Trace cursor = trace;
                for (int i = 0; i < 80 && cursor != null; i++, cursor = cursor.previous()) {
                    recent.add(cursor.event());
                }
                trace = null;
                for (int i = recent.size() - 1; i >= 0; i--) {
                    trace = new Trace(recent.get(i), trace);
                }
                traceSize = recent.size();
            }
        }

        /** 等价判据包含所有会影响未来接受输入的状态，不比较诊断历史。 */
        @Override
        public boolean equals(Object other) {
            return other instanceof State state && Arrays.equals(owners, state.owners)
                    && Arrays.equals(cooldown, state.cooldown)
                    && Arrays.equals(releaseProtected, state.releaseProtected) && done.equals(state.done);
        }

        /** 与完整游戏状态的等价判据保持一致。 */
        @Override
        public int hashCode() {
            int result = Arrays.hashCode(owners);
            result = 31 * result + Arrays.hashCode(cooldown);
            result = 31 * result + Arrays.hashCode(releaseProtected);
            return 31 * result + done.hashCode();
        }
    }

    /** 同刻实际屏幕输入批次，时间单位为 record 毫秒。 */
    private record Batch(int timing, List<InputEvent> events) {
    }

    /** 真实 ID/屏幕坐标/状态，不携带谱面归属。 */
    private record InputEvent(int id, int x, int y, boolean down) {
    }

    /** 从按下到抬起的物理触点，移动不改变 began。 */
    private record Touch(int id, int x, int y, double began) {
    }

    /** 普通点击、Hold 解锁和两种持续判定身份。 */
    private enum Kind { TAP, HOLD_HEAD, CONTINUOUS, ARC_HEAD }

    /** 原谱名义时间及固定的可判定区间，不随操作偏移。 */
    private record Span(double nominal, double from, double until) {
    }

    /** 一个原谱需求；unlockId 仅表示 Hold 起按，不是触点归属。 */
    private record Goal(int id, Note source, Kind kind, Span span, int unlockId) {
    }

    /** 全部需求及按进入时刻建立的只读索引。 */
    private record Goals(List<Goal> all, List<Goal> byStart) {
    }

    /** 音弧组真正结束时刻及包含颜色的位图。 */
    private record GroupEnd(double time, int colors) {
    }

    /** 同刻普通点击边沿处理进度，used 参与状态等价。 */
    private record PressProgress(BitSet used, State state) {
    }

    /** 同刻染色先后顺序进度，避免固定遍历顺序掩盖竞争。 */
    private record ColorProgress(int pending, State state) {
    }

    /** 只读反例历史链，不参与状态等价。 */
    private record Trace(ReplayReport.TraceEvent event, Trace previous) {
    }

    /** 格式错误与未知程序错误分离，前者作为输入失败报告。 */
    private static final class ScriptFormatException extends IllegalArgumentException {
        /** 保存具体字段或触控批次的格式诊断。 */
        private ScriptFormatException(String message) {
            super(message);
        }
    }

    /** 已定义的失败/未确定结论用于终止当前场景，其余异常仍交给调用方诊断。 */
    private static final class Stop extends RuntimeException {
        private final ReplayReport.Status status;
        private final int sourceId;
        private final State state;

        /** 保存触发结论时的分支，不生成高成本的异常栈。 */
        private Stop(ReplayReport.Status status, String message, int sourceId, State state) {
            super(message, null, false, false);
            this.status = status;
            this.sourceId = sourceId;
            this.state = state;
        }
    }
}
