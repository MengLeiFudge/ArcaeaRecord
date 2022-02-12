package arcaea_record.convert.base;

import lombok.Data;

import java.io.File;

/**
 * 对于指定的谱面和分辨率，其中一种脚本生成选项.
 *
 * @author MengLeiFudge
 */
@Data
public class BaseProcess implements Comparable<BaseProcess> {
    private final File targetDir;
    private final int miss;
    private final int minPure;
    private final boolean isSongStartBegin;
    private final boolean isMirror;

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
