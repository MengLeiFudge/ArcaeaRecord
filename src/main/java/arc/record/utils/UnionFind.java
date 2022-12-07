package arc.record.utils;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 动态大小，并查集.
 * <p>
 * 输入同一个类的两个对象，输出这两个对象是否具有关联。
 *
 * @author MengLeiFudge
 */
public class UnionFind<T> {
    /**
     * 存放 类T 的实力对象关系的 Map，key 为对象，value 为 key 所在关系圈的最后一个对象.
     * <p>
     * value 不会为 null。
     * <p>
     * 如果 value 为 key，表示该 key 在某个关系组中，且它是关系组的最后一位。
     */
    private final Map<T, T> masterMap = new HashMap<>();

    /**
     * 添加多个有关联的实例对象.
     *
     * @param ts 要添加的实例列表
     */
    public final void add(List<T> ts) {
        if (ts == null) {
            return;
        }
        ts.removeIf(Objects::isNull);
        if (ts.isEmpty()) {
            return;
        }
        T value = ts.get(0);
        for (var t : ts) {
            masterMap.put(t, value);
        }
    }

    /**
     * 获取某个实例对象的最终关联对象，并刷新所有的相关对象到同一关联.
     *
     * @param t 要获取最终关联对象的实例
     * @return 该实例对应的最终关联对象
     */
    private T get(@NotNull T t) {
        T value = masterMap.get(t);
        if (value == t) {
            return t;
        }
        value = get(value);
        masterMap.put(t, value);
        return value;
    }

    /**
     * 连接两个列表中的实例对象，使它们全部指向同一个最终关联对象.
     *
     * @param t1s 要连接的实例列表
     * @param t2s 要连接的实例列表
     */
    public final void merge(List<T> t1s, List<T> t2s) {
        List<T> list = new ArrayList<>();
        if (t1s != null) {
            t1s.removeIf(Objects::isNull);
            list.addAll(t1s);
        }
        if (t2s != null) {
            t2s.removeIf(Objects::isNull);
            list.addAll(t2s);
        }
        if (list.isEmpty()) {
            return;
        }
        T notNullT = list.get(0);
        T value = get(notNullT);
        for (var t : list) {
            masterMap.put(t, value);
        }
    }

    /**
     * 判断两个列表是否有关联.
     *
     * @param t1s 要判断是否有关联的实例列表
     * @param t2s 要判断是否有关联的实例列表
     * @return 如果有关联，返回 true；否则返回 false
     */
    public boolean isRelated(List<T> t1s, List<T> t2s) {
        if (t1s == null || t2s == null) {
            return false;
        }
        t1s.removeIf(Objects::isNull);
        t2s.removeIf(Objects::isNull);
        if (t1s.isEmpty() || t2s.isEmpty()) {
            return false;
        }
        return get(t1s.get(0)) == get(t2s.get(0));
    }
}
