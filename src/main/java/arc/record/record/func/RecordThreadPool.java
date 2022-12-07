package arc.record.record.func;

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

import static arc.record.SettingsAndUtils.CLICK_TIME;
import static arc.record.SettingsAndUtils.EFFECT_TIME;
import static arc.record.SettingsAndUtils.THREAD_NUM;
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

    /**
     * 多线程生成脚本.
     *
     * @param processMap 处理需求
     */
    public static synchronized void process(Map<File, Map<Integer, List<Request>>> processMap) {
        long startTime = System.currentTimeMillis();
        // 初始化数据，判断是否需要处理
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
        System.out.println("已完成 " + df.format((double) processedNum / targetNum));
        long endTime = System.currentTimeMillis() - startTime;
        String minuteStr = endTime >= 60000 ? " " + (int) (endTime / 60000) + " min" : "";
        endTime %= 60000;
        String secondStr = endTime >= 1000 ? " " + (int) (endTime / 1000) + " s" : "";
        endTime %= 1000;
        String milliStr = " " + endTime + " ms";
        System.out.println("处理完毕，用时" + minuteStr + secondStr + milliStr);
    }

    private final int threadNo;
    private static Map<File, Map<Integer, List<Request>>> processMap;
    private static List<File> processFileList;
    private static int processedNum;
    private static final DecimalFormat df = new DecimalFormat("0.00%");

    private RecordThreadPool(int threadNo) {
        this.threadNo = threadNo;
    }

    /**
     * // 1.修改note列表，例如长条与蛇交互的处理、miss/小p的数目
     * // 2.构建一个ActionManager对象，初始化所有note
     * // 3.使用Note.mergeNotes(Note a, Note b)合并两个note，
     * // 需要处理各个note的list -> note a的保留，b的改为空，全部加到a中，然后排序？
     * // 【在note中加一个前继note指向？】
     * // 并在ActionManager中合并这两个list -> 调用并查集的merge
     * // 【合并两个note，一方面是操作上的增删，另一方面是将所有操作设为同一id】
     * // 4.对每组合并的note分配一个id，作为触控id
     * // 5.从aff.getRatio46k获取46k比例，从处理要求获取分辨率等，计算出触控实际位置，构建List<SimpleAction>
     * // 6.利用List<SimpleAction>生成最终脚本
     */
    @Override
    public void run() {
        for (int i = 0; i < processFileList.size(); i++) {
            if (i % THREAD_NUM == threadNo) {
                File affFile = processFileList.get(i);
                Aff aff = new Aff(affFile);
                Map<Integer, List<Request>> map = processMap.get(affFile);
                List<Note> baseNoteList = aff.getNoteList();
                optimizeArcOnHold(baseNoteList);
                for (var x : map.keySet()) {
                    int miss = x / (aff.getNoteCount() + 1);
                    int noShinyPure = x % (aff.getNoteCount() + 1);
                    List<Note> noteList = SerializationUtils.clone((ArrayList<Note>) baseNoteList);
                    modifyMP(noteList, miss, noShinyPure);
                    UnionFind<Action> manager = new UnionFind<>();
                    for (var note : noteList) {
                        note.initActions(manager);
                    }
                    mergeArcAndArc(noteList, manager);
                    mergeClickAndArc(noteList, manager);
                    mergeArcAndHold(noteList, manager);
                    for (var p : map.get(x)) {
                        saveRecord(noteList, p);
                    }
                    synchronized (RecordThreadPool.class) {
                        processedNum += map.size();
                    }
                }
            }
        }
    }

    /**
     * 如果蛇在长条上方，则该长条不需要点击.
     * <p>
     * 如果检测到符合要求的键型，应将长条拆分为多个。
     * <p>
     * 显然一个 list 无法描述一个 hold 的多次抬起与按下的操作，所以需要创建新的 hold，且该处理要在连接 note 之前。
     *
     * @param noteList 要处理的按键列表
     */
    private void optimizeArcOnHold(List<Note> noteList) {
        // 筛选出所有 hold
        List<Hold> holds = new ArrayList<>();
        noteList.stream()
                .filter(o -> o instanceof Hold).map(o -> (Hold) o)
                .forEachOrdered(holds::add);
        for (var hold : holds) {
            // 筛选出所有与该 hold 时间段有关的蛇
            List<Arc> arcs = new ArrayList<>();
            noteList.stream()
                    .filter(o -> o instanceof Arc arc
                            && arc.getT2() > hold.getT1()
                            && arc.getT1() < hold.getT2()).map(o -> (Arc) o)
                    .forEachOrdered(arcs::add);
            for (var arc : arcs) {
                // todo: 如何处理
            }
        }
    }

    /**
     * 按照指定的 miss、小p 处理传入的按键列表.
     *
     * @param noteList    被处理的按键列表
     * @param miss        目标 miss 数
     * @param noShinyPure 目标 小p 数
     */
    private void modifyMP(List<Note> noteList, int miss, int noShinyPure) {
        // 由于偏移也不一定小p（模拟器原因，执行时间有偏差），这里适当增加小p数
        // noShinyPure *= 1.8;
        // 获取 noteList 中所有的地键和天键，只有单点会被修改
        List<Note> clicks = noteList.stream().filter(n -> n instanceof Click || n instanceof ArcTap).toList();
        // 暂存将会转为 miss 的 note
        List<Note> missClicks = new ArrayList<>();
        // 暂存将会转为 小p 的 note
        List<Note> noShinyPureClicks = new ArrayList<>();
        // 可转为 miss/小p 的条件：其余所有按键都与该键在时间、位置上有一定距离
        // 如果纵连中间移除掉一个，可能导致后面的键判定于被移除的键，所以有这样的要求
        for (Note c1 : clicks) {
            if (missClicks.size() == miss && noShinyPureClicks.size() == noShinyPure) {
                break;
            }
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
                if (missClicks.size() < miss) {
                    missClicks.add(c1);
                } else if (noShinyPureClicks.size() < noShinyPure) {
                    noShinyPureClicks.add(c1);
                }
            }
        }
        // 处理 miss
        noteList.removeAll(missClicks);
        // 处理 小p
        // 指示下一个变为 小p 的按键应该变为 early 还是 late
        boolean nextToEarly = true;
        for (var note : noShinyPureClicks) {
            note.setT1(nextToEarly ? note.getT1() - 37 : note.getT1() + 37);
            note.setT2(note.getT1() + CLICK_TIME);
            nextToEarly = !nextToEarly;
        }
        Collections.sort(noteList);
    }

    /**
     * 连接同色蛇/碎蛇，连接天键与蛇，连接蛇与长条.
     *
     * @param noteList 要处理的按键列表
     */
    private void mergeArcAndArc(List<Note> noteList, UnionFind<Action> manager) {
        // 时间差小于
        Map<Integer, List<Arc>> arcs = new HashMap<>();
        noteList.stream()
                .filter(o -> o instanceof Arc).map(o -> (Arc) o)
                .forEachOrdered(arc -> {
                    if (arcs.containsKey(arc.getColor())) {
                        arcs.get(arc.getColor()).add(arc);
                    } else {
                        List<Arc> list = new ArrayList<>();
                        list.add(arc);
                        arcs.put(arc.getColor(), list);
                    }
                });
        for (List<Arc> arcList : arcs.values()) {
            Arc startArc = null;
            for (Arc arc : arcList) {
                // 这个判断条件还要改
                if (arc.isHasHead()) {
                    startArc = arc;
                } else {
                    Note.mergeNotes(startArc, arc, manager);
                }
            }
        }
    }

    /**
     * 连接天键与蛇.
     *
     * @param noteList 要处理的按键列表
     */
    private void mergeClickAndArc(List<Note> noteList, UnionFind<Action> manager) {
        // 只有!hasEnd的蛇才能与click/arctap合并
    }

    /**
     * 连接蛇与长条.
     *
     * @param noteList 要处理的按键列表
     */
    private void mergeArcAndHold(List<Note> noteList, UnionFind<Action> manager) {
        List<Arc> arcs = new ArrayList<>();
        noteList.stream()
                .filter(o -> o instanceof Arc arc && !arc.isHasHead()).map(o -> (Arc) o)
                .forEachOrdered(arcs::add);
        List<Hold> holds = new ArrayList<>();
        noteList.stream()
                .filter(o -> o instanceof Hold).map(o -> (Hold) o)
                .forEachOrdered(holds::add);
        for (List<Arc> arcList : arcs.values()) {
            Arc startArc = null;
            for (Arc arc : arcList) {
                // 这个判断条件还要改
                if (arc.isHasHead()) {
                    startArc = arc;
                } else {
                    Note.mergeNotes(startArc, arc, manager);
                }
            }
        }
    }


    private static void saveRecord(Aff aff, List<Note> noteList, UnionFind<Action> manager, Request p) {
        // 构建谱面操作列表，有关联的操作会放在同一个 list 中
        List<List<Action>> actionsList = new ArrayList<>();
        for (var note : noteList) {
            List<Action> noteActions = note.getActions();
            boolean isRelated = false;
            for (var x : actionsList) {
                if (manager.isRelated(x, noteActions)) {
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
        actionsList.sort(Comparator.comparingInt(list -> list.get(0).t()));
        // 构建实际操作列表
        TouchIdManager idManager = new TouchIdManager();
        List<SimpleAction> simpleActions = new ArrayList<>();
        for (var relatedActions : actionsList) {
            int beginTime = relatedActions.get(0).t();
            Action endAction = relatedActions.get(relatedActions.size() - 1);
            int endTime = endAction.t();
            int id = idManager.getId(beginTime, endTime);
            for (var action : relatedActions) {
                int[] XY = p.resolution().convertToXY(action.x(), action.y(), aff.getRatio46k(action.t()));
                simpleActions.add(new SimpleAction(action.t(), id, XY[0], XY[1], action != endAction));
            }
        }
        // 排序
        Collections.sort(simpleActions);
        // 根据要求生成脚本
        JSONArray operationsArray = new JSONArray();
        // 写入暂停、继续操作
        List<SimpleAction> preSimpleActions = p.resolution().getPreSimpleActions();
        for (int i = 0; i < preSimpleActions.size(); i++) {
            int timing = preSimpleActions.get(i).timing();
            JSONArray points = new JSONArray();
            for (int j = i; j < preSimpleActions.size(); j++) {
                SimpleAction o1 = preSimpleActions.get(j);
                if (o1.timing() == timing) {
                    add(points, o1, false, p.resolution());
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
                    add(points, o1, p.mirror(), p.resolution());
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
        recordInfo.put("circleDuration", recordEndTime);
        recordInfo.put("loopInterval", 0);
        recordInfo.put("loopDuration", 0);
        recordInfo.put("accelerateTimes", 1);
        recordInfo.put("recordName", "");
        String s = difficult +
                (p.mirror() ? "_镜像_" : "_原版_") +
                miss + "L" + minPure + "小";
        recordInfo.put("createTime", s);
        recordInfo.put("playOnBoot", false);
        recordInfo.put("rebootTiming", 0);
        JSONObject obj = new JSONObject();
        obj.put("operations", operationsArray);
        obj.put("recordInfo", recordInfo);
        // 格式化字符串，并保存至文件
        String formatStr = obj.toString(JSONWriter.Feature.PrettyFormat);// 80%时间
        p.targetDir().mkdirs();
        File recordFile = new File(p.targetDir(), song + "_" + s + ".record");
        try {
            FileUtils.write(recordFile, formatStr, StandardCharsets.UTF_8);// 10%时间
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
