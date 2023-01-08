package arc.record.record.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import static arc.record.Utils.INTERVAL_TIME;

/**
 * 该类可根据传入的指定时间段，返回最小操作 id.
 *
 * @author MengLeiFudge
 */
public class TouchIdManager implements Serializable {
    /**
     * 存储所有ID的触控情况.
     */
    private final List<List<TouchTime>> touchTimeLists = new ArrayList<>();

    public TouchIdManager() {
    }

    /**
     * 非蛇操作基础类，仅包含开始时间、结束时间.
     */
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
     * @return 可用的最小ID
     */
    public int getId(int beginTime, int endTime) {
        int id = 0;
        while (!canUseId(id, beginTime, endTime)) {
            id++;
        }
        return id;
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
        if (id >= touchTimeLists.size()) {
            List<TouchTime> list = new ArrayList<>();
            list.add(new TouchTime(beginTime, endTime));
            touchTimeLists.add(list);
            return true;
        }
        List<TouchTime> list = touchTimeLists.get(id);
        List<TouchTime> filterList = list.stream()
                .filter(o -> o.beginTime - endTime > INTERVAL_TIME
                        || beginTime - o.endTime > INTERVAL_TIME).toList();
        if (list.size() == filterList.size()) {
            list.add(new TouchTime(beginTime, endTime));
            return true;
        } else {
            return false;
        }
    }
}
