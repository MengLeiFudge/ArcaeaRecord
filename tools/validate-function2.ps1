param(
    [string[]]$Charts = @(),
    [switch]$AllCharts,
    [switch]$TheoryOnly,
    [Alias('Profile')][switch]$CaptureProfile,
    [ValidateRange(1, 16)][int]$Workers = 4,
    [ValidateRange(30, 7200)][int]$TimeoutSeconds = 3600,
    [string]$JavaDir = 'C:\Program Files\BellSoft\LibericaJDK-25\bin'
)
$ErrorActionPreference = 'Stop'
$Charts = @($Charts | ForEach-Object { $_.Split(';') } | Where-Object { $_ })
$project = Split-Path $PSScriptRoot -Parent
$run = Join-Path $project ('target\function2-validation\' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
[IO.Directory]::CreateDirectory($run) | Out-Null
$utf8 = New-Object Text.UTF8Encoding($false)

# 编译和运行都保留 PID、标准输出、错误输出及有限截止；超时只终止本脚本创建的进程。
function Invoke-BoundedJava([string]$Exe, [string]$ArgFile, [string]$Name, [int]$Seconds, [string]$InputText) {
    $prefix = if ($Name -eq 'compile') { 'compile-' } else { '' }
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = $Exe
    $info.Arguments = '@"' + $ArgFile + '"'
    if ($Name -eq 'compile') {
        $info.Arguments = '-J-Dfile.encoding=UTF-8 -J-Dsun.stdout.encoding=UTF-8 -J-Dsun.stderr.encoding=UTF-8 ' + $info.Arguments
    }
    $info.WorkingDirectory = $project
    $info.UseShellExecute = $false
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.CreateNoWindow = $true
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $info
    $outFile = [IO.FileStream]::new((Join-Path $run ($prefix + 'stdout.log')), 'Create', 'Write', 'ReadWrite', 1)
    $errFile = [IO.FileStream]::new((Join-Path $run ($prefix + 'stderr.log')), 'Create', 'Write', 'ReadWrite', 1)
    $started = Get-Date
    $launched = $false
    $code = 1
    try {
        $launched = $process.Start()
        $outTask = $process.StandardOutput.BaseStream.CopyToAsync($outFile)
        $errTask = $process.StandardError.BaseStream.CopyToAsync($errFile)
        if ($InputText) { $process.StandardInput.Write($InputText) }
        $process.StandardInput.Close()
        $metadata = @{ pid = $process.Id; started = $started.ToString('o'); timeoutSeconds = $Seconds;
            mode = $Name; charts = $Charts; java = $Exe }
        [IO.File]::WriteAllText((Join-Path $run ($prefix + 'run.json')), ($metadata | ConvertTo-Json -Depth 5), $utf8)
        Write-Host "RUN=$run MODE=$Name PID=$($process.Id)"
        if (!$process.WaitForExit($Seconds * 1000)) {
            $process.Kill()
            [void]$process.WaitForExit(10000)
            $code = 124
        } else {
            $code = $process.ExitCode
        }
        [void]$outTask.Wait(10000)
        [void]$errTask.Wait(10000)
        $metadata.exitCode = $code
        $metadata.elapsedSeconds = ((Get-Date) - $started).TotalSeconds
        [IO.File]::WriteAllText((Join-Path $run ($prefix + 'run.json')), ($metadata | ConvertTo-Json -Depth 5), $utf8)
    } finally {
        if ($launched -and !$process.HasExited) { $process.Kill(); [void]$process.WaitForExit(10000) }
        $outFile.Dispose()
        $errFile.Dispose()
        $process.Dispose()
    }
    Write-Host "EXIT=$code MODE=$Name RUN=$run"
    return $code
}

$repo = Join-Path $env:USERPROFILE '.m2\repository'
# 当前主源码及功能 2 直接使用的依赖，版本与 pom.xml 一致；不下载或升级依赖。
$jars = @(
    'org\projectlombok\lombok\1.18.46\lombok-1.18.46.jar',
    'com\alibaba\fastjson2\fastjson2\2.0.61.android8\fastjson2-2.0.61.android8.jar',
    'commons-io\commons-io\2.22.0\commons-io-2.22.0.jar',
    'org\apache\commons\commons-lang3\3.20.0\commons-lang3-3.20.0.jar'
) | ForEach-Object { Join-Path $repo $_ }
foreach ($path in $jars) {
    if (!(Test-Path $path)) { throw "Missing dependency: $path" }
}
$diagnostic = $AllCharts -or $TheoryOnly -or $Charts.Count -gt 0
$classes = if ($diagnostic) { Join-Path $run 'classes' } else { Join-Path $project 'target\classes' }
[IO.Directory]::CreateDirectory($classes) | Out-Null
$compileArgs = @('--release', '25', '-encoding', 'UTF-8', '-proc:full', '-classpath', ($jars -join ';'),
    '-processorpath', $jars[0], '-d', $classes)
$compileArgs += Get-ChildItem (Join-Path $project 'src\main\java') -Filter '*.java' -Recurse | ForEach-Object { $_.FullName }
$compileFile = Join-Path $run 'compile.args'
[IO.File]::WriteAllLines($compileFile, @($compileArgs | ForEach-Object { '"' + $_.Replace('\', '/') + '"' }), $utf8)
$code = Invoke-BoundedJava (Join-Path $JavaDir 'javac.exe') $compileFile 'compile' 90 ''
if ($code -ne 0) { throw "Compilation failed: $run\compile-stderr.log" }

$javaArgs = @('-Dfile.encoding=UTF-8', '-Dsun.stdout.encoding=UTF-8', '-Dsun.stderr.encoding=UTF-8',
    '-classpath', ($classes + ';' + ($jars -join ';')))
if ($CaptureProfile) {
    # 采样仅用于定位当前生成/校验热点；正常一键运行没有录制开销。
    $javaArgs += '-XX:FlightRecorderOptions=stackdepth=128'
    $javaArgs += '-XX:StartFlightRecording=settings=profile,dumponexit=true,filename=' + (Join-Path $run 'profile.jfr')
}
if ($diagnostic) {
    $mode = if ($TheoryOnly) { 'theory' } else { 'all' }
    $checker = Join-Path $run 'Function2Check.java'
    Copy-Item (Join-Path $PSScriptRoot 'Function2Check.java') $checker
    $javaArgs += @($checker, $run, $mode, "$Workers") + $Charts
} else {
    # 与 IDEA 内运行 Main、输入 2 后回车的一键流程一致。
    $javaArgs += 'arc.record.Main'
}
$argFile = Join-Path $run 'java.args'
[IO.File]::WriteAllLines($argFile, @($javaArgs | ForEach-Object { '"' + $_.Replace('\', '/') + '"' }), $utf8)
$mode = if ($diagnostic) { 'diagnostic' } else { 'Main-auto' }
$inputText = if ($diagnostic) { '' } else { "2`n`n`n0`n" }
$code = Invoke-BoundedJava (Join-Path $JavaDir 'java.exe') $argFile $mode $TimeoutSeconds $inputText
exit $code
