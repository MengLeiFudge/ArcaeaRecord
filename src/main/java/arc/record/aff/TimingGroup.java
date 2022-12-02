package arc.record.aff;

import arc.record.aff.note.Note;
import arc.record.aff.timing.Timing;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * @author MengLeiFudge
 */
public class TimingGroup implements Serializable {
    /**
     * 指示该 timinggroup 是否带有 noinput 参数.
     * <p>
     * 带有 noinput 参数时，内部所有 note 仅具备显示效果.
     */
    boolean noInput;

    /**
     * 该 timinggroup 包含的所有 timing.
     */
    List<Timing> timingList = new ArrayList<>();

    /**
     * 该 timinggroup 包含的所有 note.
     */
    List<Note> noteList = new ArrayList<>();

    public TimingGroup(boolean noInput) {
        this.noInput = noInput;
    }
}
