package arcaea.record.base;

import arcaea.record.SettingsAndUtils;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * 谱面数据结构.
 *
 * @author MengLeiFudge
 */
public class Record implements Serializable {
    private final SettingsAndUtils.Resolution resolution;
    /**
     * 谱面按键时间最小值.
     */
    private int minTime = 999999;
    /**
     * 谱面按键时间最大值.
     */
    private int maxTime = -1;
    /**
     * 按键整体偏移时间.
     */
    private int noteOffsetTime;
    /**
     * 脚本结束时间（准确讲是循环时间，但是脚本只运行一次，等价于结束时间）.
     */
    private int recordEndTime;
    /**
     * 地键、天键的键数和，用于处理 lost 和 小p.
     */
    private int clickNum = 0;
    private final TouchIdManager touchIdManager = new TouchIdManager();
    private final int[] arcEndTime = new int[SettingsAndUtils.ARC_KINDS];
    private final int[] arcEndX = new int[SettingsAndUtils.ARC_KINDS];
    private final int[] arcEndY = new int[SettingsAndUtils.ARC_KINDS];
    private final ArrayList<SimpleAction> simpleActions = new ArrayList<>();


    private static final Random random = new Random();

    public static int getRandomInt(int min, int max) {
        return random.nextInt(max - min + 1) + min;
    }

    /**
     * 随机坐标偏移.
     *
     * @return 返回以分辨率宽为基准的随机坐标偏移
     */
    public int getRandomPositionDeflection() {
        return 0;
        /*
        int maxR = (int) (-0.03 * resolution.getMaxX());
        int ret;
        while (true) {
            ret = getRandomInt(-maxR, maxR);
            if (ret * ret * 2 <= maxR * maxR) {
                return ret;
            }
        }
         */
    }

    Record(SettingsAndUtils.Resolution resolution) {
        this.resolution = resolution;
        //Arrays.fill(arcEndTime,-999999);
        //Arrays.fill(arcEndX,-999999);
        //Arrays.fill(arcEndTime,-999999);
    }

