package arcaea.record.aff.note;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * @author MengLeiFudge
 */
public class Arc extends Note implements Serializable {

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
}
