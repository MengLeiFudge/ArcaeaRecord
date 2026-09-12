package arc.record.record.func;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import arc.record.aff.Aff;
import arc.record.aff.judge.ChartJudgementModel;
import arc.record.aff.note.Arc;
import arc.record.aff.note.ArcTap;
import arc.record.aff.note.Click;
import arc.record.aff.note.Note;
import arc.record.record.model.Request;
import arc.record.record.model.Resolution;
import arc.record.record.model.SimpleAction;
import arc.record.record.plan.ArcColorStateMachine;
import arc.record.record.plan.TouchScheduler;
import arc.record.record.plan.TouchStroke;
import arc.record.record.plan.TouchStrokeRenderer;
import arc.record.record.verify.ForwardReplay;
import arc.record.record.verify.ReplayContext;
import arc.record.record.verify.ReplayReport;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.SerializationUtils;

import static arc.record.Settings.CLICK_TIME;
import static arc.record.Settings.DEBUG_MODE;
import static arc.record.Settings.EFFECT_TIME;
import static arc.record.Settings.FIRST_NOTE_TIME;
import static arc.record.Utils.THREAD_NUM;
import static arc.record.Utils.dfTime;

/**
 * 可生成脚本的线程池，调用 {@link #process} 以生成脚本。
 *
 * @author MengLeiFudge
 */
public class RecordThreadPool {
    private static final DecimalFormat df = new DecimalFormat("0.00%");

    public static Set<File> process(Map<File, Map<Integer, List<Request>>> processMap) {
        Map<File, Aff> affMap = new HashMap<>();
        for (File file : processMap.keySet()) {
            affMap.put(file, new Aff(file));
        }
        return process(affMap, processMap);
    }

    /**
     * 将每张谱面作为独立任务动态分配给转换线程。
     *
     * @param affMap     已解析谱面
     * @param processMap 处理需求
     * @return 本轮成功写出的脚本文件
     */
    public static Set<File> process(Map<File, Aff> affMap,
                                    Map<File, Map<Integer, List<Request>>> processMap) {
        long startTime = System.currentTimeMillis();
        int targetNum = 0;
        for (Map<Integer, List<Request>> map : processMap.values()) {
            for (List<Request> list : map.values()) {
                targetNum += list.size();
            }
        }
        if (targetNum == 0) {
            System.out.println("没有需要生成的脚本！");
            return Set.of();
        }

        List<File> processFiles = new ArrayList<>(processMap.keySet());
        if (DEBUG_MODE && processFiles.size() > 1) {
            throw new IllegalStateException("DEBUG开启且谱面文件数目大于1！");
        }
        processFiles.sort(Comparator
                .<File>comparingLong(file -> estimateProcessCost(affMap.get(file), processMap.get(file)))
                .reversed()
                .thenComparing(File::getAbsolutePath));

        int threadNum = Math.min(THREAD_NUM, processFiles.size());
        ExecutorService pool = new ThreadPoolExecutor(
                threadNum, threadNum,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                new ConvertThreadFactory());
        ExecutorCompletionService<FileResult> completionService = new ExecutorCompletionService<>(pool);
        List<Future<FileResult>> futures = new ArrayList<>();
        for (File file : processFiles) {
            futures.add(completionService.submit(
                    () -> new RecordThreadPool().processFile(
                            file, affMap.get(file), processMap.get(file))));
        }
        pool.shutdown();

        int processedNum = 0;
        int checkedNum = 0;
        int failedNum = 0;
        int printedPercent = -1;
        Set<File> recordFiles = new HashSet<>();
        try {
            for (int i = 0; i < processFiles.size(); i++) {
                FileResult result = completionService.take().get();
                processedNum += result.processedNum();
                recordFiles.addAll(result.recordFiles());
                checkedNum += result.checkedNum();
                failedNum += result.failedNum();
                int percent = processedNum * 100 / targetNum;
                if (percent != printedPercent) {
                    System.out.println("转换进度：" + df.format((double) processedNum / targetNum));
                    printedPercent = percent;
                }
            }
        } catch (InterruptedException e) {
            futures.forEach(future -> future.cancel(true));
            pool.shutdownNow();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("脚本转换被中断", e);
        } catch (ExecutionException e) {
            futures.forEach(future -> future.cancel(true));
            pool.shutdownNow();
            throw new IllegalStateException("脚本转换任务失败", e.getCause());
        } finally {
            if (!pool.isTerminated()) {
                pool.shutdownNow();
            }
        }

        System.out.println("理论值自检汇总：检查 " + checkedNum + " 个谱面变体，通过 "
                + (checkedNum - failedNum) + "，未通过或未确定 " + failedNum);
        long endTime = System.currentTimeMillis() - startTime;
        String minuteStr = endTime >= 60000 ? " " + (int) (endTime / 60000) + " min" : "";
        endTime %= 60000;
        String secondStr = endTime >= 1000 ? " " + (int) (endTime / 1000) + " s" : "";
        endTime %= 1000;
        System.out.println("处理完毕，用时" + minuteStr + secondStr + " " + endTime + " ms");
        return Set.copyOf(recordFiles);
    }

