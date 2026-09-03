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
     * 解析阶段分配的稳定来源身份；深拷贝和变体修改后保持不变。
     */
    int sourceId = -1;

    /**
     * 来源 timinggroup 的稳定编号；基础组为 0。
     */
    int timingGroupId;

    /**
     * 指示来源 timinggroup 是否具有 noinput 参数。
     */
    boolean noInput;

    /**
     * 起始 timing 的绝对 BPM，已应用高 BPM 折半但尚未乘密度系数。
     */
    double timingBpm;

    /**
     * 长键判定公式使用的绝对 BPM，已应用高 BPM 折半和密度系数。
     */
    double judgeBpm;

    /**
     * 根据按键所在 timing 的 bpm 计算的判定块间隔.
     */
    float beatTime;
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
        // 这里必须先把两个Action建立起联系，再移除不需要的Action
        // 否则，可能出现b.actionDownList刚好只有一个Action而导致b.actionDownList为空从而无法连接的情况
        manager.merge(a.getFirstAction(), b.getFirstAction());
        a.actionUp = null;
        b.actionDownList.removeIf(o -> o.t() <= a.actionDownList.getFirst().t());
    }

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
     * 初始化该 note 对应的所有操作.
     * <p>
     * 特别的，直蛇可以修改 timing 并将 actionDownList 重置为 null，再重新初始化。
     *
     * @param actionUnionFind 管理作的实例对象
     */
    public void initActions(UnionFind<Action> actionUnionFind) {
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
        } else if (this instanceof Arc arc) {
            if (getNoteCount() == 0) {
                actionDownList.add(new Action(arc.x1, arc.y1, t1));
                actionUp = new Action(arc.x2, arc.y2, t2);
                actionUnionFind.add(getActions());
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
        if (!actionDownList.isEmpty()) {
            return actionDownList.getFirst();
        }
        if (actionUp != null) {
            return actionUp;
        }
        return null;
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
