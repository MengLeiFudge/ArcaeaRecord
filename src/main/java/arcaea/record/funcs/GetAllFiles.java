package arcaea.record.funcs;

import arcaea.record.SettingsAndUtils;

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
        System.out.println("开始重命名 dl 文件夹中的内容！");
        renameFile();
        System.out.println("dl 文件夹重命名完毕，开始移动文件！");
        moveDir(dlDir, affDir);
        System.out.println("dl 文件夹中内容移动完毕，开始从 apk 提取文件！");
        if (apk == null || !apk.isFile()) {
            System.out.println("未找到 apk 文件，跳过该步骤！");
        } else {
            unZipApk();
            System.out.println("apk 文件提取完毕！");
        }
        System.out.println("回车继续...");
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

    private void renameFile() {
        for (File f : fileList) {
            File newFile;
            String fileName = f.getName();
            if (fileName.endsWith(".pre")) {
                // 歌曲未下载完成或其他原因，testify使用下载全部也不行，必须在歌曲预览界面下载
                System.out.println("歌曲未下载完成或其他原因：" + f.getAbsolutePath());
                System.out.println("请下载该歌曲后再运行！");
                System.exit(0);
            }
            if (!fileName.contains("_")) {
                // aegleseeker -> base.ogg
                File dir = new File(f.getParentFile(), "dl_" + fileName);
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                newFile = new File(dir, "base.ogg");
            } else {
                String sid = fileName.substring(0, fileName.indexOf("_"));
                File dir = new File(f.getParentFile(), "dl_" + sid);
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                String end = fileName.substring(fileName.indexOf("_") + 1);
                switch (end) {
                    // aegleseeker_0 -> 0.aff
                    case "0", "1", "2", "3" -> newFile = new File(dir, end + ".aff");
                    // dropdead_audio_3 -> 3.ogg（目前只有audio_3）
                    case "audio_0", "audio_1", "audio_2", "audio_3" ->
                            newFile = new File(dir, end.substring(end.length() - 1) + ".ogg");
                    // arcanaeden_video.mp4 -> video.mp4, arcanaeden_video_audio.ogg -> video_audio.ogg
                    case "video.mp4", "video_audio.ogg" -> newFile = new File(dir, end);
                    default -> {
                        System.out.println("未知文件类型：" + f.getAbsolutePath());
                        System.out.println("请修改代码后再运行！");
                        System.exit(0);
                        return;
                    }
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
}
