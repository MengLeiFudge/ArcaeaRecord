package arcaea.record.aff;

import arcaea.record.aff.note.Arc;
import arcaea.record.aff.note.Click;
import arcaea.record.aff.note.Hold;
import arcaea.record.aff.note.Note;
import arcaea.record.aff.timing.Timing;
import arcaea.record.aff.view.SceneControl;
import org.apache.commons.lang3.SerializationUtils;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 谱面文件信息类，具体规则参考中文wiki的谱面格式页面（问题答案为ifi）.
 *
 * @author MengLeiFudge
 */
public class Aff {
    /**
     * 谱面文件对象.
     */
    File affFile;

    /**
     * 音频偏移.
     * <p>
     * {@code AudioOffset:x} 表示谱面整体向前(-)/向后(+)移动x毫秒。
     * <p>
     * 如果x≠0，物件在音乐中实际对应的毫秒数=物件时间+x。
     */
    int audioOffset = 0;

    /**
     * 音符密度.
     * <p>
     * {@code TimingPointDensityFactor:y} 表示音弧和长条的物量密度调整为正常值的y倍。
     * <p>
     * y=1时效果与省略此行相同。
     */
    double timingPointDensityFactor = 1;

    /**
     * 按键组列表.
     * <p>
     * 任何谱子都可分为多个按键组，不在 timinggroup 内部的都认为在默认按键组。
     */
    List<TimingGroup> timingGroupList = new ArrayList<>();

    /**
     * 视觉列表.
     * <p>
     * 仅存储 4k/6k 变化的相关语句，即 enwidencamera。enwidenlanes 仅起显示作用，无需处理。
     */
    List<SceneControl> sceneControlList = new ArrayList<>();

    private static final Pattern P_CLICK = Pattern.compile("\\([0-9]+,[0-5]\\)");

    private static final Pattern P_HOLD = Pattern.compile("hold\\([0-9]+,[0-9]+,[0-5]\\)");

    private static final Pattern P_ARC = Pattern.compile("arc\\([0-9]+,[0-9]+,-?[0-9.]+,-?[0-9.]+," +
            "(b|s|si|so|sisi|siso|sosi|soso),-?[0-9.]+,-?[0-9.]+,[0-2],none,(true|false)\\)" +
            "(\\[arctap\\([0-9]+\\)(,arctap\\([0-9]+\\))*])?");

    private static final Pattern P_TIMING = Pattern.compile("timing\\([0-9]+,[0-9.]+,[0-9.]+\\)");

    private static final Pattern P_SCENE_CONTROL = Pattern.compile("scenecontrol\\([0-9]+,enwidencamera,[0-9.]+,[01]\\)");

    public Aff(File affFile) {
        this.affFile = affFile;
        analyzeAff();
    }

    private void analyzeAff() {
        try (BufferedReader br = new BufferedReader(new FileReader(affFile))) {
            String line;
            // 读取文件头
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if ("-".equals(line)) {
                    break;
                }
                if (line.startsWith("AudioOffset:")) {
                    audioOffset = Integer.parseInt(line.substring("AudioOffset:".length()));
                } else if (line.startsWith("TimingPointDensityFactor:")) {
                    timingPointDensityFactor = Double.parseDouble(line.substring("TimingPointDensityFactor:".length()));
                }
            }
            // 创建默认 timinggroup 以及临时 timinggroup，根据 tempTimingGroup 是否为 null 判断将新按键放入哪里
            TimingGroup baseTimingGroup = new TimingGroup();
            timingGroupList.add(baseTimingGroup);
            TimingGroup tempTimingGroup = null;
            // 读取按键
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("timinggroup")) {
                    // 一个新的 timinggroup
                    String param = line.substring("timinggroup(".length(), line.length() - 2);
                    if (!"".equals(param)) {
                        // 带参 timinggroup，参数以 _ 分隔
                        List<String> paramList = Arrays.stream(param.split("_")).toList();
                        // fadingholds、anglex、angley 均对落点无影响，无需判断
                        if (paramList.contains("noinput")) {
                            // noinput 里面的按键都是动画效果，无需处理
                            while ((line = br.readLine()) != null) {
                                line = line.trim();
                                if ("};".equals(line)) {
                                    break;
                                }
                            }
                            continue;
                        }
                        // 走到这里说明是没有 noinput 参数的 timinggroup
                    }
                    // 无参 timinggroup，或没有 noinput 参数的 timinggroup
                    tempTimingGroup = new TimingGroup();
                } else if (line.startsWith("};")) {
                    if (tempTimingGroup == null) {
                        throw new IllegalStateException("检测到 timinggroup 结尾但是未检测到开头");
                    }
                    // timinggroup 结尾，且此时 tempTimingGroup 一定不为 null
                    if (!tempTimingGroup.noteList.isEmpty()) {
                        timingGroupList.add(SerializationUtils.clone(tempTimingGroup));
                    }
                    tempTimingGroup = null;
                } else {
                    // 非 timinggroup 语句
                    Note note;
                    if (P_CLICK.matcher(line).matches()) {
                        Objects.requireNonNullElse(tempTimingGroup, baseTimingGroup).noteList.add(new Click(line));
                    } else if (P_HOLD.matcher(line).matches()) {
                        Objects.requireNonNullElse(tempTimingGroup, baseTimingGroup).noteList.add(new Hold(line));
                    } else if (P_ARC.matcher(line).matches()) {
                        Objects.requireNonNullElse(tempTimingGroup, baseTimingGroup).noteList.add(new Arc(line));
                    } else if (P_TIMING.matcher(line).matches()) {
                        Objects.requireNonNullElse(tempTimingGroup, baseTimingGroup).timingList.add(new Timing(line));
                    } else if (P_SCENE_CONTROL.matcher(line).matches()) {
                        sceneControlList.add(new SceneControl(line));
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


}
