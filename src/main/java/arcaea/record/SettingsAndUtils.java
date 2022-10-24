package arcaea.record;

import arcaea.record.base.SimpleAction;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * @author MengLeiFudge
 */
public class SettingsAndUtils {
    private SettingsAndUtils() {
    }

    /**
     * 返回谱面、音乐、曲绘文件夹.
     */
    public static File getAffDir() {
        return new File("D:/arc/官谱");
    }

    /**
     * 返回名称开始为 arc，格式为 apk 的游戏安装包.
     * <p>
     * 多个 apk 满足条件时，返回名称排序最后一位（大概率为所有安装包中最新版本）。
     */
    public static File getApk() {
        File[] files = new File("D:/arc").listFiles();
        if (files == null) {
            return null;
        }
        ArrayList<File> fileList = new ArrayList<>(Arrays.asList(files));
        fileList.removeIf(f -> !f.getName().matches("(?i)arc.*\\.apk"));
        fileList.sort(Comparator.comparing(File::getName));
        return fileList.get(fileList.size() - 1);
    }

    /**
     * 返回从模拟器中复制出来的dl文件夹.
     */
    public static File getDlDir() {
        return new File("C:/Users/" + System.getProperty("user.name") +
                "/Documents/leidian/Pictures/dl");
    }

    /**
     * 返回角色全身图、头像文件夹.
     */
    public static File getCharDir() {
        return new File("D:/arc/char");
    }

    /**
     * 返回脚本文件夹所在的根目录.
     */
    public static File getVmsDir() {
        return new File("F:/leidian/vms");
    }

    /**
     * JVM 可用的最大 CPU 数量.
     */
    public static final int THREAD_NUM = Runtime.getRuntime().availableProcessors();

    private static File getSongInfoCsv() {
        return new File("songInfo.csv");
    }

    public static final String[] DIFFICULTY_STR = {"PST", "PRS", "FTR", "BYD"};

