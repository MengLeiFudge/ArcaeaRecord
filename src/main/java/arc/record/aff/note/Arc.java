package arc.record.aff.note;

import java.util.ArrayList;
import java.util.List;

import arc.record.aff.judge.LongNoteJudgement;
import lombok.Data;
import lombok.EqualsAndHashCode;

import static arc.record.Utils.dfTime;
import static arc.record.Utils.dfXY;

/**
 * AFF Arc 对象，保留输入类型、曲线、颜色和 Arctap 信息。
 *
 * @author MengLeiFudge
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class Arc extends Note {
    /** Arc 起点在 AFF 坐标系中的横坐标。 */
    final double x1;
    /** Arc 终点在 AFF 坐标系中的横坐标。 */
    final double x2;
    /** Arc 缓动类型。 */
    final String easing;
    /** Arc 起点在 AFF 坐标系中的纵坐标。 */
    final double y1;
    /** Arc 终点在 AFF 坐标系中的纵坐标。 */
    final double y2;
    /** Arc 颜色编号，取值范围为 0-3。 */
    final int color;
    /** Arc 视觉平滑度；该值不参与判定轨迹生成。 */
    @EqualsAndHashCode.Exclude
    final double smoothness;
    /** AFF 中的 false、true 或 designant 原始类型。 */
    final ArcType arcType;
    /** Arc 上的 Arctap 时间列表。 */
    final List<Integer> arctapTimingList = new ArrayList<>();
    /** 是否至少存在一个严格首尾连接前驱。 */
    boolean hasHead;
    /** 是否至少存在一个严格首尾连接后继。 */
    boolean hasTail;

    public Arc(String line) {
        boolean containsArctap = line.contains("arctap");
        String[] data = containsArctap
                ? line.substring("arc(".length(), line.indexOf(")[")).split(",")
                : line.substring("arc(".length(), line.length() - 2).split(",");
        super.t1 = Integer.parseInt(data[0]);
        super.t2 = Integer.parseInt(data[1]);
        this.x1 = Double.parseDouble(data[2]);
        this.x2 = Double.parseDouble(data[3]);
        this.easing = data[4];
        this.y1 = Double.parseDouble(data[5]);
        this.y2 = Double.parseDouble(data[6]);
        this.color = Integer.parseInt(data[7]);
        this.arcType = ArcType.fromAffValue(data[9]);
        this.smoothness = data.length > 10 ? Double.parseDouble(data[10]) : 1.0;
        if (!containsArctap) {
            return;
        }
        String[] arctaps = line.substring(line.indexOf(")[") + 2, line.length() - 2).split(",");
        for (String arctap : arctaps) {
            arctapTimingList.add(Integer.parseInt(arctap.replaceAll("arctap\\(|\\)", "")));
        }
    }

    /**
     * 判断父 Arc 是否具有持续输入身份。
     *
     * <p>悬挂 Arctap 是独立点击身份，不改变 false 父 Arc 的持续输入语义。</p>
     *
     * @return 原始类型为 false 时返回 true
     */
    public boolean isRealArc() {
        return arcType.acceptsInput();
    }

    /**
     * 判断当前 Arc 的尾是否严格连接到另一 Arc 的头。
     *
     * @param next 候选后继
     * @return 原始类型、横纵坐标和时间均满足严格规则时返回 true
     */
    public boolean connectsTo(Arc next) {
        return arcType == next.arcType
                && Math.abs(x2 - next.x1) < 0.1
                && Double.compare(y2, next.y1) == 0
                && Math.abs(t2 - next.t1) < 10;
    }

    public boolean hasPredecessor() {
        return hasHead;
    }

    public boolean hasSuccessor() {
        return hasTail;
    }

    private static double getP(double start, double end, boolean isSi, double ratio) {
        double eased = isSi ? Math.sin(ratio * Math.PI / 2) : 1 - Math.cos(ratio * Math.PI / 2);
        return start + eased * (end - start);
    }

    /**
     * 返回父 Arc 自身携带的持续物量。
     *
     * <p>Arctap 由独立 Note 计数；携带 Arctap 的父 Arc 仍提供持续覆盖，但自身不重复计量。</p>
     *
     * @return 输入 Arc 的名义判定点数量
     */
    @Override
    public int getNoteCount() {
        if (!arctapTimingList.isEmpty()) {
            return 0;
        }
        if (!arcType.acceptsInput() || noInput || t1 == t2) {
            return 0;
        }
        return LongNoteJudgement.nominalTimes(this).size();
    }

    @Override
    public double[] getAffPoint(int time) {
        return getAffPoint((double) time);
    }

    /**
     * 计算未量化时刻的 Arc 精确 AFF 坐标。
     *
     * @param time 谱面时间，单位为毫秒
     * @return AFF 横纵坐标
     */
    public double[] getAffPoint(double time) {
        if (time < t1 - 1e-7 || time > t2 + 1e-7) {
            throw new IllegalArgumentException("时间 " + time + " 不在 [" + t1 + ", " + t2 + "] 区间内");
        }
        double pointTime = Math.max(t1, Math.min(time, t2));
        double ratio = t1 == t2 ? 0.0 : (pointTime - t1) / (t2 - t1);
        return switch (easing) {
            case "b" -> {
                if (ratio < 0.5) {
                    yield new double[]{
                            getP(x1, (x1 + x2) / 2, false, ratio * 2),
                            getP(y1, (y1 + y2) / 2, false, ratio * 2),
                    };
                }
                yield new double[]{
                        getP((x1 + x2) / 2, x2, true, (ratio - 0.5) * 2),
                        getP((y1 + y2) / 2, y2, true, (ratio - 0.5) * 2),
                };
            }
            case "s" -> new double[]{x1 + (x2 - x1) * ratio, y1 + (y2 - y1) * ratio};
            case "si" -> new double[]{getP(x1, x2, true, ratio), y1 + (y2 - y1) * ratio};
            case "so" -> new double[]{getP(x1, x2, false, ratio), y1 + (y2 - y1) * ratio};
            case "sisi" -> new double[]{getP(x1, x2, true, ratio), getP(y1, y2, true, ratio)};
            case "siso" -> new double[]{getP(x1, x2, true, ratio), getP(y1, y2, false, ratio)};
            case "sosi" -> new double[]{getP(x1, x2, false, ratio), getP(y1, y2, true, ratio)};
            case "soso" -> new double[]{getP(x1, x2, false, ratio), getP(y1, y2, false, ratio)};
            default -> throw new IllegalArgumentException("错误的蛇类型 " + easing);
        };
    }

    /**
     * 根据原始 Arc 创建稳定来源可追踪的 Arctap。
     *
     * <p>Arctap 允许超出 Arc 首尾各 1 ms；触控时间保留原值，坐标按最近的 Arc 端点计算。</p>
     *
     * @return 新建 Arctap 列表
     */
    public List<ArcTap> getArcTapList() {
        List<ArcTap> result = new ArrayList<>();
        for (int time : arctapTimingList) {
            long minimumTime = (long) t1 - 1;
            long maximumTime = (long) t2 + 1;
            if (time < minimumTime || time > maximumTime) {
                throw new IllegalArgumentException(
                        "Arctap 时间 " + time + " 不在允许区间 ["
                                + minimumTime + ", " + maximumTime + "] 内");
            }
            int positionTime = Math.max(t1, Math.min(time, t2));
            result.add(new ArcTap(
                    getAffPoint(positionTime), time, sourceId, arcType != ArcType.DESIGNANT));
        }
        return result;
    }

    @Override
    public String toString() {
        return "arc" + color + "  "
                + " [" + dfTime.format(t1) + ", " + dfTime.format(t2) + "]"
                + " (" + dfXY.format(x1) + ", " + dfXY.format(y1) + ")"
                + " (" + dfXY.format(x2) + ", " + dfXY.format(y2) + ")";
    }
}
