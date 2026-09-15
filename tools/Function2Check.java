import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import arc.record.aff.note.Arc;
import arc.record.aff.note.Note;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;

import arc.record.aff.Aff;
import arc.record.funcs.AffToRecord;
import arc.record.record.func.RecordThreadPool;
import arc.record.record.model.Request;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;

/**
 * 功能 2 的隔离诊断入口：复用一键请求发现、生产转换及内置最终 JSON 校验。
 * 可筛选失败谱面，单张异常不取消其余清单；正式验收仍由 Main 的一键入口执行。
 */
public final class Function2Check {
    private Function2Check() {
    }

    /**
     * @param args 输出目录、all/theory、工作线程数、可选 sid/难度.aff 筛选列表
     * @throws Exception 初始化、文件或工作线程故障
     */
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        boolean theoryOnly = "theory".equals(args[1]);
        int workers = Integer.parseInt(args[2]);
        List<String> filters = List.of(args).subList(3, args.length);
        Files.createDirectories(output.resolve("charts"));
        AffToRecord discovery = new AffToRecord();
        Method auto = AffToRecord.class.getDeclaredMethod("auto");
        auto.setAccessible(true);
        auto.invoke(discovery);
        Map<File, Aff> charts = field(discovery, "affMap");
        Map<File, Map<Integer, List<Request>>> all = field(discovery, "processMap");
        int fullRequests = all.values().stream().flatMap(map -> map.values().stream()).mapToInt(List::size).sum();
        List<File> selected = all.keySet().stream().filter(file -> filters.isEmpty() || filters.contains(key(file)))
                .sorted(Comparator.comparing(File::getAbsolutePath)).toList();
        System.out.println("DISCOVERED charts=" + all.size() + " requests=" + fullRequests
                + " selected=" + selected.size() + " mode=" + args[1]);
        if (selected.isEmpty()) throw new IllegalArgumentException("筛选没有匹配谱面：" + filters);
        Method process = RecordThreadPool.class.getDeclaredMethod("processFile", File.class, Aff.class, Map.class);
        process.setAccessible(true);
        List<JSONObject> rows = new ArrayList<>();
        long started = System.nanoTime();
        try (var pool = Executors.newFixedThreadPool(workers);
             var journal = Files.newBufferedWriter(output.resolve("results.ndjson"), StandardCharsets.UTF_8)) {
            ExecutorCompletionService<JSONObject> completion = new ExecutorCompletionService<>(pool);
            for (File file : selected) {
                Map<Integer, List<Request>> requests = new LinkedHashMap<>();
                all.get(file).entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                    if (theoryOnly && entry.getKey() != 0) return;
                    requests.put(entry.getKey(), entry.getValue().stream().map(request -> new Request(
                            output.resolve("records").resolve(request.targetDir().getName()).toFile(),
                            request.mirror(), request.resolution())).toList());
                });
                completion.submit(() -> check(process, file, charts.get(file), requests, output));
            }
            for (int i = 0; i < selected.size(); i++) {
                JSONObject row = completion.take().get();
                rows.add(row);
                journal.write(row.toJSONString());
                journal.newLine();
                journal.flush();
                System.out.println("RESULT " + (i + 1) + "/" + selected.size() + " "
                        + row.getString("key") + " " + row.getString("status")
                        + (row.containsKey("error") ? " " + row.getString("error") : ""));
            }
        }
        JSONObject summary = new JSONObject();
        summary.put("discoveredCharts", all.size());
        summary.put("fullRequests", fullRequests);
        summary.put("selectedCharts", selected.size());
        summary.put("theoryOnly", theoryOnly);
        summary.put("completed", rows.size());
        summary.put("passed", rows.stream().filter(row -> "PASS".equals(row.getString("status"))).count());
        summary.put("failed", rows.stream().filter(row -> "FAIL".equals(row.getString("status"))).count());
        summary.put("errors", rows.stream().filter(row -> "ERROR".equals(row.getString("status"))).count());
        summary.put("millis", (System.nanoTime() - started) / 1_000_000L);
        summary.put("rows", rows.stream().sorted(Comparator.comparing(row -> row.getString("key"))).toList());
        Files.writeString(output.resolve("summary.json"), JSON.toJSONString(summary, JSONWriter.Feature.PrettyFormat));
        System.out.println("SUMMARY " + summary.getLongValue("passed") + "/" + rows.size()
                + " PASS，FAIL=" + summary.getLongValue("failed") + "，ERROR=" + summary.getLongValue("errors"));
        System.exit(summary.getLongValue("passed") == selected.size() ? 0 : 1);
    }

    /** 原样调用一个谱面的全部生产请求，只将诊断输出写至本轮隔离目录。 */
    private static JSONObject check(Method process, File file, Aff chart,
                                    Map<Integer, List<Request>> requests, Path output) throws Exception {
        JSONObject row = new JSONObject();
        row.put("key", key(file));
        row.put("requests", requests.values().stream().mapToInt(List::size).sum());
        long started = System.nanoTime();
        try {
            Object result = process.invoke(new RecordThreadPool(), file, chart, requests);
            int checked = component(result, "checkedNum");
            int failed = component(result, "failedNum");
            row.put("checked", checked);
            row.put("failed", failed);
            row.put("processed", (Integer) component(result, "processedNum"));
            List<?> problems = component(result, "problems");
            row.put("status", !problems.isEmpty() ? "ERROR" : checked > 0 && failed == 0 ? "PASS" : "FAIL");
            if (!problems.isEmpty()) {
                List<JSONObject> details = new ArrayList<>();
                for (Object problem : problems) {
                    JSONObject detail = new JSONObject();
                    for (String name : List.of("chart", "score", "stage", "reason", "trace")) {
                        detail.put(name, (String) component(problem, name));
                    }
                    details.add(detail);
                }
                row.put("problems", details);
                row.put("error", details.getFirst().getString("reason"));
                row.put("trace", details.getFirst().getString("trace"));
                addContext(row, chart, row.getString("error"));
            }
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            row.put("status", "ERROR");
            row.put("error", cause.getClass().getName() + ": " + cause.getMessage());
            StringWriter trace = new StringWriter();
            cause.printStackTrace(new PrintWriter(trace));
            row.put("trace", trace.toString());
            addContext(row, chart, cause.getMessage());
        }
        row.put("millis", (System.nanoTime() - started) / 1_000_000L);
        Files.writeString(output.resolve("charts").resolve(file.getParentFile().getName() + "_" + file.getName() + ".json"),
                JSON.toJSONString(row, JSONWriter.Feature.PrettyFormat));
        return row;
    }

    /** 将原始错误里的时刻用于筛选上下文，不改变或重新解释判定规则。 */
    private static void addContext(JSONObject row, Aff chart, String reason) {
        var time = Pattern.compile("(?:timing=|window=\\[)([0-9.]+)").matcher(String.valueOf(reason));
        if (time.find()) {
            double at = Double.parseDouble(time.group(1));
            row.put("context", chart.getNoteList().stream()
                    .filter(note -> note.getT1() <= at + 300 && note.getT2() >= at - 300)
                    .map(note -> describe(chart, note)).toList());
        }
    }

    /** 失败附近的解析后身份和连接，便于直接对照 AFF 原行。 */
    private static JSONObject describe(Aff chart, Note note) {
        JSONObject value = new JSONObject();
        value.put("id", note.getSourceId());
        value.put("kind", note.getClass().getSimpleName());
        value.put("t1", note.getT1());
        value.put("t2", note.getT2());
        value.put("bpm", note.getJudgeBpm());
        value.put("start", note.getAffPoint(note.getT1()));
        value.put("end", note.getAffPoint(note.getT2()));
        if (note instanceof Arc arc) {
            value.put("color", arc.getColor());
            value.put("easing", arc.getEasing());
            value.put("predecessors", chart.getArcTopology().predecessorsOf(note.getSourceId()).stream().map(Arc::getSourceId).toList());
            value.put("successors", chart.getArcTopology().successorsOf(note.getSourceId()).stream().map(Arc::getSourceId).toList());
        }
        return value;
    }

    /** 反射只用于诊断入口接入既有生产流程，不向生产模型注入验收结论。 */
    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(target);
    }

    /** 读取生产转换本身的计数，FAIL/INCONCLUSIVE 均不能计为 PASS。 */
    @SuppressWarnings("unchecked")
    private static <T> T component(Object record, String name) throws Exception {
        Method method = record.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return (T) method.invoke(record);
    }

    /** 与官方目录对应的稳定筛选键。 */
    private static String key(File file) {
        return file.getParentFile().getName() + "/" + file.getName();
    }
}
