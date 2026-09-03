package arc.record.aff.judge;

import java.util.List;

import arc.record.aff.note.Note;

/**
 * 同一个来源长键的全部持续判定需求。
 *
 * @param source      来源 Hold 或输入 Arc
 * @param componentId Arc 弱连通组编号；Hold 使用自身 sourceId 的负值
 * @param color       Arc 颜色；Hold 为 -1
 * @param demands     按名义时间排序且保留重复身份的判定需求
 */
public record LongNoteDemand(Note source, int componentId, int color,
                             List<CoverageDemand> demands) {
}
