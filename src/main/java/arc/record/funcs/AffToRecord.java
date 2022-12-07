package arc.record.funcs;

import arc.record.SettingsAndUtils;
import arc.record.aff.Aff;
import arc.record.record.func.RecordThreadPool;
import arc.record.record.model.Mirror;
import arc.record.record.model.MissAndMinPure;
import arc.record.record.model.Request;
import arc.record.record.model.Resolution;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static arc.record.Main.sc;
import static arc.record.SettingsAndUtils.AFF_DIR;
import static arc.record.SettingsAndUtils.VMS_DIR;

/**
 * 通过用户输入，将谱面文件转为脚本.
 * <p>
 * 生成脚本流程如下：
 * <ul>
 *     <li>获取用户输入，将符合要求的谱面与处理请求通过 {@link #addRequest} 添加至 {@link #processMap}</li>
 *     <li>调用 {@link RecordThreadPool}，利用多线程处理</li>
 * </ul>
 *
 * @author MengLeiFudge
 */
public class AffToRecord {
    private File affDir;
    private int minDifficulty;
    private int maxDifficulty;
    private MissAndMinPure missAndMinPure;
    /**
     * 脚本文件生成目录.
     * <p>
     * 如果为 null，表示生成在 aff 文件相同目录。
     */
    private File targetDir;
    private Mirror mirror;
    private Resolution resolution;
    /**
     * 要压缩的文件夹.
     * <p>
     * 将需要压缩的文件夹添加至该 list，并调用 {@link #autoZip}，每个文件夹都会生成一个压缩文件。
     */
    private final List<File> zipDirList = new ArrayList<>();

    public void process() {
        System.out.println("使用一键生成脚本（谱面目录使用 " + AFF_DIR + "）？");
        System.out.println("注：包含ftr+byd 0L2%、1L6%、991w8%、982w10% 原版+镜像，以及全难度理论值原版");
        System.out.println("回车表示一键生成脚本");
        System.out.println("输入其他内容表示自定义生成脚本");
        String s = sc.nextLine();
        if ("".equals(s)) {
            auto();
        } else if (".".equals(s)) {
            test();
        } else {
            diy();
        }
        if (processMap.isEmpty()) {
            System.out.println("查找完毕，未找到需要处理的谱面文件！");
            return;
        }
        System.out.println("查找完毕，共找到 " + processMap.size() + " 个谱面文件！");
        RecordThreadPool.process(processMap);
        if ("".equals(s)) {
            System.out.println("开始将脚本打包至 zip...");
            autoZip();
            System.out.println("已将所有脚本打包至 zip！");
        }
        System.out.println("回车继续...");
        sc.nextLine();
        System.out.println();
    }

