@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul
set "ADB=adb"
where adb >nul 2>nul
if errorlevel 1 set "ADB=E:/Code/_tools/platform-tools/adb.exe"
set "PKG=com.duan.lynkcluster"
set "BAK=E:/Code/_logs/backup"
set "REMOTE="

echo === 1/5 重启 adb ===
"%ADB%" kill-server >nul 2>&1
"%ADB%" start-server >nul 2>&1
"%ADB%" devices

echo.
echo === 2/5 定位已安装的 APK ===
for /f "tokens=2 delims=:" %%p in ('"%ADB%" shell pm path %PKG% 2^>nul') do set "REMOTE=%%p"
if "!REMOTE!"=="" (
    echo [跳过] 车机上没有 %PKG%，无需卸载
    goto :end
)
echo    已安装于 !REMOTE!

echo.
echo === 3/5 先备份到 %BAK% ===
if not exist "%BAK%" mkdir "%BAK%"
"%ADB%" pull "!REMOTE!" "%BAK%\lynkcluster-backup.apk"

echo.
echo === 4/5 卸载 ===
"%ADB%" push "%~dp0unin_lynk.sh" /data/local/tmp/unin_lynk.sh >nul
"%ADB%" shell sh /data/local/tmp/unin_lynk.sh

echo.
echo === 5/5 结果 ===
"%ADB%" shell pm path %PKG% 2>&1
echo   上面没有 package: 开头 = 已卸载干净

echo.
echo ---- 想装回来：adb 装 %BAK%\lynkcluster-backup.apk ----
:end
endlocal
