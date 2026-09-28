@echo off
REM ============================================================
REM  BIKA 一键编译脚本（Release，armeabi-v7a + arm64-v8a）
REM  用法：双击本文件，或在终端执行  build.bat
REM  可选参数：任意 Gradle 参数，如  build.bat clean
REM ============================================================
setlocal

set "JAVA_HOME=C:\Users\Administrator\AndroidBuild\jdk\jdk-21.0.12.1+1"
set "ANDROID_HOME=C:\Users\Administrator\AndroidBuild\sdk"
set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
set "PATH=%JAVA_HOME%\bin;%PATH%"

cd /d "%~dp0"

echo [1/1] 开始编译 Release APK ...
call gradlew.bat :app:assembleRelease -PminifyWithR8=false %*
if errorlevel 1 (
    echo.
    echo [失败] 编译出错，请查看上方日志。
    exit /b 1
)

echo.
echo [完成] 产物目录：%~dp0app\build\outputs\apk\release\
dir /b "%~dp0app\build\outputs\apk\release\*.apk"
endlocal
