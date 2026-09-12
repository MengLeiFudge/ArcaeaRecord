package arc.record.record.verify;

import java.util.List;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;

/**
 * 理论值脚本在全部时间偏移和合法输入归属分支下的正向回放结论。
 *
 * @param chart 原始 AFF 路径
 * @param status 三个偏移场景的汇总结论
 * @param model 本次结论适用的离线模型与计算边界
 * @param cases 各偏移的独立结论，不以一个有利场景代替全部场景
 */
public record ReplayReport(String chart, Status status, String model, List<CaseResult> cases) {
    /** 保存不可变报告，避免后续回放覆盖失败证据。 */
    public ReplayReport {
        cases = List.copyOf(cases);
    }

    /** 模型内全部分支通过、存在失败反例，或因资源/机制边界未能作出结论。 */
    public enum Status {
        PASS, FAIL, INCONCLUSIVE
    }

    /**
     * 单个整体操作偏移的结论。
     *
     * @param offsetMillis 只加在脚本事件上的偏移，负数为提前
     * @param status 本场景的结论
     * @param stats 检查量与资源证据
     * @param failure 未通过时的反例或未确定原因，通过时为 null
     */
    public record CaseResult(int offsetMillis, Status status, Stats stats, Failure failure) {
    }

    /**
     * 回放统计，需求数同时包含必要起按和不计物量但需要判定的物件。
     *
     * @param completed 当前结论所在分支已完成的需求数
     * @param required 原谱必需判定和起按需求总数
     * @param peakStates 同时保留的最多非等价状态数
     * @param frames 实际检查的离线时刻数
     * @param elapsedMillis 本场景耗时
     */
    public record Stats(int completed, int required, int peakStates, int frames, long elapsedMillis) {
    }

    /**
     * 一条失败路径，保留实际触点和引发失败的操作/染色链。
     *
     * @param timing 判定失败或无法继续作出结论的谱面时间
     * @param reason 原因，包含判定种类、名义时间和窗口边界
     * @param sourceIds 相关原谱来源身份，不用于预分配触点
     * @param touches 该时刻实际仍按住的触点
     * @param history 该分支的近期操作及颜色变化；完整操作仍在原始候选 JSON 中
     */
    public record Failure(double timing, String reason, List<Integer> sourceIds,
                          List<TouchSnapshot> touches, List<TraceEvent> history) {
        /** 固定反例快照，不保留可变回放容器。 */
        public Failure {
            sourceIds = List.copyOf(sourceIds);
            touches = List.copyOf(touches);
            history = List.copyOf(history);
        }
    }

    /**
     * 实际触点状态。
     *
     * @param id 脚本触点 ID
     * @param x 当前反解 AFF 横坐标
     * @param y 当前反解 AFF 纵坐标
     * @param color 实际已染颜色，-1 表示未染色或处于放行
     */
    public record TouchSnapshot(int id, double x, double y, int color) {
    }

    /**
     * 回放中真实发生的操作或状态转移。
     *
     * @param timing 谱面时间，已经应用本场景整体偏移
     * @param event 操作或颜色事件说明
     * @param touch 相关触点，无单个触点的事件为 null
     * @param sourceId 相关来源身份，没有单个来源时为 -1
     */
    public record TraceEvent(double timing, String event, TouchSnapshot touch, int sourceId) {
    }

    /**
     * 将诊断结构序列化为独立 JSON，不改变公共 record 格式。
     *
     * @return 包含三个偏移结论与反例的可读 JSON
     */
    public String toJson() {
        JSONObject root = new JSONObject();
        root.put("chart", chart);
        root.put("status", status.name());
        root.put("model", model);
        JSONArray scenes = new JSONArray();
        for (CaseResult scene : cases) {
            JSONObject value = new JSONObject();
            value.put("offsetMillis", scene.offsetMillis());
            value.put("status", scene.status().name());
            Stats stats = scene.stats();
            JSONObject counts = new JSONObject();
            counts.put("completed", stats.completed());
            counts.put("required", stats.required());
            counts.put("peakStates", stats.peakStates());
            counts.put("frames", stats.frames());
            counts.put("elapsedMillis", stats.elapsedMillis());
            value.put("stats", counts);
            Failure failure = scene.failure();
            if (failure != null) {
                JSONObject detail = new JSONObject();
                detail.put("timing", failure.timing());
                detail.put("reason", failure.reason());
                detail.put("sourceIds", failure.sourceIds());
                JSONArray touches = new JSONArray();
                for (TouchSnapshot touch : failure.touches()) {
                    touches.add(touchJson(touch));
                }
                detail.put("touches", touches);
                JSONArray history = new JSONArray();
                for (TraceEvent event : failure.history()) {
                    JSONObject entry = new JSONObject();
                    entry.put("timing", event.timing());
                    entry.put("event", event.event());
                    entry.put("touch", event.touch() == null ? null : touchJson(event.touch()));
                    entry.put("sourceId", event.sourceId());
                    history.add(entry);
                }
                detail.put("history", history);
                value.put("failure", detail);
            }
            scenes.add(value);
        }
        root.put("cases", scenes);
        return root.toString(JSONWriter.Feature.PrettyFormat);
    }

    /** 使用项目 Android 版 Fastjson 支持的显式对象，不依赖 Java record 反射识别。 */
    private static JSONObject touchJson(TouchSnapshot touch) {
        JSONObject value = new JSONObject();
        value.put("id", touch.id());
        value.put("x", touch.x());
        value.put("y", touch.y());
        value.put("color", touch.color());
        return value;
    }
}
