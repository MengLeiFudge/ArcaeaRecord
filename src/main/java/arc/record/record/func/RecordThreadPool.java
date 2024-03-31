package arc.record.record.func;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import arc.record.aff.Aff;
import arc.record.aff.action.Action;
import arc.record.aff.note.Arc;
import arc.record.aff.note.ArcTap;
import arc.record.aff.note.Click;
import arc.record.aff.note.Hold;
import arc.record.aff.note.Note;
import arc.record.record.model.Request;
import arc.record.record.model.Resolution;
import arc.record.record.model.SimpleAction;
import arc.record.record.model.TouchIdManager;
import arc.record.utils.UnionFind;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.SerializationUtils;

import static arc.record.Settings.CLICK_TIME;
import static arc.record.Settings.DEBUG_MODE;
import static arc.record.Settings.EFFECT_TIME;
import static arc.record.Settings.FIRST_NOTE_TIME;
import static arc.record.Settings.TOUCH_SAMPLE_FREQUENCY;
import static arc.record.Utils.THREAD_NUM;
import static java.lang.Thread.sleep;

/**
 * 可生成脚本的线程池，调用 {@link #process} 以生成脚本.
 *
 * @author MengLeiFudge
 */
public class RecordThreadPool implements Runnable {
    private static class ConvertThreadFactory implements ThreadFactory {
        final ThreadGroup group;
        final AtomicInteger threadNumber = new AtomicInteger(1);
        final String namePrefix;

        ConvertThreadFactory() {
            group = Thread.currentThread().getThreadGroup();
            namePrefix = "thread-";
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(group, r, namePrefix + threadNumber.getAndIncrement(), 0);
            // 设置为非后台进程
            if (t.isDaemon()) {
                t.setDaemon(false);
            }
            // 设置为普通优先级
            if (t.getPriority() != Thread.NORM_PRIORITY) {
                t.setPriority(Thread.NORM_PRIORITY);
            }
            return t;
        }
    }

    public static void process(Map<File, Map<Integer, List<Request>>> processMap) {
        Map<File, Aff> affMap = new HashMap<>();
        for (var file : processMap.keySet()) {
            Aff aff = new Aff(file);
            affMap.put(file, aff);
        }
        process(affMap, processMap);
    }

