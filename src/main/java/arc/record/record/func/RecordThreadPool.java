package arc.record.record.func;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import arc.record.aff.note.ArcTap;
import arc.record.aff.note.Click;
import arc.record.aff.note.Note;
import arc.record.record.model.Request;
import arc.record.record.model.Resolution;
import arc.record.record.model.SimpleAction;
import arc.record.record.plan.TouchScheduler;
import arc.record.record.plan.TouchStroke;
import arc.record.record.plan.TouchStrokeRenderer;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.SerializationUtils;

import static arc.record.Settings.CLICK_TIME;
import static arc.record.Settings.DEBUG_MODE;
import static arc.record.Settings.EFFECT_TIME;
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
        int printedPercent = -1;
        Set<File> recordFiles = new HashSet<>();
        try {
            for (int i = 0; i < processFiles.size(); i++) {
                FileResult result = completionService.take().get();
                processedNum += result.processedNum();
                recordFiles.addAll(result.recordFiles());
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
            } else {
                ChartJudgementModel judgementModel = ChartJudgementModel.build(aff, noteList);
                List<TouchStroke> strokes = new TouchScheduler().schedule(aff, judgementModel);
                if (DEBUG_MODE) {
                    saveStrokes(strokes, new File(debugDir, "touch-strokes.txt"));
                }
                if (!strokes.isEmpty()) {
                    Map<Resolution, List<SimpleAction>> actionsByResolution = new EnumMap<>(Resolution.class);
                    Map<RecordVariant, String> jsonByVariant = new HashMap<>();
                    TouchStrokeRenderer renderer = new TouchStrokeRenderer();
                    for (Request request : requests) {
                        List<SimpleAction> actions = actionsByResolution.computeIfAbsent(
                                request.resolution(),
                                resolution -> renderer.render(aff, strokes, resolution));
                        RecordVariant variant = new RecordVariant(request.resolution(), request.mirror());
                        String json = jsonByVariant.computeIfAbsent(
                                variant,
                                ignored -> buildRecordJson(aff, actions, miss, noShinyPure, request));
                        if (DEBUG_MODE) {
                            writeRecord(aff, miss, noShinyPure, request, debugDir, json);
                        }
                        recordFiles.add(writeRecord(
                                aff, miss, noShinyPure, request, request.targetDir(), json));
                    }
                }
            }
            processedNum += requests.size();
        }
        return new FileResult(processedNum, Set.copyOf(recordFiles));
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
        recordInfo.put("circleDuration", simpleActions.getLast().timing() + 500);
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

    private record FileResult(int processedNum, Set<File> recordFiles) {
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