    private static long estimateProcessCost(Aff aff, Map<Integer, List<Request>> requestMap) {
        int requestNum = requestMap.values().stream().mapToInt(List::size).sum();
        return (long) Math.max(1, aff.getNoteCount()) * (requestMap.size() + requestNum);
    }

    private static void add(JSONArray points, SimpleAction action,
                            boolean mirror, Resolution resolution) {
        JSONObject point = new JSONObject();
        point.put("id", action.id());
        point.put("x", mirror ? resolution.getMaxX() - action.x() : action.x());
        point.put("y", action.y());
        point.put("state", action.getState());
        points.add(point);
    }

    /**
     * 处理一张谱面的全部 miss/小 Pure、分辨率和镜像请求。
     */
    private FileResult processFile(File affFile, Aff aff, Map<Integer, List<Request>> requestMap) {
        List<Note> baseNoteList = aff.getNoteList();
        List<Integer> requestKeys = new ArrayList<>(requestMap.keySet());
        Collections.sort(requestKeys);
        int firstKey = requestKeys.getFirst();
        Request firstRequest = requestMap.get(firstKey).getFirst();
        File debugRoot = new File(
                firstRequest.targetDir(), "test/" + aff.getSongName() + "_" + aff.getDiffStr());
        if (DEBUG_MODE) {
            saveNotes(baseNoteList, new File(debugRoot, "base.txt"));
        }

        List<Integer> modifiableClickIndexes = findModifiableClickIndexes(baseNoteList);
        Set<File> recordFiles = new HashSet<>();
        int processedNum = 0;
        int checkedNum = 0;
        int failedNum = 0;
        for (int key : requestKeys) {
            int miss = key / (aff.getNoteCount() + 1);
            int noShinyPure = key % (aff.getNoteCount() + 1);
            File debugDir = new File(debugRoot, miss + "L" + noShinyPure + "小");
            List<Note> noteList = SerializationUtils.clone((ArrayList<Note>) baseNoteList);
            modifyMP(noteList, modifiableClickIndexes, miss, noShinyPure);
            List<Request> requests = requestMap.get(key);
            if (noteList.isEmpty()) {
                boolean isLastEternity012 = affFile.getParentFile().getName().equals("dl_lasteternity")
                        && Integer.parseInt(affFile.getName().substring(0, 1)) < 3;
                if (!isLastEternity012) {
                    System.out.println("未在 " + affFile.getAbsolutePath() + " 内发现Note，需确认谱面文件状态！");
                }
                if (miss == 0 && noShinyPure == 0) {
                    // 合法空谱仍生成完整的启动/返回操作，不能从全清单中静默消失。
                    ChartJudgementModel model = ChartJudgementModel.build(aff, noteList);
                    Map<RecordVariant, RecordCandidate> candidates = new HashMap<>();
                    for (Request request : requests) {
                        RecordVariant variant = new RecordVariant(request.resolution(), request.mirror());
                        RecordCandidate candidate = candidates.computeIfAbsent(variant, ignored ->
                                verifyTheoreticalRecord(aff, model,
                                        buildRecordJson(aff, List.of(), 0, 0, request), request, List.of()));
                        recordFiles.add(writeRecord(aff, 0, 0, request, request.targetDir(), candidate.json()));
                    }
                    checkedNum += candidates.size();
                    failedNum += (int) candidates.values().stream()
                            .filter(candidate -> candidate.report().status() != ReplayReport.Status.PASS).count();
                }
            } else {
                ChartJudgementModel judgementModel = ChartJudgementModel.build(aff, noteList);
                List<TouchStroke> strokes = new TouchScheduler().schedule(aff, judgementModel);
                if (DEBUG_MODE) {
                    saveStrokes(strokes, new File(debugDir, "touch-strokes.txt"));
                }
                if (!strokes.isEmpty()) {
                    Map<Resolution, List<SimpleAction>> actionsByResolution = new EnumMap<>(Resolution.class);
                    Map<RecordVariant, RecordCandidate> jsonByVariant = new HashMap<>();
                    TouchStrokeRenderer renderer = new TouchStrokeRenderer();
                    for (Request request : requests) {
                        List<SimpleAction> actions = actionsByResolution.computeIfAbsent(
                                request.resolution(),
                                resolution -> renderer.render(aff, strokes, resolution));
                        RecordVariant variant = new RecordVariant(request.resolution(), request.mirror());
                        RecordCandidate candidate = jsonByVariant.computeIfAbsent(
                                variant,
                                ignored -> {
                                    String json = buildRecordJson(aff, actions, miss, noShinyPure, request);
                                    return miss == 0 && noShinyPure == 0
                                            ? verifyTheoreticalRecord(aff, judgementModel, json, request, strokes)
                                            : new RecordCandidate(json, null);
                                });
                        String json = candidate.json();
                        if (DEBUG_MODE) {
                            writeRecord(aff, miss, noShinyPure, request, debugDir, json);
                        }
                        recordFiles.add(writeRecord(
                                aff, miss, noShinyPure, request, request.targetDir(), json));
                    }
                    for (RecordCandidate candidate : jsonByVariant.values()) {
                        if (candidate.report() != null) {
                            checkedNum++;
                            if (candidate.report().status() != ReplayReport.Status.PASS) {
                                failedNum++;
                            }
                        }
                    }
                }
            }
            processedNum += requests.size();
        }
        return new FileResult(processedNum, Set.copyOf(recordFiles), checkedNum, failedNum);
    }

