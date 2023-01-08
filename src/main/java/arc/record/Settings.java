package arc.record;

import java.io.File;

public class Settings {
    private Settings() {
    }

    /**
     * 指示是否为调试模式.
     * <p>
     * 调试模式下会生成中间的处理按键列表
     */
    public static final boolean DEBUG_MODE = false;

    /**
     * arc 文件存放的根目录.
     */
    //public static final File ARC_DIR = new File("D:/arc");
    public static final File ARC_DIR = new File("C:\\机台源码勿动\\MLJ\\arc");

    /**
     * 官谱路径，以歌曲 sid 为文件夹存储谱面、音乐、曲绘等.
     */
    public static final File AFF_DIR = new File(ARC_DIR, "官谱");

}