    /**
     * 多线程生成脚本.
     *
     * @param processMap 处理需求
     */
    public static void process(Map<File, Aff> affMap, Map<File, Map<Integer, List<Request>>> processMap) {
        long startTime = System.currentTimeMillis();
        // 初始化数据，判断是否需要处理
        RecordThreadPool.affMap = affMap;
        RecordThreadPool.processMap = processMap;
        processFileList = processMap.keySet().stream().toList();
        processedNum = 0;
        int targetNum = 0;
        for (var map : processMap.values()) {
            for (var list : map.values()) {
                targetNum += list.size();
            }
        }
        if (targetNum == 0) {
            System.out.println("没有需要生成的脚本！");
            return;
        }
        // 建立线程池，开始多线程处理
        ExecutorService pool = new ThreadPoolExecutor(
                THREAD_NUM, THREAD_NUM,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                new ConvertThreadFactory());
        for (int i = 0; i < THREAD_NUM; i++) {
            pool.execute(new RecordThreadPool(i));
        }
        pool.shutdown();
        // 等待处理完成
        try {
            while (!pool.isTerminated()) {
                System.out.println("转换进度：" + df.format((double) processedNum / targetNum));
                sleep(1000);
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
            Thread.currentThread().interrupt();
        }
        // 处理完毕，输出处理时间
        System.out.println("转换进度：" + df.format((double) processedNum / targetNum));
        long endTime = System.currentTimeMillis() - startTime;
        String minuteStr = endTime >= 60000 ? " " + (int) (endTime / 60000) + " min" : "";
        endTime %= 60000;
        String secondStr = endTime >= 1000 ? " " + (int) (endTime / 1000) + " s" : "";
        endTime %= 1000;
        String milliStr = " " + endTime + " ms";
        System.out.println("处理完毕，用时" + minuteStr + secondStr + milliStr);
    }

    private final int threadNo;
    private static Map<File, Aff> affMap;
    private static Map<File, Map<Integer, List<Request>>> processMap;
    private static List<File> processFileList;
    private static int processedNum;
    private static final DecimalFormat df = new DecimalFormat("0.00%");

    private RecordThreadPool(int threadNo) {
        this.threadNo = threadNo;
    }

    /**
     * 某个线程处理某个谱面文件的所有脚本请求.
     * <p>
     * 步骤如下：
     * <ul>
     *     <li>长条与蛇判定重合时，去除长条（注意长条不能完全去除，需要保留一定头部）</li>
     *     <li>遍历该谱面文件的所有脚本请求，根据 miss/小p 的数目生成不同的按键列表</li>
     *     <li>创建并查集，将每个按键转为2个及以上的操作，并关联每个按键的所有操作</li>
     *     <li>将谱面中应该使用同一触控的蛇（指中途不能抬手的情形）进行关联</li>
     *     <li>将谱面中应该使用同一触控的单点（指天键或地键）与邻近蛇头进行关联</li>
     *     <li>将谱面中应该使用同一触控的长条尾与邻近蛇头进行关联</li>
     *     <li>根据是否需要镜像等设定，生成不同的脚本</li>
     * </ul>
     */
    @Override
    public void run() {
        if (DEBUG_MODE && processFileList.size() > 1) {
            System.out.println("DEBUG开启且谱面文件数目大于1！");
            return;
        }
        for (int i = 0; i < processFileList.size(); i++) {
            if (i % THREAD_NUM == threadNo) {
                File affFile = processFileList.get(i);
                Aff aff = affMap.get(affFile);
                Map<Integer, List<Request>> map = processMap.get(affFile);
                List<Note> baseNoteList = aff.getNoteList();
                // 非DEBUG：dir/testify_BYD_0L0小.record
                // DEBUG：dir/testify_BYD_test/base.txt
                //        dir/testify_BYD_test/ArcOnHold.txt
                //        dir/testify_BYD_test/0L0小/1_modifyMP.txt
                //        dir/testify_BYD_test/0L0小/2_mergeArcAndArc.txt
                //        dir/testify_BYD_test/0L0小/3_mergeClickAndArcStart.txt
                //        dir/testify_BYD_test/0L0小/4_mergeHoldEndAndArcStart.txt
                int x0 = map.keySet().toArray(new Integer[0])[0];
                Request request0 = map.get(x0).getFirst();
                File dir0 = new File(request0.targetDir(), "test/" + aff.getSongName() + "_" + aff.getDiffStr());
                saveNotes(baseNoteList, new File(dir0, "base.txt"));
                optimizeArcOnHold(aff, baseNoteList);
                saveNotes(baseNoteList, new File(dir0, "ArcOnHold.txt"));
                for (var x : map.keySet()) {
                    int miss = x / (aff.getNoteCount() + 1);
                    int noShinyPure = x % (aff.getNoteCount() + 1);
                    File dir1 = new File(dir0, miss + "L" + noShinyPure + "小");
                    List<Note> noteList = SerializationUtils.clone((ArrayList<Note>) baseNoteList);
                    modifyMP(noteList, miss, noShinyPure);
                    if (noteList.isEmpty()) {
                        // last eternity 的前三个难度是无需处理的空谱面，但是其他谱面空则需要提示异常
                        boolean isLastEternity012 = affFile.getParentFile().getName().equals("dl_lasteternity")
                                && Integer.parseInt(affFile.getName().substring(0, 1)) < 3;
                        if (!isLastEternity012) {
                            System.out.println("未在 " + affFile.getAbsolutePath() + " 内发现Note，需确认谱面文件状态！");
                        }
                    } else {
                        UnionFind<Action> actionUnionFind = new UnionFind<>();
                        for (var note : noteList) {
                            note.initActions(actionUnionFind);
                        }
                        if (DEBUG_MODE) {
                            saveActions(noteList, actionUnionFind, new File(dir1, "1_modifyMP.txt"));
                            System.out.println("2");
                        }
                        mergeArcAndArc(noteList, actionUnionFind);
                        if (DEBUG_MODE) {
                            saveActions(noteList, actionUnionFind, new File(dir1, "2_ArcArc.txt"));
                            System.out.println("3");
                        }
                        mergeClickAndArcStart(noteList, actionUnionFind);
                        if (DEBUG_MODE) {
                            saveActions(noteList, actionUnionFind, new File(dir1, "3_ClickArc.txt"));
                            System.out.println("4");
                        }
                        mergeHoldEndAndArcStart(noteList, actionUnionFind);
                        if (DEBUG_MODE) {
                            saveActions(noteList, actionUnionFind, new File(dir1, "4_HoldArc.txt"));
                        }
                        for (var request : map.get(x)) {
                            saveRecord(aff, noteList, actionUnionFind, miss, noShinyPure, request, dir1);
                        }
                    }
                    synchronized (RecordThreadPool.class) {
                        processedNum += map.get(x).size();
                    }
                }
            }
        }
    }

    private void saveNotes(List<Note> noteList, File file) {
        if (!DEBUG_MODE) {
            return;
        }
        List<String> lines = new ArrayList<>();
        for (var note : noteList) {
            lines.add(note.toString());
        }
        try {
            FileUtils.writeLines(file, lines);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private List<List<Action>> convertToActionList(List<Note> noteList, UnionFind<Action> actionUnionFind) {
        List<List<Action>> actionsList = new ArrayList<>();
        for (var note : noteList) {
            List<Action> noteActions = note.getActions();
            if (noteActions.isEmpty()) {
                continue;
            }
            boolean isRelated = false;
            for (var x : actionsList) {
                if (actionUnionFind.isRelated(x.get(0), noteActions.get(0))) {
                    x.addAll(noteActions);
                    isRelated = true;
                    break;
                }
            }
            if (!isRelated) {
                actionsList.add(noteActions);
            }
        }
        // 排序
        for (var relatedActions : actionsList) {
            Collections.sort(relatedActions);
        }
        actionsList.removeIf(List::isEmpty);
        actionsList.sort(Comparator.comparingInt(list -> list.get(0).t()));
        return actionsList;
    }

    private void saveActions(List<Note> noteList, UnionFind<Action> actionUnionFind, File file) {
        if (!DEBUG_MODE) {
            return;
        }
        // 原始list
        List<List<Action>> actionLists = convertToActionList(noteList, actionUnionFind);
        // 操作map
        Map<Action, Integer> map = new HashMap<>();
        for (var actionList : actionLists) {
            for (var action : actionList) {
                map.put(action, actionLists.indexOf(actionList));
            }
        }
        // 操作集合
        List<Action> actions = new ArrayList<>();
        for (var list : actionLists) {
            actions.addAll(list);
        }
        Collections.sort(actions);
        // 输出
        List<String> lines = new ArrayList<>();
        for (var action : actions) {
            lines.add(action.toString() /*+ " " + String.format("%4d", map.get(action))*/);
        }
        try {
            FileUtils.writeLines(file, lines);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 如果蛇在长条上方，则该长条不需要点击.
     * <p>
     * 如果检测到符合要求的键型，应将长条拆分为多个。
     * <p>
     * 显然一个 list 无法描述一个 hold 的多次抬起与按下的操作，所以需要创建新的 hold，且该处理要在连接 note 之前。
     * <p>
     * 假设 y<0.5 时，蛇判定可以替代长条。
     * 前提：对于任意的Arc（代码里面的Arc对象），一定是单调的。也就是说，一个Arc只有至多一段时间满足y<0.5。
     * 步骤如下：
     * 1.如果蛇的开始y、结束y都大于0.5，去掉
     * 2.对于指定的hold，遍历与其相关的arc
     * 3.根据蛇的【所有判定点位置】，找到x在长条范围内且y<0.5的时间段（至多有一个），暂存这个时间段
     * ps: 考虑到actionList生成的时候是使用beatTime/2作为间隔，找蛇的判定点间隔也用这个
     * 4.把所有的暂存时间段进行合并，并进行排序
     * eg: 蓝色在1-3s，红蛇在2-4s，则1-4s都应该去掉
     * 5.根据排好序的时间列表，拆分hold为多个新的hold
     * 【拆分是否要以长条判定点为基准，还是直接以拆分结果为基准？以拆分结果为基准。】
     *
     * @param noteList 要处理的按键列表
     */
    private void optimizeArcOnHold(Aff aff, List<Note> noteList) {
        List<Arc> arcs = new ArrayList<>(noteList.stream()
                .filter(o -> o instanceof Arc).map(o -> (Arc) o)
                .toList());
        // 移除触控点的y总是大于y一半的蛇
        double[] xy;
        List<Arc> arcsToBeRemoved = new ArrayList<>();
        for (var arc : arcs) {
            boolean yGreaterThanMiddle = true;
            for (float t = arc.getT1(); t < arc.getT2(); t += arc.getBeatTime() / TOUCH_SAMPLE_FREQUENCY) {
                xy = arc.getAffPoint((int) t);
                if (xy[1] < aff.getMiddleY((int) t)) {
                    yGreaterThanMiddle = false;
                    break;
                }
            }
            if (yGreaterThanMiddle) {
                arcsToBeRemoved.add(arc);
            }
        }
        arcs.removeAll(arcsToBeRemoved);
        List<Hold> holds = new ArrayList<>(noteList.stream()
                .filter(o -> o instanceof Hold).map(o -> (Hold) o)
                .toList());
        for (var hold : holds) {
            arcs.removeIf(arc -> arc.getT2() <= hold.getT1());
            // 找到所有时间可能符合要求的Arc
            List<Arc> candidateArcs = arcs.stream().filter(arc -> hold.getT2() > arc.getT1()).toList();
            if (candidateArcs.isEmpty()) {
                continue;
            }
            // 长条判定区
            double minX = hold.getLane() * 0.5 - 1;
            double maxX = hold.getLane() * 0.5 - 0.5;
            // 对于所有对该长条有影响的蛇，保存有影响的时间段；保存时，如果差距在50ms内，则合并两个时间段
            Map<Integer, Integer> timeMap = new HashMap<>();
            for (var arc : candidateArcs) {
                // 一个蛇至多有一段时间与长条有影响
                int startTime = -1;
                int endTime = -1;
                for (float t = arc.getT1(); t < arc.getT2(); t += arc.getBeatTime() / TOUCH_SAMPLE_FREQUENCY) {
                    xy = arc.getAffPoint((int) t);
                    if (xy[0] > minX && xy[0] < maxX && xy[1] < aff.getMiddleY((int) t)) {
                        if (startTime == -1) {
                            startTime = (int) t;
                        }
                        endTime = (int) t;
                    } else if (startTime != -1) {
                        endTime = (int) t;
                        break;
                    }
                    if (t + arc.getBeatTime() / TOUCH_SAMPLE_FREQUENCY >= arc.getT2()) {
                        xy = arc.getAffPoint(arc.getT2());
                        if (xy[0] > minX && xy[0] < maxX && xy[1] < aff.getMiddleY(arc.getT2())) {
                            if (startTime == -1) {
                                startTime = arc.getT2();
                            }
                            endTime = arc.getT2();
                        } else if (startTime != -1) {
                            endTime = arc.getT2();
                            break;
                        }
                    }
                }
                if (startTime >= endTime) {
                    continue;
                }
                // 找到这段时间后，将其添加至 timeMap
                // canMerge 为 true，表示这个新的时间段应该与现有的合并；false 表示该时间段是独立的
                boolean canMerge = false;
                for (var x : timeMap.entrySet()) {
                    if (x.getKey() < endTime + 50 && x.getValue() > startTime - 50) {
                        canMerge = true;
                        break;
                    }
                }
                if (!canMerge) {
                    timeMap.put(startTime, endTime);
                } else {
                    while (true) {
                        boolean flag = false;
                        for (var x : timeMap.entrySet()) {
                            if (startTime == x.getKey() && endTime == x.getValue()) {
                                continue;
                            }
                            if (x.getKey() < endTime + 50 && x.getValue() > startTime - 50) {
                                startTime = Math.min(x.getKey(), startTime);
                                endTime = Math.max(x.getValue(), endTime);
                                timeMap.remove(x.getKey());
                                flag = true;
                                break;
                            }
                        }
                        if (!flag) {
                            break;
                        }
                    }
                    timeMap.put(startTime, endTime);
                }
            }
            if (timeMap.isEmpty()) {
                continue;
            }
            // 如果时间段超出note范围，将其缩小到note时间范围
            List<Integer> startTimeList = new ArrayList<>(timeMap.keySet().stream().toList());
            Collections.sort(startTimeList);
            for (var x : startTimeList) {
                int key = Math.max(hold.getT1(), x);
                int value = Math.min(hold.getT2(), timeMap.get(x));
                timeMap.remove(x);
                if (key != value) {
                    timeMap.put(key, value);
                }
            }
            if (timeMap.isEmpty()) {
                continue;
            }
            // 根据timeMap的时间段，将hold处理为多个新的hold
            startTimeList = new ArrayList<>(timeMap.keySet().stream().toList());
            Collections.sort(startTimeList);
            List<Hold> newHolds = new ArrayList<>();
            int firstStartTime = startTimeList.get(0);
            Hold newHoldStart = SerializationUtils.clone(hold);
            newHoldStart.setT2(Math.max(hold.getT1() + CLICK_TIME, firstStartTime));
            newHolds.add(newHoldStart);
            for (int i = 0; i < startTimeList.size() - 1; i++) {
                Hold newHoldMiddle = SerializationUtils.clone(hold);
                newHoldMiddle.setT1(timeMap.get(startTimeList.get(i)));
                newHoldMiddle.setT2(startTimeList.get(i + 1));
                newHolds.add(newHoldMiddle);
            }
            int lastEndTime = timeMap.get(startTimeList.get(startTimeList.size() - 1));
            if (hold.getT2() - lastEndTime > 50) {
                Hold newHoldEnd = SerializationUtils.clone(hold);
                newHoldEnd.setT1(lastEndTime);
                newHolds.add(newHoldEnd);
            }
            noteList.remove(hold);
            noteList.addAll(newHolds);
        }
        Collections.sort(noteList);
    }

    /**
     * 按照指定的 miss、小p 处理传入的按键列表.
     *
     * @param noteList    被处理的按键列表
     * @param miss        目标 miss 数
     * @param noShinyPure 目标 小p 数
     */
    private void modifyMP(List<Note> noteList, final int miss, int noShinyPure) {
        // 获取 noteList 中所有的地键和天键，只有单点会被修改
        List<Note> clicks = noteList.stream().filter(o -> o instanceof Click || o instanceof ArcTap).toList();
        // 可转为 miss/小p 的条件：其余所有按键都与该键在时间、位置上有一定距离
        // 如果纵连中间移除掉一个，可能导致后面的键判定于被移除的键，所以有这样的要求
        // 下面这个存储所有可以修改的click
        List<Note> canModifyClicks = new ArrayList<>();
        // 遍历，寻找所有可以修改的click
        for (Note c1 : clicks) {
            boolean canBeModified = true;
            for (Note c2 : clicks) {
                if (c1 == c2) {
                    continue;
                }
                if (Math.abs(c1.getT1() - c2.getT1()) <= EFFECT_TIME) {
                    // 如果时间接近，需要判断距离是否接近（模拟器上的距离小于模拟器宽的 0.09375）
                    double[] xy1 = c1.getAffPoint();
                    double[] xy2 = c2.getAffPoint();
                    int[] XY1 = Resolution.R16_9_1280_720.convertToXY(xy1[0], xy1[1], 0);
                    int[] XY2 = Resolution.R16_9_1280_720.convertToXY(xy2[0], xy2[1], 0);
                    double dis = Math.sqrt(Math.pow(XY1[0] - XY2[0], 2) + Math.pow(XY1[1] - XY2[1], 2));
                    double judgeRange = Resolution.R16_9_1280_720.getMaxX() * 0.09375;
                    if (dis <= judgeRange) {
                        // 时间、距离都接近，则该键不能被修改
                        canBeModified = false;
                        break;
                    }
                }
            }
            if (canBeModified) {
                canModifyClicks.add(c1);
            }
        }
        if (canModifyClicks.isEmpty()) {
            return;
        }
        // 暂存将会转为 miss 的 note
        List<Note> missClicks = new ArrayList<>();
        // 暂存将会转为 小p 的 note
        List<Note> noShinyPureClicks = new ArrayList<>();
        // 由于偏移也不一定小p（模拟器原因，执行时间有偏差），这里适当增加小p数
        noShinyPure *= 1.2;
        int startTime = canModifyClicks.getFirst().getT1();
        int endTime = canModifyClicks.getLast().getT1();
        int fullTimeSpan = endTime - startTime;
        // 本次循环的目标 miss 数
        int targetMissNum = miss;
        // 循环，直至均分时的 miss 数达标
        while (true) {
            missClicks.clear();
            noShinyPureClicks.clear();
            double noteTimeSpan = fullTimeSpan * 1.0 / Math.max(1, targetMissNum);
            // 循环，直至所有 note 都已分配至 miss
            for (Note c1 : canModifyClicks) {
                // 计算出此时应该至少有多少 miss 的 note
                int currNeedMissNum = (int) ((c1.getT1() - startTime) / noteTimeSpan) + 1;
                if (missClicks.size() < currNeedMissNum && missClicks.size() < miss) {
                    missClicks.add(c1);
                } else if (noShinyPureClicks.size() < noShinyPure) {
                    noShinyPureClicks.add(c1);
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
        // 处理 miss
        noteList.removeAll(missClicks);
        // 处理 小p
        // 指示下一个变为 小p 的按键应该变为 early 还是 late
        boolean nextToEarly = true;
        for (var note : noShinyPureClicks) {
            note.setT1(nextToEarly ? note.getT1() - 30 : note.getT1() + 30);
            note.setT2(note.getT1() + CLICK_TIME);
            nextToEarly = !nextToEarly;
        }
        Collections.sort(noteList);
    }

    /**
     * 存储所有代表蛇头的 arc，便于其他方法使用.
     */
    private List<Arc> arcStarts;

    /**
     * 连接同色蛇/碎蛇.
     * <p>
     * 不考虑异色蛇的连接；不考虑同时出现同色蛇的情况。
     * <p>
     * 步骤如下：
     * 1.按照颜色给蛇分类。不需要排序，因为noteList本身就是已经排序的
     * 2.如果前者蛇尾距离后者蛇头时间差值（使用绝对值）小于 100 ms，则连接这两个蛇
     *
     * @param noteList 要处理的按键列表
     */
    private void mergeArcAndArc(List<Note> noteList, UnionFind<Action> actionUnionFind) {
        Map<Integer, List<Arc>> arcsMap = new HashMap<>();
        noteList.stream()
                .filter(o -> o instanceof Arc).map(o -> (Arc) o)
                .forEachOrdered(arc -> {
                    if (arcsMap.containsKey(arc.getColor())) {
                        arcsMap.get(arc.getColor()).add(arc);
                    } else {
                        List<Arc> list = new ArrayList<>();
                        list.add(arc);
                        arcsMap.put(arc.getColor(), list);
                    }
                });
        arcStarts = new ArrayList<>();
        for (List<Arc> arcs : arcsMap.values()) {
            boolean lastArcMerged = false;
            for (int i = 0; i < arcs.size() - 1; i++) {
                Arc arc1 = arcs.get(i);
                Arc arc2 = arcs.get(i + 1);
                if (!lastArcMerged) {
                    arcStarts.add(arc1);
                }
                if (arc2.getT1() - arc1.getT2() < 50) {
                    Note.mergeNotes(arc1, arc2, actionUnionFind);
                    if (DEBUG_MODE) {
                        System.out.println(arc1 + " + " + arc2);
                    }
                    //System.out.println(arc1.getColor() + " " + arc1.getT1() + "-"
                    // + arc1.getT2() + ", " + arc2.getT1() + "-" + arc2.getT2());
                    lastArcMerged = true;
                } else {
                    lastArcMerged = false;
                }
            }
            if (!lastArcMerged) {
                arcStarts.add(arcs.get(arcs.size() - 1));
            }
        }
        Collections.sort(arcStarts);
       /* for (var arc : arcStarts) {
            System.out.println(arc.getColor() + " " + arc.getT1() + "-" + arc.getT2());
        }*/
    }

    /**
     * 连接天键与蛇头.
     * <p>
     * 如果要连接某个天键与蛇头，那么蛇的 hasHead 必须为 false；
     * 但天键与蛇较近且的 hasHead 必须为 false，并不能说明二者一定要连接。
     * <p>
     * 有可能出现一个键对应多个蛇的情况，merge 操作必须要注意。
     * eg.天键a后面跟了红蛇和蓝蛇，假如先merge了天键和红蛇，这个天键就不能merge蓝蛇，否则红蓝蛇将使用同一个触控点。
     * <p>
     * 还有可能出现天键在蛇头之后。该情况由 Note.mergeNotes() 处理对应 action。
     * <p>
     * hasHead = false，如果这个是个碎蛇咋办？蛇+蛇 与 单点+蛇头，这两个哪个先执行？逻辑需要如何调整？
     * eg. — — |— — 操作是什么样的？
     * 第一种：蛇按住，单点点一下松开
     * 第二种：蛇在第二个结尾松开，单点再点下去
     * 第三种：一个触控点一直按蛇不松，后一个触控点点击天键后，后续与蛇一致
     * yyq：采用第一种。游戏机制：在按住蛇的情况下，无论怎么点周围都不会断蛇。
     * 使用第一种操作方式情况下，应该先关联 蛇+蛇，再关联 单点+蛇头。
     * <p>
     * 特别声明：该方法作者yyq，有问题找他！
     *
     * @param noteList 要处理的按键列表
     */
    private void mergeClickAndArcStart(List<Note> noteList, UnionFind<Action> actionUnionFind) {
        // 天键与地键判定机区域不同
        List<Note> clicks = new ArrayList<>(noteList);
        clicks.removeIf(o -> !(o instanceof Click) && !(o instanceof ArcTap));
        List<Arc> arcsToBeRemoved = new ArrayList<>();
        for (var arc : arcStarts) {
            clicks.removeIf(o -> o.getT1() < arc.getT1() - CLICK_TIME);
            // 找到所有时间符合要求的单点
            List<Note> candidateClicks = new ArrayList<>(clicks.stream()
                    .filter(o -> o.getT1() <= arc.getT1() + CLICK_TIME).toList());
            if (candidateClicks.isEmpty()) {
                continue;
            }
            // 按照距离最近排序
            candidateClicks.sort((o1, o2) -> {
                double[] xy1 = o1.getAffPoint();
                double dis1 = Math.sqrt(Math.pow(xy1[0] - arc.getX1(), 2) + Math.pow(xy1[1] - arc.getY1(), 2));
                double[] xy2 = o2.getAffPoint();
                double dis2 = Math.sqrt(Math.pow(xy2[0] - arc.getX1(), 2) + Math.pow(xy2[1] - arc.getY1(), 2));
                return Double.compare(dis1, dis2);
            });
            Note bestChoice = candidateClicks.getFirst();
            double[] xy = bestChoice.getAffPoint();
            double minDis = Math.sqrt(Math.pow(xy[0] - arc.getX1(), 2) + Math.pow(xy[1] - arc.getY1(), 2));
            // todo: 改为用模拟器宽，区分天键和地键
            if (minDis > 0.3/*Resolution.R16_9_1280_720.getMaxX() * 0.09375*/) {
                continue;
            }
            Note.mergeNotes(bestChoice, arc, actionUnionFind);
            if (DEBUG_MODE) {
                System.out.println(bestChoice + " + " + arc);
            }
            clicks.remove(bestChoice);
            arcsToBeRemoved.add(arc);
        }
        arcStarts.removeAll(arcsToBeRemoved);
    }

    /**
     * 连接长条尾与蛇头.
     * <p>
     * 时间（100ms内）、距离在一定范围内，直接merge就行。
     * <p>
     * 蛇与长条重合的修改在此方法之前，所以此时不会出现长条跟蛇有重合的情况。
     *
     * @param noteList 要处理的按键列表
     */
    private void mergeHoldEndAndArcStart(List<Note> noteList, UnionFind<Action> actionUnionFind) {
        List<Hold> holds = new ArrayList<>(noteList.stream()
                .filter(o -> o instanceof Hold).map(o -> (Hold) o)
                .toList());
        List<Arc> arcsToBeRemoved = new ArrayList<>();
        for (var arc : arcStarts) {
            holds.removeIf(hold -> hold.getT2() < arc.getT1() - CLICK_TIME);
            // 找到所有时间符合要求的长条
            List<Hold> candidateHolds = new ArrayList<>(holds.stream()
                    .filter(hold -> hold.getT2() <= arc.getT1() + CLICK_TIME).toList());
            if (candidateHolds.isEmpty()) {
                continue;
            }
            // 按照距离最近排序
            candidateHolds.sort((o1, o2) -> {
                double[] xy1 = o1.getAffPoint();
                double dis1 = Math.sqrt(Math.pow(xy1[0] - arc.getX1(), 2) + Math.pow(xy1[1] - arc.getY1(), 2));
                double[] xy2 = o2.getAffPoint();
                double dis2 = Math.sqrt(Math.pow(xy2[0] - arc.getX1(), 2) + Math.pow(xy2[1] - arc.getY1(), 2));
                return Double.compare(dis1, dis2);
            });
            Hold bestChoice = candidateHolds.getFirst();
            double[] xy = bestChoice.getAffPoint();
            double minDis = Math.sqrt(Math.pow(xy[0] - arc.getX1(), 2) + Math.pow(xy[1] - arc.getY1(), 2));
            // todo: 改为用模拟器宽
            if (minDis > 0.3/*Resolution.R16_9_1280_720.getMaxX() * 0.09375*/) {
                continue;
            }
            Note.mergeNotes(bestChoice, arc, actionUnionFind);
            if (DEBUG_MODE) {
                System.out.println(bestChoice + " + " + arc);
            }
            holds.remove(bestChoice);
            arcsToBeRemoved.add(arc);
        }
        arcStarts.removeAll(arcsToBeRemoved);
    }

    /**
     * 根据需求生成脚本.
     *
     * @param aff             要生成脚本的谱面文件
     * @param noteList        处理后的最终按键列表
     * @param actionUnionFind 操作并查集，记录action的关联
     * @param miss            miss个数
     * @param noShinyPure     小p个数
     * @param request         脚本生成需求
     * @param dir1            如果为调试模式，该目录为调试脚本保存路径
     */
    private void saveRecord(Aff aff, List<Note> noteList, UnionFind<Action> actionUnionFind,
                            int miss, int noShinyPure, Request request, File dir1) {
        // 构建谱面操作列表，有关联的操作会放在同一个 list 中
        List<List<Action>> actionsList = convertToActionList(noteList, actionUnionFind);
        // 构建实际操作列表
        TouchIdManager idManager = new TouchIdManager();
        List<SimpleAction> simpleActions = new ArrayList<>();
        for (var relatedActions : actionsList) {
            int beginTime = relatedActions.getFirst().t();
            Action endAction = relatedActions.getLast();
            int endTime = endAction.t();
            int id = idManager.getId(beginTime, endTime);
            for (var action : relatedActions) {
                int[] XY = DEBUG_MODE
                        ? new int[]{(int) (action.x() * 100), (int) (action.y() * 100)}
                        : request.resolution().convertToXY(action.x(), action.y(), aff.getRatio46k(action.t()));
                simpleActions.add(new SimpleAction(action.t() + FIRST_NOTE_TIME + CLICK_TIME, id, XY[0], XY[1], action != endAction));
            }
        }
        // 排序
        Collections.sort(simpleActions);
        // 根据要求生成脚本
        JSONArray operationsArray = new JSONArray();
        // 写入暂停、继续操作
        List<SimpleAction> preSimpleActions = request.resolution().getPreSimpleActions();
        for (int i = 0; i < preSimpleActions.size(); i++) {
            int timing = preSimpleActions.get(i).timing();
            JSONArray points = new JSONArray();
            for (int j = i; j < preSimpleActions.size(); j++) {
                SimpleAction o1 = preSimpleActions.get(j);
                if (o1.timing() == timing) {
                    add(points, o1, false, request.resolution());
                    i++;
                } else {
                    break;
                }
            }
            i--;
            JSONObject obj = new JSONObject();
            obj.put("timing", timing);
            obj.put("operationId", "PutMultiTouch");
            obj.put("points", points);
            operationsArray.add(obj);
        }
        // 写入已经处理好的脚本操作
        for (int i = 0; i < simpleActions.size(); i++) {
            int timing = simpleActions.get(i).timing();
            JSONArray points = new JSONArray();
            for (int j = i; j < simpleActions.size(); j++) {
                SimpleAction o1 = simpleActions.get(j);
                if (o1.timing() == timing) {
                    add(points, o1, request.mirror(), request.resolution());
                    i++;
                } else {
                    break;
                }
            }
            i--;
            JSONObject obj = new JSONObject();
            obj.put("timing", timing);
            obj.put("operationId", "PutMultiTouch");
            obj.put("points", points);
            operationsArray.add(obj);
        }
        JSONObject recordInfo = new JSONObject();
        recordInfo.put("loopType", 0);
        recordInfo.put("loopTimes", 1);
        // 获取按键最大时间
        int circleDuration = simpleActions.get(simpleActions.size() - 1).timing() + 500;
        recordInfo.put("circleDuration", circleDuration);
        recordInfo.put("loopInterval", 0);
        recordInfo.put("loopDuration", 0);
        recordInfo.put("accelerateTimes", 1);
        recordInfo.put("recordName", "");
        String s = aff.getDiffStr() +
                (request.mirror() ? "_镜像_" : "_原版_") +
                miss + "L" + noShinyPure + "小";
        recordInfo.put("createTime", s);
        recordInfo.put("playOnBoot", false);
        recordInfo.put("rebootTiming", 0);
        JSONObject obj = new JSONObject();
        obj.put("operations", operationsArray);
        obj.put("recordInfo", recordInfo);
        // 格式化字符串，并保存至文件
        String formatStr = obj.toString(JSONWriter.Feature.PrettyFormat);
        File targetDir = DEBUG_MODE ? dir1 : request.targetDir();
        File recordFile = new File(targetDir, aff.getSongName() + "_" + s + ".record");
        try {
            FileUtils.write(recordFile, formatStr, StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void add(JSONArray points, SimpleAction simpleAction, boolean mirror, Resolution resolution) {
        JSONObject obj = new JSONObject();
        obj.put("id", simpleAction.id());
        obj.put("x", mirror ? resolution.getMaxX() - simpleAction.x() : simpleAction.x());
        obj.put("y", simpleAction.y());
        obj.put("state", simpleAction.getState());
        points.add(obj);
    }
}
