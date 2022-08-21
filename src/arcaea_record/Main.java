package arcaea_record;

import arcaea_record.convert.AffToRecord;
import arcaea_record.delete.DeleteRecords;
import arcaea_record.get.GetAllFiles;
import arcaea_record.song_info.AddNewSongInfo;
import arcaea_record.song_info.SortSongInfo;
import arcaea_record.unlock.UnlockTempestissimo;

import java.util.Scanner;

/**
 * @author MengLeiFudge
 */
public class Main {
    public static final Scanner sc = new Scanner(System.in).useDelimiter("\n");

    public static void main(String[] args) {
        while (true) {
            System.out.println("※ 该项目仅供学习研究之用 ※");
            System.out.println("选择功能：");
            System.out.println("1.获取所有谱面、音乐、曲绘，以及搭档头像、全身图");
            System.out.println("2.对 songInfo.csv 增加未知曲目的名称并显示 wiki 网址");
            System.out.println("3.对 songInfo.csv 排序");
            System.out.println("4.谱面文件转换为脚本");
            System.out.println("5.删除指定文件夹（包括子文件夹）内所有脚本");
            System.out.println("6.生成 Tempestissimo 所有解锁脚本");
            System.out.println("0.结束");
            switch (sc.nextLine()) {
                case "1" -> new GetAllFiles().process();
                case "2" -> new AddNewSongInfo().process();
                case "3" -> new SortSongInfo().process();
                case "4" -> new AffToRecord().process();
                case "5" -> new DeleteRecords().process();
                case "6" -> new UnlockTempestissimo().process();
                case "0" -> {
                    System.out.println("喜欢本项目的话，请给萌泪点个star！");
                    return;
                }
                default -> {
                    System.out.println("输入有误！");
                    System.out.println();
                    System.out.println();
                }
            }
        }
    }
}
