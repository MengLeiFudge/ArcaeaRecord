package arc.record.record.base;

import arc.record.SettingsAndUtils;
import arc.record.record.data.Resolution;
import arc.record.record.data.SimpleAction;
import arc.record.record.data.TouchIdManager;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import lombok.Data;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 谱面数据结构.
 *
 * @author MengLeiFudge
 */
@Data
public class Record implements Serializable {
    /**
     * 谱面按键时间最小值.
     */
    int minTime = 999999;
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

    Record() {
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


    /*-- 按键转为 Action 并保存在 actions 中 --*/

    /**
     * 获取非蛇按键的ID.
     *
     * @param beginTime 操作开始时间
     * @param endTime   操作结束时间
     * @return 该操作对应的ID
     */
    private int getID(int beginTime, int endTime) {
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
       /* press(beginTime, endTime,
                resolution.convertToX((double) position / 2 - 0.75, 0.0),
                resolution.convertToY(0.0));*/
    }

    public void arc(int beginTime, int endTime, double beginX, double endX,
                    String type, double beginY, double endY, int color) {
      /*  if (beginTime == endTime) {
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
        }*/
    }

    public void arctap(int beginTime, int endTime, double beginX, double endX,
                       String type, double beginY, double endY, int time) {
        /*press(time, time + SettingsAndUtils.CLICK_TIME,
                getX(beginTime, endTime, beginX, endX, type, beginY, endY, time) + getRandomPositionDeflection(),
                getY(beginTime, endTime, beginX, endX, type, beginY, endY, time) + getRandomPositionDeflection());
        clickNum++;*/
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
        /*boolean[] isArcBegin = new boolean[SettingsAndUtils.ARC_KINDS];
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
        }*/
    }

    /**
     * 根据miss和小p数目，调整按键
     *
     * @param miss    miss数
     * @param minPure 小p数
     */
    private void processMissAndMinPure(String affPath, int miss, int minPure) {
        /*// 目前已经处理完毕的单点个数
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
