package arc.record.record.base;

import arc.record.aff.Aff;
import arc.record.aff.note.Arc;
import arc.record.aff.note.ArcTap;
import arc.record.aff.note.Click;
import arc.record.aff.note.Note;
import arc.record.record.data.SimpleAction;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.apache.commons.lang3.SerializationUtils;

import java.io.File;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
    public static synchronized void process(Map<File, List<BaseProcess>> processMap) {
        long startTime = System.currentTimeMillis();
        // 初始化数据，判断是否需要处理
        RecordThreadPool.processMap = processMap;
        processFileList = processMap.keySet().stream().toList();
        processedNum = 0;
        int targetNum = 0;
        for (var list : processMap.values()) {
            targetNum += list.size();
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
    private static Map<File, List<BaseProcess>> processMap;
    private static List<File> processFileList;
    private static int processedNum;
    private static final DecimalFormat df = new DecimalFormat("0.00%");

    private RecordThreadPool(int threadNo) {
        this.threadNo = threadNo;
    }

    @Override
    public void run() {
        for (int i = 0; i < processFileList.size(); i++) {
            if (i % THREAD_NUM == threadNo) {
                File affFile = processFileList.get(i);
                Aff aff = new Aff(affFile);
                List<BaseProcess> processList = processMap.get(affFile);
                // 预处理，如长条与蛇代替判定、蛇头的天键，长条后面接蛇，碎蛇，蛇中间加单点...等等
                ArrayList<Note> baseNoteList = (ArrayList<Note>) aff.getNoteList();
                preProcess(baseNoteList);
                // 根据 miss、小p 的数目，将处理要求分组
                Map<Integer, List<BaseProcess>> map = new ConcurrentHashMap<>();
                for (var p : processList) {
                    int key = p.miss() * (aff.getNoteCount() + 1) + p.minPure();
                    if (map.containsKey(key)) {
                        map.get(key).add(p);
                    } else {
                        List<BaseProcess> list = new ArrayList<>();
                        list.add(p);
                        map.put(key, list);
                    }
                }
                // 每组都应有唯一的 noteList
                for (var x : map.keySet()) {
                    int miss = map.get(x).get(0).miss();
                    int minPure = map.get(x).get(0).minPure();
                    ArrayList<Note> noteList = SerializationUtils.clone(baseNoteList);
                    // 通过 miss、小p 构建唯一的 noteList
                    modifyMissAndMinPure(noteList, miss, minPure);
                    // 遍历要求，生成脚本
                    for (var p : map.get(x)) {
                        saveRecord(noteList, p);
                    }
                }
                synchronized (this) {
                    processedNum += processList.size();
                }
            }
        }
    }

    /**
     * 预处理按键列表.
     * <p>
     * 需要处理如下特殊键型：
     * <ul>
     *     <li>碎蛇 -> 改为连续的蛇</li>
     *     <li>位于蛇头的天键 -> 改为去掉天键，蛇头移动到天键</li>
     *     <li>长条后面接蛇 -> 长条改为蛇，与后面蛇连接</li>
     *     <li>蛇中间有单点 -> 好像并不需要额外处理</li>
     *     <li>蛇位于长条上方 -> 这一段长条不需要按</li>
     * </ul>
     */
    static class PreProcess {
        public static void connectArc(List<Note> noteList) {
            Map<Integer, List<Arc>> arcs = new HashMap<>();
            noteList.stream()
                    .filter(n -> n instanceof Arc)
                    .map(n -> (Arc) n)
                    .forEachOrdered(n -> {
                        if (arcs.containsKey(n.getColor())) {
                            arcs.get(n.getColor()).add(n);
                        } else {
                            List<Arc> list = new ArrayList<>();
                            list.add(n);
                            arcs.put(n.getColor(), list);
                        }
                    });
            //TODO:连接碎蛇
        }

        public static void headClick(List<Note> noteList) {
            //TODO:同上
        }

        public static void arcAfterHold(List<Note> noteList) {
            //TODO:同上
        }

        public static void arcOnHold(List<Note> noteList) {
            //TODO:不同上
        }
    }

    /**
     * @param noteList 要处理的按键列表
     */
    private static void preProcess(List<Note> noteList) {

    }

    private static void modifyMissAndMinPure(List<Note> noteList, int miss, int minPure) {

    }

    private static List<JSONObject> getNoteOperationList(List<Note> noteList) {
        /*for (int i = 0; i < simpleActions.size(); i++) {
            int timing = simpleActions.get(i).getTiming();
            JSONArray points = new JSONArray();
            for (int j = i; j < simpleActions.size(); j++) {
                SimpleAction o1 = simpleActions.get(j);
                if (o1.getTiming() == timing) {
                    add(points, o1, mirror);
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
        }*/
        return new ArrayList<>();
    }

    private static void saveRecord(List<Note> noteList, BaseProcess p) {
        /*JSONArray operationsArray = new JSONArray();
        // 写入 PreSimpleActions，即暂停、继续那些操作
        if (haveBegin) {
            List<SimpleAction> preSimpleActions = resolution.getPreSimpleActions();
            for (int i = 0; i < preSimpleActions.size(); i++) {
                int timing = preSimpleActions.get(i).getTiming();
                JSONArray points = new JSONArray();
                for (int j = i; j < preSimpleActions.size(); j++) {
                    SimpleAction o1 = preSimpleActions.get(j);
                    if (o1.getTiming() == timing) {
                        add(points, o1, false);
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
        }
        // 写入已经处理好的脚本操作
        for (int i = 0; i < simpleActions.size(); i++) {
            int timing = simpleActions.get(i).getTiming();
            JSONArray points = new JSONArray();
            for (int j = i; j < simpleActions.size(); j++) {
                SimpleAction o1 = simpleActions.get(j);
                if (o1.getTiming() == timing) {
                    add(points, o1, mirror);
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
                (haveBegin ? "" : "_首键") +
                (mirror ? "_镜像_" : "_原版_") +
                miss + "L" + minPure + "小";
        recordInfo.put("createTime", s);
        recordInfo.put("playOnBoot", false);
        recordInfo.put("rebootTiming", 0);
        JSONObject obj = new JSONObject();
        obj.put("operations", operationsArray);
        obj.put("recordInfo", recordInfo);
        String formatStr = obj.toString(JSONWriter.Feature.PrettyFormat);// 80%时间
        targetDir.mkdirs();
        File recordFile = new File(targetDir, song + "_" + s + ".record");
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(recordFile))) {
            bw.write(formatStr);// 10%时间
        } catch (IOException e) {
            e.printStackTrace();
        }*/
    }

    private void add(JSONArray points, SimpleAction simpleAction, boolean mirror) {
        /*JSONObject obj = new JSONObject();
        obj.put("id", simpleAction.getId());
        obj.put("x", mirror ? resolution.getMaxX() - simpleAction.getX() : simpleAction.getX());
        obj.put("y", simpleAction.getY());
        obj.put("state", simpleAction.getState());
        points.add(obj);*/
    }
}
