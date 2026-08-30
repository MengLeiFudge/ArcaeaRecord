package arc.record.funcs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.commons.io.FileUtils;

import static arc.record.Main.sc;
import static arc.record.Settings.AFF_DIR;
import static arc.record.Settings.CHAR_DIR;
import static arc.record.Settings.DL_DIR;
import static arc.record.Settings.SONG_LIST;
import static arc.record.Settings.STICKER_DIR;
import static arc.record.Settings.getApk;
import static arc.record.Utils.THREAD_NUM;

/**
 * @author MengLeiFudge
 */
public class GetAllFiles {
    private static final int APK_THREAD_NUM = Math.min(4, THREAD_NUM);
    private static final int DL_THREAD_NUM = Math.min(8, THREAD_NUM);
    private static final Set<String> LOOSE_EXTENSIONS = Set.of(".ogg", ".jpg", ".mp4", ".png", ".wav");
    private static final List<String> STICKER_LANGUAGE_SUFFIXES =
            List.of("_sc_tc", "_en", "_jp", "_kr", "_sc", "_tc");

    private File dlDir;
    private File affDir;
    private File apk;
    private File charDir;
    private File stickerDir;
    private ArrayList<File> fileList = new ArrayList<>();

    public GetAllFiles() {
    }

    public void process() {
        init();
        System.out.println("开始从 apk 提取文件...");
        if (apk == null || !apk.isFile()) {
            System.out.println("未找到 apk 文件，跳过该步骤！");
        } else {
            unZipApk();
            System.out.println("apk 文件提取完毕！");
        }
        System.out.println("复制并整理 dl 文件夹中的内容至谱面目录...");
        copyAndRenameFiles();
        System.out.println("dl 文件夹中内容复制完毕！");
        System.out.println("提取songlist到项目目录...");
        moveSongList();
        System.out.println("提取完毕，回车继续...");
        sc.nextLine();
        System.out.println();
    }

    private void init() {
        System.out.println("输入 dl 文件夹路径");
        File defFile = DL_DIR;
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
        defFile = getApk();
        System.out.println("回车表示 " + defFile);
        s = sc.nextLine();
        apk = s.equals("") ? defFile : new File(s);

        System.out.println("输入目标官谱文件夹路径");
        defFile = AFF_DIR;
        System.out.println("回车表示 " + defFile);
        s = sc.nextLine();
        affDir = s.equals("") ? defFile : new File(s);

        System.out.println("输入目标搭档图片文件夹路径");
        defFile = CHAR_DIR;
        System.out.println("回车表示 " + defFile);
        s = sc.nextLine();
        charDir = s.equals("") ? defFile : new File(s);

        System.out.println("输入目标Link表情包文件夹路径");
        defFile = STICKER_DIR;
        System.out.println("回车表示 " + defFile);
        s = sc.nextLine();
        stickerDir = s.equals("") ? defFile : new File(s);
    }

    /**
     * 将 dl 文件夹中的所有原始文件复制并按官谱目录格式重命名，源文件保持不变.
     * <p>
     * fileList 中含有 dl 文件夹内的所有文件（文件夹已经移除），按照名称排序，可以按歌曲 sid 分组，可能的组如下：
     * <ul>
     *     <li>aegleseeker aegleseeker_0 aegleseeker_1 aegleseeker_2</li>
     *     <li>antithese antithese_0 antithese_1 antithese_2 antithese_3</li>
     *     <li>hellohell hellohell_0 hellohell_1 hellohell_2 hellohell_4</li>
     *     <li>arcanaeden arcanaeden_0 arcanaeden_1 arcanaeden_2 arcanaeden_3 arcanaeden_video.mp4 arcanaeden_video_audio.ogg</li>
     *     <li>bookmaker_3</li>
     *     <li>dropdead dropdead_0 dropdead_1 dropdead_2 dropdead_3 dropdead_audio_3</li>
     *     <li>ignotus_3 ignotus_audio_3</li>
     * </ul>
     * APK 解包后若已存在无前缀的 sid 目录，本组文件写入该目录；否则写入 dl_sid 目录。
     * <p>
     * 文件名转换关系如下（x表示0-4的数字）：
     * <ul>
     *     <li>sid -> base.ogg</li>
     *     <li>sid_x -> x.aff</li>
     *     <li>sid_video.mp4 -> video.mp4</li>
     *     <li>sid_video_audio.ogg -> video_audio.ogg</li>
     *     <li>sid_audio_x -> x.ogg</li>
     * </ul>
     */
    private void copyAndRenameFiles() {
        // 检查是否有未下载完成的文件
        for (File f : fileList) {
            if (f.getName().endsWith(".pre")) {
                // 歌曲未下载完成或其他原因，testify使用下载全部也不行，必须在歌曲预览界面下载
                System.out.println("歌曲未下载完成或其他原因：" + f.getAbsolutePath());
                System.out.println("请下载该歌曲后再运行！");
                System.exit(0);
            }
        }

        Map<String, List<FileCopyOperation>> operationsBySong = new TreeMap<>();
        for (File source : fileList) {
            FileCopyOperation operation = createFileCopyOperation(source);
            if (operation == null) {
                return;
            }
            operationsBySong.computeIfAbsent(operation.groupName(), key -> new ArrayList<>()).add(operation);
        }

        List<Callable<GroupResult>> tasks = new ArrayList<>();
        for (var entry : operationsBySong.entrySet()) {
            String groupName = entry.getKey();
            List<FileCopyOperation> operations = List.copyOf(entry.getValue());
            tasks.add(() -> copyFileGroup(groupName, operations));
        }
        executeTasks("雷电 DL", DL_THREAD_NUM, tasks);
    }

