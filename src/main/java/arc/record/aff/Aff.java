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

import arc.record.aff.judge.ArcTopology;
import arc.record.aff.note.Arc;
import arc.record.aff.note.ArcTap;
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
 * 谱面文件信息类，具体规则参考中文 wiki 的谱面格式页面。
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

    /** 谱面文件对象。 */
    private final File affFile;
    /** 歌曲名。 */
    private final String songName;
    /** 歌曲难度。 */
    private final String diffStr;
    /** 实际产生输入需求的 Note 列表。 */
    private final List<Note> noteList = new ArrayList<>();
    /** 会产生持续输入需求或连接身份的实体 Arc 列表。 */
    private final List<Arc> arcList = new ArrayList<>();
    /** enwidencamera 视觉变化列表。 */
    @Setter(AccessLevel.NONE)
    private final List<SceneControl> sceneControlList = new ArrayList<>();
    /** 输入实体 Arc 的严格首尾连接图。 */
    @Setter(AccessLevel.NONE)
    private ArcTopology arcTopology;
    /** AFF 音频偏移，单位为毫秒。 */
    @Setter(AccessLevel.NONE)
    private int audioOffset;
    /** 长键物量密度倍率。 */
    @Setter(AccessLevel.NONE)
    private float timingPointDensityFactor = 1;
    /** 谱面总物量。 */
    @Setter(AccessLevel.NONE)
    private int noteCount;
    private int nextSourceId;

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
        List<TimingGroup> timingGroups = new ArrayList<>();
        TimingGroup baseTimingGroup = new TimingGroup(0, false);
        timingGroups.add(baseTimingGroup);
        try (BufferedReader reader = new BufferedReader(new FileReader(affFile))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
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
                        timingPointDensityFactor = Float.parseFloat(
                                line.substring("TimingPointDensityFactor:".length()));
                        if (timingPointDensityFactor < 0) {
                            throw new IllegalArgumentException("暂不支持负 TimingPointDensityFactor");
                        }
                    } else if (!P_HEADER.matcher(line).matches()) {
                        throw new IllegalArgumentException("无法识别的文件头行");
                    }
                } catch (RuntimeException e) {
                    throw createLineParseException(lineNumber, sourceLine, e);
                }
            }

            TimingGroup currentGroup = baseTimingGroup;
            int nextTimingGroupId = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String sourceLine = line;
                try {
                    line = line.stripLeading();
                    if (line.isEmpty()) {
                        continue;
                    }
                    if (line.startsWith("timinggroup")) {
                        requireFormat(line, P_TIMING_GROUP, "timinggroup");
                        String parameter = line.substring("timinggroup(".length(), line.length() - 2);
                        boolean noInput = !parameter.isEmpty()
                                && Arrays.asList(parameter.split("_")).contains("noinput");
                        currentGroup = new TimingGroup(nextTimingGroupId++, noInput);
                        timingGroups.add(currentGroup);
                    } else if ("};".equals(line)) {
                        currentGroup = baseTimingGroup;
                    } else if (line.startsWith("(")) {
                        requireFormat(line, P_CLICK, "tap");
                        Click click = new Click(line);
                        if (currentGroup.noInput) {
                            continue;
                        }
                        initializeNote(click, currentGroup);
                        currentGroup.sourceNotes.add(click);
                        currentGroup.noteList.add(click);
                    } else if (line.startsWith("hold")) {
                        requireFormat(line, P_HOLD, "hold");
                        Hold hold = new Hold(line);
                        if (currentGroup.noInput) {
                            continue;
                        }
                        initializeNote(hold, currentGroup);
                        currentGroup.sourceNotes.add(hold);
                        currentGroup.noteList.add(hold);
                    } else if (line.startsWith("arc")) {
                        requireFormat(line, P_ARC, "arc");
                        Arc arc = new Arc(line);
                        if (currentGroup.noInput) {
                            arc.getArcTapList();
                            continue;
                        }
                        initializeNote(arc, currentGroup);
                        List<ArcTap> arcTaps = arc.getArcTapList();
                        if (arc.isRealArc()) {
                            currentGroup.sourceNotes.add(arc);
                            currentGroup.noteList.add(arc);
                            arcList.add(arc);
                        }
                        for (ArcTap arcTap : arcTaps) {
                            initializeNote(arcTap, currentGroup);
                            currentGroup.sourceNotes.add(arcTap);
                            currentGroup.noteList.add(arcTap);
                        }
                    } else if (line.startsWith("timing")) {
                        requireFormat(line, P_TIMING, "timing");
                        currentGroup.timingList.add(new Timing(line));
                    } else if (line.startsWith("camera")) {
                        requireFormat(line, P_CAMERA, "camera");
                    } else if (line.startsWith("scenecontrol")) {
                        requireFormat(line, P_SCENE_CONTROL, "scenecontrol");
                        if (line.contains(",enwidencamera,") || line.contains(",enwidencamera)")) {
                            requireFormat(line, P_ENWIDEN_CAMERA, "enwidencamera scenecontrol");
                            SceneControl control = new SceneControl(line);
                            if (!currentGroup.noInput) {
                                sceneControlList.add(control);
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
        } catch (IOException e) {
            throw new IllegalStateException("无法读取谱面：" + affFile.getAbsolutePath(), e);
        }

        List<Timing> baseTimings = baseTimingGroup.timingList;
        for (TimingGroup timingGroup : timingGroups) {
            assignTimingContext(timingGroup, baseTimings);
            noteList.addAll(timingGroup.noteList);
        }
        Collections.sort(arcList);
        arcTopology = new ArcTopology(arcList);
        Collections.sort(sceneControlList);
        Collections.sort(noteList);
        noteCount = noteList.stream().mapToInt(Note::getNoteCount).sum();
    }

    private void initializeNote(Note note, TimingGroup timingGroup) {
        note.setSourceId(nextSourceId++);
        note.setTimingGroupId(timingGroup.id);
        note.setNoInput(timingGroup.noInput);
    }

    private void assignTimingContext(TimingGroup timingGroup, List<Timing> baseTimings) {
        List<Timing> timings = timingGroup.timingList.isEmpty() ? baseTimings : timingGroup.timingList;
        if (timings.isEmpty()) {
            if (!timingGroup.sourceNotes.isEmpty()) {
                throw new IllegalArgumentException("timinggroup 缺少可用 timing：" + affFile.getAbsolutePath());
            }
            return;
        }
        Collections.sort(timings);
        Collections.sort(timingGroup.sourceNotes);
        int timingIndex = 0;
        for (Note note : timingGroup.sourceNotes) {
            while (timingIndex + 1 < timings.size()
                    && timings.get(timingIndex + 1).getT() <= note.getT1()) {
                timingIndex++;
            }
            double rawBpm = Math.abs(timings.get(timingIndex).getBpm());
            double realBpm = rawBpm >= 256 ? rawBpm / 2.0 : rawBpm;
            double judgeBpm = realBpm * timingPointDensityFactor;
            note.setTimingBpm(realBpm);
            note.setJudgeBpm(judgeBpm);
            note.setBeatTime(judgeBpm == 0 ? Float.MAX_VALUE : (float) (30000.0 / judgeBpm));
        }
    }

    private static void requireFormat(String line, Pattern pattern, String type) {
        if (!pattern.matcher(line).matches()) {
            throw new IllegalArgumentException(type + " 行格式不符合预期");
        }
    }

    private IllegalArgumentException createLineParseException(
            int lineNumber, String sourceLine, RuntimeException cause) {
        return new IllegalArgumentException(
                "谱面解析失败：" + affFile.getAbsolutePath()
                        + "，第 " + lineNumber + " 行：" + sourceLine,
                cause);
    }

    /**
     * 返回指定谱面时刻的 4K/6K 过渡比例。
     *
     * @param time 谱面时间，单位为毫秒
     * @return 0 表示 4K，1 表示 6K
     */
    public double getRatio46k(double time) {
        double ratio46k = 0;
        for (SceneControl control : sceneControlList) {
            if (control.getT() > time) {
                break;
            }
            if (control.getT() + control.getDuration() <= time) {
                ratio46k = control.isTo6k() ? 1 : 0;
            } else {
                double progress = (time - control.getT()) / control.getDuration();
                ratio46k = control.isTo6k() ? progress : 1 - progress;
            }
        }
        return ratio46k;
    }

    public double getRatio46k(int time) {
        return getRatio46k((double) time);
    }

    /**
     * 返回指定时刻天空判定区域纵坐标上限的一半。
     *
     * @param time 谱面时间，单位为毫秒
     * @return 4K 时为 0.5，6K 时为 0.805
     */
    public double getMiddleY(int time) {
        return (1 + getRatio46k(time) * 0.61) / 2;
    }
}
