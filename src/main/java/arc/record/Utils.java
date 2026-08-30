package arc.record;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.apache.commons.io.FileUtils;

import static arc.record.Settings.SONG_LIST;

/**
 * 程序运行所需工具类.
 *
 * @author MengLeiFudge
 */
public class Utils {
    /**
     * JVM 可用的最大 CPU 数量.
     */
    public static final int THREAD_NUM = Runtime.getRuntime().availableProcessors();
    /**
     * aff 文件编号对应的基础难度名；编号 3 的实际名称必须通过 {@link #getDifficultyStr} 解析。
     */
    public static final String[] DIFFICULTY_STR = {"PST", "PRS", "FTR", "BYD", "ETR"};
    /**
     * 当前 songlist 中 sid 与歌曲对象的对应关系，保留 songlist 原始顺序。
     */
    private static Map<String, JSONObject> songInfoMap = Map.of();
    private static long songListLastModified;
    private static long songListLength;
    private static boolean songListLoaded;
    public static SimpleDateFormat sdf = new SimpleDateFormat("MMdd_HHmmss");
    public static DecimalFormat dfTime = new DecimalFormat("000000");
    public static DecimalFormat dfXY = new DecimalFormat(" 0.00;-0.00");

    private Utils() {
    }

    /**
     * songlist 中一张预期谱面的定位信息。
     *
     * @param sid         歌曲 id
     * @param ratingClass aff 文件编号
     * @param difficulty  已解析的难度名
     */
    public record SongChart(String sid, int ratingClass, String difficulty) {
    }

    private static synchronized Map<String, JSONObject> getSongInfoMap() {
        long lastModified = SONG_LIST.lastModified();
        long length = SONG_LIST.length();
        if (!songListLoaded || lastModified != songListLastModified || length != songListLength) {
            songInfoMap = readSongList();
            songListLastModified = lastModified;
            songListLength = length;
            songListLoaded = true;
        }
        return songInfoMap;
    }

