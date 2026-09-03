package arc.record.aff.judge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import arc.record.aff.note.Arc;
import arc.record.aff.note.Note;

/**
 * 长键名义判定点的统一计算入口。
 *
 * <p>这里仅计算物量身份及其名义时间。实际命中时刻由判定窗口和触控调度器决定。</p>
 */
public final class LongNoteJudgement {
    private static final double EPSILON = 1e-9;

    private LongNoteJudgement() {
    }

    /**
     * 计算一个 Hold 或 Arc 的全部名义判定时间。
     *
     * @param note 已赋予起始 timing 上下文的长键
     * @return 保留重复身份并按时间排序的毫秒时间列表
     */
    public static List<Double> nominalTimes(Note note) {
        if (note.getT2() <= note.getT1()) {
            return List.of();
        }
        double bpm = note.getJudgeBpm();
        if (bpm == 0) {
            return List.of((double) note.getT1());
        }
        double beatMillis = 60000.0 / bpm;
        double beats = (note.getT2() - note.getT1()) / beatMillis;
        List<Double> times = new ArrayList<>();
        if (beats < 0.5) {
            times.add((double) note.getT1());
        } else if (beats <= 1.0) {
            times.add(note.getT1() + (beats - 0.5) / 2.0 * beatMillis);
        } else {
            int count = Math.max(1, (int) Math.floor(beats * 2.0 + EPSILON) - 1);
            for (int n = 1; n <= count; n++) {
                times.add(note.getT1() + (n / 2.0 - 0.25) * beatMillis);
            }
        }

        if (note instanceof Arc arc) {
            if (arc.hasPredecessor()) {
                if (beats < 1.0) {
                    times.set(0, (double) arc.getT1());
                } else {
                    times.add((double) arc.getT1());
                }
            }
            if (arc.hasSuccessor() && beats >= 1.0 && times.size() >= 2) {
                double integralHalfBeats = Math.floor(beats * 2.0 + EPSILON) / 2.0;
                double integralLength = integralHalfBeats * beatMillis;
                if (arc.getT2() - arc.getT1() - integralLength <= 1.0 + EPSILON) {
                    Collections.sort(times);
                    times.set(times.size() - 1, times.get(times.size() - 2));
                }
            }
        }
        Collections.sort(times);
        return List.copyOf(times);
    }

    /**
     * 返回长键起始 timing 下的一拍时长。
     *
     * @param note 已赋予 timing 上下文的长键
     * @return 毫秒；BPM 为零时返回正无穷
     */
    public static double beatMillis(Note note) {
        return note.getJudgeBpm() == 0 ? Double.POSITIVE_INFINITY : 60000.0 / note.getJudgeBpm();
    }
}
