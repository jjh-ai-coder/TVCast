#!/bin/bash
# 构建 TVCast 的 FFmpeg 软解扩展 AAR（media3 decoder_ffmpeg + FFmpeg n6.1.1, LGPL）。
# 依赖：Git Bash、MSYS2（make）、Android NDK r26+。
# 用法：./tools/build_ffmpeg_ext.sh [输出目录，默认 app/libs]
set -eu

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MEDIA_DIR="$REPO_ROOT/third_party/media-1.4.1"
JNI_DIR="$MEDIA_DIR/libraries/decoder_ffmpeg/src/main/jni"
NDK_PATH="${NDK_PATH:-H:/ProgramData/Android/SDK/ndk/26.1.10909125}"
HOST_PLATFORM="windows-x86_64"
ANDROID_API=21
# 只启用硬解覆盖不到的解码器（LGPL 内置实现，无 GPL/外部依赖）：
# hevc 老盒子最缺；ac3/eac3/dca/truehd 授权限制的音频；mpeg2video/mp2 TS 场景；
# vp8/vp9/opus/flac 兜底。h264/mp3/vorbis 等全硬解覆盖，不编入以控制体积。
DECODERS=(hevc ac3 eac3 dca truehd mpeg2video mp2 vp8 vp9 opus flac)

export PATH="/c/msys64/usr/bin:$PATH"

# media3 1.4.1 的 Gradle wrapper 是 8.4，不支持 JDK 21；找 JDK 17 运行
if [[ -d "/d/Program/jdk-17.0.12+7" ]]; then
  export JAVA_HOME="D:\\Program\\jdk-17.0.12+7"
  export PATH="/d/Program/jdk-17.0.12+7/bin:$PATH"
fi

if [[ ! -d "$JNI_DIR/ffmpeg" ]]; then
  echo "FFmpeg source missing at $JNI_DIR/ffmpeg" >&2
  exit 1
fi

bash "$JNI_DIR/build_ffmpeg.sh" \
  "$JNI_DIR/.." \
  "$NDK_PATH" \
  "$HOST_PLATFORM" \
  "$ANDROID_API" \
  "${DECODERS[@]}"

echo "== native build done, building AAR =="
cd "$MEDIA_DIR"
./gradlew --no-daemon :lib-decoder-ffmpeg:assembleRelease

OUT_DIR="${1:-$REPO_ROOT/app/libs}"
mkdir -p "$OUT_DIR"
cp libraries/decoder_ffmpeg/buildout/outputs/aar/lib-decoder-ffmpeg-release.aar \
   "$OUT_DIR/media3-decoder-ffmpeg-1.4.1-tvcast.aar"
echo "== AAR copied to $OUT_DIR =="