    private static Map<String, JSONObject> readSongList() {
        try {
            JSONObject root = JSON.parseObject(FileUtils.readFileToString(SONG_LIST, StandardCharsets.UTF_8));
            if (root == null || !(root.get("songs") instanceof JSONArray songs)) {
                throw new IllegalStateException("songlist 缺少 songs 数组：" + SONG_LIST.getAbsolutePath());
            }
            Map<String, JSONObject> result = new LinkedHashMap<>();
            for (int i = 0; i < songs.size(); i++) {
                if (!(songs.get(i) instanceof JSONObject songInfo)) {
                    throw new IllegalStateException("songlist 的 songs[" + i + "] 不是歌曲对象");
                }
                String sid = songInfo.getString("id");
                if (sid == null || sid.isBlank()) {
                    throw new IllegalStateException("songlist 的 songs[" + i + "] 缺少有效 id");
                }
                if (result.putIfAbsent(sid, songInfo) != null) {
                    throw new IllegalStateException("songlist 包含重复 sid：" + sid);
                }
            }
            return Collections.unmodifiableMap(result);
        } catch (IOException e) {
            throw new IllegalStateException("无法读取 songlist：" + SONG_LIST.getAbsolutePath(), e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalStateException("无法解析 songlist：" + SONG_LIST.getAbsolutePath(), e);
        }
    }

    private static boolean isDeleted(String sid, JSONObject songInfo) {
        if (!songInfo.containsKey("deleted")) {
            return false;
        }
        Object deleted = songInfo.get("deleted");
        if (!(deleted instanceof Boolean)) {
            throw new IllegalStateException("songlist 中歌曲 " + sid + " 的 deleted 不是布尔值");
        }
        return (Boolean) deleted;
    }

    private static JSONArray getDifficulties(String sid, JSONObject songInfo) {
        if (!(songInfo.get("difficulties") instanceof JSONArray difficulties) || difficulties.isEmpty()) {
            throw new IllegalStateException("songlist 中歌曲 " + sid + " 缺少有效 difficulties 数组");
        }
        return difficulties;
    }

    private static JSONObject getDifficultyInfo(String sid, JSONArray difficulties, int index) {
        if (!(difficulties.get(index) instanceof JSONObject difficultyInfo)) {
            throw new IllegalStateException("songlist 中歌曲 " + sid + " 的 difficulties[" + index + "] 不是对象");
        }
        return difficultyInfo;
    }

    private static int getRatingClass(String sid, JSONObject difficultyInfo) {
        Object value = difficultyInfo.get("ratingClass");
        if (!(value instanceof Number number) || number.doubleValue() != number.intValue()) {
            throw new IllegalStateException("songlist 中歌曲 " + sid + " 存在无效 ratingClass");
        }
        int ratingClass = number.intValue();
        if (ratingClass < 0 || ratingClass >= DIFFICULTY_STR.length) {
            throw new IllegalStateException("songlist 中歌曲 " + sid + " 存在不支持的 ratingClass：" + ratingClass);
        }
        return ratingClass;
    }

    private static String resolveDifficultyStr(String sid, int ratingClass, JSONObject difficultyInfo) {
        if (ratingClass != 3) {
            if (difficultyInfo.containsKey("ratingClassAlias")) {
                throw new IllegalStateException("songlist 中歌曲 " + sid + " 的难度 " + ratingClass
                        + " 存在不支持的 ratingClassAlias");
            }
            return DIFFICULTY_STR[ratingClass];
        }
        if (!difficultyInfo.containsKey("ratingClassAlias")) {
            return DIFFICULTY_STR[ratingClass];
        }
        Object value = difficultyInfo.get("ratingClassAlias");
        if (!(value instanceof Number number) || number.doubleValue() != number.intValue()) {
            throw new IllegalStateException("songlist 中歌曲 " + sid + " 的 ratingClassAlias 不是整数");
        }
        int alias = number.intValue();
        if (alias == 1) {
            return "INS";
        }
        throw new IllegalStateException("songlist 中歌曲 " + sid + " 存在不支持的 ratingClassAlias：" + alias);
    }

    /**
     * 返回 songlist 中所有未删除歌曲的预期谱面，顺序与 songlist 一致。
     *
     * @return 不可修改的预期谱面列表
     * @throws IllegalStateException songlist 结构或难度字段无法解析时抛出
     */
    public static List<SongChart> getSongCharts() {
        List<SongChart> result = new ArrayList<>();
        for (var entry : getSongInfoMap().entrySet()) {
            String sid = entry.getKey();
            JSONObject songInfo = entry.getValue();
            if (isDeleted(sid, songInfo)) {
                continue;
            }
            JSONArray difficulties = getDifficulties(sid, songInfo);
            boolean[] ratingClassSeen = new boolean[DIFFICULTY_STR.length];
            for (int i = 0; i < difficulties.size(); i++) {
                JSONObject difficultyInfo = getDifficultyInfo(sid, difficulties, i);
                int ratingClass = getRatingClass(sid, difficultyInfo);
                if (ratingClassSeen[ratingClass]) {
                    throw new IllegalStateException("songlist 中歌曲 " + sid
                            + " 包含重复 ratingClass：" + ratingClass);
                }
                ratingClassSeen[ratingClass] = true;
                result.add(new SongChart(sid, ratingClass,
                        resolveDifficultyStr(sid, ratingClass, difficultyInfo)));
            }
        }
        return List.copyOf(result);
    }

    /**
     * 根据歌曲 id 与 aff 文件编号解析难度名。
     *
     * @param sid         歌曲 id
     * @param ratingClass aff 文件编号
     * @return PST、PRS、FTR、BYD、ETR 或 INS
     * @throws IllegalArgumentException ratingClass 超出当前支持范围时抛出
     * @throws IllegalStateException    songlist 无法唯一解析该歌曲难度时抛出
     */
    public static String getDifficultyStr(String sid, int ratingClass) {
        if (ratingClass < 0 || ratingClass >= DIFFICULTY_STR.length) {
            throw new IllegalArgumentException("不支持的 aff 文件编号：" + ratingClass);
        }
        JSONObject songInfo = getSongInfoMap().get(sid);
        if (songInfo == null) {
            throw new IllegalStateException("songlist 中不存在歌曲：" + sid);
        }
        if (isDeleted(sid, songInfo)) {
            throw new IllegalStateException("songlist 中歌曲已删除：" + sid);
        }
        JSONArray difficulties = getDifficulties(sid, songInfo);
        JSONObject matched = null;
        for (int i = 0; i < difficulties.size(); i++) {
            JSONObject difficultyInfo = getDifficultyInfo(sid, difficulties, i);
            if (getRatingClass(sid, difficultyInfo) != ratingClass) {
                continue;
            }
            if (matched != null) {
                throw new IllegalStateException("songlist 中歌曲 " + sid
                        + " 包含重复 ratingClass：" + ratingClass);
            }
            matched = difficultyInfo;
        }
        if (matched == null) {
            throw new IllegalStateException("songlist 中歌曲 " + sid
                    + " 缺少 ratingClass：" + ratingClass);
        }
        return resolveDifficultyStr(sid, ratingClass, matched);
    }

    /**
     * 根据输入的 sid，返回对应的歌曲英文名。
     *
     * @param sid 要查找歌曲名的歌曲 id
     * @return 如果 songlist 包含该歌曲，返回歌曲英文名；否则返回 null
     * @throws IllegalStateException songlist 中该歌曲信息无法解析时抛出
     */
    public static String getTitleLocalizedEN(String sid) {
        JSONObject songInfo = getSongInfoMap().get(sid);
        if (songInfo == null) {
            return null;
        }
        if (isDeleted(sid, songInfo)) {
            return sid;
        }
        if (!(songInfo.get("title_localized") instanceof JSONObject titleLocalized)) {
            throw new IllegalStateException("songlist 中歌曲 " + sid + " 缺少 title_localized 对象");
        }
        String title = titleLocalized.getString("en");
        if (title == null || title.isBlank()) {
            throw new IllegalStateException("songlist 中歌曲 " + sid + " 缺少英文标题");
        }
        return title;
    }

    /**
     * 根据输入的 sid，返回经过处理后的英文名.
     *
     * @param sid 要查找歌曲名的歌曲 id
     * @return 如果 SONG_LIST 文件中包含该歌曲，返回经过处理后的英文歌曲名；否则返回 sid
     */
    public static String getProcessedTitle(String sid) {
        //quon有两个，需要做区分
        if (sid.equals("quonwacca")) {
            return "quon wacca";
        }
        //Genesis有两个，需要做区分
        if (sid.equals("genesischunithm")) {
            return "genesis chunithm";
        }
        String songName = getTitleLocalizedEN(sid);
        if (songName == null) {
            return sid;
        }
        // 雷电模拟器脚本按照先大写再小写排序，很不方便，这里全部改成小写
        // Windows 文件名不能有 \/:*?"<>| 这些字符，将其全部替换为空格
        return songName.toLowerCase(Locale.ROOT)
                .replaceAll(":", "：")
                .replaceAll("\\?", "？")
                .replaceAll("[\\\\/:*?\"<>|]", " ");
    }
}
