package arc.record.record.model;

import java.io.Serializable;

/**
 * 该类表示脚本中的某个基础操作，即在指定时间、指定位置的按下【或】抬起操作.
 *
 * @author MengLeiFudge
 */
public record SimpleAction(int timing, int id, int x, int y, boolean isPressDown)
        implements Serializable, Comparable<SimpleAction> {
    /**
     * 获取操作类型在脚本中的表示.
     *
     * @return 按下返回 1，抬起返回 0
     */
    public int getState() {
        return isPressDown ? 1 : 0;
    }

    @Override
    public int compareTo(SimpleAction o) {
        if (timing != o.timing) {
            return timing - o.timing;
        }
        if (id != o.id) {
            return id - o.id;
        }
        if (isPressDown != o.isPressDown) {
            return isPressDown ? 1 : -1;
        }
        if (x != o.x) {
            return x - o.x;
        }
        return y - o.y;
    }

    @Override
    public String toString() {
        return "SimpleAction(timing=" + timing + ", id=" + id +
                ", x=" + x + ", y=" + y + ", press=" + (isPressDown ? "按下" : "抬起") + ")";
    }
}
