package arcaea.record.aff;

import arcaea.record.base.SimpleAction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import static arcaea.record.SettingsAndUtils.CLICK_TIME;

/**
 * 分辨率，具有 16:9 和 4:3 两种比例的多个常用分辨率.
 * <p>
 * 模拟器具有 宽、高、DPI 三个属性，其中 DPI 对游戏画面无影响。
 * <p>
 * 以下使用 x 表示宽，y 表示高。
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

    //1280*720 的分辨率，模拟器的x值为0-1280*15，也就是0-19200；y同理

    /**
     * 1280 * 720 分辨率下记录的模拟器坐标.
     */
    private static final int[][] PARAMS16_9 = {
            {1128, 8544},  // (-0.5,0)
            {18050, 8534}, // (1.5,0)
            {4469, 4338},  // (0,1)
            {14709, 4338}, // (1,1)
            {4025, 8795},  // (-0.5,0)
            {15155, 8783}, // (1.5,0)
            {6455, 6213},  // (0,1)
            {12745, 6213}, // (1,1)
            {1280, 720},   //分辨率
    };

    private static final int[][] PARAMS4_3 = {
            // TODO:需要测定坐标。
            {1128, 8544},  // (-0.5,0)
            {18050, 8534}, // (1.5,0)
            {4469, 4338},  // (0,1)
            {14709, 4338}, // (1,1)
            {4025, 8795},  // (-0.5,0)
            {15155, 8783}, // (1.5,0)
            {6455, 6213},  // (0,1)
            {12745, 6213}, // (1,1)
            {1280, 720},   //分辨率
    };

    private static final Function<double[], int[]> GAME_TO_SCREEN_16_9;
    private static final Function<double[], int[]> GAME_TO_SCREEN_4_3;

    static {
        Function<int[][], Function<double[], int[]>> process = (input) -> {
            // 0,0到1,0的模拟器X0差值 dX0_4k -> 地上，短
            // 0,1到1,1的模拟器X1差值 dX1_4k
            // 0,0到0,1的模拟器dY差值 dY_4k
            double dX0_4k = (input[1][0] - input[0][0]) / 2.0;
            // 雷电模拟器脚本中坐标最大值为分辨率的 15 倍
            double X0_4k = (input[8][0] * 15) / 2.0;
            double dX1_4k = input[3][0] - input[2][0];
            double Y0_4k = (input[0][1] + input[1][1]) / 2.0;
            double dY_4k = -Y0_4k + (input[2][1] + input[3][1]) / 2.0;
            // f_x(t) 满足 f_x(0)=dX0_4k，f_x(1)=dX1_4k 【f_x(t)为辅助函数】
            // f_x(t)=kt+b，b=dX0_4k，k=dX1_4k-dX0_4k => f_x(t)=(dX1_4k-dX0_4k)*t+dX0_4k
            // 设 轨道坐标（0.5，0）对应（X，Y）
            // y = 0 时 谱面横坐标每变化d，模拟器横坐标变化d*X0_4k
            // y = 1 时 谱面横坐标每变化d，模拟器横坐标变化d*X1_4k
            // y = t 时 谱面横坐标每变化d，模拟器横坐标变化d*f_x(t)
            // 谱面坐标（x,y）对应模拟器坐标(X+f_x(y)*(x-0.5),Y+y*dY) => (X+(ky+b)*(x-0.5), Y+y*dY)
            // new_x = X + (k * y + b) * (x - 0.5)
            // new_y = Y + y * dY
            double dX0_6k = (input[5][0] - input[4][0]) / 2.0;
            double X0_6k = (input[8][0] * 15) / 2.0;
            double dX1_6k = input[7][0] - input[6][0];
            double Y0_6k = (input[4][1] + input[5][1]) / 2.0;
            double dY_6k = -Y0_6k + (input[6][1] + input[7][1]) / 2.0;
            return (inputs) -> {
                double X_4K = X0_4k + ((dX1_4k - dX0_4k) * inputs[1] + dX0_4k) * (inputs[0] - 0.5);
                double Y_4K = Y0_4k + inputs[1] * dY_4k;
                double X_6K = X0_6k + ((dX1_6k - dX0_6k) * inputs[1] + dX0_6k) * (inputs[0] - 0.5);
                double Y_6K = Y0_6k + inputs[1] * dY_6k;
                int X = (int) (X_4K + (X_6K - X_4K) * inputs[2]);
                int Y = (int) (Y_4K + (Y_6K - Y_4K) * inputs[2]);
                return new int[]{X, Y};
            };
        };
        GAME_TO_SCREEN_16_9 = process.apply(PARAMS16_9);
        GAME_TO_SCREEN_4_3 = process.apply(PARAMS4_3);
    }

    /**
     * 输入谱面x, y, 4/6k比例，输出模拟器坐标X, Y.
     */
    public int convertToX(double x, double y) {
        return convertToX(x, y, 0);
    }

    public int convertToX(double x, double y, double ratio46k) {
        return is16_9
                ? GAME_TO_SCREEN_16_9.apply(new double[]{x, y, ratio46k})[0]
                : GAME_TO_SCREEN_4_3.apply(new double[]{x, y, ratio46k})[0];
    }

    public int convertToY(double y) {
        return convertToY(y, 0);
    }

    public int convertToY(double y, double ratio46k) {
        return is16_9
                ? GAME_TO_SCREEN_16_9.apply(new double[]{0, y, ratio46k})[1]
                : GAME_TO_SCREEN_4_3.apply(new double[]{0, y, ratio46k})[1];
    }

    private final boolean is16_9;

    /**
     * 该分辨率的描述.
     */
    private final String describe;

    private final int pauseX;
    private final int pauseY;
    private final int continueX;
    private final int continueY;

    private final int maxX;

    public int getMaxX() {
        return maxX;
    }

    Resolution(int x, boolean is16_9) {
        this.is16_9 = is16_9;
        int y = is16_9 ? x * 9 / 16 : x * 3 / 4;
        double ratio = y / 180.0;
        this.maxX = x * 15;
        if (is16_9) {
            describe = x + " * " + y + "（16:9）";
            pauseX = (int) (355.0 * ratio);
            pauseY = (int) (262.5 * ratio);
            continueX = (int) (1657.5 * ratio);
            continueY = (int) (1605.0 * ratio);
        } else {
            describe = x + " * " + y + "（4:3）";
            pauseX = (int) (280.0 * ratio);
            pauseY = (int) (156.0 * ratio);
            continueX = (int) (1332.0 * ratio);
            continueY = (int) (968.0 * ratio);
        }
    }

    public String getDescribe() {
        return describe;
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

}
