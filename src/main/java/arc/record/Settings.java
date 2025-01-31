package arc.record;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

import javax.swing.filechooser.FileSystemView;

/**
 * 程序运行所需配置，如 arc 文件夹位置等.
 *
 * @author MengLeiFudge
 */
public class Settings {
    /**
     * 脚本文件夹所在的根目录.
     */
    public static final File VMS_DIR = new File("D:\\leidian\\LDPlayer9\\vms");
    /**
     * arc 文件存放的根目录.
     */
    public static final File ARC_DIR = new File("D:\\Games\\Arcaea");
    /**
     * 官谱路径，以歌曲 sid 为文件夹存储谱面、音乐、曲绘等.
     */
    public static final File AFF_DIR = new File(ARC_DIR, "官谱");
    /**
     * 从模拟器中复制出来的 dl 文件夹.
     */
    public static final File DL_DIR = new File(FileSystemView.getFileSystemView().getDefaultDirectory().getPath()
            + "/leidian9/Pictures/dl");
    /**
     * 搭档全身图、头像文件夹.
     */
    public static final File CHAR_DIR = new File(ARC_DIR, "char");
    /**
     * Link表情包文件夹.
     */
    public static final File STICKER_DIR = new File(ARC_DIR, "stickers");
    /**
     * 项目目录的 songlist 文件.
     */
    public static final File SONG_LIST = new File("src/main/resources/songlist.json");
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
     * 指示谱面时间戳与脚本时间戳的对应关系.
     * <p>
     * 如果脚本 late 较多，则应减小该值。
     */
    public static final int FIRST_NOTE_TIME = 8295;
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
    /**
     * 指示是否为调试模式.
     * <p>
     * 调试模式下会将处理过程存储至表格中，用于确认程序的处理逻辑是否正确。
     * 同样的，由于每个脚本都会生成多个调试表格，因此开启调试模式情况下，输入的曲目的数目不应超过1。
     */
    public static boolean DEBUG_MODE = false;

    private Settings() {
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
        return fileList.getLast();
    }
}
