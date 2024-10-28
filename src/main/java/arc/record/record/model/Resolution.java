package arc.record.record.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import static arc.record.Settings.CLICK_TIME;

/**
 * 分辨率，具有 16:9 和 4:3 两种比例的多个常用分辨率.
 * <p>
 * 模拟器具有 宽、高、DPI 三个属性，其中 DPI 对游戏画面无影响。
 * <p>
 * 模拟器左上角为原点，x 向右，y 向下，模拟器最大坐标为分辨率 * 15。
 *
 * @author MengLeiFudge
 */
public enum Resolution {
    // 16:9 分辨率，通常表示手机
    R16_9_960_540(960, true),
    R16_9_1280_720(1280, true),
    R16_9_1600_900(1600, true),
    R16_9_1920_1080(1920, true),
    // 4:3 分辨率，通常表示平板
    R4_3_720_540(720, false),
    R4_3_960_720(960, false),
    R4_3_1200_900(1200, false),
    R4_3_1440_1080(1440, false);

    /**
     * 分辨率的宽.
     */
    private final int width;

    /**
     * 分辨率的高.
     */
    private final int height;

    /**
     * 分辨率比例类型，true 表示 16:9，false 表示 4:3.
     */
    private final boolean is16_9;

    /**
     * 该分辨率相对基准分辨率（高为720）的比例，用于计算不同分辨率的模拟器坐标.
     */
    private final double ratioHeight720;

    public String getDescribe() {
        return width + " * " + height + (is16_9 ? "（16:9）" : "（4:3）");
    }

    private final ArrayList<SimpleAction> preSimpleActions;

    public List<SimpleAction> getPreSimpleActions() {
        return preSimpleActions;
    }

    Resolution(int width, boolean is16_9) {
        this.width = width;
        this.height = is16_9 ? width * 9 / 16 : width * 3 / 4;
        this.is16_9 = is16_9;
        this.ratioHeight720 = height / 720.0;
        // 构建脚本开始前的操作list
        preSimpleActions = new ArrayList<>();
        // 多次点击暂停键
        int pauseX = (int) ((is16_9 ? 355.0 : 280.0) * height / 180.0);
        int pauseY = (int) ((is16_9 ? 262.5 : 156.0) * height / 180.0);
        for (int time = 0; time < 4997; time += 3) {
            preSimpleActions.add(new SimpleAction(time, time / 3 % 4, pauseX, pauseY, true));
            preSimpleActions.add(new SimpleAction(time + 3, time / 3 % 4, pauseX, pauseY, false));
        }
        // 点击返回键
        int continueX = (int) ((is16_9 ? 1657.5 : 1332.0) * height / 180.0);
        int continueY = (int) ((is16_9 ? 1605.0 : 968.0) * height / 180.0);
        preSimpleActions.add(new SimpleAction(5000, 0, continueX, continueY, true));
        preSimpleActions.add(new SimpleAction(5000 + CLICK_TIME, 0, continueX, continueY, false));
        Collections.sort(preSimpleActions);
    }

    private static final Function<double[], int[]> GAME_TO_SCREEN_16_9;
    private static final Function<double[], int[]> GAME_TO_SCREEN_4_3;

    static {
        // 以 y=720 为基准，构建坐标转换方法
        int[][] PARAMS16_9 = {
                // 4k(-0.5,0), (1.5,0), (0,1), (1,1)
                {1128, 8544}, {18050, 8534}, {4469, 4338}, {14709, 4338},
                // 6k(-0.5,0), (1.5,0), (0,1), (1,1)
                {4025, 8795}, {15155, 8783}, {6455, 6213}, {12745, 6213},
                // 分辨率
                {1280, 720},
        };
        int[][] PARAMS4_3 = {
                // todo: 添加4:3分辨率适配
                // 4k(-0.5,0), (1.5,0), (0,1), (1,1)
                {1, 2}, {3, 4}, {5, 6}, {7, 8},
                // 6k(-0.5,0), (1.5,0), (0,1), (1,1)
                {9, 10}, {11, 12}, {13, 14}, {15, 16},
                // 分辨率
                {960, 720},
        };
        // x,y 表示谱面坐标；X,Y 表示模拟器脚本坐标
        // 雷电模拟器脚本中坐标最大值为分辨率的 15 倍
        Function<int[][], Function<double[], int[]>> process = (param) -> {
            // (0,0)到(1,0)的X变化量
            double dX0_4k = (param[1][0] - param[0][0]) / 2.0;
            // (0,1)到(1,1)的X变化量
            double dX1_4k = param[3][0] - param[2][0];
            // x=0.5,y=? 对应的 X坐标
            double X_x0p5_4k = (param[8][0] * 15) / 2.0;
            // x=?,y=0 对应的 Y坐标
            double Y_y0_4k = (param[0][1] + param[1][1]) / 2.0;
            // (0,0)到(0,1)的Y变化量，注意该值为负数
            double dY_4k = (param[2][1] + param[3][1]) / 2.0 - Y_y0_4k;
            // 假设 f_x(t) 满足 f_x(0) = dX0，f_x(1) = dX1，由于x的单位长度是线性变化的，所以 f_x(t) 为一次函数
            // 则 k = dX1 - dX0，b = dX0，可得 f_x(t) = (dX1 - dX0) * t + dX0
            // y = t 时，谱面横坐标每变化 dx，模拟器横坐标会变化 dx * f_x(t)
            // 考虑到 x=0.5 时 X 为定值，所以 (x,y) 对应 (X_x0p5 + f_x(y) * (x - 0.5), Y_y0 + y * dY)
            double dX0_6k = (param[5][0] - param[4][0]) / 2.0;
            double dX1_6k = param[7][0] - param[6][0];
            double X_x0p5_6k = (param[8][0] * 15) / 2.0;
            double Y_y0_6k = (param[4][1] + param[5][1]) / 2.0;
            double dY_6k = (param[6][1] + param[7][1]) / 2.0 - Y_y0_6k;
            return (inputs) -> {
                double X_4K = X_x0p5_4k + ((dX1_4k - dX0_4k) * inputs[1] + dX0_4k) * (inputs[0] - 0.5);
                double Y_4K = Y_y0_4k + inputs[1] * dY_4k;
                double X_6K = X_x0p5_6k + ((dX1_6k - dX0_6k) * inputs[1] + dX0_6k) * (inputs[0] - 0.5);
                double Y_6K = Y_y0_6k + inputs[1] * dY_6k;
                int X = (int) ((X_4K + (X_6K - X_4K) * inputs[2]) * inputs[3]);
                int Y = (int) ((Y_4K + (Y_6K - Y_4K) * inputs[2]) * inputs[3]);
                return new int[]{X, Y};
            };
        };
        GAME_TO_SCREEN_16_9 = process.apply(PARAMS16_9);
        GAME_TO_SCREEN_4_3 = process.apply(PARAMS4_3);
    }

    /**
     * 输入谱面x, y, 4/6k比例，输出模拟器坐标X, Y.
     */
    public int[] convertToXY(double x, double y, double ratio46k) {
        return is16_9
                ? GAME_TO_SCREEN_16_9.apply(new double[]{x, y, ratio46k, ratioHeight720})
                : GAME_TO_SCREEN_4_3.apply(new double[]{x, y, ratio46k, ratioHeight720});
    }

    public int getMaxX() {
        return width * 15;
    }
}
