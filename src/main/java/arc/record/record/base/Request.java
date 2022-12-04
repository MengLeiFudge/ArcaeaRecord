package arc.record.record.base;

import arc.record.record.data.Resolution;

import java.io.File;

/**
 * 脚本要求.
 * <p>
 * 包含如下可选项：
 * <ul>
 *     <li>miss数（该个数不应超过预处理后的地键+天键个数）</li>
 *     <li>小p数（该个数不应超过miss处理后的地键+天键个数）</li>
 *     <li>目标目录</li>
 *     <li>是否为镜像模式</li>
 *     <li>模拟器分辨率</li>
 * </ul>
 * 处理时，根据 miss、小p 得到不同的按键列表，再根据其余设定生成脚本文件。
 *
 * @author MengLeiFudge
 */
public record Request(File targetDir, boolean mirror, Resolution resolution) {
}
