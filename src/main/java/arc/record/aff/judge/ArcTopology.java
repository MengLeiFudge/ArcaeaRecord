package arc.record.aff.judge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import arc.record.aff.note.Arc;

/**
 * 完整 AFF Arc 集合的严格首尾连接图。
 *
 * <p>图中包含 noinput、零时长、带 Arctap 以及非输入 Arc。连接只影响判定点和音弧组，
 * 不直接表示最终应使用同一个物理触点。</p>
 */
public final class ArcTopology {
    private static final int TIME_TOLERANCE_EXCLUSIVE = 10;

    private final List<Arc> arcs;
    private final Map<Integer, Arc> arcBySourceId;
    private final Map<Integer, List<Arc>> predecessors;
    private final Map<Integer, List<Arc>> successors;
    private final Map<Integer, Integer> componentBySourceId;
    private final Map<Integer, List<Arc>> components;

    /**
     * 根据完整 Arc 列表建立连接图，并把前驱/后继状态写回 Arc。
     *
     * @param sourceArcs 保留稳定 sourceId 的完整 Arc 列表
     */
    public ArcTopology(List<Arc> sourceArcs) {
        this.arcs = sourceArcs.stream()
                .sorted()
                .toList();
        this.arcBySourceId = new HashMap<>();
        this.predecessors = new HashMap<>();
        this.successors = new HashMap<>();
        for (Arc arc : arcs) {
            arcBySourceId.put(arc.getSourceId(), arc);
            predecessors.put(arc.getSourceId(), new ArrayList<>());
            successors.put(arc.getSourceId(), new ArrayList<>());
            arc.setHasHead(false);
            arc.setHasTail(false);
        }
        connectArcs();
        ComponentIndex componentIndex = buildComponents();
        this.componentBySourceId = componentIndex.componentBySourceId();
        this.components = componentIndex.components();
    }

    private void connectArcs() {
        TreeMap<Integer, List<Arc>> arcsByStart = new TreeMap<>();
        for (Arc arc : arcs) {
            arcsByStart.computeIfAbsent(arc.getT1(), ignored -> new ArrayList<>()).add(arc);
        }
        for (Arc arc : arcs) {
            int from = arc.getT2() - TIME_TOLERANCE_EXCLUSIVE + 1;
            int to = arc.getT2() + TIME_TOLERANCE_EXCLUSIVE - 1;
            for (List<Arc> candidates : arcsByStart.subMap(from, true, to, true).values()) {
                for (Arc next : candidates) {
                    if (arc == next || !arc.connectsTo(next)) {
                        continue;
                    }
                    successors.get(arc.getSourceId()).add(next);
                    predecessors.get(next.getSourceId()).add(arc);
                    arc.setHasTail(true);
                    next.setHasHead(true);
                }
            }
        }
        ComparatorBySourceId comparator = new ComparatorBySourceId();
        predecessors.values().forEach(list -> list.sort(comparator));
        successors.values().forEach(list -> list.sort(comparator));
    }

    private ComponentIndex buildComponents() {
        int size = arcs.size();
        int[] parent = new int[size];
        Map<Integer, Integer> indexBySourceId = new HashMap<>();
        for (int i = 0; i < size; i++) {
            parent[i] = i;
            indexBySourceId.put(arcs.get(i).getSourceId(), i);
        }
        for (Arc arc : arcs) {
            int a = indexBySourceId.get(arc.getSourceId());
            for (Arc next : successors.get(arc.getSourceId())) {
                union(parent, a, indexBySourceId.get(next.getSourceId()));
            }
        }

        Map<Integer, Integer> stableComponentIds = new HashMap<>();
        Map<Integer, List<Arc>> result = new LinkedHashMap<>();
        Map<Integer, Integer> componentMap = new HashMap<>();
        for (int i = 0; i < size; i++) {
            int root = find(parent, i);
            int sourceId = arcs.get(i).getSourceId();
            int componentId = stableComponentIds.computeIfAbsent(root, ignored -> sourceId);
            componentMap.put(sourceId, componentId);
            result.computeIfAbsent(componentId, ignored -> new ArrayList<>()).add(arcs.get(i));
        }
        result.replaceAll((ignored, list) -> List.copyOf(list));
        return new ComponentIndex(Map.copyOf(componentMap), Collections.unmodifiableMap(result));
    }

    private static int find(int[] parent, int index) {
        int root = index;
        while (parent[root] != root) {
            root = parent[root];
        }
        while (parent[index] != index) {
            int next = parent[index];
            parent[index] = root;
            index = next;
        }
        return root;
    }

    private static void union(int[] parent, int first, int second) {
        int a = find(parent, first);
        int b = find(parent, second);
        if (a != b) {
            parent[Math.max(a, b)] = Math.min(a, b);
        }
    }

    /**
     * 返回稳定 sourceId 对应的原始 Arc。
     *
     * @param sourceId 解析阶段分配的稳定身份
     * @return 原始 Arc
     */
    public Arc getArc(int sourceId) {
        Arc arc = arcBySourceId.get(sourceId);
        if (arc == null) {
            throw new IllegalArgumentException("不存在 sourceId=" + sourceId + " 的 Arc");
        }
        return arc;
    }

    /**
     * 返回指定 Arc 的全部严格前驱。
     *
     * @param sourceId Arc 稳定身份
     * @return 不可修改前驱列表
     */
    public List<Arc> predecessorsOf(int sourceId) {
        return List.copyOf(predecessors.getOrDefault(sourceId, List.of()));
    }

    /**
     * 返回指定 Arc 的全部严格后继。
     *
     * @param sourceId Arc 稳定身份
     * @return 不可修改后继列表
     */
    public List<Arc> successorsOf(int sourceId) {
        return List.copyOf(successors.getOrDefault(sourceId, List.of()));
    }

    /**
     * 返回 Arc 所属弱连通音弧组的稳定编号。
     *
     * @param sourceId Arc 稳定身份
     * @return 组内首个稳定 sourceId
     */
    public int componentIdOf(int sourceId) {
        Integer componentId = componentBySourceId.get(sourceId);
        if (componentId == null) {
            throw new IllegalArgumentException("不存在 sourceId=" + sourceId + " 的 Arc");
        }
        return componentId;
    }

    /**
     * 返回弱连通音弧组内的全部原始 Arc。
     *
     * @param componentId 音弧组稳定编号
     * @return 不可修改 Arc 列表
     */
    public List<Arc> component(int componentId) {
        return components.getOrDefault(componentId, List.of());
    }

    /**
     * 返回完整 Arc 列表。
     *
     * @return 按时间排序且不可修改的 Arc 列表
     */
    public List<Arc> arcs() {
        return arcs;
    }

    private record ComponentIndex(Map<Integer, Integer> componentBySourceId,
                                  Map<Integer, List<Arc>> components) {
    }

    private static final class ComparatorBySourceId implements java.util.Comparator<Arc> {
        @Override
        public int compare(Arc first, Arc second) {
            return Integer.compare(first.getSourceId(), second.getSourceId());
        }
    }
}
