#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -eq 0 ]]; then
  echo "usage: verify-native-apk.sh APK [APK ...]" >&2
  exit 2
fi
: "${ANDROID_HOME:?ANDROID_HOME must identify the Android SDK}"
ndk="$ANDROID_HOME/ndk/30.0.16248370/toolchains/llvm/prebuilt/linux-x86_64/bin"
nm="$ndk/llvm-nm"
readelf="$ndk/llvm-readelf"
zipalign="$(find "$ANDROID_HOME/build-tools" -mindepth 2 -maxdepth 2 -type f -name zipalign | sort -V | tail -n 1)"
[[ -x "$nm" && -x "$readelf" && -x "$zipalign" ]]
temp="$(mktemp -d)"
trap 'rm -f "$temp/library.so"; rmdir "$temp"' EXIT

abis=(arm64-v8a armeabi-v7a x86_64 x86)
expected="$(printf 'lib/%s/libscene_core_jni.so\n' "${abis[@]}" | LC_ALL=C sort)"
methods=(nativeProjectTargets nativeCreate nativeApply nativeClose nativeBeginVerification nativeCompleteVerification nativeGuidance)

for apk in "$@"; do
  [[ -f "$apk" ]] || { echo "APK missing: $apk" >&2; exit 1; }
  actual="$(unzip -Z1 "$apk" | grep -E '(^|/)libscene_core_jni\.so$' | LC_ALL=C sort)"
  [[ "$actual" == "$expected" ]] || { echo "JNI ABI entries differ: $apk" >&2; exit 1; }
  "$zipalign" -c -P 16 4 "$apk"
  for abi in "${abis[@]}"; do
    library="$temp/library.so"
    unzip -p "$apk" "lib/$abi/libscene_core_jni.so" > "$library"
    read -r -a header <<< "$(od -An -v -tu1 -N20 "$library" | tr '\n' ' ')"
    [[ "${#header[@]}" -eq 20 ]]
    [[ "${header[0]}" == 127 && "${header[1]}" == 69 && "${header[2]}" == 76 && "${header[3]}" == 70 ]]
    [[ "${header[5]}" == 1 && "${header[16]}" == 3 && "${header[17]}" == 0 && "${header[19]}" == 0 ]]
    case "$abi" in
      arm64-v8a) class=2; machine=183 ;;
      armeabi-v7a) class=1; machine=40 ;;
      x86_64) class=2; machine=62 ;;
      x86) class=1; machine=3 ;;
    esac
    [[ "${header[4]}" == "$class" && "${header[18]}" == "$machine" ]]
    symbols="$("$nm" --dynamic --defined-only "$library" | awk '{print $NF}')"
    for method in "${methods[@]}"; do
      grep -q -F -x "Java_com_modose_app_core_NativeSceneBindings_$method" <<< "$symbols"
    done
    alignments="$("$readelf" --program-headers --wide "$library" | awk '$1 == "LOAD" {print $NF}')"
    [[ -n "$alignments" ]]
    while IFS= read -r alignment; do
      (( alignment >= 16384 )) || { echo "ELF LOAD alignment too small: $abi" >&2; exit 1; }
    done <<< "$alignments"
    printf 'verified %s %s: ELF ABI, seven JNI exports, 16KB LOAD alignment\n' "$apk" "$abi"
  done
done
