package arcaea.record.aff;

import arcaea.record.aff.note.Arc;
import arcaea.record.aff.note.Click;
import arcaea.record.aff.note.Hold;
import arcaea.record.aff.note.Note;
import arcaea.record.aff.timing.Timing;
import arcaea.record.aff.view.SceneControl;
import lombok.Data;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 谱面文件信息类，具体规则参考中文wiki的谱面格式页面（问题答案为ifi）.
 *
 * @author MengLeiFudge
 */
@Data
public class Aff {
    /**
     * 谱面文件对象.
     */
    private File affFile;

    /**
     * 音频偏移.
     * <p>
     * {@code AudioOffset:x} 表示谱面整体向前(-)/向后(+)移动x毫秒。
     * <p>
     * 如果x≠0，物件在音乐中实际对应的毫秒数=物件时间+x。
     */
    private int audioOffset = 0;

    /**
     * 音符密度.
     * <p>
     * {@code TimingPointDensityFactor:y} 表示音弧和长条的物量密度调整为正常值的y倍。
     * <p>
     * y=1时效果与省略此行相同。
     */
    private double timingPointDensityFactor = 1;

    /**
     * 按键列表.
     */
    private List<Note> noteList = new ArrayList<>();

    /**
     * 视觉列表.
     * <p>
     * 仅存储 4k/6k 变化的相关语句，即 enwidencamera。enwidenlanes 仅起显示作用，无需处理。
     */
    private List<SceneControl> sceneControlList = new ArrayList<>();

    private static final Pattern P_CLICK = Pattern.compile("\\([0-9]+,[0-5]\\);");

    private static final Pattern P_HOLD = Pattern.compile("hold\\([0-9]+,[0-9]+,[0-5]\\);");

    private static final Pattern P_ARC = Pattern.compile("arc\\([0-9]+,[0-9]+,-?[0-9.]+,-?[0-9.]+," +
            "(b|s|si|so|sisi|siso|sosi|soso),-?[0-9.]+,-?[0-9.]+,[0-3],.+,(true|false)\\)" +
            "(\\[arctap\\([0-9]+\\)(,arctap\\([0-9]+\\))*])?;");

    private static final Pattern P_TIMING = Pattern.compile("timing\\([0-9]+,-?[0-9.]+,[0-9.]+\\);");

    private static final Pattern P_SCENE_CONTROL = Pattern.compile("scenecontrol\\([0-9]+,enwidencamera,[0-9.]+,[01]\\);");

    /**
     * 谱面note总数.
     */
    private int noteCount = 0;

    public Aff(File affFile) {
        this.affFile = affFile;
        readAffAndPreProcess();
    }

