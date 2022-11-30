package arcaea.record.aff.note;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

/**
 * @author MengLeiFudge
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class Arc extends Note implements Serializable, Comparable<Note> {
    double x1;
    double x2;
    String easing;
    double y1;
    double y2;
    int color;
    boolean skylineBoolean;
    /**
     * 存放所有的 arctap.
     */
    List<Integer> arctapList = new ArrayList<>();
    /**
     * 指示该蛇是否具有头判定.
     */
    boolean hasHead = false;

    public Arc(String line) {
        skylineBoolean = line.contains("arctap");
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
            // 不含天键情况下，蛇可能为黑线；含天键情况下，必定为黑线
            skylineBoolean = Boolean.parseBoolean(data[9]);
            return;
        }
        data = line.substring(line.indexOf(")[") + 2, line.length() - 2).split(",");
        for (var x : data) {
            x = x.replaceAll("arctap\\(|\\)", "");
            arctapList.add(Integer.parseInt(x));
        }
    }

    @Override
    public int compareTo(Note o) {
        if (o instanceof Click oClick) {
            if (t1 != oClick.t1) {
                return t1 - oClick.t1;
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
            //蛇在前，天键在后
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

    /**
     * 返回 note 总数.
     * <p>
     * 计算规则如下：
     * <ul>
     *     <li>如果有arctap，返回arctap的数量</li>
     *     <li>如果为黑线，返回0</li>
     *     <li>如果时间长度为0，返回0</li>
     *     <li>按 beatTime 分割为多个判定块，最后一个判定块长度为[1判定块,2判定块)</li>
     *     <li>除第一个判定块外，其余判定块头+1combo</li>
     *     <li>hasHead为true时，第一个判定块头+1combo</li>
     *     <li>至少有1combo</li>
     * </ul>
     *
     * @return 该长条的 note 总数
     */
    @Override
    public int getNoteCount() {
        if (arctapList.size() > 0) {
            return arctapList.size();
        }
        if (skylineBoolean) {
            return 0;
        }
        if (t2 - t1 == 0) {
            return 0;
        }
        int beatCount = (int) ((t2 - t1) / beatTime);
        return Math.max(beatCount + (hasHead ? 0 : -1), 1);
    }

    @Override
    public double[] getAffPoint(int time) {
        if (time < t1 || time > t2) {
            throw new IllegalArgumentException("时间 " + time + " 不在 [" + t1 + ", " + t2 + "] 区间内");
        }
        double timeRatio = t1 == t2 ? 0.0 : (double) (time - t1) / (t2 - t1);
        return switch (easing) {
            case "b" -> {
                if (timeRatio < 0.5) {
                    yield new double[]{
                            getP(x1, (x1 + x2) / 2, false, timeRatio * 2),
                            getP(y1, (y1 + y2) / 2, false, timeRatio * 2),
                    };
                } else {
                    yield new double[]{
                            getP((x1 + x2) / 2, x2, true, (timeRatio - 0.5) * 2),
                            getP((y1 + y2) / 2, y2, true, (timeRatio - 0.5) * 2),
                    };
                }
            }
            case "s" -> new double[]{
                    x1 + (x2 - x1) * timeRatio,
                    y1 + (y2 - y1) * timeRatio,
            };
            case "si" -> new double[]{
                    getP(x1, x2, true, timeRatio),
                    y1 + (y2 - y1) * timeRatio,
            };
            case "so" -> new double[]{
                    getP(x1, x2, false, timeRatio),
                    y1 + (y2 - y1) * timeRatio,
            };
            case "sisi" -> new double[]{
                    getP(x1, x2, true, timeRatio),
                    getP(y1, y2, true, timeRatio),
            };
            case "siso" -> new double[]{
                    getP(x1, x2, true, timeRatio),
                    getP(y1, y2, false, timeRatio),
            };
            case "sosi" -> new double[]{
                    getP(x1, x2, false, timeRatio),
                    getP(y1, y2, true, timeRatio),
            };
            case "soso" -> new double[]{
                    getP(x1, x2, false, timeRatio),
                    getP(y1, y2, false, timeRatio),
            };
            default -> throw new IllegalArgumentException("错误的蛇类型" + easing);
        };
    }

    private static double getP(double pStart, double pEnd, boolean isSi, double timeRatio) {
        // 将时间比例转换为正弦/余弦比例
        // arc 实际使用贝塞尔曲线，使用正弦/余弦近似
        // 0<t<1, si(t)=sin(t*pi/2), so(t)=1-cos(t*pi/2)
        double ratioB = isSi ? Math.sin(timeRatio * Math.PI / 2) : 1 - Math.cos(timeRatio * Math.PI / 2);
        return pStart + ratioB * (pEnd - pStart);
    }
}
