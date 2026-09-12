package arc.record.aff.judge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import arc.record.aff.Aff;
import arc.record.aff.note.Arc;
import arc.record.aff.note.ArcTap;
import arc.record.aff.note.Click;
import arc.record.aff.note.Hold;
import arc.record.aff.note.Note;

/**
 * 一份 miss/小 Pure 变体的统一判定需求模型。
 */
public final class ChartJudgementModel {
    private final List<PressDemand> pressDemands;
    private final List<LongNoteDemand> longNoteDemands;

    private ChartJudgementModel(List<PressDemand> pressDemands,
                                List<LongNoteDemand> longNoteDemands) {
        this.pressDemands = List.copyOf(pressDemands);
        this.longNoteDemands = List.copyOf(longNoteDemands);
    }

    /**
     * 从变体 Note 列表建立普通点击和长键窗口。
     *
     * @param aff      保留输入实体 Arc 拓扑的谱面
     * @param noteList 已完成 miss/小 Pure 修改的输入 Note
     * @return 判定需求模型
     */
    public static ChartJudgementModel build(Aff aff, List<Note> noteList) {
        List<PressDemand> presses = new ArrayList<>();
        List<LongNoteDemand> longNotes = new ArrayList<>();
        int pointId = 0;
        for (Note note : noteList) {
            if (note instanceof Click || note instanceof ArcTap) {
                double[] xy = note.getAffPoint(note.getT1());
                presses.add(new PressDemand(note, note.getT1(), new AffPoint(xy[0], xy[1])));
                continue;
            }
            if (note instanceof Hold hold && hold.getT2() > hold.getT1()) {
                double[] xy = hold.getAffPoint(hold.getT1());
                presses.add(new PressDemand(hold, hold.getT1(), new AffPoint(xy[0], xy[1])));
            }
            if (!(note instanceof Hold) && !(note instanceof Arc)) {
                continue;
            }
            if (note instanceof Arc arc
                    && arc.getT2() > arc.getT1()
                    && arc.getTimingBpm() == 0) {
                throw new IllegalStateException("BPM 为零的输入 Arc 无法生成可靠触控："
                        + aff.getAffFile().getAbsolutePath() + "，sourceId=" + arc.getSourceId());
            }
            List<Double> nominalTimes = LongNoteJudgement.nominalTimes(note);
            if (nominalTimes.isEmpty()) {
                continue;
            }
            List<CoverageDemand> demands = new ArrayList<>(nominalTimes.size());
            double beatMillis = LongNoteJudgement.beatMillis(note);
            for (int i = 0; i < nominalTimes.size(); i++) {
                double nominalTime = nominalTimes.get(i);
                double deadline = Math.min(
                        nominalTime + beatMillis * 0.25 + 500.0,
                        nominalTime + beatMillis);
                if (i + 2 < nominalTimes.size()) {
                    deadline = Math.min(deadline, nominalTimes.get(i + 2));
                }
                if (note instanceof Arc arc && i == nominalTimes.size() - 1) {
                    for (Arc successor : aff.getArcTopology().successorsOf(arc.getSourceId())) {
                        deadline = Math.min(deadline, successor.getT1());
                    }
                }
                double start = Math.max(nominalTime, note.getT1());
                double end = Math.min(deadline, note.getT2());
                boolean countsCombo = !(note instanceof Arc arc)
                        || arc.getArctapTimingList().isEmpty();
                JudgePoint.Kind kind = note instanceof Arc arc
                        && LongNoteJudgement.isAdditionalArcHead(arc, nominalTime)
                        ? JudgePoint.Kind.ARC_HEAD
                        : JudgePoint.Kind.CONTINUOUS;
                JudgePoint point = new JudgePoint(
                        pointId++, nominalTime, note, countsCombo, kind);
                demands.add(new CoverageDemand(point, new JudgeWindow(point, start, end)));
            }
            int componentId;
            int color;
            if (note instanceof Arc arc) {
                componentId = aff.getArcTopology().componentIdOf(arc.getSourceId());
                color = arc.getColor();
            } else {
                componentId = -note.getSourceId() - 1;
                color = -1;
            }
            longNotes.add(new LongNoteDemand(note, componentId, color, List.copyOf(demands)));
        }
        presses.sort(Comparator
                .comparingDouble(PressDemand::time)
                .thenComparingInt(demand -> demand.source().getSourceId()));
        longNotes.sort(Comparator
                .comparingInt((LongNoteDemand demand) -> demand.source().getT1())
                .thenComparingInt(demand -> demand.source().getSourceId()));
        return new ChartJudgementModel(presses, longNotes);
    }

    public List<PressDemand> pressDemands() {
        return pressDemands;
    }

    public List<LongNoteDemand> longNoteDemands() {
        return longNoteDemands;
    }
}
