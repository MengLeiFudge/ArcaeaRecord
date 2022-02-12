package arcaea_record.convert.base;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import static arcaea_record.SettingsAndUtils.INTERVAL_TIME;
import static arcaea_record.SettingsAndUtils.MAX_TOUCH_NUM;

/**
 * 该类用于生成脚本时，为每个非蛇操作分配id.
 *
 * @author MengLeiFudge
 */
public class TouchIdManager implements Serializable {
    /**
     * 存储所有ID的触控情况.
     */
    private final List<ArrayList<TouchTime>> touchTimeLists = new ArrayList<>();

    public TouchIdManager() {
        for (int i = 0; i < MAX_TOUCH_NUM; i++) {
            touchTimeLists.add(new ArrayList<>());
        }
    }

    /**
     * 非蛇操作基础类，仅包含开始时间、结束时间.
     */
    @Data
    private static class TouchTime implements Serializable {
        int beginTime;
        int endTime;

        TouchTime(int b, int e) {
            beginTime = b;
            endTime = e;
        }
    }

    /**
     * 获取指定时间段情况下，可用的最小ID.
     *
     * @param beginTime 操作开始时间
     * @param endTime   操作结束时间
     * @return 可用的最小ID，如果返回-1那一定是谱子有问题！
     */
    public int getId(int beginTime, int endTime) {
        for (int i = 0; i < touchTimeLists.size(); i++) {
            if (canUseId(i, beginTime, endTime)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 在开始时间到结束时间这一时期，能否使用指定ID.
     *
     * @param id        指定ID
     * @param beginTime 开始时间
     * @param endTime   结束时间
     * @return 能否使用指定ID
     */
    private boolean canUseId(int id, int beginTime, int endTime) {
        List<TouchTime> list = touchTimeLists.get(id);
        // 列表空，直接添加
        if (list.isEmpty()) {
            list.add(new TouchTime(beginTime, endTime));
            return true;
        }
        // 开头能否添加
        if (list.get(0).beginTime - endTime > INTERVAL_TIME) {
            list.add(0, new TouchTime(beginTime, endTime));
            return true;
        }
        // 中间能否添加
        for (int i = 0; i < list.size() - 1; i++) {
            TouchTime t1 = list.get(i);
            TouchTime t2 = list.get(i + 1);
            if (beginTime - t1.endTime > INTERVAL_TIME && t2.beginTime - endTime > INTERVAL_TIME) {
                list.add(i + 1, new TouchTime(beginTime, endTime));
                return true;
            }
        }
        // 结尾能否添加
        if (beginTime - list.get(list.size() - 1).endTime > INTERVAL_TIME) {
            list.add(new TouchTime(beginTime, endTime));
            return true;
        }
        return false;
    }
}