    private FileCopyOperation createFileCopyOperation(File source) {
        String fileName = source.getName();
        int separator = fileName.indexOf('_');
        if (separator < 0) {
            String sid = fileName;
            File target = new File(getTargetSongDirectory(sid), "base.ogg");
            return new FileCopyOperation("歌曲/" + sid, source, target);
        }

        String[] info = fileName.split("_");
        String sid = info[0];
        File directory = getTargetSongDirectory(sid);
        File target;
        switch (info[1]) {
            // aegleseeker_0 -> 0.aff
            case "0", "1", "2", "3", "4" -> target = new File(directory, info[1] + ".aff");
            // dropdead_audio_3 -> 3.ogg
            case "audio" -> target = new File(directory, info[2] + ".ogg");
            // arcanaeden_video.mp4 -> video.mp4, arcanaeden_video_audio.ogg -> video_audio.ogg
            case "video.mp4", "video" -> target = new File(directory, fileName.substring(separator + 1));
            default -> {
                System.out.println("未知文件类型，请修改代码后再运行！");
                System.out.println(source.getAbsolutePath());
                System.exit(0);
                return null;
            }
        }
        return new FileCopyOperation("歌曲/" + sid, source, target);
    }

    private GroupResult copyFileGroup(String groupName, List<FileCopyOperation> operations) {
        GroupResultBuilder result = new GroupResultBuilder(groupName);
        for (FileCopyOperation operation : operations) {
            File source = operation.source();
            File target = operation.target();
            if (target.isFile() && source.length() == target.length()) {
                if (isLooseFile(target)) {
                    result.skipLoose();
                    continue;
                }
                try {
                    if (FileUtils.checksumCRC32(source) == FileUtils.checksumCRC32(target)) {
                        result.skipStrict();
                        continue;
                    }
                } catch (IOException e) {
                    result.addLog("CRC 校验失败，改为覆盖：" + source.getAbsolutePath()
                            + " -> " + target.getAbsolutePath() + "（" + e + "）");
                }
            }
            try {
                FileUtils.copyFile(source, target, false);
                result.written("复制成功：" + source.getAbsolutePath() + " -> " + target.getAbsolutePath());
            } catch (IOException e) {
                result.failed("复制失败：" + source.getAbsolutePath() + " -> " + target.getAbsolutePath(), e);
            }
        }
        return result.build();
    }

    private File getTargetSongDirectory(String sid) {
        File localDirectory = new File(affDir, sid);
        File targetDirectory = localDirectory.isDirectory()
                ? localDirectory
                : new File(affDir, "dl_" + sid);
        if (!targetDirectory.exists()) {
            targetDirectory.mkdirs();
        }
        return targetDirectory;
    }