    /**
     * 使用默认值生成通常需要的全部脚本.
     */
    private void auto() {
        affDir = AFF_DIR;
        minDifficulty = 2;
        maxDifficulty = 3;
        mirror = Mirror.BOTH;
        resolution = Resolution.R16_9_1280_720;

        missAndMinPure = new MissAndMinPure("0", "2%");
        targetDir = new File(VMS_DIR, "脚本/0L2%");
        try {
            targetDir = targetDir.getCanonicalFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
        zipDirList.add(targetDir);
        addRequests(affDir);

        missAndMinPure = new MissAndMinPure("1", "6%");
        targetDir = new File(VMS_DIR, "脚本/1L6%");
        try {
            targetDir = targetDir.getCanonicalFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
        zipDirList.add(targetDir);
        addRequests(affDir);

        missAndMinPure = new MissAndMinPure("991w", "8%");
        targetDir = new File(VMS_DIR, "脚本/991w8%");
        try {
            targetDir = targetDir.getCanonicalFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
        zipDirList.add(targetDir);
        addRequests(affDir);

        missAndMinPure = new MissAndMinPure("982w", "10%");
        targetDir = new File(VMS_DIR, "脚本/982w10%");
        try {
            targetDir = targetDir.getCanonicalFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
        zipDirList.add(targetDir);
        addRequests(affDir);

        missAndMinPure = new MissAndMinPure("0", "0");
        targetDir = new File(VMS_DIR, "脚本/全难度理论值");
        try {
            targetDir = targetDir.getCanonicalFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
        minDifficulty = 0;
        mirror = Mirror.ORIGIN;
        addRequests(affDir);

        missAndMinPure = new MissAndMinPure("0", "0");
        targetDir = new File(VMS_DIR, "脚本/低难度理论值");
        try {
            targetDir = targetDir.getCanonicalFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
        maxDifficulty = 1;
        zipDirList.add(targetDir);
        addRequests(affDir);
    }

    private void test() {
        affDir = AFF_DIR;
        minDifficulty = 3;
        maxDifficulty = 3;
        mirror = Mirror.ORIGIN;
        resolution = Resolution.R16_9_1280_720;
        missAndMinPure = new MissAndMinPure("0", "0");
        targetDir = VMS_DIR;
        try {
            targetDir = targetDir.getCanonicalFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
        addRequests(affDir);
    }

    /**
     * 使用自定义设置生成脚本.
     */
    private void diy() {
        System.out.println("输入要转换的谱面文件夹路径");
        File defFile = AFF_DIR;
        System.out.println("回车表示 " + defFile);
        String s = sc.nextLine();
        affDir = s.length() == 0 ? defFile : new File(s);

        System.out.println("选择最低难度：");
        System.out.println("0 表示 pst，1 表示 prs，2 表示 ftr，3 表示 byd");
        System.out.println("回车表示 2，即 ftr");
        s = sc.nextLine();
        minDifficulty = s.length() == 0 ? 2 : Integer.parseInt(s);
        System.out.println("选择最高难度：");
        System.out.println("0 表示 pst，1 表示 prs，2 表示 ftr，3 表示 byd");
        System.out.println("回车表示 3，即 byd");
        s = sc.nextLine();
        maxDifficulty = s.length() == 0 ? 3 : Integer.parseInt(s);

        System.out.println("输入单点miss数（如3）或目标分数上限（以W/w结尾，如990w）：");
        System.out.println("回车表示 991w");
        s = sc.nextLine();
        String missStr = s.length() == 0 ? "991w" : s;
        System.out.println("输入小p数（如50）或小p比例（以%结尾，如5.6%）");
        System.out.println("回车表示 8%");
        s = sc.nextLine();
        String minPureStr = s.length() == 0 ? "8%" : s;
        missAndMinPure = new MissAndMinPure(missStr, minPureStr);

        System.out.println("选择目标文件夹路径：");
        defFile = new File(VMS_DIR, "operationRecords");
        System.out.println("1.仅使用新路径，在 " + defFile + " 集中生成脚本");
        System.out.println("2.仅使用原始路径，在每个谱面所对应文件夹下分别生成脚本");
        System.out.println("还可以直接输入路径，此时仅使用输入的新路径");
        System.out.println("回车表示 1");
        s = sc.nextLine();
        switch (s) {
            case "1", "" -> targetDir = defFile;
            case "2" -> targetDir = null;
            default -> targetDir = new File(s);
        }
        if (targetDir != null) {
            try {
                targetDir = targetDir.getCanonicalFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        System.out.println("选择镜像情况：");
        System.out.println("1.正常  2.镜像  3.同时生成以上两种脚本");
        System.out.println("回车表示 3");
        s = sc.nextLine();
        switch (s) {
            case "1" -> mirror = Mirror.ORIGIN;
            case "2" -> mirror = Mirror.MIRROR;
            case "3", "" -> mirror = Mirror.BOTH;
            default -> throw new IllegalArgumentException("输入有误");
        }

        System.out.println("选择分辨率：");
        Resolution[] resolutions = Resolution.values();
        int i = 1;
        for (Resolution r : resolutions) {
            System.out.println((i++) + "." + r.getDescribe());
        }
        System.out.println("回车表示 " + Resolution.R16_9_1280_720.getDescribe());
        s = sc.nextLine();
        resolution = s.length() == 0 ? Resolution.R16_9_1280_720 : resolutions[Integer.parseInt(s) - 1];

        addRequests(affDir);
    }

    /**
     * 存储脚本生成信息的 map.
     */
    private final Map<File, Map<Integer, List<Request>>> processMap = new HashMap<>();

    private static final Pattern P_AFF = Pattern.compile("[0-3]\\.aff");

    /**
     * 查找所有符合条件的谱面，并加入处理列表.
     *
     * @param file aff 文件或包含 aff 文件的文件夹
     */
    private void addRequests(File file) {
        if (file.isDirectory()) {
            // 忽略Arcade自动保存的谱面、教程、愚人节谱面
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
            File[] listFiles = file.listFiles();
            if (listFiles != null) {
                for (File f : listFiles) {
                    addRequests(f);
                }
            }
            return;
        }
        String fileName = file.getName();
        if (!P_AFF.matcher(fileName).matches()) {
            return;
        }
        int difficulty = Integer.parseInt(fileName.substring(0, 1));
        if (difficulty < minDifficulty || difficulty > maxDifficulty) {
            return;
        }
        Aff aff = new Aff(file);
        int note = aff.getNoteCount();
        int miss = missAndMinPure.getMissNum(note);
        int noShinyPure = missAndMinPure.getMinPureNum(note);
        int key = miss * (note + 1) + noShinyPure;
        File targetDir = this.targetDir == null ? file.getParentFile() : this.targetDir;
        if (mirror == Mirror.ORIGIN || mirror == Mirror.BOTH) {
            addRequest(file, key, new Request(targetDir, false, resolution));
        }
        if (mirror == Mirror.MIRROR || mirror == Mirror.BOTH) {
            addRequest(file, key, new Request(targetDir, true, resolution));
        }
    }

    /**
     * 添加一个具体的脚本需求.
     *
     * @param affFile 谱面文件
     * @param key     miss * (note + 1) + 小p
     * @param request 脚本需求
     */
    private void addRequest(File affFile, int key, Request request) {
        Map<Integer, List<Request>> map;
        if (processMap.containsKey(affFile)) {
            map = processMap.get(affFile);
        } else {
            map = new HashMap<>();
            processMap.put(affFile, map);
        }
        List<Request> list;
        if (map.containsKey(key)) {
            list = map.get(key);
        } else {
            list = new ArrayList<>();
            map.put(key, list);
        }
        if (!list.contains(request)) {
            list.add(request);
        }
    }

    private static final Pattern P_APK = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+c");

    private void autoZip() {
        String version = null;
        File apk = SettingsAndUtils.getApk();
        if (apk != null) {
            String apkName = apk.getName();
            Matcher matcher = P_APK.matcher(apkName);
            if (matcher.find()) {
                version = matcher.group(0);
            }
        }
        if (version == null) {
            version = new SimpleDateFormat("yyyyMMdd").format(new Date());
        }
        File zipDir = new File(VMS_DIR, "脚本打包");
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
