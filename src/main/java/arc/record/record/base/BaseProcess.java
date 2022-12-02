package arc.record.record.base;

import arc.record.record.data.Resolution;

import java.io.File;

/**
 * 脚本要求.
 *
 * @author MengLeiFudge
 */
public record BaseProcess(
        File targetDir,
        int miss,
        int minPure,
        boolean isSongStartBegin,
        boolean isMirror,
        Resolution resolution) {
}