    /**
     * 回放最终理论值候选，将不可靠的低操作量路径逐组替换为完整跟踪。
     * 累计来源并比较两种空闲触点策略；相同JSON不重复回放，不可改善时报告后继续批次。
     *
     * @param aff 未修改的原谱
     * @param model 当前理论值的只读判定需求
     * @param json 最终整数事件和镜像已经写入的候选
     * @param request 当前输出投影与镜像要求
     * @param strokes 仅随反例保存生成意图，不传入正向回放器
     * @return 最终候选与真实回放结果，失败不伪装为通过
     */
    private RecordCandidate verifyTheoreticalRecord(
            Aff aff, ChartJudgementModel model, String json, Request request, List<TouchStroke> strokes) {
        Set<Integer> tracked = new HashSet<>();
        Set<String> seen = new HashSet<>();
        seen.add(json);
        boolean splitIdle = true;
        int attempts = 0;
        long elapsed = 0;
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw new java.util.concurrent.CancellationException("理论值候选规划已取消");
            }
            ReplayReport report = new ForwardReplay().verify(aff, json,
                    new ReplayContext(request.resolution(), request.mirror(), FIRST_NOTE_TIME + CLICK_TIME));
            attempts++;
            elapsed += report.cases().stream().mapToLong(result -> result.stats().elapsedMillis()).sum();
            if (report.status() == ReplayReport.Status.PASS) {
                int peak = report.cases().stream().mapToInt(result -> result.stats().peakStates()).max().orElse(0);
                System.out.println("理论值自检通过：" + aff.getSongName() + "_" + aff.getDiffStr()
                        + (request.mirror() ? "_镜像" : "_原版") + "，0/-10/+10 ms，峰值状态="
                        + peak + "，候选数=" + attempts + "，回放用时=" + elapsed + " ms");
                return new RecordCandidate(json, report);
            }
            Path directory = saveReplayFailure(aff, json, report, strokes);
            boolean expanded = tracked.addAll(trackedFallbackSources(aff, report, strokes));
            if (report.status() == ReplayReport.Status.FAIL && !tracked.isEmpty() && (expanded || splitIdle)) {
                if (!expanded) splitIdle = false;
                String next = null;
                while (true) {
                    try {
                        List<TouchStroke> fallback = new TouchScheduler()
                                .scheduleTrackedFallback(aff, model, tracked, splitIdle);
                        List<SimpleAction> actions = new TouchStrokeRenderer().render(aff, fallback, request.resolution());
                        String candidate = buildRecordJson(aff, actions, 0, 0, request);
                        if (seen.add(candidate)) {
                            next = candidate;
                            strokes = fallback;
                            break;
                        }
                    } catch (ArcColorStateMachine.UnsatisfiedColorException | TouchScheduler.UnsatisfiedWindowException e) {
                        System.out.println("跳过不可行的重规划候选：" + e.getMessage());
                    }
                    if (!splitIdle) break;
                    splitIdle = false;
                }
                if (next != null) {
                    System.out.println("调整未通过的低操作候选：" + aff.getSongName()
                            + "，完整跟踪来源数=" + tracked.size()
                            + (splitIdle ? "，拆开空闲区间" : "，保留组内持续触点")
                            + "，反例=" + directory.toAbsolutePath());
                    json = next;
                    continue;
                }
            }
            System.out.println("理论值自检未通过，继续生成：" + aff.getAffFile().getAbsolutePath()
                    + "，诊断=" + directory.toAbsolutePath() + "\n" + replaySummary(report));
            return new RecordCandidate(json, report);
        }
    }

    /** 保存失败候选及偏移反例，未知 I/O 错误仍显式报告。 */
    private Path saveReplayFailure(Aff aff, String json, ReplayReport report, List<TouchStroke> strokes) {
        try {
            Path root = Path.of("target", "self-check");
            Files.createDirectories(root);
            Path directory = Files.createTempDirectory(root, "failure-");
            Files.writeString(directory.resolve("candidate.record"), json, StandardCharsets.UTF_8);
            Files.writeString(directory.resolve("report.json"), report.toJson(), StandardCharsets.UTF_8);
            double from = report.cases().stream().filter(result -> result.failure() != null)
                    .mapToDouble(result -> result.failure().timing()).min().orElse(0) - 1500;
            double to = report.cases().stream().filter(result -> result.failure() != null)
                    .mapToDouble(result -> result.failure().timing()).max().orElse(0) + 500;
            List<String> plan = new ArrayList<>(List.of("生成器计划仅用于定位，不参与独立回放判定。"));
            for (TouchStroke stroke : strokes) {
                if (stroke.startTime() > to || stroke.endTime() < from) continue;
                plan.add(stroke.kind() + " [" + stroke.startTime() + "," + stroke.endTime()
                        + "] sources=" + stroke.sourceIds().stream().sorted().toList()
                        + " initial=" + stroke.initialPosition());
                plan.add("objects=" + stroke.hitOpportunities().stream()
                        .map(hit -> hit.demand().point().source()).distinct()
                        .map(source -> source.getClass().getSimpleName() + "#" + source.getSourceId()
                                + " [" + source.getT1() + "," + source.getT2() + "] "
                                + java.util.Arrays.toString(source.getAffPoint())).toList());
                plan.add("anchors=" + stroke.anchors().stream()
                        .filter(anchor -> anchor.time() >= from && anchor.time() <= to).toList());
            }
            Files.write(directory.resolve("plan.txt"), plan, StandardCharsets.UTF_8);
            return directory;
        } catch (IOException e) {
            throw new IllegalStateException("理论值自检未通过，且无法保存诊断："
                    + aff.getAffFile().getAbsolutePath() + "\n" + replaySummary(report), e);
        }
    }

    /** 每个偏移独立列出，不以最终生成成功覆盖失败结论。 */
    private String replaySummary(ReplayReport report) {
        return report.cases().stream()
                .map(result -> result.offsetMillis() + " ms=" + result.status()
                        + (result.failure() == null ? "" : "@" + result.failure().timing()
                        + "，sourceIds=" + result.failure().sourceIds() + "，" + result.failure().reason()))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    /**
     * 收集失败 Arc、反例染色/释放历史的来源及失败时刻活动 Arc，覆盖冷却和提前点击。
     *
     * @param aff 原谱和实体 Arc 身份
     * @param report 独立回放的不可变反例，不会改写本次回放结果
     * @param strokes 仅用于把真实反例时刻关联到待改进的生成路径，不作为判定证据
     * @return 可累计的局部完整跟踪来源集合；未确定结果不自动重规划
     */
    private Set<Integer> trackedFallbackSources(Aff aff, ReplayReport report, List<TouchStroke> strokes) {
        if (report.status() != ReplayReport.Status.FAIL) {
            return Set.of();
        }
        Set<Integer> failed = report.cases().stream()
                .filter(result -> result.failure() != null)
                .flatMap(result -> result.failure().sourceIds().stream())
                .collect(java.util.stream.Collectors.toSet());
        List<Double> failureTimes = report.cases().stream()
                .filter(result -> result.failure() != null)
                .map(result -> result.failure().timing()).toList();
        for (ReplayReport.CaseResult result : report.cases()) {
            if (result.failure() == null) continue;
            double time = result.failure().timing();
            for (TouchStroke stroke : strokes) {
                if (stroke.startTime() <= time + 10 && stroke.endTime() >= time - 1010)
                    failed.addAll(stroke.sourceIds());
            }
            result.failure().history().stream()
                    .filter(event -> event.event().equals("DYE"))
                    .forEach(event -> failed.add(event.sourceId()));
        }
        List<Arc> failedArcs = aff.getArcList().stream()
                .filter(arc -> failed.contains(arc.getSourceId())).toList();
        return aff.getArcList().stream()
                .filter(arc -> failedArcs.stream().anyMatch(other ->
                        arc.getT1() <= other.getT2() && other.getT1() <= arc.getT2())
                        || failureTimes.stream().anyMatch(time -> arc.getT1() <= time + 10
                        && arc.getT2() >= time - 10))
                .map(Arc::getSourceId)
                .collect(java.util.stream.Collectors.toSet());
    }

    /** 一个唯一输出变体及其最终校验结果，非理论值变体的 report 为 null。 */
    private record RecordCandidate(String json, ReplayReport report) {
    }

    private void saveNotes(List<Note> noteList, File file) {
        List<String> lines = noteList.stream().map(Note::toString).toList();
        try {
            FileUtils.writeLines(file, lines);
        } catch (IOException e) {
            throw new IllegalStateException("无法写入调试 Note：" + file.getAbsolutePath(), e);
        }
    }

    private void saveStrokes(List<TouchStroke> strokes, File file) {
        List<String> lines = new ArrayList<>();
        for (TouchStroke stroke : strokes) {
            lines.add(stroke.kind() + " [" + dfTime.format(stroke.startTime())
                    + ", " + dfTime.format(stroke.endTime()) + "] "
                    + stroke.anchors() + " sources=" + stroke.sourceIds());
        }
        try {
            FileUtils.writeLines(file, lines);
        } catch (IOException e) {
            throw new IllegalStateException("无法写入调试触控段落：" + file.getAbsolutePath(), e);
        }
    }

    /**
     * 找出与其他普通点击在时间和判定位置上均不相邻的 Note 下标。
     */
    private List<Integer> findModifiableClickIndexes(List<Note> noteList) {
        List<ClickPosition> clicks = new ArrayList<>();
        for (int i = 0; i < noteList.size(); i++) {
            Note note = noteList.get(i);
            if ((!(note instanceof Click) && !(note instanceof ArcTap))
                    || note.getNoteCount() == 0) {
                continue;
            }
            double[] xy = note.getAffPoint();
            int[] screenPoint = Resolution.R16_9_1280_720.convertToXY(xy[0], xy[1], 0);
            clicks.add(new ClickPosition(i, note.getT1(), screenPoint[0], screenPoint[1]));
        }
        boolean[] blocked = new boolean[clicks.size()];
        double judgeRange = Resolution.R16_9_1280_720.getMaxX() * 0.09375;
        double judgeRangeSquared = judgeRange * judgeRange;
        for (int i = 0; i < clicks.size(); i++) {
            ClickPosition first = clicks.get(i);
            for (int j = i + 1; j < clicks.size(); j++) {
                ClickPosition second = clicks.get(j);
                if (second.timing() - first.timing() > EFFECT_TIME) {
                    break;
                }
                long dx = first.x() - second.x();
                long dy = first.y() - second.y();
                if (dx * dx + dy * dy <= judgeRangeSquared) {
                    blocked[i] = true;
                    blocked[j] = true;
                }
            }
        }
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < clicks.size(); i++) {
            if (!blocked[i]) {
                result.add(clicks.get(i).noteIndex());
            }
        }
        return List.copyOf(result);
    }

    /**
     * 按照指定 miss、小 Pure 数修改普通点击。
     */
    private void modifyMP(List<Note> noteList, List<Integer> modifiableClickIndexes,
                          final int miss, int noShinyPure) {
        List<Note> canModifyClicks = new ArrayList<>(modifiableClickIndexes.size());
        for (int index : modifiableClickIndexes) {
            canModifyClicks.add(noteList.get(index));
        }
        if (canModifyClicks.isEmpty()) {
            return;
        }
        List<Note> missClicks = new ArrayList<>();
        List<Note> noShinyPureClicks = new ArrayList<>();
        noShinyPure *= 1.2;
        int startTime = canModifyClicks.getFirst().getT1();
        int endTime = canModifyClicks.getLast().getT1();
        int fullTimeSpan = endTime - startTime;
        int targetMissNum = miss;
        while (true) {
            missClicks.clear();
            noShinyPureClicks.clear();
            double noteTimeSpan = fullTimeSpan * 1.0 / Math.max(1, targetMissNum);
            for (Note click : canModifyClicks) {
                int needMissNum = (int) ((click.getT1() - startTime) / noteTimeSpan) + 1;
                if (missClicks.size() < needMissNum && missClicks.size() < miss) {
                    missClicks.add(click);
                } else if (noShinyPureClicks.size() < noShinyPure) {
                    noShinyPureClicks.add(click);
                }
                if (missClicks.size() == miss && noShinyPureClicks.size() == noShinyPure) {
                    break;
                }
            }
            if (missClicks.size() == miss && noShinyPureClicks.size() == noShinyPure) {
                break;
            }
            if (targetMissNum >= canModifyClicks.size()) {
                break;
            }
            targetMissNum++;
        }
        noteList.removeAll(missClicks);
        boolean nextToEarly = true;
        for (Note note : noShinyPureClicks) {
            note.setT1(nextToEarly ? note.getT1() - 30 : note.getT1() + 30);
            note.setT2(note.getT1() + CLICK_TIME);
            nextToEarly = !nextToEarly;
        }
        Collections.sort(noteList);
    }

    /**
     * 为一种分辨率和镜像组合构建完整 record JSON。
     */
    private String buildRecordJson(Aff aff, List<SimpleAction> simpleActions,
                                   int miss, int noShinyPure, Request request) {
        JSONArray operationsArray = new JSONArray();
        List<SimpleAction> preSimpleActions = request.resolution().getPreSimpleActions();
        appendOperations(operationsArray, preSimpleActions, false, request.resolution());
        appendOperations(operationsArray, simpleActions, request.mirror(), request.resolution());

        JSONObject recordInfo = new JSONObject();
        recordInfo.put("loopType", 0);
        recordInfo.put("loopTimes", 1);
        recordInfo.put("circleDuration", (simpleActions.isEmpty()
                ? preSimpleActions.getLast().timing() : simpleActions.getLast().timing()) + 500);
        recordInfo.put("loopInterval", 0);
        recordInfo.put("loopDuration", 0);
        recordInfo.put("accelerateTimes", 1);
        recordInfo.put("recordName", "");
        recordInfo.put("createTime", getRecordDescription(aff, miss, noShinyPure, request));
        recordInfo.put("playOnBoot", false);
        recordInfo.put("rebootTiming", 0);
        JSONObject record = new JSONObject();
        record.put("operations", operationsArray);
        record.put("recordInfo", recordInfo);
        return record.toString(JSONWriter.Feature.PrettyFormat);
    }

    private void appendOperations(JSONArray operationsArray, List<SimpleAction> actions,
                                  boolean mirror, Resolution resolution) {
        for (int i = 0; i < actions.size(); i++) {
            int timing = actions.get(i).timing();
            JSONArray points = new JSONArray();
            int j = i;
            while (j < actions.size() && actions.get(j).timing() == timing) {
                add(points, actions.get(j), mirror, resolution);
                j++;
            }
            JSONObject operation = new JSONObject();
            operation.put("timing", timing);
            operation.put("operationId", "PutMultiTouch");
            operation.put("points", points);
            operationsArray.add(operation);
            i = j - 1;
        }
    }

    private String getRecordDescription(Aff aff, int miss, int noShinyPure, Request request) {
        return aff.getDiffStr()
                + (request.mirror() ? "_镜像_" : "_原版_")
                + miss + "L" + noShinyPure + "小";
    }

    private File writeRecord(Aff aff, int miss, int noShinyPure, Request request,
                             File targetDir, String json) {
        String description = getRecordDescription(aff, miss, noShinyPure, request);
        try {
            File recordFile = new File(targetDir,
                    aff.getSongName() + "_" + description + ".record").getCanonicalFile();
            FileUtils.write(recordFile, json, StandardCharsets.UTF_8);
            return recordFile;
        } catch (IOException e) {
            throw new IllegalStateException("无法写入脚本：" + targetDir.getAbsolutePath(), e);
        }
    }

    private record ClickPosition(int noteIndex, int timing, int x, int y) {
    }

    private record RecordVariant(Resolution resolution, boolean mirror) {
    }

    /** 当前谱面任务生成与校验分别计数，失败自检不取消其他任务。 */
    private record FileResult(int processedNum, Set<File> recordFiles, int checkedNum, int failedNum) {
    }

    private static class ConvertThreadFactory implements ThreadFactory {
        final ThreadGroup group;
        final AtomicInteger threadNumber = new AtomicInteger(1);
        final String namePrefix;

        ConvertThreadFactory() {
            group = Thread.currentThread().getThreadGroup();
            namePrefix = "thread-";
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(
                    group, runnable, namePrefix + threadNumber.getAndIncrement(), 0);
            if (thread.isDaemon()) {
                thread.setDaemon(false);
            }
            if (thread.getPriority() != Thread.NORM_PRIORITY) {
                thread.setPriority(Thread.NORM_PRIORITY);
            }
            return thread;
        }
    }
}
