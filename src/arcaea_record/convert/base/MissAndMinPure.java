package arcaea_record.convert.base;

import lombok.Data;

/**
 * 表示某种 miss 与 小p 的组合.
 *
 * @author MengLeiFudge
 */
@Data
public class MissAndMinPure {
    private final String missStr;
    private final String minPureStr;

    public int getMissNum(int note) {
        if (missStr.endsWith("w") || missStr.endsWith("W")) {
            // 分数转miss个数
            int score = Integer.parseInt(missStr.substring(0, missStr.length() - 1));
            // 最后+1，保证分数比输入分数低
            return (1000 - score) * note / 1000 + 1;
        } else {
            return Integer.parseInt(missStr);
        }
    }

    public int getMinPureNum(int note) {
        if (minPureStr.endsWith("%")) {
            // 比例转小p个数
            double bili = Double.parseDouble(minPureStr.substring(0, minPureStr.length() - 1));
            return (int) (note * bili / 100);
        } else {
            return Integer.parseInt(minPureStr);
        }
    }
}
