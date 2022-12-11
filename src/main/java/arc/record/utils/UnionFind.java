package arc.record.utils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 并查集，输入同一个类的两个对象，输出这两个对象是否具有关联.
 * <p>
 * <b>要进行操作的类必须保证 equals 方法内部判断为 == 。</b>
 *
 * @author MengLeiFudge
 */
public class UnionFind<T> {
    /**
     * 存放连接关系的 map，value 为 自身表示.
     */
    private final Map<T, T> rootMap = new HashMap<>();

    /**
     * 添加一个实例对象.
     *
     * @param t 要添加的实例
     */
    public final void add(T t) {
        if (t == null) {
            return;
        }
        rootMap.put(t, t);
    }

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
            rootMap.put(t, value);
        }
    }

    /**
     * 获取某个实例对象的最终关联对象，并刷新所有的相关对象到同一关联.
     *
     * @param t 要获取最终关联对象的实例
     * @return 该实例对应的最终关联对象
     */
    private T getRootValue(T t) {
        T rootValue = rootMap.get(t);
        if (rootValue == t) {
            return t;
        }
        rootValue = getRootValue(rootValue);
        rootMap.put(t, rootValue);
        return rootValue;
    }

    /**
     * 为两个实例对象建立联系.
     *
     * @param t1 要连接的实例
     * @param t2 要连接的实例
     */
    public final void merge(T t1, T t2) {
        if (t1 == null || t2 == null) {
            return;
        }
        rootMap.put(getRootValue(t1), getRootValue(t2));
    }

    /**
     * 判断两个实例对象是否有关联.
     *
     * @param t1 要判断是否有关联的实例列表
     * @param t2 要判断是否有关联的实例列表
     * @return 如果有关联，返回 true；否则返回 false
     */
    public boolean isRelated(T t1, T t2) {
        if (t1 == null || t2 == null) {
            return false;
        }
        return getRootValue(t1) == getRootValue(t2);
    }
}
