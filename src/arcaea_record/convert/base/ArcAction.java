package arcaea_record.convert.base;

import lombok.Data;

/**
 * 一段蛇的基础类型.
 *
 * @author MengLeiFudge
 */
@Data
public class ArcAction {
    private final int beginTime;
    private final int endTime;
    private final double beginX;
    private final double endX;
    private final String type;
    private final double beginY;
    private final double endY;
    private final int color;
}
