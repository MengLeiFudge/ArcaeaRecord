package arcaea.record;

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

/**
 * 设定 arc 文件夹的位置，以及其他参数.
 * <p>
 * 在开始前，应按照如下步骤配置：
 * <ul>
 *     <li>修改 {@link #ARC_DIR} 至合适的目录</li>
 *     <li>将最新的 arcaea 安装包（如 arcaea_4.1.0c.apk）放至 {@link #ARC_DIR} 内</li>
 *     <li>用雷电模拟器下载全部的歌曲，并将 dl 文件夹移动至 Pictures 内</li>
 *     <li>打开雷电模拟器脚本路径，将其复制到 {@link #VMS_DIR}</li>
 *     <li>运行程序！enjoy it！</li>
 * </ul>
 *
 * @author MengLeiFudge
 */
public class SettingsAndUtils {
    private SettingsAndUtils() {
    }

    /**
     * arc 文件存放的根目录.
     */
    public static final File ARC_DIR = new File("D:/arc");
    //public static final File ARC_DIR = new File("C:\\机台源码勿动\\MLJ\\arc");

    /**
     * 官谱路径，以歌曲 sid 为文件夹存储谱面、音乐、曲绘等.
     */
    public static final File AFF_DIR = new File(ARC_DIR, "官谱");

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
    public static final File VMS_DIR = new File("F:/leidian/vms");

    /**
     * JVM 可用的最大 CPU 数量.
     */
    public static final int THREAD_NUM = Runtime.getRuntime().availableProcessors();

    /**
     * 项目目录的 songlist 文件.
     */
    public static final File SONG_LIST = new File("songlist.json");

    /**
     * 暂存 sid 与 歌曲英文名 的对应关系，提升读取速度.
     */
    private static final HashMap<String, String> SONG_MAP = new HashMap<>();

    /**
     * 根据输入的 sid，返回对应的歌曲英文名.
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
     * 蛇种类，目前只有红蓝绿.
     */
    public static final int ARC_KINDS = 3;

    /**
     * 去掉蛇后的最高触控数.
     * <p>
     * 因为蛇的触控ID不会更改，且排在非蛇触控ID的后面，所以才有了这个限制。
     * <p>
     * 正常来讲，ID不应该超过4个，10个ID足够用了；
     * 为了方便蛇的拓展，才将蛇的触控ID放在非蛇触控ID之后；
     * 最终脚本操作都是按下、抬起，无法区分是否为蛇，但是需要对蛇头有单点/长条的情况进行处理，
     * 所以需要给蛇设置单独的ID，这也是未将蛇也像非蛇按键一样使用 TouchIdManager 进行管理的原因。
     */
    public static final int MAX_TOUCH_NUM = 10;

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
     * 如果
     */
    public static final int CONTINUE_TIME = 6990;


    static SimpleDateFormat sdf = new SimpleDateFormat("MMdd_HHmmss");
}
