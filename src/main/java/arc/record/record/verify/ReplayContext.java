package arc.record.record.verify;

import java.util.Objects;

import arc.record.record.model.Resolution;

/**
 * 最终脚本与游戏判定坐标、时钟之间的只读对应关系。
 *
 * @param resolution 生成脚本所使用的屏幕分辨率
 * @param mirror 是否在游戏和脚本中同时使用镜像
 * @param timeOriginMillis record 时间减去该值后得到未偏移的谱面时间
 */
public record ReplayContext(Resolution resolution, boolean mirror, int timeOriginMillis) {
    /** 校验必需的投影信息，偏移场景固定由回放器管理。 */
    public ReplayContext {
        Objects.requireNonNull(resolution, "resolution");
    }
}