    public void unZipApk() {
        // GBK解决中文文件夹乱码
        try (ZipFile zipFile = new ZipFile(apk, Charset.forName("GBK"))) {
            Map<String, List<ApkCopyOperation>> operationsByGroup = new TreeMap<>();
            for (Enumeration<? extends ZipEntry> entries = zipFile.entries(); entries.hasMoreElements(); ) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String pathInZip = entry.getName();
                File target = getApkTarget(pathInZip);
                if (target == null) {
                    continue;
                }
                String groupName = getApkGroupName(pathInZip);
                operationsByGroup.computeIfAbsent(groupName, key -> new ArrayList<>())
                        .add(new ApkCopyOperation(entry, target));
            }

            List<Callable<GroupResult>> tasks = new ArrayList<>();
            for (var entry : operationsByGroup.entrySet()) {
                String groupName = entry.getKey();
                List<ApkCopyOperation> operations = List.copyOf(entry.getValue());
                tasks.add(() -> unzipGroup(zipFile, groupName, operations));
            }
            executeTasks("APK 解包", APK_THREAD_NUM, tasks);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private File getApkTarget(String pathInZip) {
        if (pathInZip.startsWith("assets/char/")) {
            return new File(charDir, pathInZip.substring("assets/char/".length()));
        }
        if (pathInZip.startsWith("assets/img/multiplayer/stickers/")) {
            return new File(stickerDir, pathInZip.substring("assets/img/multiplayer/stickers/".length()));
        }
        if (pathInZip.startsWith("assets/songs/")) {
            return new File(affDir, pathInZip.substring("assets/songs/".length()));
        }
        return null;
    }

    private String getApkGroupName(String pathInZip) {
        if (pathInZip.startsWith("assets/songs/")) {
            String relativePath = pathInZip.substring("assets/songs/".length());
            String directory = firstPathSegment(relativePath);
            return switch (directory) {
                case "pack", "packlist", "songlist", "unlocks" -> "歌曲资源/" + directory;
                default -> "歌曲/" + (directory.startsWith("dl_") ? directory.substring(3) : directory);
            };
        }
        if (pathInZip.startsWith("assets/char/")) {
            String relativePath = pathInZip.substring("assets/char/".length());
            String fileName = lastPathSegment(relativePath);
            if (fileName.equals("characters.json")) {
                return "搭档/元数据";
            }
            String partnerId = removeExtension(fileName);
            int suffix = partnerId.indexOf('_');
            if (suffix >= 0) {
                partnerId = partnerId.substring(0, suffix);
            }
            return "搭档/" + partnerId;
        }
        String fileName = lastPathSegment(pathInZip);
        String stickerId = removeExtension(fileName);
        for (String suffix : STICKER_LANGUAGE_SUFFIXES) {
            if (stickerId.endsWith(suffix)) {
                stickerId = stickerId.substring(0, stickerId.length() - suffix.length());
                break;
            }
        }
        return "Link表情/" + stickerId;
    }

    private static String firstPathSegment(String path) {
        int separator = path.indexOf('/');
        return separator < 0 ? path : path.substring(0, separator);
    }

    private static String lastPathSegment(String path) {
        int separator = path.lastIndexOf('/');
        return separator < 0 ? path : path.substring(separator + 1);
    }

    private static String removeExtension(String fileName) {
        int separator = fileName.lastIndexOf('.');
        return separator < 0 ? fileName : fileName.substring(0, separator);
    }

    private GroupResult unzipGroup(ZipFile zipFile, String groupName, List<ApkCopyOperation> operations) {
        GroupResultBuilder result = new GroupResultBuilder(groupName);
        for (ApkCopyOperation operation : operations) {
            ZipEntry entry = operation.entry();
            File target = operation.target();
            if (target.isFile() && entry.getSize() == target.length()) {
                if (isLooseFile(target)) {
                    result.skipLoose();
                    continue;
                }
                long sourceCrc = entry.getCrc();
                if (sourceCrc >= 0) {
                    try {
                        if (sourceCrc == FileUtils.checksumCRC32(target)) {
                            result.skipStrict();
                            continue;
                        }
                    } catch (IOException e) {
                        result.addLog("CRC 校验失败，改为覆盖：" + entry.getName()
                                + " -> " + target.getAbsolutePath() + "（" + e + "）");
                    }
                }
            }

            File parent = target.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            try (InputStream input = zipFile.getInputStream(entry);
                 FileOutputStream output = new FileOutputStream(target)) {
                input.transferTo(output);
                result.written("解压成功：" + entry.getName() + " -> " + target.getAbsolutePath());
            } catch (IOException e) {
                result.failed("解压失败：" + entry.getName() + " -> " + target.getAbsolutePath(), e);
            }
        }
        return result.build();
    }

    public void moveSongList() {
        long startTime = System.nanoTime();
        File source = new File(affDir, "songlist");
        FileCopyOperation operation = new FileCopyOperation("歌曲资源/songlist", source, SONG_LIST);
        GroupResult result = copyFileGroup(operation.groupName(), List.of(operation));
        printStageResults("songlist 更新", List.of(result), startTime);
    }

    private static boolean isLooseFile(File target) {
        String fileName = target.getName().toLowerCase(Locale.ROOT);
        for (String extension : LOOSE_EXTENSIONS) {
            if (fileName.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    private void executeTasks(String stageName, int threadNum, List<Callable<GroupResult>> tasks) {
        long startTime = System.nanoTime();
        if (tasks.isEmpty()) {
            printStageResults(stageName, List.of(), startTime);
            return;
        }

        int actualThreadNum = Math.min(threadNum, tasks.size());
        ExecutorService executor = Executors.newFixedThreadPool(actualThreadNum);
        try {
            List<Future<GroupResult>> futures = new ArrayList<>();
            for (Callable<GroupResult> task : tasks) {
                futures.add(executor.submit(task));
            }
            executor.shutdown();

            List<GroupResult> results = new ArrayList<>();
            for (Future<GroupResult> future : futures) {
                results.add(future.get());
            }
            printStageResults(stageName, results, startTime);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(stageName + "被中断", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(stageName + "任务执行失败", e.getCause());
        } finally {
            if (!executor.isTerminated()) {
                executor.shutdownNow();
            }
        }
    }

    private void printStageResults(String stageName, List<GroupResult> results, long startTime) {
        int looseSkipped = 0;
        int strictSkipped = 0;
        int written = 0;
        int failed = 0;
        for (GroupResult result : results) {
            printGroupResult(result);
            looseSkipped += result.looseSkipped();
            strictSkipped += result.strictSkipped();
            written += result.written();
            failed += result.failed();
        }
        double elapsedSeconds = (System.nanoTime() - startTime) / 1_000_000_000.0;
        System.out.printf(Locale.ROOT,
                "%s汇总：宽松跳过 %d，严格跳过 %d，写入 %d，失败 %d，耗时 %.3f s%n",
                stageName, looseSkipped, strictSkipped, written, failed, elapsedSeconds);
    }

    private void printGroupResult(GroupResult result) {
        if (result.logs().isEmpty()) {
            return;
        }
        System.out.println("[" + result.groupName() + "]");
        for (String log : result.logs()) {
            System.out.println(log);
        }
        int skipped = result.looseSkipped() + result.strictSkipped();
        if (skipped > 0) {
            System.out.println("组内跳过：宽松 " + result.looseSkipped() + "，严格 " + result.strictSkipped());
        }
    }

    /**
     * 一个已解析目标位置的普通文件复制操作。
     */
    private record FileCopyOperation(String groupName, File source, File target) {
    }

    /**
     * 一个 APK 条目到目标文件的解包操作。
     */
    private record ApkCopyOperation(ZipEntry entry, File target) {
    }

    /**
     * 一个逻辑资源组的日志与处理计数。
     */
    private record GroupResult(
            String groupName,
            List<String> logs,
            int looseSkipped,
            int strictSkipped,
            int written,
            int failed) {
    }

    /**
     * 工作线程私有的资源组结果收集器，完成后转换为不可变结果。
     */
    private static final class GroupResultBuilder {
        private final String groupName;
        private final List<String> logs = new ArrayList<>();
        private int looseSkipped;
        private int strictSkipped;
        private int written;
        private int failed;

        private GroupResultBuilder(String groupName) {
            this.groupName = groupName;
        }

        private void skipLoose() {
            looseSkipped++;
        }

        private void skipStrict() {
            strictSkipped++;
        }

        private void addLog(String log) {
            logs.add(log);
        }

        private void written(String log) {
            logs.add(log);
            written++;
        }

        private void failed(String message, IOException exception) {
            StringWriter stackTrace = new StringWriter();
            exception.printStackTrace(new PrintWriter(stackTrace));
            logs.add(message + System.lineSeparator() + stackTrace.toString().stripTrailing());
            failed++;
        }

        private GroupResult build() {
            return new GroupResult(
                    groupName,
                    List.copyOf(logs),
                    looseSkipped,
                    strictSkipped,
                    written,
                    failed);
        }
    }
}
