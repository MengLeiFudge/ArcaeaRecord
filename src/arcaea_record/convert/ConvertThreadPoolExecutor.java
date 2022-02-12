package arcaea_record.convert;

import arcaea_record.convert.base.AffProcess;
import arcaea_record.convert.base.BaseProcess;
import org.apache.commons.lang.SerializationUtils;

import java.text.DecimalFormat;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static arcaea_record.SettingsAndUtils.THREAD_NUM;
import static java.lang.Thread.sleep;

/**
 * 用于分流处理的线程.
 *
 * @author MengLeiFudge
 */
public class ConvertThreadPoolExecutor implements Runnable {
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
            if (t.isDaemon()) {
                t.setDaemon(false);
            }
            if (t.getPriority() != Thread.NORM_PRIORITY) {
                t.setPriority(Thread.NORM_PRIORITY);
            }
            return t;
        }
    }

    /**
     * 使用多线程处理谱面文件并生成脚本.
     */
    public static void process(List<AffProcess> list) {
        processList = list;
        processedNum = 0;
        int allProcessNum = 0;
        for (AffProcess process : processList) {
            allProcessNum += process.getBaseProcessList().size();
        }
        if (allProcessNum == 0) {
            System.out.println("没有需要生成的脚本！");
            return;
        }
        ExecutorService pool = new ThreadPoolExecutor(
                THREAD_NUM, THREAD_NUM,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(THREAD_NUM),
                new ConvertThreadFactory());
        for (int i = 0; i < THREAD_NUM; i++) {
            pool.execute(new ConvertThreadPoolExecutor(i));
        }
        pool.shutdown();
        try {
            while (!pool.isTerminated()) {
                System.out.println("转换进度：" + df.format((double) processedNum / allProcessNum));
                sleep(1000);
            }
            //pool.awaitTermination(1, TimeUnit.HOURS);
        } catch (InterruptedException e) {
            e.printStackTrace();
            Thread.currentThread().interrupt();
        }
        System.out.println("转换进度：" + df.format((double) processedNum / allProcessNum));
    }

    private final int threadNo;
    private static List<AffProcess> processList;
    private static int processedNum;
    private static final DecimalFormat df = new DecimalFormat("0.00%");

    private ConvertThreadPoolExecutor(int threadNo) {
        this.threadNo = threadNo;
    }

    @Override
    public void run() {
        for (int i = 0; i < processList.size(); i++) {
            if (i % THREAD_NUM == threadNo) {
                AffProcess affProcess = processList.get(i);
                Record baseRecord = new Record(affProcess.getResolution());
                baseRecord.getNoteInfo(affProcess.getAffFile());
                for (BaseProcess bp : affProcess.getBaseProcessList()) {
                    Record r = (Record) SerializationUtils.clone(baseRecord);
                    r.setTime(bp.isSongStartBegin());
                    r.optimize(affProcess.getAffFile().getPath(), bp.getMiss(), bp.getMinPure());
                    r.save(bp.getTargetDir(), affProcess.getSong(), affProcess.getDifficultyStr(),
                            bp.getMiss(), bp.getMinPure(), bp.isSongStartBegin(), bp.isMirror());// 80%以上时间
                }
                synchronized (this) {
                    processedNum += affProcess.getBaseProcessList().size();
                    //System.out.println("已转换 " + df.format((double) processedNum / allProcessNum)
                    //        + "，转换完毕：" + affProcess.getAffFile());
                }
            }
        }
    }
}
