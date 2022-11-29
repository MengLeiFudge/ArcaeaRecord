package arcaea.record.funcs;

import arcaea.record.Main;
import arcaea.record.SettingsAndUtils;
import arcaea.record.aff.Aff;
import arcaea.record.aff.Resolution;
import arcaea.record.base.AffProcess;
import arcaea.record.base.ConvertThreadPoolExecutor;
import arcaea.record.base.MissAndMinPure;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static arcaea.record.SettingsAndUtils.getTitleLocalizedEN;

/**
 * 谱面文件转脚本的入口.
 * <p>
 * 该类主要功能如下：
 * <ul>
 *     <li>获取用户输入的设置</li>
 *     <li>遍历获取所有符合条件的谱面，构建 {@link AffProcess} 列表</li>
 *     <li>使用线程池处理谱面并生成脚本</li>
 * </ul>
 *
 * @author MengLeiFudge
 */
public class AffToRecord {
    private final List<AffProcess> processList = new ArrayList<>();
    private File affDir;
    private File targetDir;
    private int minDifficulty;
    private int maxDifficulty;
    private SettingsAndUtils.RunState runState;
    private SettingsAndUtils.Mirror mirror;
    private MissAndMinPure missAndMinPure;
    private Resolution resolution;
    private final List<File> zipDirList = new ArrayList<>();

    public void process() {
        System.out.println("使用一键生成脚本（谱面目录使用 " + SettingsAndUtils.AFF_DIR + "）？");
        System.out.println("注：包含ftr+byd 0L2%、1L6%、991w8%、982w10% 原版+镜像，以及全难度理论值原版");
        System.out.println("回车表示一键生成脚本");
        System.out.println("输入其他内容表示自定义生成脚本");
        String s = Main.sc.nextLine();
        if ("".equals(s)) {
            auto();
        } else {
            diy();
        }
        if (processList.isEmpty()) {
            System.out.println("查找完毕，未找到需要处理的谱面文件！");
            return;
        }
        System.out.println("查找完毕，共找到 " + processList.size() + " 个谱面文件！");
        long millis = System.currentTimeMillis();
        ConvertThreadPoolExecutor.process(processList);
        millis = System.currentTimeMillis() - millis;
        int minutes = (int) (millis / 60000);
        millis %= 60000;
        int seconds = (int) (millis / 1000);
        millis %= 1000;
        System.out.println("所有谱面文件均已转化为脚本，用时 "
                + (minutes == 0 ? "" : (minutes + " min ")) + seconds + " s " + millis + " ms");
        if ("".equals(s)) {
            System.out.println("开始将脚本打包至 zip...");
            autoZip();
            System.out.println("已将所有脚本打包至 zip！");
        }
        System.out.println("回车继续...");
        Main.sc.nextLine();
        System.out.println();
    }