    public static String[] getInfo(String srcSongName) {
        try (BufferedReader br = new BufferedReader(new FileReader(getSongInfoCsv()))) {
            String s;
            while ((s = br.readLine()) != null) {
                String[] data = s.split(",");
                if (data.length > 0 && data[0].equals(srcSongName)) {
                    return data;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return new String[0];
    }

    /**
     * 分辨率，具有 16:9 和 4:3 两种比例的多个常用分辨率.
     * <p>
     * 模拟器具有 宽、高、DPI 三个属性，其中 DPI 对游戏画面无影响。
     * <p>
     * 以下使用 x 表示宽，y 表示高。
     */
    public enum Resolution {
        // 16:9 分辨率，通常表示手机
        R960_540(960, 540),
        R1280_720(1280, 720),
        R1600_900(1600, 900),
        R1920_1080(1920, 1080),
        // 4:3 分辨率，通常表示平板
        R720_540(720, 540),
        R960_720(960, 720),
        R1200_900(1200, 900),
        R1440_1080(1440, 1080);

        private final String describe;
        private final int x;
        private final int y;
        private final double paramX1;
        private final double paramX2;
        private final double paramX3;
        private final double paramX4;
        private final double paramY1;
        private final double paramY2;
        private final int pauseX;
        private final int pauseY;
        private final int continueX;
        private final int continueY;
        private final int maxX;
        private final int maxY;

        /**
         * 根据分辨率的 x、y 值为各参数赋值.
         * <p>
         * 参数的值是通过计算得到的，计算方法如下（以 1280 * 720 为例）：
         * <p>
         * 1.通过录制脚本的方式，确定蛇在左上(0,1)、右上(1,1)、左下(-0.5,0)、右下(1.5,0)时，脚本的坐标数值；
         * <p>
         * 2.已知脚本横坐标最大值为 1280 * 15 = 19200，左下横坐标为 1160，右下横坐标为 18060，
         * 则左下使用 1150，右下使用 18050。
         * 这也是选用四个点而非两个点的原因。
         * 最终可得左上(4500,4350)、右上(14700,4350)、左下(1150,8550)、右下(18050,8550)。
         * <p>
         * 3.记脚本中的 x 比例值为 f1(x,y)，可得 f1(x,0) = 0.440104x + 0.279948, f1(x,1) = 0.53125x + 0.234375。
         * 联立可得 f1(x,y) = (0.091146y + 0.440104)x - 0.045573y + 0.279948。
         * 同理，记脚本中的 y 比例值为 f2(y)，可得 f2(y) = -0.388889y + 0.791667。
         * <p>
         * 4.只要横纵比例相同，则可以使用同样的数值计算出不同分辨率的脚本值。
         * 这步在 {@link #convertToX(double, double)} 和 {@link #convertToY(double)} 中进行。
         *
         * @param x 模拟器横向像素值
         * @param y 模拟器纵向像素值
         */
        Resolution(int x, int y) {
            this.x = x;
            this.y = y;
            double ratio2 = y / 180.0;
            this.maxX = x * 15;
            this.maxY = y * 15;
            if (Math.abs(x / 16.0 - y / 9.0) < 1e-5) {
                describe = x + " * " + y + "（16:9）";
                paramX1 = 0.091146;
                paramX2 = 0.440104;
                paramX3 = -0.045573;
                paramX4 = 0.279948;
                paramY1 = -0.388889;
                paramY2 = 0.791667;
                pauseX = (int) (355.0 * ratio2);
                pauseY = (int) (262.5 * ratio2);
                continueX = (int) (1657.5 * ratio2);
                continueY = (int) (1605.0 * ratio2);
            } else if (Math.abs(x / 4.0 - y / 3.0) < 1e-5) {
                describe = x + " * " + y + "（4:3）";
                /*
                paramX1 = 428.0;
                paramX2 = 1780.0;
                paramX3 = -214.0;
                paramX4 = 1026.0;
                paramY1 = -644.0;
                paramY2 = 1624.0;
                 */
                paramX1 = 0.091146;
                paramX2 = 0.440104;
                paramX3 = -0.045573;
                paramX4 = 0.279948;
                paramY1 = -0.388889;
                paramY2 = 0.791667;
                pauseX = (int) (280.0 * ratio2);
                pauseY = (int) (156.0 * ratio2);
                continueX = (int) (1332.0 * ratio2);
                continueY = (int) (968.0 * ratio2);
            } else {
                throw new IllegalArgumentException("非标准分辨率，无法使用！");
            }
        }

        public String getDescribe() {
            return describe;
        }

        /**
         * 将谱面坐标转换为模拟器横坐标.
         * <p>
         * 雷电模拟器脚本中坐标值为分辨率的 15 倍。
         * 即分辨率为 x,y 的模拟器，左上角脚本坐标为 (0,0)，右下角脚本坐标为 (15*x,15*y)。
         *
         * @param x 谱面坐标x
         * @param y 谱面坐标y
         * @return 该点对应的脚本横坐标
         */
        public int convertToX(double x, double y) {
            return (int) (((paramX1 * y + paramX2) * x + paramX3 * y + paramX4) * 15 * this.x);
        }

        /**
         * 将谱面坐标转换为模拟器纵坐标.
         * <p>
         * 雷电模拟器脚本中坐标值为分辨率的 15 倍。
         * 即分辨率为 x, y 的模拟器，左上角脚本坐标为 (0, 0)，右下角脚本坐标为 (15 * x, 15 * y)。
         *
         * @param y 谱面坐标y
         * @return 该点对应的脚本纵坐标
         */
        public int convertToY(double y) {
            return (int) ((paramY1 * y + paramY2) * 15 * this.y);
        }

        private final ArrayList<SimpleAction> preSimpleActions = new ArrayList<>();

        /**
         * 点击暂停、继续的基础操作.
         */
        public synchronized List<SimpleAction> getPreSimpleActions() {
            if (!preSimpleActions.isEmpty()) {
                return preSimpleActions;
            }
            int time = 0;
            int id = 0;
            // 多次点击暂停键
            for (; time < 4000; time += 5) {
                preSimpleActions.add(new SimpleAction(
                        time, id, pauseX, pauseY, true));
                preSimpleActions.add(new SimpleAction(
                        time + 5, id, pauseX, pauseY, false));
                id = id < 3 ? id + 1 : 0;
            }
            // 点击返回键。若脚本 late 多，则增加该值
            time = 6990;
            preSimpleActions.add(new SimpleAction(
                    time - CLICK_TIME, id, continueX, continueY, true));
            preSimpleActions.add(new SimpleAction(
                    time, id, continueX, continueY, false));
            Collections.sort(preSimpleActions);
            return preSimpleActions;
        }

        public int getMaxX() {
            return maxX;
        }

        public int getMaxY() {
            return maxY;
        }
    }

    /**
     * 脚本运行类型.
     */
    public enum RunState {
        // 【推荐】歌曲开始时运行。
        // 指点击曲目，游戏“关门”后，就开始运行脚本。
        SONG_START_BEGIN,
        // 【不推荐】首键到达判定线时运行。
        // 指开始曲目后按住脚本开始按钮，第一个键到达判定位置时松手。
        FIRST_NOTE_BEGIN,
        // 两种运行脚本都生成。
        BOTH
    }

    /**
     * 脚本镜像类型.
     */
    public enum Mirror {
        // 使用原版
        ORIGIN,
        // 使用镜像
        MIRROR,
        // 二者都生成
        BOTH
    }

    /**
     * 某个ID多次使用时，各个操作之间的最短间隔.
     * <p>
     * 这玩意好像没啥必要。
     * <p>
     * 如果你觉得不需要，请将其改为0（不可改为负数，因为一个ID不能同时点两个位置）。
     */
    public static final int INTERVAL_TIME = 50;

    /**
     * 蛇种类，目前只有红蓝绿.
     */
    public static final int ARC_KINDS = 3;

    /**
     * 去掉蛇后的最高触控数.
     * <p>
     * 因为蛇的触控ID不会更改，且排在非蛇触控ID的后面，所以才有了这个限制。
     * <p>
     * 正常来讲，ID不应该超过4个，10个ID足够用了；
     * 为了方便蛇的拓展，才将蛇的触控ID放在非蛇触控ID之后；
     * 最终脚本操作都是按下、抬起，无法区分是否为蛇，但是需要对蛇头有单点/长条的情况进行处理，
     * 所以需要给蛇设置单独的ID，这也是未将蛇也像非蛇按键一样使用 TouchIdManager 进行管理的原因。
     */
    public static final int MAX_TOUCH_NUM = 10;

    /**
     * 按键之间影响时间最小值.
     * <p>
     * 该值是为了避免按键操作变化导致相近按键无法正常判定而设立的。
     * <p>
     * 对于任意两个按键，无论哪个按键转为 miss 或 小p，只要时间超过 270，即可保证不会互相干扰。
     */
    public static final int EFFECT_TIME = 220 + 25 * 2;

    /**
     * 地键/天键点击时间.
     */
    public static final int CLICK_TIME = 100;
}
