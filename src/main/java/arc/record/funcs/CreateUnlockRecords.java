package arc.record.funcs;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

import arc.record.aff.Aff;

import static arc.record.Main.sc;
import static arc.record.Settings.AFF_DIR;
import static arc.record.Settings.VMS_DIR;
import static arc.record.Utils.DIFFICULTY_STR;

/**
 * @author MengLeiFudge
 */
public class CreateUnlockRecords {
    private final Aff[] affs = new Aff[4];
    private File recordDir;

    public CreateUnlockRecords() {
    }

    public void process() {
        if (!init()) {
            return;
        }
        System.out.println("生成理论脚本中....");
        createOriginRecord();
        System.out.println("已生成理论脚本，开始生成解锁脚本....");
        createUnlockRecord();
        System.out.println("已生成解锁脚本！");
        System.out.println("回车继续...");
        sc.nextLine();
        System.out.println();
    }

    private boolean init() {
        System.out.println("输入 Tempestissimo 谱面所在的【文件夹】路径");
        File defFile = new File(AFF_DIR, "dl_tempestissimo");
        System.out.println("回车表示 " + defFile);
        String s = sc.nextLine();
        File affDir = s.equals("") ? defFile : new File(s);
        for (int i = 0; i < 4; i++) {
            File affFile = new File(affDir, i + ".aff");
            if (!affFile.exists()) {
                System.out.println(affDir + "目录下缺少谱面文件" + i + ".aff！");
                return false;
            }
            affs[i] = new Aff(affFile);
        }
        System.out.println("输入要生成脚本的文件夹路径");
        defFile = new File(VMS_DIR, "tempestissimo解锁脚本");
        System.out.println("回车表示 " + defFile);
        s = sc.nextLine();
        recordDir = s.equals("") ? defFile : new File(s);
        return true;
    }

    public void createOriginRecord() {
        /*ArrayList<AffProcess> processList = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            AffProcess process = new AffProcess(affs[i], "Temp",
                    DIFFICULTY_STR[i], Resolution.R16_9_1280_720, new ArrayList<>());
            process.addBaseProcess(recordDir, 0, 0, true, false);
            processList.add(process);
        }
        RecordThreadPool.process(processList);*/
    }

    public void createUnlockRecord() {
        File pst = new File(recordDir, "Temp_" + DIFFICULTY_STR[0] + "_原版_0L0小.record");
        File prs = new File(recordDir, "Temp_" + DIFFICULTY_STR[1] + "_原版_0L0小.record");
        File ftr = new File(recordDir, "Temp_" + DIFFICULTY_STR[2] + "_原版_0L0小.record");
        File byd = new File(recordDir, "Temp_" + DIFFICULTY_STR[3] + "_原版_0L0小.record");
        if (!pst.exists() || !prs.exists() || !ftr.exists() || !byd.exists()) {
            System.out.println("目录下无对应脚本文件，需检查代码！");
            return;
        }
        File pstUnlock = new File(recordDir, "TempPST转PRS.record");
        File prsUnlock = new File(recordDir, "TempPRS转FTR.record");
        File ftrUnlock = new File(recordDir, "TempFTR转BYD.record");
        createUnlockRecord0(pst, prs, pstUnlock);
        createUnlockRecord0(prs, ftr, prsUnlock);
        createUnlockRecord0(ftr, byd, ftrUnlock);
        pst.delete();
        prs.delete();
        ftr.delete();
        byd.delete();
    }

    private void createUnlockRecord0(File oldFile1, File oldFile2, File newFile) {
        try (BufferedReader br1 = new BufferedReader(new FileReader(oldFile1));
             BufferedReader br2 = new BufferedReader(new FileReader(oldFile2));
             BufferedWriter bw = new BufferedWriter(new FileWriter(newFile))) {
            String s;
            while ((s = br1.readLine()) != null) {
                if (s.contains("timing")) {
                    int timing = Integer.parseInt(s.substring(s.indexOf(':') + 1, s.length() - 1));
                    if (timing > 94500) {
                        break;
                    }
                }
                bw.write(s);
                bw.newLine();
            }
            // 是否达到预定时间（区间为四押前，所有难度均无按键的一段）
            // 经过观察，得出94500是一个合适的值（这是有十秒预留的歌曲开始模式）
            boolean overTime = false;
            while ((s = br2.readLine()) != null) {
                if (!overTime) {
                    if (!s.contains("timing")
                            || Integer.parseInt(s.substring(s.indexOf(':') + 1, s.length() - 1)) < 94500) {
                        continue;
                    } else {
                        overTime = true;
                    }
                }
                bw.write(s);
                bw.newLine();
            }
            System.out.println("生成完毕：" + newFile.getCanonicalPath());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
