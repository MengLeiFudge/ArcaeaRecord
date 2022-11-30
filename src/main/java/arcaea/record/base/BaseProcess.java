package arcaea.record.base;

import lombok.Data;

import java.io.File;

/**
 * 对于指定的谱面和分辨率，其中一种脚本生成选项.
 *
 * @author MengLeiFudge
 */
public record BaseProcess(File targetDir, int miss, int minPure, boolean isSongStartBegin,
                          boolean isMirror) implements Comparable<BaseProcess> {
    @Override
    public int compareTo(BaseProcess o) {
        if (targetDir.getPath().equals(o.targetDir.getPath())
                && miss == o.miss
                && minPure == o.minPure
                && isSongStartBegin == o.isSongStartBegin
                && isMirror == o.isMirror) {
            return 0;
        } else {
            return 1;
        }
    }
}