    /**
     * 从谱面文件获取所有按键信息，以及按键的最小、最大时间.
     */
    void getNoteInfo(File affFile) {
        // timinggroup 可能导致蛇的读取顺序错乱，该数组用于给蛇排序
        List<ArcAction> arcActions = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(affFile))) {
            String s;
            while ((s = br.readLine()) != null) {
                // 去掉首尾空格，避免timinggroup内部按键不能正确读取
                s = s.trim();
                if (s.length() == 0) {
                    continue;
                }
                if (s.charAt(0) == '(') {
                    // 地面单点
                    // eg: "(8400,3);"
                    // 时间戳（ms为单位），键位（1-4，从左到右）
                    String[] click = s.substring(1, s.length() - 2).split(",");
                    int time = Integer.parseInt(click[0]);
                    minTime = Math.min(time, minTime);
                    maxTime = Math.max(time + 20, maxTime);
                    int position = Integer.parseInt(click[1]);
                    click(time, position);
                } else if (s.charAt(0) == 'h') {
                    // 地面长条
                    String[] hold = s.substring(5, s.length() - 2).split(",");
                    int bt = Integer.parseInt(hold[0]);
                    int et = Integer.parseInt(hold[1]);
                    minTime = Math.min(bt, minTime);
                    maxTime = Math.max(et, maxTime);
                    int position = Integer.parseInt(hold[2]);
                    hold(bt, et, position);
                } else if (s.charAt(0) == 'a') {
                    // 蛇
                    if (!s.contains("arctap") && s.contains("true")) {
                        // eg: "arc(2400,6000,0.13,0.50,b,0.50,0.50,0,none,true);"
                        // 忽略黑线
                        continue;
                    }
                    // eg1: "arc(2400,6000,0.00,0.33,b,1.00,0.00,0,none,false);"
                    // 开始时间戳，结束时间戳，开始x，结束x，蛇类型，
                    // 开始y，结束y，蛇颜色，none（目前无意义），是否为黑线
                    // eg2: "arc(18000,19200,0.50,0.75,b,1.00,1.00,1,none,true)[arctap(18600)];"
                    // 18600 表示天键时间戳
                    // eg3: "arc(19200,21600,0.75,0.25,b,1.00,1.00,0,none,true)[arctap(19800),arctap(21000)];"
                    String[] data = s.split("\\[");
                    // data[0] = "arc(2400,6000,0.00,0.33,b,1.00,0.00,0,none,false)"
                    // data[1] = "arctap(19800),arctap(21000)];"（不一定存在）
                    String[] arc = data[0].substring(4, data[0].length() - 1).split(",");
                    int bt = Integer.parseInt(arc[0]);
                    int et = Integer.parseInt(arc[1]);
                    double beginX = Double.parseDouble(arc[2]);
                    double endX = Double.parseDouble(arc[3]);
                    String type = arc[4];
                    double beginY = Double.parseDouble(arc[5]);
                    double endY = Double.parseDouble(arc[6]);
                    int color = Integer.parseInt(arc[7]);
                    if (!s.contains("arctap")) {
                        // 蛇
                        minTime = Math.min(bt, minTime);
                        maxTime = Math.max(et, maxTime);
                        arcActions.add(new ArcAction(bt, et, beginX, endX, type, beginY, endY, color));
                    } else {
                        // 天空键
                        String[] arctap = data[1].substring(7, data[1].length() - 3)
                                .split("\\),arctap\\(");
                        // eg: "arctap(18600)"
                        for (String str : arctap) {
                            int time = Integer.parseInt(str);
                            minTime = Math.min(time, minTime);
                            maxTime = Math.max(time + 20, maxTime);
                            arctap(bt, et, beginX, endX, type, beginY, endY, time);
                        }
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        arcActions.sort(Comparator.comparingInt(ArcAction::getBeginTime));
        for (ArcAction a : arcActions) {
            arc(a.getBeginTime(), a.getEndTime(), a.getBeginX(), a.getEndX(),
                    a.getType(), a.getBeginY(), a.getEndY(), a.getColor());
        }
    }

    /**
     * 设置按键整体偏移时间，以及脚本结束时间
     *
     * @param isSongStartBegin 是否为歌曲开始就执行脚本
     */
    public void setTime(boolean isSongStartBegin) {
        if (isSongStartBegin) {
            this.noteOffsetTime = 10000;
            this.recordEndTime = maxTime + 10500;
        } else {
            this.noteOffsetTime = -minTime;
            this.recordEndTime = maxTime - minTime + 500;
        }
    }


    /*-- 谱面坐标换算为模拟器坐标 --*/

    /**
     * 将均匀的比例值转换为贝赛尔比例值.
     * 由于具体转换函数未知，故采用分段近似直线计算.
     * 先判断该值在哪个区间，再用近似直线计算出对应贝赛尔比例值.
     *
     * @param ratio 均匀的比例
     * @param arr   贝赛尔比例数组
     * @return 近似的贝塞尔比例值
     */
    private double ratio(double ratio, double[] arr) {
        if (ratio < 0 || ratio > 1) {
            throw new IllegalArgumentException("比例越界：" + ratio);
        }
        // 转换区间左闭右开，增加ratio是否为1的判断，误差取0.001
        if (Math.abs(ratio - 1) < 1e-3) {
            return 1;
        }
        // len 表示区间的个数
        int len = arr.length - 1;
        // 寻找 ratio 对应哪一个区间
        for (int i = 0; i < len; i++) {
            // 用均匀数值判断对应的区间位置
            if (ratio < (double) (i + 1) / len) {
                // 取用贝赛尔比例对应区间的上下限，进行转换
                // 贝塞尔两点：((double) i / len, arr[i]), ((double) (i + 1) / len, arr[i + 1])
                // 贝塞尔该段近似直线方程：y = (arr[i + 1] - arr[i]) * (len * x - i) + arr[i]
                return (arr[i + 1] - arr[i]) * (len * ratio - i) + arr[i];
            }
        }
        throw new RuntimeException("比例 " + ratio + " 转换出错！\n" +
                "该信息不应出现，请检查代码！");
    }

    private static final double[] SI = {
            0, 0.098, 0.196, 0.291, 0.383, 0.472, 0.556, 0.635,
            0.707, 0.773, 0.832, 0.882, 0.924, 0.957, 0.981, 0.995, 1
    };

    private double siRatio(double ratio) {
        return ratio(ratio, SI);
    }

    private static final double[] SO = {
            0, 0.005, 0.019, 0.043, 0.076, 0.118, 0.169, 0.227,
            0.293, 0.365, 0.445, 0.528, 0.617, 0.709, 0.805, 0.902, 1
    };

    private double soRatio(double ratio) {
        return ratio(ratio, SO);
    }

    private int getX(int beginTime, int endTime, double beginX, double endX,
                     String type, double beginY, double endY, int time) {
        if (time < beginTime || time > endTime) {
            throw new IllegalArgumentException("按键时间错误：time " + time
                    + "不在 [" + beginTime + ", " + endTime + "] 区间内");
        }
        double ratioX = time == beginTime ? 0.0 :
                (double) (time - beginTime) / (endTime - beginTime);// 0到1的一个值
        if (type.equals("b")) {
            if (ratioX < 0.5) {
                return getX(beginTime, (beginTime + endTime) / 2,
                        beginX, (beginX + endX) / 2, "soso",
                        beginY, (beginY + endY) / 2, time);
            } else {
                return getX((beginTime + endTime) / 2, endTime,
                        (beginX + endX) / 2, endX, "sisi",
                        (beginY + endY) / 2, endY, time);
            }
        }
        if (type.startsWith("si")) {
            ratioX = siRatio(ratioX);
        } else if (type.startsWith("so")) {
            ratioX = soRatio(ratioX);
        } else if (!type.equals("s")) {
            throw new IllegalArgumentException("arc类型错误：" + type);
        }
        double ratioY = ratioX;
        if (type.equals("sisi") || type.equals("sosi")) {
            ratioY = siRatio(ratioY);
        } else if (type.equals("siso") || type.equals("soso")) {
            ratioY = soRatio(ratioY);
        } else if (!type.equals("s") && !type.equals("si") && !type.equals("so")) {
            throw new IllegalArgumentException("arc类型错误：" + type);
        }
        return resolution.convertToX(ratioX * (endX - beginX) + beginX, ratioY * (endY - beginY) + beginY);
    }

    private int getY(int beginTime, int endTime, double beginX, double endX,
                     String type, double beginY, double endY, int time) {
        if (time < beginTime || time > endTime) {
            throw new IllegalArgumentException("按键时间错误：time " + time
                    + "不在 [" + beginTime + ", " + endTime + "] 区间内");
        }
        double ratioY = time == beginTime ? 0.0 :
                (double) (time - beginTime) / (endTime - beginTime);// 0到1的一个值
        if (type.equals("b")) {
            if (ratioY < 0.5) {
                return getY(beginTime, (beginTime + endTime) / 2,
                        beginX, (beginX + endX) / 2, "soso",
                        beginY, (beginY + endY) / 2, time);
            } else {
                return getY((beginTime + endTime) / 2, endTime,
                        (beginX + endX) / 2, endX, "sisi",
                        (beginY + endY) / 2, endY, time);
            }
        }
        if (type.equals("sisi") || type.equals("sosi")) {
            ratioY = siRatio(ratioY);
        } else if (type.equals("siso") || type.equals("soso")) {
            ratioY = soRatio(ratioY);
        } else if (!type.equals("s") && !type.equals("si") && !type.equals("so")) {
            throw new IllegalArgumentException("arc类型错误：" + type);
        }
        return resolution.convertToY(ratioY * (endY - beginY) + beginY);
    }


    /*-- 按键转为 Action 并保存在 actions 中 --*/

    /**
     * 获取非蛇按键的ID.
     *
     * @param beginTime 操作开始时间
     * @param endTime   操作结束时间
     * @return 该操作对应的ID
     */
    private int getID(int beginTime, int endTime) {
        /*
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < MAX_TAP; i++) {
            sb.append(useTime[i]).append(" ");
        }
        System.out.println(sb.toString());
        for (int i = 0; i < MAX_TAP; i++) {
            // ID未使用过/该ID已经闲置较长时间，这两种情况可以使用该ID
            if (useTime[i] == 0 || beginTime - useTime[i] > WAIT_TIME) {
                useTime[i] = endTime;
                return i;
            }
        }

         */
        int id = touchIdManager.getId(beginTime, endTime);
        if (id == -1) {
            throw new ArrayIndexOutOfBoundsException("所需ID超过" + SettingsAndUtils.MAX_TOUCH_NUM + "，需扩充数组！");
        } else {
            return id;
        }
    }

    /**
     * 添加地键/长条/天键的按下、抬起两个动作.
     *
     * @param beginTime 按下时间戳
     * @param endTime   抬起时间戳
     * @param x         点击位置x
     * @param y         点击位置y
     */
    private void press(int beginTime, int endTime, int x, int y) {
        int id = getID(beginTime, endTime);
        down(beginTime, id, x, y);
        up(endTime, id, x, y);
    }

    /**
     * 添加蛇的按下动作.
     * 地键/长条/天键应使用{@link #press(int, int, int, int)}，而非直接调用该方法.
     *
     * @param time 按下时间戳
     * @param id   触控点id
     * @param x    点击位置x
     * @param y    点击位置y
     */
    private void down(int time, int id, int x, int y) {
        simpleActions.add(new SimpleAction(time, id, x, y, true));
    }

    /**
     * 添加蛇的抬起动作.
     * 地键/长条/天键应使用{@link #press(int, int, int, int)}，而非直接调用该方法.
     *
     * @param time 抬起时间戳
     * @param id   触控点id
     * @param x    点击位置x
     * @param y    点击位置y
     */
    private void up(int time, int id, int x, int y) {
        simpleActions.add(new SimpleAction(time, id, x, y, false));
    }

    public void click(int time, int position) {
        hold(time, time + SettingsAndUtils.CLICK_TIME, position);
        clickNum++;
    }

    public void hold(int beginTime, int endTime, int position) {
        // 长条和地键触控点使用比y=0更低的位置
        // 好吧，事实证明这样没用。只要长条跟蛇同一个x，就会被带走。
        // 那么，如果不是同一个x。。？
        // 我给x减了0.23，但愿不会越界。
        // 果然，越界了。我应该把触控点也往下移。
        // nmd，不出所料，有的越界有的不越界，这是逼我写一个switch啊

        //press(beginTime, endTime,
        //        //resolution.convertToX((double) position / 2 - 0.75, 0.0) + getRandomPositionDeflection(),
        //        resolution.convertToX((double) position / 2 - 0.75 - 0.23, 0.0) + getRandomPositionDeflection(),
        //        //resolution.convertToY(0.0) + getRandomPositionDeflection());
        //        (int) (resolution.getMaxY() * 0.98) + getRandomPositionDeflection());

        int x = switch (position) {
            case 1 -> (int) (resolution.getMaxX() * 0.02);
            case 2 -> (int) (resolution.getMaxX() * 0.29);


            case 3 -> (int) (resolution.getMaxX() * 0.71);
            case 4 -> (int) (resolution.getMaxX() * 0.98);
            default -> throw new IllegalStateException("Unexpected value: " + position);
        };

        int y = (int) (resolution.getMaxY() * 0.98);

        press(beginTime, endTime, x, y);
    }

    public void arc(int beginTime, int endTime, double beginX, double endX,
                    String type, double beginY, double endY, int color) {
        if (beginTime == endTime) {
            // 去掉没有判定点的蛇
            return;
        }
        if (color < 0 || color >= SettingsAndUtils.ARC_KINDS) {
            throw new IllegalArgumentException("未知颜色的蛇：" + color);
        }
        int useID = SettingsAndUtils.MAX_TOUCH_NUM + color;
        // 需要抬起的条件：之前有同色蛇，且之前最近的同色蛇蛇尾与该蛇蛇头相距 100 ms 以上
        boolean needUp = arcEndTime[color] != 0 && beginTime > arcEndTime[color] + 100;
        if (needUp) {
            up(arcEndTime[color], useID, arcEndX[color], arcEndY[color]);
        }
        if (endTime - beginTime < 50
                || (Math.abs(beginX - endX) < 1e-5 && Math.abs(beginY - endY) < 1e-5)) {
            // 低于 50 ms 的蛇全程只按中间；位置不动的蛇只按一次
            int midTime = (beginTime + endTime) / 2;
            int midX = getX(beginTime, endTime, beginX, endX, type, beginY, endY, midTime);
            int midY = getY(beginTime, endTime, beginX, endX, type, beginY, endY, midTime);
            if (needUp || midX != arcEndX[color] || midY != arcEndY[color]) {
                // 需要按下的条件（满足其一即可）：
                // 1.前面有同色蛇，且同色蛇已抬起
                // 2.蛇位置有变化
                // 3.该颜色蛇首次出现
                // 其中，第三点可以归到第二点中，因为 arcEndX 初值为 0，即蛇在屏幕最左上角，这是不可能的
                down(beginTime, useID, midX, midY);
            }
            arcEndTime[color] = endTime;
            arcEndX[color] = midX;
            arcEndY[color] = midY;
        } else {
            // 高于 50 ms 且需要复杂操作的蛇，每 10 ms 按一次
            int time0 = beginTime;
            while (time0 < endTime) {
                int x = getX(beginTime, endTime, beginX, endX, type, beginY, endY, time0);
                int y = getY(beginTime, endTime, beginX, endX, type, beginY, endY, time0);
                down(time0, useID, x, y);
                time0 += 10;
            }
            arcEndTime[color] = endTime;
            arcEndX[color] = getX(beginTime, endTime, beginX, endX, type, beginY, endY, endTime);
            arcEndY[color] = getY(beginTime, endTime, beginX, endX, type, beginY, endY, endTime);
        }
    }

    public void arctap(int beginTime, int endTime, double beginX, double endX,
                       String type, double beginY, double endY, int time) {
        press(time, time + SettingsAndUtils.CLICK_TIME,
                getX(beginTime, endTime, beginX, endX, type, beginY, endY, time) + getRandomPositionDeflection(),
                getY(beginTime, endTime, beginX, endX, type, beginY, endY, time) + getRandomPositionDeflection());
        clickNum++;
    }


    /*-- 排序、修正与优化 --*/

    public void optimize(String affPath, int miss, int minPure) {
        // 蛇最后一下还未抬起，应在此处抬起
        for (int color = 0; color < SettingsAndUtils.ARC_KINDS; color++) {
            if (arcEndTime[color] == 0) {
                // 如果没有出现这个颜色的蛇，无需抬起，直接跳过
                continue;
            }
            up(arcEndTime[color], SettingsAndUtils.MAX_TOUCH_NUM + color, arcEndX[color], arcEndY[color]);
        }
        Collections.sort(simpleActions);
        //debug(1);
        optimizeArcActions(affPath);
        //debug(2);
        processMissAndMinPure(affPath, miss, minPure);
        //debug(3);
        // 调整所有操作的 timing
        for (SimpleAction simpleAction : simpleActions) {
            simpleAction.setTiming(simpleAction.getTiming() + noteOffsetTime);
        }
        Collections.sort(simpleActions);
    }

    private void debug(int i) {
        File f = new File(i + ".txt");
        try {
            f.createNewFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(f))) {
            for (SimpleAction action : simpleActions) {
                bw.write(action.toString());
                bw.newLine();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 优化以长条结尾、地键、天键为蛇头的蛇相关操作.
     */
    private void optimizeArcActions(String affPath) {
        boolean[] isArcBegin = new boolean[SettingsAndUtils.ARC_KINDS];
        Arrays.fill(isArcBegin, true);
        for (int i = 0; i < simpleActions.size(); i++) {
            SimpleAction actionArc = simpleActions.get(i);
            if (!actionArc.isArc()) {
                continue;
            }
            int color = actionArc.getId() - SettingsAndUtils.MAX_TOUCH_NUM;
            if (!actionArc.isPressDown()) {
                // 蛇结尾抬手时，重置 isArcBegin[color] 为 true
                isArcBegin[color] = true;
                continue;
            }
            if (!isArcBegin[color]) {
                continue;
            }
            // 找到了蛇头按下的操作
            isArcBegin[color] = false;
            // 向前寻找最靠近的非蛇按键
            // todo: 向后延伸
            for (int j = simpleActions.size() - 1; j >= 0; j--) {
                SimpleAction actionNotArc = simpleActions.get(j);
                if (actionNotArc.getTiming() > actionArc.getTiming() + 10) {
                    continue;
                }
                // 这个时间必须足够小，比蛇之间的最小间距要短，但是要大于 click time
                if (actionNotArc.getTiming() < actionArc.getTiming() - 100) {
                    break;
                }
                // 忽略蛇或距离过远的非蛇按键操作
                if (actionNotArc.isArc()
                        || Math.abs(actionArc.getX() - actionNotArc.getX()) > resolution.getMaxX() * 0.1
                        || Math.abs(actionArc.getY() - actionNotArc.getY()) > resolution.getMaxX() * 0.1) {
                    continue;
                }
                // 找到了最近的非蛇按键，需根据按键类型处理
                SimpleAction actionNotArc2;
                if (actionNotArc.isPressDown()) {
                    // 最近的非蛇按键操作是按下
                    // 找到该按键的抬起
                    int k = j + 1;
                    for (; ; k++) {
                        actionNotArc2 = simpleActions.get(k);
                        if (actionNotArc2.getId() == actionNotArc.getId()
                                && !actionNotArc2.isPressDown()
                                && actionNotArc2.getX() == actionNotArc.getX()
                                && actionNotArc2.getY() == actionNotArc.getY()) {
                            break;
                        }
                    }
                    //synchronized (this) {
                    //    System.out.println("发现可优化 type1 " + affPath);
                    //    System.out.println("actionArc: " + actionArc);
                    //    System.out.println("actionNotArc: " + actionNotArc);
                    //    System.out.println("actionNotArc2: " + actionNotArc2);
                    //}
                    if (actionArc.getTiming() == actionNotArc.getTiming()) {
                        // 蛇头与按下时间相同
                        if (actionNotArc2.getTiming() - actionNotArc.getTiming() == SettingsAndUtils.CLICK_TIME) {
                            // 该键是地键/天键，应移除该键的按下、抬起
                            // 注意，必须先移除后面的操作
                            simpleActions.remove(k);
                            simpleActions.remove(j);
                            i -= 2;
                        } else {
                            // 该键是长条，理论上不会出现这样的配置
                            System.out.println("出现特殊谱面，蛇与长条相距较近且同时按下！");
                            // 应将该长条位置下移
                            actionNotArc.setY(actionNotArc.getY() + 200);
                            simpleActions.set(j, actionNotArc);
                            actionNotArc2.setY(actionNotArc2.getY() + 200);
                            simpleActions.set(k, actionNotArc2);
                        }
                    } else {
                        // 蛇头与按下时间不同
                        if (actionNotArc2.getTiming() - actionNotArc.getTiming() == SettingsAndUtils.CLICK_TIME) {
                            // 该键是地键/天键，应移除该键的抬起，并将按下改为蛇的按下
                            simpleActions.remove(k);
                            i--;
                            actionNotArc.setId(actionArc.getId());
                            simpleActions.set(j, actionNotArc);
                        } else {
                            // 该键是长条，理论上不会出现这样的配置
                            System.out.println("出现特殊谱面，蛇与长条相距较近且几乎同时按下！");
                            // 应将该长条位置下移
                            actionNotArc.setY(actionNotArc.getY() + 200);
                            simpleActions.set(j, actionNotArc);
                            actionNotArc2.setY(actionNotArc2.getY() + 200);
                            simpleActions.set(k, actionNotArc2);
                        }
                    }
                } else {
                    // 最近的非蛇按键操作是抬起
                    // 找到该按键的按下
                    int k = j - 1;
                    for (; ; k--) {
                        actionNotArc2 = simpleActions.get(k);
                        if (actionNotArc2.getId() == actionNotArc.getId()
                                && actionNotArc2.isPressDown()
                                && actionNotArc2.getX() == actionNotArc.getX()
                                && actionNotArc2.getY() == actionNotArc.getY()) {
                            break;
                        }
                    }
                    //synchronized (this) {
                    //    System.out.println("发现可优化 type2 " + affPath);
                    //    System.out.println("actionArc: " + actionArc);
                    //    System.out.println("actionNotArc2: " + actionNotArc2);
                    //    System.out.println("actionNotArc: " + actionNotArc);
                    //}
                    // 移除该键的抬起，并将按下改为蛇的按下
                    simpleActions.remove(j);
                    i--;
                    actionNotArc2.setId(actionArc.getId());
                    simpleActions.set(k, actionNotArc2);
                }
                break;
            }
        }
    }

    /**
     * 根据miss和小p数目，调整按键
     *
     * @param miss    miss数
     * @param minPure 小p数
     */
    private void processMissAndMinPure(String affPath, int miss, int minPure) {
        // 目前已经处理完毕的单点个数
        int processedNum = 0;
        // 目前已生成的miss数目
        int missNum = 0;
        // 目前已生成的小p数目
        int minPureNum = 0;
        // 由于偏移也不一定小p（模拟器原因，执行时间有偏差），这里适当增加小p数
        minPure = (int) (minPure * 1.8);
        // 下一个小p前移还是后移
        boolean isLate = true;
        // 倒序移除按键，避免索引i混乱
        for (int i = simpleActions.size() - 1; i > 0; i--) {
            // 处理完成，提前结束循环
            if (missNum == miss && minPureNum == minPure) {
                break;
            }
            // o1表示按键按下
            SimpleAction o1 = simpleActions.get(i);
            // 忽略与蛇有关的操作、忽略抬起操作
            if (o1.getId() >= SettingsAndUtils.MAX_TOUCH_NUM || !o1.isPressDown()) {
                continue;
            }
            for (int j = i + 1; ; j++) {
                // o2表示按键抬起
                SimpleAction o2 = simpleActions.get(j);
                if (o1.getId() != o2.getId()) {
                    continue;
                }
                // id相同，无论是什么，都要结束循环
                if (o2.getTiming() - o1.getTiming() == SettingsAndUtils.CLICK_TIME) {
                    // 非长条
                    boolean shouldMissThisNote = false;
                    // 判断是否需要miss该按键
                    // 思路是从后往前均匀分布 miss，但是前面可能出现一段都没有合适的按键可以 miss 的情况
                    // 这里使用 10% - 100% 这一段均匀分布所有 miss，以确保 miss 达标
                    if (missNum < miss && processedNum >= missNum * clickNum * 0.9 / miss) {
                        shouldMissThisNote = true;
                        // 避免冲突，若该按键后一定时间内有紧跟的键，则该键不能miss
                        for (int k = j + 1; k < simpleActions.size(); k++) {
                            SimpleAction o3 = simpleActions.get(k);
                            if (o3.getTiming() - o2.getTiming() > SettingsAndUtils.EFFECT_TIME) {
                                break;
                            }
                            if (Math.abs(o2.getX() - o3.getX()) < resolution.getMaxX() * 0.09375
                                    && Math.abs(o2.getY() - o3.getY()) < resolution.getMaxX() * 0.09375) {
                                shouldMissThisNote = false;
                                break;
                            }
                        }
                        if (shouldMissThisNote) {
                            // 先移除后面的操作
                            simpleActions.remove(j);
                            simpleActions.remove(i);
                            missNum++;
                        }
                    }
                    // 判断是否需要将该按键改为小p
                    if (!shouldMissThisNote && minPureNum < minPure) {
                        boolean shouldMoveThisNote = true;
                        if (isLate) {
                            // 避免冲突，若该按键后一定时间内有紧跟的键，则该键不能后移
                            for (int k = j + 1; k < simpleActions.size(); k++) {
                                SimpleAction o3 = simpleActions.get(k);
                                if (o3.getTiming() - o2.getTiming() > 200) {
                                    break;
                                }
                                if (Math.abs(o2.getX() - o3.getX()) < resolution.getMaxX() * 0.09375
                                        && Math.abs(o2.getY() - o3.getY()) < resolution.getMaxX() * 0.09375) {
                                    shouldMoveThisNote = false;
                                    break;
                                }
                            }
                        } else {
                            // 避免冲突，若该按键前一定时间内有紧跟的键，则该键不能前移
                            for (int k = i - 1; k >= 0; k--) {
                                SimpleAction o3 = simpleActions.get(k);
                                if (o1.getTiming() - o3.getTiming() > 200) {
                                    break;
                                }
                                if (Math.abs(o1.getX() - o3.getX()) < resolution.getMaxX() * 0.09375
                                        && Math.abs(o1.getY() - o3.getY()) < resolution.getMaxX() * 0.09375) {
                                    shouldMoveThisNote = false;
                                    break;
                                }
                            }
                        }
                        if (shouldMoveThisNote) {
                            if (isLate) {
                                o1.setTiming(o1.getTiming() + 25);
                                o2.setTiming(o2.getTiming() + 25);
                            } else {
                                o1.setTiming(o1.getTiming() - 25);
                                o2.setTiming(o2.getTiming() - 25);
                            }
                            simpleActions.set(i, o1);
                            simpleActions.set(j, o2);
                            isLate = !isLate;
                            minPureNum++;
                        }
                    }
                    processedNum++;
                }
                break;
            }
        }
        if (missNum != miss) {
            System.out.println(affPath + " 问题如下：");
            System.out.println("脚本Lost数量 " + missNum + " 低于预计Lost数量 " + miss + "！");
        }
        /*
        if (minPureNum != minPure) {
            System.out.println(affPath + " 问题如下：");
            System.out.println("脚本小p数量 " + minPureNum + " 低于预计小p数量 " + minPure + "！");
        }
         */
    }


    /*-- 生成脚本 --*/

    /**
     * 将某个脚本保存至指定目录.
     */
    public void save(File targetDir, String song, String difficult,
                     int miss, int minPure, boolean haveBegin, boolean mirror) {
        JSONArray operationsArray = new JSONArray();
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
        String formatStr = obj.toString(SerializerFeature.PrettyFormat);// 80%时间
        targetDir.mkdirs();
        File recordFile = new File(targetDir, song + "_" + s + ".record");
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(recordFile))) {
            bw.write(formatStr);// 10%时间
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void add(JSONArray points, SimpleAction simpleAction, boolean mirror) {
        JSONObject obj = new JSONObject();
        obj.put("id", simpleAction.getId());
        obj.put("x", mirror ? resolution.getMaxX() - simpleAction.getX() : simpleAction.getX());
        obj.put("y", simpleAction.getY());
        obj.put("state", simpleAction.getState());
        points.add(obj);
    }
}
