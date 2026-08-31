package arc.record.aff;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import arc.record.aff.note.Arc;
import arc.record.aff.note.Click;
import arc.record.aff.note.Hold;
import arc.record.aff.note.Note;
import arc.record.aff.timing.Timing;
import arc.record.aff.view.SceneControl;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Setter;

import static arc.record.Utils.getDifficultyStr;
import static arc.record.Utils.getProcessedTitle;

/**
 * 谱面文件信息类，具体规则参考中文wiki的谱面格式页面（问题答案为ifi）.
 *
 * @author MengLeiFudge
 */
@Data
public class Aff {
    private static final String UNSIGNED_NUMBER = "[0-9]+(?:\\.[0-9]+)?";
    private static final String NUMBER = "-?" + UNSIGNED_NUMBER;
    private static final Pattern P_AUDIO_OFFSET = Pattern.compile("AudioOffset:-?[0-9]+");
    private static final Pattern P_TIMING_POINT_DENSITY_FACTOR = Pattern.compile(
            "TimingPointDensityFactor:" + NUMBER);
    private static final Pattern P_HEADER = Pattern.compile("[^:]+:.*");
    private static final Pattern P_CLICK = Pattern.compile("\\([0-9]+,[0-5]\\);");
    private static final Pattern P_HOLD = Pattern.compile("hold\\([0-9]+,[0-9]+,[0-5]\\);");
    private static final Pattern P_ARC = Pattern.compile(
            "arc\\([0-9]+,[0-9]+," + NUMBER + "," + NUMBER + ","
                    + "(b|s|si|so|sisi|siso|sosi|soso)," + NUMBER + "," + NUMBER
                    + ",[0-3],[^,]+,(true|false|designant)(," + NUMBER + ")?\\)"
                    + "(\\[arctap\\([0-9]+\\)(,arctap\\([0-9]+\\))*])?;");
    private static final Pattern P_TIMING = Pattern.compile(
            "timing\\([0-9]+," + NUMBER + "," + UNSIGNED_NUMBER + "\\);");
    private static final Pattern P_CAMERA = Pattern.compile(
            "camera\\([0-9]+," + NUMBER + "," + NUMBER + "," + NUMBER + ","
                    + NUMBER + "," + NUMBER + "," + NUMBER + ",(qi|qo|l|reset|s),"
                    + UNSIGNED_NUMBER + "\\);");
    private static final Pattern P_SCENE_CONTROL = Pattern.compile(
            "scenecontrol\\([0-9]+,[a-z]+(," + NUMBER + ",-?[0-9]+)?\\);");
    private static final Pattern P_ENWIDEN_CAMERA = Pattern.compile(
            "scenecontrol\\([0-9]+,enwidencamera," + UNSIGNED_NUMBER + ",[01]\\);");
    private static final Pattern P_TIMING_GROUP = Pattern.compile(
            "timinggroup\\((?:[a-z]+[0-9]*(?:_[a-z]+[0-9]*)*)?\\)\\{");
    private static final Pattern P_FLICK = Pattern.compile(
            "flick\\([0-9]+," + NUMBER + "," + NUMBER + "," + NUMBER + "," + NUMBER + "\\);");
    /**
     * 谱面文件对象.
     */
    private final File affFile;
    /**
     * 歌曲名.
     */
    private final String songName;
    /**
     * 歌曲难度.
     */
    private final String diffStr;
    /**
     * 按键列表.
     */
    private final List<Note> noteList = new ArrayList<>();
    /**
     * 视觉列表.
     * <p>
     * 仅存储 4k/6k 变化的相关语句，即 enwidencamera。enwidenlanes 仅起显示作用，无需处理。
     */
    @Setter(AccessLevel.NONE)
    private final List<SceneControl> sceneControlList = new ArrayList<>();
    /**
     * 音频偏移.
     * <p>
     * {@code AudioOffset:x} 表示谱面整体向前(-)/向后(+)移动x毫秒。
     * <p>
     * 如果x≠0，物件在音乐中实际对应的毫秒数=物件时间+x。
     */
    @Setter(AccessLevel.NONE)
    private int audioOffset = 0;
    /**
     * 音符密度.
     * <p>
     * {@code TimingPointDensityFactor:y} 表示音弧和长条的物量密度调整为正常值的y倍。
     * <p>
     * y=1时效果与省略此行相同。
     */
    @Setter(AccessLevel.NONE)
    private float timingPointDensityFactor = 1;
    /**
     * 谱面note总数.
     */
    @Setter(AccessLevel.NONE)
    private int noteCount = 0;