    /**
     * 使用默认值生成通常需要的全部脚本.
     */
    private void auto() {
        affDir = SettingsAndUtils.AFF_DIR;
        minDifficulty = 2;
        maxDifficulty = 3;
        runState = SettingsAndUtils.RunState.SONG_START_BEGIN;
        mirror = SettingsAndUtils.Mirror.BOTH;
        resolution = Resolution.R16_9_1280_720;

        targetDir = new File(SettingsAndUtils.VMS_DIR, "脚本/0L2%");
        try {
            targetDir = targetDir.getCanonicalFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
        missAndMinPure = new MissAndMinPure("0", "2%");
        zipDirList.add(targetDir);
        getProcessList(affDir);

        targetDir = new File(SettingsAndUtils.VMS_DIR, "脚本/1L6%");
        try {
            targetDir = targetDir.getCanonicalFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
        missAndMinPure = new MissAndMinPure("1", "6%");
        zipDirList.add(targetDir);
        getProcessList(affDir);

        targetDir = new File(SettingsAndUtils.VMS_DIR, "脚本/991w8%");
        try {
            targetDir = targetDir.getCanonicalFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
        missAndMinPure = new MissAndMinPure("991w", "8%");
        zipDirList.add(targetDir);
        getProcessList(affDir);

        targetDir = new File(SettingsAndUtils.VMS_DIR, "脚本/982w10%");
        try {
            targetDir = targetDir.getCanonicalFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
        missAndMinPure = new MissAndMinPure("982w", "10%");
        zipDirList.add(targetDir);
        getProcessList(affDir);

        targetDir = new File(SettingsAndUtils.VMS_DIR, "脚本/全难度理论值");
        try {
            targetDir = targetDir.getCanonicalFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
        minDifficulty = 0;
        mirror = SettingsAndUtils.Mirror.ORIGIN;
        missAndMinPure = new MissAndMinPure("0", "0");
        getProcessList(affDir);

        targetDir = new File(SettingsAndUtils.VMS_DIR, "脚本/低难度理论值");
        try {
            targetDir = targetDir.getCanonicalFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
        maxDifficulty = 1;
        missAndMinPure = new MissAndMinPure("0", "0");
        zipDirList.add(targetDir);
        getProcessList(affDir);
    }

    /**
     * 使用自定义设置生成脚本.
     */
    private void diy() {
        System.out.println("输入要转换的谱面文件夹路径");
        File defFile = SettingsAndUtils.AFF_DIR;
        System.out.println("回车表示 " + defFile);
        String s = Main.sc.nextLine();
        affDir = s.equals("") ? defFile : new File(s);

        System.out.println("选择目标文件夹路径：");
        defFile = new File(SettingsAndUtils.VMS_DIR, "operationRecords");
        System.out.println("1.仅使用新路径，在 " + defFile + " 集中生成脚本");
        System.out.println("2.仅使用原始路径，在每个谱面所对应文件夹下分别生成脚本");
        System.out.println("还可以直接输入路径，此时仅使用输入的新路径");
        System.out.println("回车表示 1");
        s = Main.sc.nextLine();
        switch (s) {
            case "1":
            case "":
                targetDir = defFile;
                break;
            case "2":
                targetDir = null;
                break;
            default:
                targetDir = new File(s);
        }
        if (targetDir != null) {
            try {
                targetDir = targetDir.getCanonicalFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        System.out.println("选择最低难度：");
        System.out.println("0 表示 pst，1 表示 prs，2 表示 ftr，3 表示 byd");
        System.out.println("回车表示 2，即 ftr");
        s = Main.sc.nextLine();
        minDifficulty = s.length() == 0 ? 2 : Integer.parseInt(s);
        System.out.println("选择最高难度：");
        System.out.println("0 表示 pst，1 表示 prs，2 表示 ftr，3 表示 byd");
        System.out.println("回车表示 3，即 byd");
        s = Main.sc.nextLine();
        maxDifficulty = s.length() == 0 ? 3 : Integer.parseInt(s);

        System.out.println("选择脚本运行情况：");
        System.out.println("1.开始歌曲后立刻运行脚本");
        System.out.println("2.第一个键点击的同时运行脚本");
        System.out.println("3.同时生成以上两种脚本");
        System.out.println("回车表示 1");
        s = Main.sc.nextLine();
        switch (s) {
            case "2":
                runState = SettingsAndUtils.RunState.FIRST_NOTE_BEGIN;
                break;
            case "3":
                runState = SettingsAndUtils.RunState.BOTH;
                break;
            case "1":
            case "":
            default:
                runState = SettingsAndUtils.RunState.SONG_START_BEGIN;
        }

        System.out.println("选择镜像情况：");
        System.out.println("1.正常  2.镜像  3.同时生成以上两种脚本");
        System.out.println("回车表示 3");
        s = Main.sc.nextLine();
        switch (s) {
            case "1":
                mirror = SettingsAndUtils.Mirror.ORIGIN;
                break;
            case "2":
                mirror = SettingsAndUtils.Mirror.MIRROR;
                break;
            case "3":
            case "":
            default:
                mirror = SettingsAndUtils.Mirror.BOTH;
        }

        System.out.println("输入单点miss数（如3）或目标分数上限（以W/w结尾，如990w）：");
        System.out.println("回车表示 991w");
        s = Main.sc.nextLine();
        String missStr = s.length() == 0 ? "991w" : s;
        String minPureStr = "0";
        if (runState == SettingsAndUtils.RunState.SONG_START_BEGIN || runState == SettingsAndUtils.RunState.BOTH) {
            System.out.println("输入小p数（如50）或小p比例（以%结尾，如5.6%）");
            System.out.println("回车表示 8%");
            s = Main.sc.nextLine();
            minPureStr = s.length() == 0 ? "8%" : s;
        }
        missAndMinPure = new MissAndMinPure(missStr, minPureStr);

        System.out.println("选择分辨率：");
        Resolution[] resolutions = Resolution.values();
        int i = 1;
        for (Resolution r : resolutions) {
            System.out.println((i++) + "." + r.getDescribe());
        }
        System.out.println("回车表示 " + Resolution.R16_9_1280_720.getDescribe());
        s = Main.sc.nextLine();
        resolution = s.length() == 0 ? Resolution.R16_9_1280_720 : resolutions[Integer.parseInt(s) - 1];

        getProcessList(affDir);
    }

    /**
     * 查找所有符合条件的谱面，并加入处理列表.
     *
     * @param file 目标文件夹
     */
    private void getProcessList(File file) {
        if (file.isDirectory()) {
            // 去掉Arcade自动保存的谱面、教程、愚人节铺子
            // todo: 优化为有 camera 变化时不处理
            if (file.getName().equals("Autosave")
                    || file.getName().equals("Backup")
                    || file.getName().equals("tutorial")
                    || file.getName().equals("ignotusafterburn")
                    || file.getName().equals("ignotusafterburn2")
                    || file.getName().equals("redandblueandgreen")
                    || file.getName().equals("singularityvvvip")
                    || file.getName().equals("overdead")) {
                return;
            }
            for (File f : Objects.requireNonNull(file.listFiles())) {
                getProcessList(f);
            }
            return;
        }
        String fileName = file.getName();
        if (!fileName.matches("[0-3].aff")) {
            return;
        }
        int difficulty = Integer.parseInt(fileName.substring(0, 1));
        if (difficulty < minDifficulty || difficulty > maxDifficulty) {
            return;
        }
        String parentDirName = file.getParentFile().getName();
        int note = new Aff(file).getNoteCount();
        String sid = parentDirName.toLowerCase(Locale.ROOT);
        if (sid.startsWith("dl_")) {
            sid = sid.substring(3);
        }
        String songName = getTitleLocalizedEN(sid);
        if (songName == null) {
            System.out.println("未找到 " + sid + " 对应的歌曲名！");
            System.out.println("回车表示使用 " + sid + " 作为歌曲名并继续，其他表示不处理该谱面");
            if (!"".equals(Main.sc.nextLine())) {
                return;
            }
        } else {
            // 雷电模拟器脚本按照先大写再小写排序，很不方便，这里全部改成小写
            // Windows 文件名不能有 \/:*?"<>| 这些字符，将其全部替换为空格
            songName = songName.toLowerCase(Locale.ROOT)
                    .replaceAll(":", "：")
                    .replaceAll("\\?", "？")
                    .replaceAll("[\\\\/:*?\"<>|]", "");
        }
        int miss = missAndMinPure.getMissNum(note);
        int minPure;
        AffProcess affProcess = null;
        for (AffProcess a : processList) {
            if (a.getAffFile().getPath().equals(file.getPath())
                    && a.getResolution() == resolution) {
                affProcess = a;
                break;
            }
        }
        if (affProcess == null) {
            affProcess = new AffProcess(file, songName, SettingsAndUtils.DIFFICULTY_STR[difficulty], resolution);
            processList.add(affProcess);
        }
        File targetDir0 = targetDir == null ? affProcess.getAffFile().getParentFile() : targetDir;
        if (runState == SettingsAndUtils.RunState.SONG_START_BEGIN || runState == SettingsAndUtils.RunState.BOTH) {
            minPure = missAndMinPure.getMinPureNum(note);
            if (mirror == SettingsAndUtils.Mirror.ORIGIN || mirror == SettingsAndUtils.Mirror.BOTH) {
                affProcess.addBaseProcess(targetDir0, miss, minPure, true, false);
            }
            if (mirror == SettingsAndUtils.Mirror.MIRROR || mirror == SettingsAndUtils.Mirror.BOTH) {
                affProcess.addBaseProcess(targetDir0, miss, minPure, true, true);
            }
        }
        if (runState == SettingsAndUtils.RunState.FIRST_NOTE_BEGIN || runState == SettingsAndUtils.RunState.BOTH) {
            minPure = 0;
            if (mirror == SettingsAndUtils.Mirror.ORIGIN || mirror == SettingsAndUtils.Mirror.BOTH) {
                affProcess.addBaseProcess(targetDir0, miss, minPure, false, false);
            }
            if (mirror == SettingsAndUtils.Mirror.MIRROR || mirror == SettingsAndUtils.Mirror.BOTH) {
                affProcess.addBaseProcess(targetDir0, miss, minPure, false, true);
            }
        }
    }

    private static final Pattern PATTERN = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+c");

    private void autoZip() {
        String version = null;
        File apk = SettingsAndUtils.getApk();
        if (apk != null) {
            String apkName = apk.getName();
            Matcher matcher = PATTERN.matcher(apkName);
            if (matcher.find()) {
                version = matcher.group(0);
            }
        }
        if (version == null) {
            version = new SimpleDateFormat("yyyyMMdd").format(new Date());
        }
        File zipDir = new File(SettingsAndUtils.VMS_DIR, "脚本打包");
        zipDir.mkdirs();
        for (File dir : zipDirList) {
            File targetZip = new File(zipDir, dir.getName() + "_" + version + ".zip");
            System.out.println("开始打包：" + dir + " -> " + targetZip);
            zip(dir, targetZip);
            System.out.println("打包完毕：" + dir + " -> " + targetZip);
        }
    }

    /**
     * @param srcFileOrDir 要压缩的文件
     * @param zipFile      压缩文件存放地方
     */
    private static void zip(File srcFileOrDir, File zipFile) {
        try {
            srcFileOrDir = srcFileOrDir.getCanonicalFile();
            zipFile = zipFile.getCanonicalFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
        try (ZipOutputStream outputStream = new ZipOutputStream(new FileOutputStream(zipFile))) {
            zipFile(outputStream, srcFileOrDir, "");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * @param zos          ZipOutputStream对象
     * @param srcFileOrDir 要压缩的文件或文件夹
     * @param basePath     条目根目录
     */
    private static void zipFile(ZipOutputStream zos, File srcFileOrDir, String basePath) throws IOException {
        if (srcFileOrDir.isDirectory()) {
            basePath = basePath + (basePath.length() == 0 ? "" : "/") + srcFileOrDir.getName();
            //System.out.println("zip中的文件夹路径：" + basePath);
            for (File f : Objects.requireNonNull(srcFileOrDir.listFiles())) {
                zipFile(zos, f, basePath);
            }
        } else {
            basePath = (basePath.length() == 0 ? "" : basePath + "/") + srcFileOrDir.getName();
            //System.out.println("zip中的文件路径：" + basePath);
            zos.putNextEntry(new ZipEntry(basePath));
            try (FileInputStream input = new FileInputStream(srcFileOrDir)) {
                int readLen;
                byte[] buffer = new byte[1024 * 8];
                while ((readLen = input.read(buffer, 0, buffer.length)) != -1) {
                    zos.write(buffer, 0, readLen);
                }
            }
        }
    }
}
