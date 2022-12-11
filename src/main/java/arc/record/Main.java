package arc.record;

import arc.record.funcs.AffToRecord;
import arc.record.funcs.CreateUnlockRecords;
import arc.record.funcs.DeleteRecords;
import arc.record.funcs.GetAllFiles;
import arc.record.funcs.Test;

import java.util.Scanner;

/**
 * 这是一个Arcaea脚本生成程序，生成的脚本可在雷电模拟器上运行.
 * <p>
 * 目前不支持camera变化。
 * <p>
 * 功能如下：
 * <ul>
 *     <li>将apk中的谱面、图片等文件与下载的谱面、音乐等文件整合到指定文件夹</li>
 *     <li>谱面文件转化为脚本，可以设定分数上限、小p数、是否镜像等</li>
 *     <li>生成Tempestissimo、Testify的相关解锁脚本</li>
 *     <li>删除指定文件夹（包括子文件夹）内所有脚本</li>
 * </ul>
 *
 * @author MengLeiFudge
 */
public class Main {
    //todo: 对于beatCount<=2的蛇/长条，判定点的计算应该使用其中点（其实也不是很准，应该是小于等于1用中点，其余情况使用count*time的位置）
    //todo: 修改模拟器坐标计算逻辑，扩大4/6k参数，提高精度
    //todo: 增加4:3分辨率适配
    //todo: 添加结尾暂停功能，便于调试
    //todo: 略微左移返回按键的位置
    //todo: 添加note转回aff的功能，便于调试
    //todo: 添加slf4j+logback的支持

    public static final Scanner sc = new Scanner(System.in).useDelimiter("\n");

    public static void main(String[] args) {
        while (true) {
            System.out.println("※ 该项目仅供学习研究之用 ※");
            System.out.println("选择功能：");
            System.out.println("1.获取所有谱面、音乐、曲绘，以及搭档头像、全身图");
            System.out.println("2.谱面文件转换为脚本");
            System.out.println("3.生成 Tempestissimo/Testify 相关解锁脚本");
            System.out.println("4.删除指定文件夹（包括子文件夹）内所有脚本");
            System.out.println("0.结束");
            switch (sc.nextLine()) {
                case "1" -> new GetAllFiles().process();
                case "2" -> new AffToRecord().process();
                case "3" -> new CreateUnlockRecords().process();
                case "4" -> new DeleteRecords().process();
                case "0" -> {
                    System.out.println("喜欢本项目的话，请给萌泪点个star！");
                    return;
                }
                case "." -> new Test().process();
                default -> {
                    System.out.println("输入有误！");
                    System.out.println();
                    System.out.println();
                }
            }
        }
    }
}
