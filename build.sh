#!/usr/bin/env bash
# ============================================================
#  BIKA 一键编译脚本（Release，armeabi-v7a + arm64-v8a）
#  用法：./build.sh          # 编译
#        ./build.sh clean    # 追加任意 Gradle 参数
# ============================================================
set -euo pipefail

export JAVA_HOME="C:/Users/Administrator/AndroidBuild/jdk/jdk-21.0.12.1+1"
export ANDROID_HOME="C:/Users/Administrator/AndroidBuild/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

cd "$(dirname "$0")"

echo "[1/1] 开始编译 Release APK ..."
./gradlew :app:assembleRelease -PminifyWithR8=false "$@"

echo
echo "[完成] 产物目录：app/build/outputs/apk/release/"
ls -la app/build/outputs/apk/release/*.apk
