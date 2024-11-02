package arc.record.aff.note;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import arc.record.aff.action.Action;
import arc.record.utils.UnionFind;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import static arc.record.Settings.TOUCH_SAMPLE_FREQUENCY;

/**
 * 一个Note有多个判定点，有多个触控点。
 *
 * @author MengLeiFudge
 */
@Data
public abstract class Note implements Serializable, Comparable<Note> {
    /**
     * 起始时间.
     */
    int t1;

    /**
     * 结束时间.
     */
    int t2;

    /**
     * 根据按键所在 timing 的 bpm 计算的判定块间隔.
     */
    float beatTime;

    /**
     * 返回该键型对应的总 note 数.
     */
    public abstract int getNoteCount();

    public double[] getAffPoint() {
        return getAffPoint(t1);
    }

    /**
     * 根据传入的时间戳，计算出在谱面上的 xy 坐标.
     *
     * @param time 要计算坐标的谱面时间戳
     * @return 转换后的谱面坐标 x, y
     */
    public abstract double[] getAffPoint(int time);

    /**
     * 按下、移动的操作集合.
     */
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private List<Action> actionDownList = null;

    /**
     * 抬起的操作.
     */
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private Action actionUp = null;

    /**
     * 初始化该 note 对应的所有操作.
     *
     * @param actionUnionFind 管理作的实例对象
     */
    public void initActions(UnionFind<Action> actionUnionFind) {
        if (actionDownList != null) {
            return;
        }
        actionDownList = new ArrayList<>();
        double[] xy;
        if (this instanceof Click || this instanceof ArcTap) {
            xy = getAffPoint(t1);
            actionDownList.add(new Action(xy[0], xy[1], t1));
        } else if (this instanceof Hold) {
            float timeAdd = beatTime / TOUCH_SAMPLE_FREQUENCY;
            for (float t = t1; t < t2; t += timeAdd) {
                xy = getAffPoint((int) t);
                actionDownList.add(new Action(xy[0], xy[1], (int) t));
            }
        } else if (this instanceof Arc) {
            if (getNoteCount() == 0) {
                return;
            }
            // 注意时间小于200ms的超短蛇的处理
            float timeAdd = Math.max(beatTime / TOUCH_SAMPLE_FREQUENCY, 1);
            for (float t = t1; t < t2; t += timeAdd) {
                xy = getAffPoint((int) t);
                actionDownList.add(new Action(xy[0], xy[1], (int) t));
            }
        }
        xy = getAffPoint(t2);
        actionUp = new Action(xy[0], xy[1], t2);
        actionUnionFind.add(getActions());
    }

    public List<Action> getActions() {
        if (actionDownList == null) {
            throw new IllegalStateException("获取前需要初始化！");
        }
        List<Action> actions = new ArrayList<>(actionDownList);
        if (actionUp != null) {
            actions.add(actionUp);
        }
        return actions;
    }

    public Action getFirstAction() {
        if (actionDownList == null) {
            throw new IllegalStateException("获取前需要初始化！");
        }
        if (actionDownList.size() > 0) {
            return actionDownList.get(0);
        }
        if (actionUp != null) {
            return actionUp;
        }
        return null;
    }

    /**
     * 合并两个 Note 的操作.
     * <p>
     * 有两种合并规则：
     * <ul>
     *     <li>直接合并，例如 长条/蛇 + 蛇</li>
     *     <li>有优先级的合并，例如 单点/天键 + 蛇</li>
     * </ul>
     * 注意，mergeNotes(a, b, manager) 与 mergeNotes(b, a, manager) 不等价，
     * 所以需要在调用前确保合并是必要的，且参数顺序正确。
     *
     * @param a       要合并操作的 Note
     * @param b       要合并操作的 Note
     * @param manager 管理作的实例对象
     */
    public static void mergeNotes(Note a, Note b, UnionFind<Action> manager) {
        a.actionUp = null;
        b.actionDownList.removeIf(o -> o.t() <= a.actionDownList.get(0).t());
        manager.merge(a.getFirstAction(), b.getFirstAction());
        /*if ((a instanceof Click || a instanceof ArcTap) && b instanceof Arc) {
            a.actionUp = null;
            b.actionDownList.removeIf(o -> o.t() <= a.actionDownList.get(0).t());
            manager.merge(a.getFirstAction(), b.getFirstAction());
        } else if ((a instanceof Hold || a instanceof Arc) && b instanceof Arc) {
            a.actionUp = null;
            b.actionDownList.removeIf(o -> o.t() <= a.actionDownList.get(0).t());
            manager.merge(a.getFirstAction(), b.getFirstAction());
        } else {
            throw new IllegalStateException("未知组合键型混合");
        }*/
    }

    @Override
    public int compareTo(Note o) {
        if (t1 != o.t1) {
            return t1 - o.t1;
        }
        if (t2 != o.t2) {
            return t2 - o.t2;
        }
        return 0;
    }
}
