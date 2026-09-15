# arcaeaRecord

由 萌泪、梦想天生、yyq2517 编写的Arcaea脚本生成程序。2026-05-14公开。

## 如何运行

<ul>
    <li>修改 {@link #ARC_DIR} 至合适的目录</li>
    <li>将最新的 arcaea 安装包（如 arcaea_4.1.0c.apk）放至 {@link #ARC_DIR} 内</li>
    <li>用雷电模拟器下载全部的歌曲，并将 dl 文件夹移动至 Pictures 内</li>
    <li>打开雷电模拟器脚本路径，将其复制到 {@link #VMS_DIR}</li>
    <li>运行程序！enjoy it！</li>
</ul>

## 功能 2 的运行与验收

统一基线是从项目根目录运行 `arc.record.Main`，选择 `2`，再输入空行使用一键生成。
使用 BellSoft Liberica JDK 25，以及 UTF-8 输入输出。IDEA 启动命令中的 `idea_rt.jar` 和端口仅属于该次 IDE 会话，命令行运行无需它们。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\validate-function2.ps1
```

该命令先编译当前主源码，再向 Main 输入 `2\n\n\n0\n`，包括生成结束后的回车与退出。
谱面和输出目录遵循 `Settings`：目前输入为 `D:\Games\Arcaea\官谱`，脚本输出在 `D:\leidian\LDPlayer9\vms\脚本`。
这是真实的一键生成流程，会写出脚本并执行原有清理与 ZIP 打包；转换或理论值回放失败时，先完成其余清单，再保存问题汇总并停止清理和打包。
当前 songlist 的基线为 1830 张谱面、11668 个请求：包含高难度六档分数的原版/镜像，以及全难度理论值原版。
其中 `dl_lasteternity/2.aff` 只有 timing，是空谱；`1L3%`、`996w4%`、`991w4%`、`986w5%`、`981w5%` 均要求非零 Miss，对应原版/镜像共 10 个请求按既有空谱逻辑不输出。因此本基线完整生成 11658 份 record；该空谱的理论值仍生成并校验。

每次运行在 `target/function2-validation/时间-标识/` 保留编译参数、运行参数、PID、退出码和标准输出/错误日志。
默认运行截止为 3600 秒，可用 `-TimeoutSeconds` 调整；超时仅终止该命令创建的 Java 进程。
需要定位耗时来源时加 `-Profile`，会在本次目录保存 `profile.jfr`；默认不开启性能采样。

定向迭代使用相同请求发现、生产转换与内置最终 JSON 校验，输出隔离在本次诊断目录中：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\validate-function2.ps1 -Charts dl_arcanaeden/2.aff
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\validate-function2.ps1 -AllCharts -TheoryOnly
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\validate-function2.ps1 -AllCharts
```

诊断会逐张保存结果，一张失败不会取消后续清单。定向通过不能替代最终完整一键运行。
内置最终 JSON 回放覆盖 0L0 理论值的 0/-20/+20 ms 场景；其他分数档执行正式转换和输出。
验收时保留全部原物件、判定窗口和整体 ±20 ms；不得通过扩大判定范围、降低偏移、恢复清色宽限或跳过需求取得 PASS。
编译成功、文件生成、最终时间校验通过和游戏内无 Miss 分别报告。
