package arcaea.record.base;

import arcaea.record.aff.Aff;
import arcaea.record.record.Resolution;
import lombok.Data;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 谱面文件基础处理类型.
 * <p>
 * 表示对于一个指定的谱面文件，需要生成那些种类的脚本。
 * <p>
 * 一个谱面文件只能有一个分辨率，可以有一个或多个生成选项。
 *
 * @author MengLeiFudge
 */
@Data
public class AffProcess {
    private final Aff aff;
    private final String song;
    private final String difficultyStr;
    private final Resolution resolution;
    private final List<BaseProcess> baseProcessList = new ArrayList<>();

    public void addBaseProcess(File targetDir, int miss, int minPure,
                               boolean isSongStartBegin, boolean isMirror) {
        BaseProcess baseProcess = new BaseProcess(targetDir, miss, minPure, isSongStartBegin, isMirror);
        for (BaseProcess bp : baseProcessList) {
            if (bp.compareTo(baseProcess) == 0) {
                return;
            }
        }
        baseProcessList.add(baseProcess);
    }
}
