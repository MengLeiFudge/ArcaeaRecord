package arc.record;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;

import static arc.record.Settings.ARC_DIR;

/**
 * 设定 arc 文件夹的位置，以及其他参数.
 *
 * @author MengLeiFudge
 */
public class Utils {
    private Utils() {
    }


    /**
     * arcaea 的 apk 安装包.
     */
    public static File getApk() {
        File[] files = ARC_DIR.listFiles();
        if (files == null) {
            return null;
        }
        ArrayList<File> fileList = new ArrayList<>(Arrays.asList(files));
        fileList.removeIf(f -> !f.getName().matches("(?i)arc.*\\.apk"));
        fileList.sort(Comparator.comparing(File::getName));
        return fileList.get(fileList.size() - 1);
    }

    /**
     * 从模拟器中复制出来的 dl 文件夹.
     */
    public static File getDlDir() {
        return new File("C:/Users/" + System.getProperty("user.name") + "/Documents/leidian/Pictures/dl");
    }

    /**
     * 搭档全身图、头像文件夹.
     */
    public static File getCharDir() {
        return new File(ARC_DIR, "char");
    }

    /**
     * 脚本文件夹所在的根目录.
     */
    //public static final File VMS_DIR = new File("F:/leidian/vms");
    public static final File VMS_DIR = new File("C:\\机台源码勿动\\MLJ\\arc\\record");

    /**
     * JVM 可用的最大 CPU 数量.
     */
    public static final int THREAD_NUM = Runtime.getRuntime().availableProcessors();

    /**
     * 项目目录的 songlist 文件.
     */
    public static final File SONG_LIST = new File("src/main/resources/songlist.json");

    /**
     * 暂存 sid 与 歌曲英文名 的对应关系，提升读取速度.
     */
    private static final HashMap<String, String> SONG_MAP = new HashMap<>();

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
        String songName = getTitleLocalizedEN(sid);
        if (songName == null) {
            return sid;
        }
        // 雷电模拟器脚本按照先大写再小写排序，很不方便，这里全部改成小写
        // Windows 文件名不能有 \/:*?"<>| 这些字符，将其全部替换为空格
        return songName.toLowerCase(Locale.ROOT)
                .replaceAll(":", "：")
                .replaceAll("\\?", "？")
                .replaceAll("[\\\\/:*?\"<>|]", "");
    }

    public static final String[] DIFFICULTY_STR = {"PST", "PRS", "FTR", "BYD"};

    /**
     * 某个ID多次使用时，各个操作之间的最短间隔.
     * <p>
     * 这玩意好像没啥必要。
     * <p>
     * 如果你觉得不需要，请将其改为0（不可改为负数，因为一个ID不能同时点两个位置）。
     */
    public static final int INTERVAL_TIME = 50;

    /**
     * 按键之间影响时间最小值.
     * <p>
     * 该值是为了避免按键操作变化导致相近按键无法正常判定而设立的。
     * <p>
     * 对于任意两个按键，无论哪个按键转为 miss 或 小p，只要时间超过 270，即可保证不会互相干扰。
     */
    public static final int EFFECT_TIME = 220 + 25 * 2;

    /**
     * 地键/天键点击时间.
     */
    public static final int CLICK_TIME = 100;

    /**
     * 点击继续按钮的抬起时机.
     * <p>
     * 如果脚本 late 较多，则应减小该值。
     */
    public static final int CONTINUE_TIME = 6990;

    /**
     * 触控采样频率.
     * <p>
     * 雷电模拟器只有按下和抬起操作，所以长条、蛇转为操作时，需要根据判定间隔计算坐标。
     * <p>
     * 该值表示每个判定间隔计算几次坐标。该值越大，移动操作就越精确，脚本大小也更大。
     * <p>
     * 该值必须大于等于1。
     */
    public static final float TOUCH_SAMPLE_FREQUENCY = 8;


    static SimpleDateFormat sdf = new SimpleDateFormat("MMdd_HHmmss");
}
