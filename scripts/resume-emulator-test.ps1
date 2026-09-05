# 重启后恢复动态测试环境（由 Claude 会话调用或手动运行）
# 前提: WHPX(HypervisorPlatform) 已启用; 宿主机 VMware 已开嵌套虚拟化(E/VHV-1)
$ErrorActionPreference = "Stop"

# 1. 恢复项目路径软链接
if (-not (Test-Path "C:\FakeLocation\gradlew.bat")) {
    cmd /c 'mklink /D C:\FakeLocation "\\vmware-host\Shared Folders\AI开发\FakeLocation"'
}
subst W: /D 2>$null
subst W: "\\vmware-host\Shared Folders"

$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
$env:ANDROID_HOME = "C:\Android\Sdk"
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\emulator;$env:Path"

# 2. 验证虚拟化加速可用
& "$env:ANDROID_HOME\emulator\emulator.exe" -accel-check

# 3. 启动模拟器（后台、无窗口模式可改 -gpu swiftshader_indirect 带窗口）
Start-Process "$env:ANDROID_HOME\emulator\emulator.exe" -ArgumentList "-avd fakedev -no-audio -no-boot-anim -gpu swiftshader_indirect -read-only" -WindowStyle Minimized

# 4. 等待启动完成（最多 5 分钟）
adb wait-for-device
$deadline = (Get-Date).AddMinutes(5)
while ((Get-Date) -lt $deadline) {
    $booted = adb shell getprop sys.boot_completed 2>$null
    if ("$booted".Trim() -eq "1") { break }
    Start-Sleep 5
}
if ("$booted".Trim() -ne "1") { throw "emulator boot timeout" }
"Emulator READY"

# 5. 安装模块 APK
adb install -r "C:\FakeLocation\app\build\outputs\apk\debug\FakeLocation_v1.6.2.apk"
"MAGISK/LSPosed 部署见会话后续步骤（scripts/setup-lsposed-emulator.ps1）"
