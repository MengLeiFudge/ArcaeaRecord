package arc.record.funcs;

import arc.record.SettingsAndUtils;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

import static arc.record.Main.sc;

/**
 * @author MengLeiFudge
 */
public class DeleteRecords {
    private File destDir;

    public void process() {
        init();
        deleteRecords(destDir);
        System.out.println("已删除 " + destDir + " 内全部脚本！");
        System.out.println("回车继续...");
        sc.nextLine();
        System.out.println();
    }

    private void init() {
        System.out.println("输入要删除脚本的文件夹路径");
        File defFile = SettingsAndUtils.AFF_DIR;
        System.out.println("1表示 " + defFile);
        System.out.println("2表示 " + SettingsAndUtils.VMS_DIR);
        System.out.println("回车表示1，输入其他表示指定目录");
        String s = sc.nextLine();
        switch (s) {
            case "":
            case "1":
                destDir = defFile;
                break;
            case "2":
                destDir = SettingsAndUtils.VMS_DIR;
                break;
            default:
                destDir = new File(s);
        }
        try {
            destDir = destDir.getCanonicalFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void deleteRecords(File file) {
        if (file.isDirectory()) {
            for (File f : Objects.requireNonNull(file.listFiles())) {
                deleteRecords(f);
            }
            return;
        }
        if (file.getName().endsWith(".record")) {
            System.out.println((file.delete() ? "删除成功：" : "删除失败：") + file.getPath());
        }
    }
}
