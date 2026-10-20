import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import arc.record.aff.Aff;
import arc.record.aff.judge.ChartJudgementModel;
import arc.record.record.model.Resolution;
import arc.record.record.verify.ForwardReplay;
import arc.record.record.verify.ReplayContext;
import arc.record.record.verify.ReplayReport;

import static arc.record.Settings.CLICK_TIME;
import static arc.record.Settings.FIRST_NOTE_TIME;

/** 直接检查已有 1280×720 record，不调用生成器，不覆盖输入脚本。 */
public final class ReplayCheck {
    private ReplayCheck() {
    }

    /**
     * @param args AFF、record、报告路径，可选的 AFF 诊断时刻及镜像标志
     * @throws Exception 输入读取、谱面解析或报告写入失败
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 5) {
            throw new IllegalArgumentException("参数：AFF record report.json [AFF时刻] [mirror]");
        }
        Aff chart = new Aff(Path.of(args[0]).toFile());
        String json = Files.readString(Path.of(args[1]), StandardCharsets.UTF_8);
        ReplayContext context = new ReplayContext(Resolution.R16_9_1280_720,
                args.length > 4 && Boolean.parseBoolean(args[4]), FIRST_NOTE_TIME + CLICK_TIME);
        ReplayReport report = new ForwardReplay().verify(chart, json, context);
        Path output = Path.of(args[2]).toAbsolutePath();
        Files.createDirectories(output.getParent());
        Files.writeString(output, report.toJson(), StandardCharsets.UTF_8);
        System.out.println(report.status() + " " + chart.getAffFile() + " -> " + output);
        for (ReplayReport.CaseResult scene : report.cases()) {
            System.out.println("offset=" + scene.offsetMillis() + " " + scene.status()
                    + " " + scene.stats()
                    + (scene.failure() == null ? "" : " " + scene.failure().reason()));
        }
        if (args.length > 3) {
            double at = Double.parseDouble(args[3]);
            for (var note : chart.getNoteList()) {
                if (note.getT1() <= at + 300 && note.getT2() >= at - 300) {
                    System.out.println("SOURCE " + note.getSourceId() + " " + note);
                }
            }
            for (var demand : ChartJudgementModel.build(chart, chart.getNoteList()).longNoteDemands()) {
                for (var point : demand.demands()) {
                    if (point.window().startTime() <= at + 300 && point.window().endTime() >= at - 300) {
                        System.out.println("WINDOW source=" + demand.source().getSourceId()
                                + " nominal=" + point.point().nominalTime()
                                + " range=[" + point.window().startTime() + "," + point.window().endTime() + "]");
                    }
                }
            }
        }
        System.exit(report.status() == ReplayReport.Status.PASS ? 0 : 1);
    }
}
