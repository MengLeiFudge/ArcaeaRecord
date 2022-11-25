package arcaea.record.aff;

import arcaea.record.aff.note.Note;
import arcaea.record.aff.timing.Timing;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * @author MengLeiFudge
 */
public class TimingGroup implements Serializable {
    List<Timing> timingList = new ArrayList<>();
    List<Note> noteList = new ArrayList<>();

    public int getNote(double timingPointDensityFactor) {
        int ret = 0;
        for (var note : noteList) {
            ret += note.getNote(timingList, timingPointDensityFactor);
            System.out.println("TimingGroup log: " + ret);
        }
        return ret;
    }
}
