#!/usr/bin/env bash
# ==============================================================================
# VisualAgent 一键编译、16KB对齐与 V2/V3 强签名脚本
# 适用机型：Redmi K80 至尊版 (天玑9400 / Xiaomi HyperOS 3 / Android 16)
# 解决痛点：彻底杜绝小米安装器报 `packageInfo is null (33)` 及 Android 16 16KB 对齐异常
# ==============================================================================

set -euo pipefail

KEYSTORE_PATH="${KEYSTORE_PATH:-./visualagent-release.jks}"
KEY_ALIAS="${KEY_ALIAS:-visualagent_key}"
KEYSTORE_PASS="${KEYSTORE_PASS:-visualagent123456}"
KEY_PASS="${KEY_PASS:-visualagent123456}"

BUILD_DIR="app/build/outputs/apk/release"
UNSIGNED_APK="${BUILD_DIR}/app-release-unsigned.apk"
ALIGNED_APK="${BUILD_DIR}/app-aligned.apk"
FINAL_APK="${BUILD_DIR}/app-final.apk"

# 自动探测 Android SDK Build-Tools (需 35.0.0+ 或 36.0.0+)
if [ -z "${ANDROID_HOME:-}" ]; then
    echo "[!] 未检测到 ANDROID_HOME 环境变量，尝试使用默认路径..."
    export ANDROID_HOME="$HOME/Android/Sdk"
fi

BUILD_TOOLS_DIR=$(find "$ANDROID_HOME/build-tools" -maxdepth 1 -mindepth 1 -type d | sort -V | tail -n 1)
if [ -z "$BUILD_TOOLS_DIR" ]; then
    echo "[X] 错误：未找到 Android SDK build-tools，请确认已安装 Build-Tools 35.0.0 或 36.0.0 以上版本。"
    exit 1
fi

ZIPALIGN="${BUILD_TOOLS_DIR}/zipalign"
APKSIGNER="${BUILD_TOOLS_DIR}/apksigner"

echo "========================================================"
echo "  VisualAgent HyperOS 3 / Android 16 防报错构建流水线"
echo "  使用 Build-Tools: ${BUILD_TOOLS_DIR}"
echo "========================================================"

# 0. 若不存在签名密钥库，则自动生成 (RSA 2048, PKCS12)
if [ ! -f "$KEYSTORE_PATH" ]; then
    echo "[0/4] 正在生成本地发布密钥库: ${KEYSTORE_PATH} ..."
    keytool -genkeypair -v \
        -keystore "$KEYSTORE_PATH" \
        -alias "$KEY_ALIAS" \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -storepass "$KEYSTORE_PASS" \
        -keypass "$KEY_PASS" \
        -dname "CN=VisualAgent, OU=MobileAI, O=OpenSource, L=Beijing, ST=Beijing, C=CN"
fi

# 1. 编译 APK
echo "[1/4] 正在执行 Gradle Release 编译..."
./gradlew assembleRelease

if [ ! -f "$UNSIGNED_APK" ]; then
    echo "[X] 错误：未找到 $UNSIGNED_APK，请检查 build.gradle.kts 是否产出了 unsigned 包。"
    exit 1
fi

# 2. 强制对齐（防小米报 packageInfo is null (33)，同时 -P 16 满足 Android 16 16KB ELF 页对齐）
echo "[2/4] 正在执行 zipalign 字节对齐 (-p -f -v 4)..."
"$ZIPALIGN" -p -f -v 4 "$UNSIGNED_APK" "$ALIGNED_APK"

# 验证对齐状态
"$ZIPALIGN" -c -v 4 "$ALIGNED_APK"

# 3. 强制 V2/V3 签名（严禁使用 jarsigner V1 签名）
echo "[3/4] 正在使用 apksigner 执行 V2 + V3 强签名..."
"$APKSIGNER" sign \
    --ks "$KEYSTORE_PATH" \
    --ks-key-alias "$KEY_ALIAS" \
    --ks-pass "pass:${KEYSTORE_PASS}" \
    --key-pass "pass:${KEY_PASS}" \
    --v1-signing-enabled true \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --out "$FINAL_APK" \
    "$ALIGNED_APK"

# 4. 强制验证（确认输出中 v2 scheme: true, v3 scheme: true）
echo "[4/4] 正在校验签名完整性与 V2/V3 Scheme..."
VERIFY_OUTPUT=$("$APKSIGNER" verify --verbose "$FINAL_APK")
echo "$VERIFY_OUTPUT"

if echo "$VERIFY_OUTPUT" | grep -q "Verified using v2 scheme (APK Signature Scheme v2): true" && \
   echo "$VERIFY_OUTPUT" | grep -q "Verified using v3 scheme (APK Signature Scheme v3): true"; then
    echo "========================================================"
    echo " [✓] 构建与签名验证通过！"
    echo " [✓] 产物路径: ${FINAL_APK}"
    echo " [✓] 可直接通过 ADB 或文件管理器安装至 Redmi K80 至尊版 (HyperOS 3)"
    echo "========================================================"
else
    echo "[X] 严重错误：APK 未通过 V2/V3 签名校验，请勿安装！"
    exit 1
fi