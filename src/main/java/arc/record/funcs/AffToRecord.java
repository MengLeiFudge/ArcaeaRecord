package arc.record.funcs;

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

import arc.record.aff.Aff;
import arc.record.record.func.RecordThreadPool;
import arc.record.record.model.Mirror;
import arc.record.record.model.MissAndMinPure;
import arc.record.record.model.Request;
import arc.record.record.model.Resolution;

import static arc.record.Main.sc;
import static arc.record.Settings.AFF_DIR;
import static arc.record.Settings.DEBUG_MODE;
import static arc.record.Settings.VMS_DIR;
import static arc.record.Settings.getApk;

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
    private static final Pattern P_AFF = Pattern.compile("[0-4]\\.aff");
    private static final Pattern P_APK = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+c");
    private final boolean[] targetDifficulty = new boolean[5];
    /**
     * 要压缩的文件夹.
     * <p>
     * 将需要压缩的文件夹添加至该 list，并调用 {@link #autoZip}，每个文件夹都会生成一个压缩文件。
     */
    private final List<File> zipDirList = new ArrayList<>();
    /**
     * 存储脚本生成信息的 map.
     */
    private final Map<File, Map<Integer, List<Request>>> processMap = new HashMap<>();
    /**
     * 临时存放所有谱面文件与 Aff 实例的对应.
     */
    private final Map<File, Aff> affMap = new HashMap<>();
    private File affDir;
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
            basePath = basePath + (basePath.isEmpty() ? "" : "/") + srcFileOrDir.getName();
            //System.out.println("zip中的文件夹路径：" + basePath);
            for (File f : Objects.requireNonNull(srcFileOrDir.listFiles())) {
                zipFile(zos, f, basePath);
            }
        } else {
            basePath = (basePath.isEmpty() ? "" : basePath + "/") + srcFileOrDir.getName();
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

    public void process() {
        System.out.println("使用一键生成脚本（谱面目录使用 " + AFF_DIR + "）？");
        System.out.println("注：包含ftr、etr、byd 0L3%、1L3%、996w4%、991w4%、986w5%、981w5% 原版+镜像，以及全难度理论值原版");
        System.out.println("回车表示一键生成脚本");
        System.out.println(".表示将指定歌曲测试脚本直接放入operation records（需要先改好代码！）");
        System.out.println("输入其他内容表示自定义生成脚本");
        String s = sc.nextLine();
        System.out.println("查找中....");
        if ("".equals(s)) {
            auto();
        } else if (".".equals(s)) {
            DEBUG_MODE = true;
            test();
        } else {
            diy();
        }
        if (processMap.isEmpty()) {
            System.out.println("查找完毕，未找到需要处理的谱面文件！");
            return;
        }
        int targetNum = 0;
        for (var map : processMap.values()) {
            for (var list : map.values()) {
                targetNum += list.size();
            }
        }
        System.out.println("查找完毕，" + processMap.size() + " 个谱面文件共计生成 " + targetNum + " 个脚本请求");
        RecordThreadPool.process(affMap, processMap);
        if ("".equals(s)) {
            System.out.println("开始将脚本打包至 zip...");
            autoZip();
            System.out.println("已将所有脚本打包至 zip！");
        } else if (".".equals(s)) {
            DEBUG_MODE = false;
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
        targetDifficulty[2] = true;
        targetDifficulty[3] = true;
        targetDifficulty[4] = true;
        mirror = Mirror.BOTH;
        resolution = Resolution.R16_9_1280_720;

        missAndMinPure = new MissAndMinPure("0", "3%");
        targetDir = new File(VMS_DIR, "脚本/0L3%");
        try {
            targetDir = targetDir.getCanonicalFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
        zipDirList.add(targetDir);
        addRequests(affDir);

        missAndMinPure = new MissAndMinPure("1", "3%");
        targetDir = new File(VMS_DIR, "脚本/1L3%");
        try {
            targetDir = targetDir.getCanonicalFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
        zipDirList.add(targetDir);
        addRequests(affDir);

        missAndMinPure = new MissAndMinPure("996w", "4%");
        targetDir = new File(VMS_DIR, "脚本/996w4%");
        try {
            targetDir = targetDir.getCanonicalFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
        zipDirList.add(targetDir);
        addRequests(affDir);

        missAndMinPure = new MissAndMinPure("991w", "4%");
        targetDir = new File(VMS_DIR, "脚本/991w4%");
        try {
            targetDir = targetDir.getCanonicalFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
        zipDirList.add(targetDir);
        addRequests(affDir);

        missAndMinPure = new MissAndMinPure("986w", "5%");
        targetDir = new File(VMS_DIR, "脚本/986w5%");
        try {
            targetDir = targetDir.getCanonicalFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
        zipDirList.add(targetDir);
        addRequests(affDir);

        missAndMinPure = new MissAndMinPure("981w", "5%");
        targetDir = new File(VMS_DIR, "脚本/981w5%");
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
        targetDifficulty[0] = true;
        targetDifficulty[1] = true;
        mirror = Mirror.ORIGIN;
        addRequests(affDir);

        missAndMinPure = new MissAndMinPure("0", "0");
        targetDir = new File(VMS_DIR, "脚本/低难度理论值");
        try {
            targetDir = targetDir.getCanonicalFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
        targetDifficulty[2] = false;
        targetDifficulty[3] = false;
        targetDifficulty[4] = false;
        zipDirList.add(targetDir);
        addRequests(affDir);
    }

    /**
     * 测试用.
     * <p>
     * 需要修改affDir，并将DEBUG设为true，然后再启动程序并运行。
     */
    private void test() {
        affDir = new File(AFF_DIR, "dl_designant");
        targetDifficulty[3] = true;
        mirror = Mirror.ORIGIN;
        resolution = Resolution.R16_9_1280_720;
        missAndMinPure = new MissAndMinPure("0", "0");
        targetDir = new File(VMS_DIR, "operationRecords");
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
        System.out.println("输入要转换的谱面路径，或谱面所在文件夹");
        System.out.println("输入谱面路径仅处理该谱面；输入谱面所在文件夹将处理文件夹内（包括子文件夹内）所有谱面");
        File defFile = AFF_DIR;
        System.out.println("回车表示 " + defFile);
        String s = sc.nextLine();
        affDir = s.isEmpty() ? defFile : new File(s);

        if (affDir.isDirectory()) {
            System.out.println("输入五个数字，指示需要生成哪些难度的脚本：");
            System.out.println("0 表示禁用，1 表示启用，顺序为 pst、prs、ftr、byd、etr");
            System.out.println("例如输入 00010 表示仅处理 byd 谱面文件");
            System.out.println("回车表示 00111，即生成 ftr、byd、etr 谱面文件");
            s = sc.nextLine();
            if (s.isEmpty()) {
                targetDifficulty[2] = true;
                targetDifficulty[3] = true;
                targetDifficulty[4] = true;
            } else {
                targetDifficulty[0] = s.charAt(0) == '1';
                targetDifficulty[1] = s.charAt(1) == '1';
                targetDifficulty[2] = s.charAt(2) == '1';
                targetDifficulty[3] = s.charAt(3) == '1';
                targetDifficulty[4] = s.charAt(4) == '1';
            }
        }

        System.out.println("输入单点miss数（如3）或目标分数上限（以W/w结尾，如990w）：");
        System.out.println("回车表示 991w");
        s = sc.nextLine();
        String missStr = s.isEmpty() ? "991w" : s;
        System.out.println("输入小p数（如50）或小p比例（以%结尾，如5.6%）");
        System.out.println("回车表示 4%");
        s = sc.nextLine();
        String minPureStr = s.isEmpty() ? "4%" : s;
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
        resolution = s.isEmpty() ? Resolution.R16_9_1280_720 : resolutions[Integer.parseInt(s) - 1];

        addRequests(affDir);
    }

    /**
     * 查找所有符合条件的谱面，并加入处理列表.
     *
     * @param file aff 文件或包含 aff 文件的文件夹
     */
    private void addRequests(File file) {
        if (file.isDirectory()) {
            // 忽略Arcade自动保存的谱面、教程、愚人节谱面，以及下架歌曲
            if (file.getName().equals("Autosave")
                    || file.getName().equals("Backup")
                    || file.getName().equals("tutorial")
                    || file.getName().equals("ignotusafterburn")
                    || file.getName().equals("ignotusafterburn2")
                    || file.getName().equals("redandblueandgreen")
                    || file.getName().equals("singularityvvvip")
                    || file.getName().equals("overdead")
                    || file.getName().equals("mismal")
                    || file.getName().equals("dl_particlearts")) {
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
        if (!targetDifficulty[difficulty]) {
            return;
        }
        Aff aff;
        if (affMap.containsKey(file)) {
            aff = affMap.get(file);
        } else {
            aff = new Aff(file);
            affMap.put(file, aff);
        }
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

    private void autoZip() {
        String version = null;
        File apk = getApk();
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
}