    public Aff(File affFile) {
        this.affFile = affFile;
        String sid = affFile.getParentFile().getName();
        if (sid.startsWith("dl_")) {
            sid = sid.substring(3);
        }
        int ratingClass = Integer.parseInt(affFile.getName().substring(0, 1));
        this.songName = getProcessedTitle(sid);
        this.diffStr = getDifficultyStr(sid, ratingClass);
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
            int lineNumber = 0;
            // 读取信息部分
            while ((line = br.readLine()) != null) {
                lineNumber++;
                String sourceLine = line;
                try {
                    line = line.stripLeading();
                    if (line.isEmpty()) {
                        continue;
                    }
                    if ("-".equals(line)) {
                        break;
                    }
                    if (line.startsWith("AudioOffset:")) {
                        requireFormat(line, P_AUDIO_OFFSET, "AudioOffset");
                        audioOffset = Integer.parseInt(line.substring("AudioOffset:".length()));
                    } else if (line.startsWith("TimingPointDensityFactor:")) {
                        requireFormat(line, P_TIMING_POINT_DENSITY_FACTOR, "TimingPointDensityFactor");
                        timingPointDensityFactor = Float.parseFloat(line.substring("TimingPointDensityFactor:".length()));
                    } else if (!P_HEADER.matcher(line).matches()) {
                        throw new IllegalArgumentException("无法识别的文件头行");
                    }
                } catch (RuntimeException e) {
                    throw createLineParseException(lineNumber, sourceLine, e);
                }
            }
            // 创建两个 timingGroup
            TimingGroup baseTimingGroup = new TimingGroup(false);
            TimingGroup currTimingGroup = baseTimingGroup;
            // 读取按键部分
            while ((line = br.readLine()) != null) {
                lineNumber++;
                String sourceLine = line;
                try {
                    line = line.stripLeading();
                    if (line.isEmpty()) {
                        continue;
                    }
                    if (line.startsWith("timinggroup")) {
                        requireFormat(line, P_TIMING_GROUP, "timinggroup");
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
                    } else if (line.startsWith("(")) {
                        requireFormat(line, P_CLICK, "tap");
                        if (!currTimingGroup.noInput) {
                            Click click = new Click(line);
                            currTimingGroup.noteList.add(click);
                        }
                    } else if (line.startsWith("hold")) {
                        requireFormat(line, P_HOLD, "hold");
                        if (!currTimingGroup.noInput) {
                            Hold hold = new Hold(line);
                            currTimingGroup.noteList.add(hold);
                        }
                    } else if (line.startsWith("arc")) {
                        requireFormat(line, P_ARC, "arc");
                        if (currTimingGroup.noInput) {
                            continue;
                        }
                        Arc arc = new Arc(line);
                        // 不处理黑线
                        if (arc.getArctapTimingList().isEmpty() && !arc.isRealArc()) {
                            continue;
                        }
                        if (!arc.isRealArc()) {
                            // 多个天键
                            currTimingGroup.noteList.addAll(arc.getArcTapList());
                        } else {
                            // 蛇需要添加到arcList中
                            arcList.add(arc);
                            currTimingGroup.noteList.add(arc);
                        }
                    } else if (line.startsWith("timing")) {
                        requireFormat(line, P_TIMING, "timing");
                        if (!currTimingGroup.noInput) {
                            Timing timing = new Timing(line);
                            currTimingGroup.timingList.add(timing);
                        }
                    } else if (line.startsWith("camera")) {
                        requireFormat(line, P_CAMERA, "camera");
                    } else if (line.startsWith("scenecontrol")) {
                        requireFormat(line, P_SCENE_CONTROL, "scenecontrol");
                        if (line.contains(",enwidencamera,") || line.contains(",enwidencamera)")) {
                            requireFormat(line, P_ENWIDEN_CAMERA, "enwidencamera scenecontrol");
                            if (!currTimingGroup.noInput) {
                                SceneControl sceneControl = new SceneControl(line);
                                sceneControlList.add(sceneControl);
                            }
                        }
                    } else if (line.startsWith("flick")) {
                        requireFormat(line, P_FLICK, "flick");
                    } else {
                        throw new IllegalArgumentException("无法识别的谱面行");
                    }
                } catch (RuntimeException e) {
                    throw createLineParseException(lineNumber, sourceLine, e);
                }
            }
            processTimingGroup(baseTimingGroup);
            timingGroupList.add(baseTimingGroup);
        } catch (IOException e) {
            e.printStackTrace();
        }
        Collections.sort(arcList);
        // 检查是否存在两个蛇时间、位置重合的情况，bpm无所谓
        // 如果重合，移除bpm较低的那个蛇
        int i = 0;
        Arc arcToBeRemoved = null;
        while (i < arcList.size() - 1) {
            Arc arc1 = arcList.get(i);
            for (int j = i + 1; j < arcList.size(); j++) {
                Arc arc2 = arcList.get(j);
                if (arc2.getT1() > arc1.getT1()) {
                    // 快速循环
                    break;
                }
                // 颜色、时间、位置相同就行，别的条件暂时不管
                if (arc1.getColor() == arc2.getColor()
                        && arc1.getT1() == arc2.getT1() && arc1.getT2() == arc2.getT2()
                        && arc1.getX1() == arc2.getX1() && arc1.getY1() == arc2.getY1()
                        && arc1.getX2() == arc2.getX2() && arc1.getY2() == arc2.getY2()) {
                    // beatTime短 = bpm高 = 不移除
                    arcToBeRemoved = arc1.getBeatTime() > arc2.getBeatTime() ? arc1 : arc2;
                    break;
                }
            }
            if (arcToBeRemoved != null) {
                arcList.remove(arcToBeRemoved);
                for (var timingGroup : timingGroupList) {
                    if (timingGroup.noInput) {
                        continue;
                    }
                    timingGroup.noteList.remove(arcToBeRemoved);
                }
                arcToBeRemoved = null;
            } else {
                i++;
            }
        }
        Collections.sort(sceneControlList);
        // 遍历 arcList，修改 hasHead 变量
        for (i = 0; i < arcList.size(); i++) {
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
            if (!timingGroup.noteList.isEmpty()) {
                noteList.addAll(timingGroup.noteList);
            }
        }
        Collections.sort(noteList);
        // 计算 noteCount
        for (var note : noteList) {
            noteCount += note.getNoteCount();
        }
    }

    /**
     * 校验规范化后的谱面行是否符合已识别类别的完整格式。
     *
     * @param line    去除前导空白后的谱面行
     * @param pattern 该行类别的完整格式
     * @param type    用于错误消息的行类别
     */
    private static void requireFormat(String line, Pattern pattern, String type) {
        if (!pattern.matcher(line).matches()) {
            throw new IllegalArgumentException(type + " 行格式不符合预期");
        }
    }

    /**
     * 为行内解析异常补充输入谱面位置，同时保留原始异常。
     *
     * @param lineNumber 从 1 开始的物理行号
     * @param sourceLine 未去除空格的原始行
     * @param cause      原始解析异常
     * @return 带谱面上下文的异常
     */
    private IllegalArgumentException createLineParseException(
            int lineNumber, String sourceLine, RuntimeException cause) {
        return new IllegalArgumentException(
                "谱面解析失败：" + affFile.getAbsolutePath()
                        + "，第 " + lineNumber + " 行：" + sourceLine,
                cause);
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

    /**
     * 返回某个时刻谱面的 4/6k 进度，范围 0-1.
     *
     * @param time 目标时间戳
     * @return 某个时刻谱面的 4/6k 进度，0 表示 4k，1 表示 6k。
     */
    public double getRatio46k(int time) {
        double ratio46k = 0;
        for (var control : sceneControlList) {
            if (control.getT() <= time) {
                if (control.getT() + control.getDuration() <= time) {
                    ratio46k = control.isTo6k() ? 1 : 0;
                } else {
                    double progress = (time - control.getT()) * 1.0 / control.getDuration();
                    ratio46k = control.isTo6k() ? progress : 1 - progress;
                }
            } else {
                break;
            }
        }
        return ratio46k;
    }

    /**
     * 返回某个时刻谱面 y 最大值的一半.
     * <p>
     * ratio46k=0，返回 1/2；ratio46k=1，返回 1.61/2。
     *
     * @param time 目标时间戳
     * @return 某个时刻谱面 y 最大值的一半
     */
    public double getMiddleY(int time) {
        return (1 + getRatio46k(time) * (1.61 - 1)) / 2;
    }
}
