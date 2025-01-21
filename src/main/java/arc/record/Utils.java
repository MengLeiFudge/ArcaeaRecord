package arc.record;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Locale;

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
    public static final String[] DIFFICULTY_STR = {"PST", "PRS", "FTR", "BYD", "ETR"};
    /**
     * 暂存 sid 与 歌曲英文名 的对应关系，提升读取速度.
     */
    private static final HashMap<String, String> SONG_MAP = new HashMap<>();
    public static SimpleDateFormat sdf = new SimpleDateFormat("MMdd_HHmmss");
    public static DecimalFormat dfTime = new DecimalFormat("000000");
    public static DecimalFormat dfXY = new DecimalFormat(" 0.00;-0.00");

    private Utils() {
    }

    /**
     * 根据输入的 sid，返回对应的歌曲英文名.
     *
     * @param sid 要查找歌曲名的歌曲 id
     * @return 如果 SONG_LIST 文件中包含该歌曲，返回歌曲英文名；否则返回 null
     */
    public static String getTitleLocalizedEN(String sid) {
        if (!SONG_MAP.containsKey(sid)) {
            SONG_MAP.clear();
            try {
                JSONObject obj = JSON.parseObject(FileUtils.readFileToString(SONG_LIST, StandardCharsets.UTF_8));
                JSONArray songInfoArr = obj.getJSONArray("songs");
                for (int i = 0; i < songInfoArr.size(); i++) {
                    JSONObject songInfo = songInfoArr.getJSONObject(i);
                    String songId = songInfo.getString("id");
                    // 过滤已删除的歌曲，例如 Particle Arts
                    if (songInfo.containsKey("deleted") && songInfo.getBoolean("deleted")) {
                        SONG_MAP.put(songId, songId);
                        continue;
                    }
                    String songNameEN = songInfo.getJSONObject("title_localized").getString("en");
                    SONG_MAP.put(songId, songNameEN);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return SONG_MAP.get(sid);
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
