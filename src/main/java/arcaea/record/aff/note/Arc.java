package arcaea.record.aff.note;

import arcaea.record.aff.timing.Timing;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * @author MengLeiFudge
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class Arc extends Note implements Serializable, Comparable<Note> {
    //arc(t1,t2,x1,x2,easing,y1,y2,color,hitsound,skylineBoolean);
    //arc(t1,t2,x1,x2,easing,y1,y2,color,hitsound,true)[arctap(tn1),arctap(tn2),……,arctap(tnm)];

    int t1;
    int t2;
    double x1;
    double x2;
    String easing;
    double y1;
    double y2;
    int color;
    boolean skylineBoolean;
    List<Integer> tList = new ArrayList<>();

    public Arc(String line) {
        boolean skylineBoolean = line.contains("arctap");
        String[] data;
        if (!skylineBoolean) {
            data = line.substring("arc(".length(), line.length() - 2).split(",");
        } else {
            data = line.substring("arc(".length(), line.indexOf(")[")).split(",");
        }
        t1 = Integer.parseInt(data[0]);
        t2 = Integer.parseInt(data[1]);
        x1 = Double.parseDouble(data[2]);
        x2 = Double.parseDouble(data[3]);
        easing = data[4];
        y1 = Double.parseDouble(data[5]);
        y2 = Double.parseDouble(data[6]);
        color = Integer.parseInt(data[7]);
        // data[8] 是打击音效，无用
        if (!skylineBoolean) {
            // 不含天键情况下，可能为蛇可能为黑线；含天键情况下，必定为黑线
            skylineBoolean = Boolean.parseBoolean(data[9]);
            return;
        }
        data = line.substring(line.indexOf(")[") + 2, line.length() - 2).split(",");
        for (var x : data) {
            x = x.replaceAll("arctap\\(|\\)", "");
            tList.add(Integer.parseInt(x));
        }
    }

    @Override
    public int compareTo(Note o) {
        if (o instanceof Click oClick) {
            if (t1 != oClick.t) {
                return t1 - oClick.t;
            }
            return 1;
        }
        if (o instanceof Hold oHold) {
            if (t1 != oHold.t1) {
                return t1 - oHold.t1;
            }
            return 1;
        }
        if (o instanceof Arc oArc) {
            if (t1 != oArc.t1) {
                return t1 - oArc.t1;
            }
            if (t2 != oArc.t2) {
                return t2 - oArc.t2;
            }
            //蛇在前，天键在后（list里面不会存黑线，不用管）
            if (skylineBoolean != oArc.skylineBoolean) {
                return skylineBoolean ? 1 : -1;
            }
            if (color != oArc.color) {
                return color - oArc.color;
            }
            if (x1 != oArc.x1) {
                return x1 - oArc.x1 > 0 ? 1 : -1;
            }
            if (x2 != oArc.x2) {
                return x2 - oArc.x2 > 0 ? 1 : -1;
            }
            if (y1 != oArc.y1) {
                return y1 - oArc.y1 > 0 ? 1 : -1;
            }
            if (y2 != oArc.y2) {
                return y2 - oArc.y2 > 0 ? 1 : -1;
            }
            return easing.compareTo(oArc.easing);
        }
        throw new IllegalArgumentException("无法比较 " + this.getClass() + " 与 " + o.getClass());
    }

    @Override
    public int getNote(List<Timing> timingList, double timingPointDensityFactor) {
        if (tList.size() > 0) {
            return tList.size();
        }
        if (isSkylineBoolean()) {
            return 0;
        }
        double beatTime = getBeatTime(timingList, timingPointDensityFactor, t1);
        return Math.max((int) ((t2 - t1) / beatTime) - 1, 1);
    }
}