    private void readAffAndPreProcess() {
        // 用于判断所有蛇有没有头判
        List<Arc> arcList = new ArrayList<>();
        // 用于后续处理和计算
        List<TimingGroup> timingGroupList = new ArrayList<>();
        // 读取 aff 文件
        try (BufferedReader br = new BufferedReader(new FileReader(affFile))) {
            String line;
            // 读取信息部分
            while ((line = br.readLine()) != null) {
                line = line.replace(" ", "");
                if ("-".equals(line)) {
                    break;
                }
                if (line.startsWith("AudioOffset:")) {
                    audioOffset = Integer.parseInt(line.substring("AudioOffset:".length()));
                } else if (line.startsWith("TimingPointDensityFactor:")) {
                    timingPointDensityFactor = Double.parseDouble(line.substring("TimingPointDensityFactor:".length()));
                }
            }
            // 创建两个 timingGroup
            TimingGroup baseTimingGroup = new TimingGroup(false);
            TimingGroup currTimingGroup = baseTimingGroup;
            // 读取按键部分
            while ((line = br.readLine()) != null) {
                line = line.replace(" ", "");
                if (line.startsWith("timinggroup")) {
                    boolean noInput = false;
                    String param = line.substring("timinggroup(".length(), line.length() - 2);
                    if (!"".equals(param)) {
                        // 带参 timinggroup，参数以 _ 分隔
                        List<String> paramList = Arrays.stream(param.split("_")).toList();
                        noInput = paramList.contains("noinput");
                    }
                    currTimingGroup = new TimingGroup(noInput);
                } else if ("};".equals(line)) {
                    processTimingGroup(currTimingGroup);
                    timingGroupList.add(currTimingGroup);
                    currTimingGroup = baseTimingGroup;
                } else {
                    if (P_CLICK.matcher(line).matches()) {
                        Click click = new Click(line);
                        currTimingGroup.noteList.add(click);
                    } else if (P_HOLD.matcher(line).matches()) {
                        Hold hold = new Hold(line);
                        currTimingGroup.noteList.add(hold);
                    } else if (P_ARC.matcher(line).matches()) {
                        Arc arc = new Arc(line);
                        // 不处理黑线
                        if (arc.getArctapList().isEmpty() && arc.isSkylineBoolean()) {
                            continue;
                        }
                        currTimingGroup.noteList.add(arc);
                        // 蛇需要添加到arcList中
                        if (!arc.isSkylineBoolean()) {
                            arcList.add(arc);
                        }
                    } else if (P_TIMING.matcher(line).matches()) {
                        Timing timing = new Timing(line);
                        currTimingGroup.timingList.add(timing);
                    } else if (P_SCENE_CONTROL.matcher(line).matches()) {
                        // 能满足 P_SCENE_CONTROL 的只有 enwidencamera 语句
                        SceneControl sceneControl = new SceneControl(line);
                        sceneControlList.add(sceneControl);
                    }
                }
            }
            processTimingGroup(baseTimingGroup);
            timingGroupList.add(baseTimingGroup);
        } catch (IOException e) {
            e.printStackTrace();
        }
        Collections.sort(arcList);
        Collections.sort(sceneControlList);
        // 遍历 arcList，修改 hasHead 变量
        for (int i = 0; i < arcList.size(); i++) {
            Arc arci = arcList.get(i);
            for (int j = i + 1; j < arcList.size(); j++) {
                Arc arcj = arcList.get(j);
                if (Math.abs(arcj.getX1() - arci.getX2()) < 0.1
                        && Math.abs(arcj.getY1() - arci.getY2()) < 1e-5
                        && Math.abs(arcj.getT1() - arci.getT2()) <= 10) {
                    arcj.setHasHead(true);
                }
            }
        }
        // 构建 noteList，具有 noinput 属性的按键不会加入 noteList
        for (var timingGroup : timingGroupList) {
            if (timingGroup.noInput) {
                continue;
            }
            noteList.addAll(timingGroup.noteList);
        }
        Collections.sort(noteList);
        // 计算 noteCount
        for (var note : noteList) {
            noteCount += note.getNoteCount();
        }
    }

    /**
     * 根据 timingList 的情况，给每个 note 赋值 beatTime.
     *
     * @param timingGroup 要处理的时间组
     */
    private void processTimingGroup(TimingGroup timingGroup) {
        Collections.sort(timingGroup.noteList);
        Collections.sort(timingGroup.timingList);
        int timingIndex = -1;
        float bpm;
        int nextT = Integer.MIN_VALUE;
        float beatTime = 0;
        for (var note : timingGroup.noteList) {
            while (note.getT1() >= nextT) {
                timingIndex++;
                bpm = Math.abs(timingGroup.timingList.get(timingIndex).getBpm());
                nextT = timingIndex == timingGroup.timingList.size() - 1
                        ? Integer.MAX_VALUE
                        : timingGroup.timingList.get(timingIndex + 1).getT();
                if (bpm == 0) {
                    beatTime = Float.MAX_VALUE;
                } else {
                    beatTime = bpm >= 256 ? 60000 / bpm : 30000 / bpm;
                    beatTime /= timingPointDensityFactor;
                }
            }
            note.setBeatTime(beatTime);
        }
    }
}
