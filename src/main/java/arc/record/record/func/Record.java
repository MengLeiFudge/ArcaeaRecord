package arc.record.record.func;

import arc.record.record.model.SimpleAction;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;

/**
 * 谱面数据结构.
 *
 * @author MengLeiFudge
 */
@Data
@Deprecated
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
    private final ArrayList<SimpleAction> simpleActions = new ArrayList<>();

    Record() {
    }

    /**
     * 设置按键整体偏移时间，以及脚本结束时间
     */
    public void setTime() {
        this.noteOffsetTime = 10000;
        this.recordEndTime = maxTime + 10500;
    }

    /*-- 排序、修正与优化 --*/

    public void optimize(String affPath, int miss, int minPure) {
        //optimizeArcActions(affPath);
        //processMissAndMinPure(affPath, miss, minPure);
        // 调整所有操作的 timing
        for (SimpleAction simpleAction : simpleActions) {
            simpleAction.timing() += noteOffsetTime;
        }
        Collections.sort(simpleActions);
    }
}
