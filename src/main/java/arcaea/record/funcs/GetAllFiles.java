package arcaea.record.funcs;

import arcaea.record.SettingsAndUtils;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static arcaea.record.Main.sc;

/**
 * @author MengLeiFudge
 */
public class GetAllFiles {
    public GetAllFiles() {
    }

    private File dlDir;
    private File affDir;
    private File apk;
    private File charDir;
    private ArrayList<File> fileList = new ArrayList<>();

    public void process() {
        init();
        System.out.println("重命名 dl 文件夹中的内容...");
        renameFile();
        System.out.println("移动 dl 文件夹中内容至谱面目录...");
        moveDir(dlDir, affDir);
        System.out.println("dl 文件夹中内容移动完毕，开始从 apk 提取文件！");
        if (apk == null || !apk.isFile()) {
            System.out.println("未找到 apk 文件，跳过该步骤！");
        } else {
            unZipApk();
            System.out.println("apk 文件提取完毕！");
        }
        System.out.println("提取songlist到项目目录...");
        moveSongList();
        System.out.println("提取完毕，回车继续...");
        sc.nextLine();
        System.out.println();
    }

    private void init() {
        System.out.println("输入 dl 文件夹路径");
        File defFile = SettingsAndUtils.getDlDir();
        System.out.println("回车表示 " + defFile);
        String s = sc.nextLine();
        dlDir = s.equals("") ? defFile : new File(s);
        File[] files = dlDir.listFiles();
        if (files != null) {
            fileList = new ArrayList<>(Arrays.asList(files));
            // 文件夹不处理，移除所有文件夹
            fileList.removeIf(f -> !f.isFile());
            // 文件按名称排序
            fileList.sort(Comparator.comparing(File::getName));
        }

        System.out.println("输入 arc apk 路径");
        defFile = SettingsAndUtils.getApk();
        System.out.println("回车表示 " + defFile);
        s = sc.nextLine();
        apk = s.equals("") ? defFile : new File(s);

        System.out.println("输入目标官谱文件夹路径");
        defFile = SettingsAndUtils.getAffDir();
        System.out.println("回车表示 " + defFile);
        s = sc.nextLine();
        affDir = s.equals("") ? defFile : new File(s);

        System.out.println("输入目标搭档图片文件夹路径");
        defFile = SettingsAndUtils.getCharDir();
        System.out.println("回车表示 " + defFile);
        s = sc.nextLine();
        charDir = s.equals("") ? defFile : new File(s);
    }

