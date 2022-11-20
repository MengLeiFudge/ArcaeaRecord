package arcaea.record.aff;

import arcaea.record.aff.note.Note;
import arcaea.record.aff.timing.Timing;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class TimingGroup implements Serializable {
    List<Timing> timingList = new ArrayList<>();
    List<Note> noteList = new ArrayList<>();
}
