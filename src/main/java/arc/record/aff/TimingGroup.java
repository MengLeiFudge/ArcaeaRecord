package arc.record.aff;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import arc.record.aff.note.Note;
import arc.record.aff.timing.Timing;

/**
 * AFF timinggroup 的输入语义和局部 timing 上下文。
 *
 * @author MengLeiFudge
 */
public class TimingGroup implements Serializable {
    /** 基础组为 0，其余组按出现顺序递增。 */
    final int id;
    /** 带有 noinput 参数时，组内玩法对象在格式校验后不进入输入模型。 */
    final boolean noInput;
    /** 该 timinggroup 包含的全部 timing。 */
    final List<Timing> timingList = new ArrayList<>();
    /** 需要进入最终输入需求列表的 Note。 */
    final List<Note> noteList = new ArrayList<>();
    /** 需要赋予当前 timing 上下文的输入 Note。 */
    final List<Note> sourceNotes = new ArrayList<>();

    public TimingGroup(int id, boolean noInput) {
        this.id = id;
        this.noInput = noInput;
    }
}