    /**
     * 将 dl 文件夹中的所有文件进行重命名.
     * <p>
     * fileList 中含有 dl 文件夹内的所有文件（文件夹已经移除），按照名称排序，可以按歌曲 sid 分组，可能的组如下：
     * <ul>
     *     <li>aegleseeker aegleseeker_0 aegleseeker_1 aegleseeker_2</li>
     *     <li>antithese antithese_0 antithese_1 antithese_2 antithese_3</li>
     *     <li>arcanaeden arcanaeden_0 arcanaeden_1 arcanaeden_2 arcanaeden_3 arcanaeden_video.mp4 arcanaeden_video_audio.ogg</li>
     *     <li>bookmaker_3</li>
     *     <li>dropdead dropdead_0 dropdead_1 dropdead_2 dropdead_3 dropdead_audio_3</li>
     *     <li>ignotus_3 ignotus_audio_3</li>
     * </ul>
     * 只有第四组、第六组是免费曲的 byd 难度，文件夹为 sid；其余文件夹都为 dl_sid。
     * 所以可以用 sid 变化时的文件名是否带 _ 确定歌曲是不是免费曲。
     * <p>
     * 文件名转换关系如下（x表示0-3的数字）：
     * <ul>
     *     <li>sid -> base.ogg</li>
     *     <li>sid_x -> x.aff</li>
     *     <li>sid_video.mp4 -> video.mp4</li>
     *     <li>sid_video_audio.ogg -> video_audio.ogg</li>
     *     <li>sid_audio_x -> x.ogg</li>
     * </ul>
     */
    private void renameFile() {
        // 检查是否有未下载完成的文件
        for (File f : fileList) {
            if (f.getName().endsWith(".pre")) {
                // 歌曲未下载完成或其他原因，testify使用下载全部也不行，必须在歌曲预览界面下载
                System.out.println("歌曲未下载完成或其他原因：" + f.getAbsolutePath());
                System.out.println("请下载该歌曲后再运行！");
                System.exit(0);
            }
        }
        String groupSid = "";
        boolean dl = false;
        for (File f : fileList) {
            String fileName = f.getName();
            File newFile;
            if (!fileName.contains("_")) {
                File dir = new File(f.getParentFile(), "dl_" + fileName);
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                newFile = new File(dir, "base.ogg");
                groupSid = fileName;
                dl = true;
            } else {
                String[] info = fileName.split("_");
                String sid = info[0];
                if (!sid.equals(groupSid)) {
                    groupSid = sid;
                    dl = !info[1].equals("3");
                }
                File dir = new File(f.getParentFile(), (dl ? "dl_" : "") + sid);
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                if (sid.equals(groupSid)) {
                    switch (info[1]) {
                        // aegleseeker_0 -> 0.aff
                        case "0", "1", "2", "3" -> newFile = new File(dir, info[1] + ".aff");
                        // dropdead_audio_3 -> 3.ogg
                        case "audio" -> newFile = new File(dir, info[2] + ".ogg");
                        // arcanaeden_video.mp4 -> video.mp4, arcanaeden_video_audio.ogg -> video_audio.ogg
                        case "video.mp4", "video" ->
                                newFile = new File(dir, fileName.substring(fileName.indexOf('_') + 1));
                        default -> {
                            System.out.println("未知文件类型，请修改代码后再运行！");
                            System.out.println(f.getAbsolutePath());
                            System.exit(0);
                            return;
                        }
                    }
                } else if (info[1].equals("3")) {
                    // 单个文件且为 _3 结尾，说明是免费曲的 byd 难度
                    newFile = new File(dir, "3.aff");
                } else {
                    System.out.println("未知文件类型，请修改代码后再运行！");
                    System.out.println(f.getAbsolutePath());
                    System.exit(0);
                    return;
                }
            }
            // 避免重命名失败，先将原有的删除
            newFile.delete();
            try {
                System.out.println((f.renameTo(newFile) ? "重命名成功：" : "重命名失败：")
                        + f.getCanonicalPath() + " -> " + newFile.getCanonicalPath());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void moveDir(File srcDir, File destDir) {
        destDir.mkdirs();
        File[] files = srcDir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                moveDir(new File(srcDir, file.getName()), new File(destDir, file.getName()));
                continue;
            }
            File newFile = new File(destDir, file.getName());
            newFile.delete();
            try {
                System.out.println((file.renameTo(newFile) ? "移动成功：" : "移动失败：")
                        + file.getCanonicalPath() + " -> " + newFile.getCanonicalPath());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        srcDir.delete();
    }

    public void unZipApk() {
        // GBK解决中文文件夹乱码
        try (ZipFile zipFile = new ZipFile(apk, Charset.forName("GBK"))) {
            for (Enumeration<? extends ZipEntry> entries = zipFile.entries(); entries.hasMoreElements(); ) {
                ZipEntry entry = entries.nextElement();
                // 此处不处理目录，在 unZipFile 时先创建父文件夹再写入文件
                if (entry.isDirectory()) {
                    continue;
                }
                String pathInZip = entry.getName();
                if (pathInZip.startsWith("assets/char/")) {
                    String s = pathInZip.substring(12);
                    unZipFile(zipFile, entry, new File(charDir, s));
                } else if (pathInZip.startsWith("assets/songs/")) {
                    String s = pathInZip.substring(13);
                    unZipFile(zipFile, entry, new File(affDir, s));
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void unZipFile(ZipFile zipFile, ZipEntry entry, File destFile) throws IOException {
        destFile.getParentFile().mkdirs();
        destFile.createNewFile();
        try (InputStream is = zipFile.getInputStream(entry);
             FileOutputStream fos = new FileOutputStream(destFile)) {
            byte[] buf = new byte[1024];
            int len;
            while ((len = is.read(buf)) != -1) {
                fos.write(buf, 0, len);
            }
            System.out.println("解压成功："
                    + entry.getName() + " -> " + destFile.getCanonicalPath());
        } catch (IOException e) {
            e.printStackTrace();
            System.out.println("解压失败："
                    + entry.getName() + " -> " + destFile.getCanonicalPath());
        }
    }

    public void moveSongList() {
        File songListInAffDir = new File(affDir, "songlist");
        File songListInProject = new File("songlist.json");
        try {
            FileUtils.deleteQuietly(songListInProject);
            FileUtils.copyFile(songListInAffDir, songListInProject);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
