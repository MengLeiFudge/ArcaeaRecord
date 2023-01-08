package arc.record.funcs;

import static arc.record.Main.sc;

public class ModifySt3DB {
    public ModifySt3DB() {
    }

    public void process() {
        System.out.println("输入Arc账号ID（9位数字）或用户名");
        String nameOrId = sc.nextLine();
        System.out.println("开始查询账号信息...");
        //todo: xxx
        System.out.println("确认模拟器当前导出st3文件对应用户名（id）-12.00吗？");
        System.out.println("输入y或直接回车表示是，其他表示否");
        String userOk = sc.nextLine();
        if (!userOk.equals("") && !userOk.equals("y")) {
            return;
        }
        System.out.println("开始查询账号信息并修改st3文件...");
        //todo：xxx
        //需要显示变动内容，以及可能出现无变动的情况
        System.out.println("提取完毕，回车继续...");
        sc.nextLine();
        System.out.println();
    }

}
