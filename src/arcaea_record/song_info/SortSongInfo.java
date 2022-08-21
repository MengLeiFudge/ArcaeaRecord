package arcaea_record.song_info;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;

import static arcaea_record.Main.sc;

/**
 * @author MengLeiFudge
 */
public class SortSongInfo {
    public void process() {
        System.out.println("开始处理谱面信息文件...");
        File oldFile = new File("songInfo.csv");
        File newFile = new File("newSongInfo.csv");
        try {
            newFile.createNewFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
        ArrayList<String[]> list = new ArrayList<>();
        boolean error = false;
        try (BufferedReader br = new BufferedReader(new FileReader(oldFile));
             BufferedWriter bw = new BufferedWriter(new FileWriter(newFile))) {
            bw.write(br.readLine());
            bw.newLine();
            String s;
            while ((s = br.readLine()) != null) {
                if ("".equals(s)) {
                    continue;
                }
                list.add(s.split(","));
            }
            list.sort((o1, o2) -> {
                for (int i = 0; i < Math.min(o1[0].length(), o2[0].length()); i++) {
                    int j = o1[0].charAt(i) - o2[0].charAt(i);
                    if (j != 0) {
                        return j;
                    }
                }
                return o1[0].length() - o2[0].length();
            });
            s = "";
            for (String[] strings : list) {
                if (!s.equals(strings[0])) {
                    s = strings[0];
                    for (int i = 0; i < 5; i++) {
                        bw.write(strings.length > i ? strings[i] : "");
                        bw.write(",");
                    }
                    bw.write(strings.length > 5 ? strings[5] : "");
                    bw.newLine();
                } else {
                    System.out.println(s + " 出现两次，请检查 songInfo.csv！");
                    error = true;
                    break;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (error) {
            newFile.delete();
            return;
        }
        oldFile.delete();
        newFile.renameTo(oldFile);
        System.out.println("已将 " + list.size() + " 条信息排序完毕！");
        System.out.println("回车继续...");
        sc.nextLine();
        System.out.println();
    }
}
