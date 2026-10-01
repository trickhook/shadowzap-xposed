#!/usr/bin/env bash
# Checks that a built module APK carries everything the Xposed frameworks need:
#   - META-INF/xposed/{java_init.list,module.prop,scope.list} and assets/xposed_init, naming existing classes;
#   - the three entry classes (ModernEntry, Api100Entry + Api100BootImpl, LegacyEntry) with the constructors and
#     callbacks frameworks look up, surviving R8;
#   - no packaged framework API classes (frameworks refuse such modules);
#   - the Xposed scope is exactly com.whatsapp;
#   - the serializers of the hook target cache, surviving R8.
#
# Usage: tools/verify-apk.sh <apk> [dexdump]
# dexdump defaults to the newest one under $ANDROID_HOME/build-tools (or $ANDROID_SDK_ROOT).
set -euo pipefail

apk="${1:?usage: verify-apk.sh <apk> [dexdump]}"
dexdump="${2:-}"
if [ -z "$dexdump" ]; then
  sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  dexdump="$(ls -d "$sdk"/build-tools/*/ 2>/dev/null | sort -V | tail -1)dexdump"
fi

fail() {
  echo "::error::$*" >&2
  exit 1
}

[ -f "$apk" ] || fail "APK not found: $apk"
[ -x "$dexdump" ] || [ -x "$dexdump.exe" ] || fail "dexdump not found: $dexdump"

listing="$(unzip -Z1 "$apk")"
for entry in META-INF/xposed/java_init.list META-INF/xposed/module.prop META-INF/xposed/scope.list assets/xposed_init; do
  grep -qx "$entry" <<<"$listing" || fail "$entry is missing from $apk"
done

java_init="$(unzip -p "$apk" META-INF/xposed/java_init.list | tr -d '\r')"
xposed_init="$(unzip -p "$apk" assets/xposed_init | tr -d '\r')"
module_prop="$(unzip -p "$apk" META-INF/xposed/module.prop | tr -d '\r')"
[ "$java_init" = $'io.github.trickhook.shadowzap.compat.api100.Api100Entry\nio.github.trickhook.shadowzap.entry.ModernEntry' ] ||
  fail "unexpected java_init.list: $java_init"
[ "$xposed_init" = 'io.github.trickhook.shadowzap.entry.LegacyEntry' ] || fail "unexpected xposed_init: $xposed_init"
grep -qx 'minApiVersion=101' <<<"$module_prop" || fail "module.prop lacks minApiVersion=101"
grep -qx 'targetApiVersion=102' <<<"$module_prop" || fail "module.prop lacks targetApiVersion=102"

scope_list="$(unzip -p "$apk" META-INF/xposed/scope.list | tr -d '\r')"
[ "$scope_list" = 'com.whatsapp' ] || fail "unexpected scope.list: $scope_list"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
dump="$work/dex.txt"
: >"$dump"
for dex in $(grep -E '^classes[0-9]*\.dex$' <<<"$listing"); do
  unzip -p "$apk" "$dex" >"$work/$dex"
  "$dexdump" -d "$work/$dex" >>"$dump"
done

has_class() {
  grep -qF "Class descriptor  : '$1'" "$dump"
}

for class in \
  'Lio/github/trickhook/shadowzap/entry/ModernEntry;' \
  'Lio/github/trickhook/shadowzap/compat/api100/Api100Entry;' \
  'Lio/github/trickhook/shadowzap/compat/api100/Api100Boot;' \
  'Lio/github/trickhook/shadowzap/entry/Api100BootImpl;' \
  'Lio/github/trickhook/shadowzap/entry/LegacyEntry;'; do
  has_class "$class" || fail "entry class $class is missing from the dex files"
done

if grep -qE "Class descriptor  : 'L(io/github/libxposed/api|de/robv/android/xposed)/" "$dump"; then
  fail "framework API classes are packaged; frameworks refuse such modules"
fi

# The API 100 constructor must still chain to XposedModule(XposedInterface, ModuleLoadedParam), and the API 100
# callback and the reflective boot hand-off must survive.
grep -qF 'Lio/github/libxposed/api/XposedModule;.<init>:(Lio/github/libxposed/api/XposedInterface;Lio/github/libxposed/api/XposedModuleInterface$ModuleLoadedParam;)V' "$dump" ||
  fail "Api100Entry no longer calls the API 100 XposedModule constructor"
grep -qF 'io.github.trickhook.shadowzap.entry.Api100BootImpl' "$dump" ||
  fail "Api100Entry no longer names Api100BootImpl"

section() {
  awk -v c="Class descriptor  : '$1'" '
    index($0, c) { on = 1; print; next }
    on && /Class descriptor  :/ { exit }
    on { print }
  ' "$dump"
}
# Sections are captured before grepping: piping awk into `grep -q` lets grep exit early, awk then dies of SIGPIPE
# and pipefail would fail the check for a correct APK.
api100_entry="$(section 'Lio/github/trickhook/shadowzap/compat/api100/Api100Entry;')"
grep -qF "name          : 'onPackageLoaded'" <<<"$api100_entry" ||
  fail "Api100Entry.onPackageLoaded was removed or renamed"
modern_entry="$(section 'Lio/github/trickhook/shadowzap/entry/ModernEntry;')"
grep -qF "name          : 'onPackageReady'" <<<"$modern_entry" ||
  fail "ModernEntry.onPackageReady was removed or renamed"

# The per-build cache of hook targets (hook/targets/TargetCache) is JSON through kotlinx.serialization: R8 must keep
# the generated serializers of its document, or every start after a WhatsApp update would discover again.
for class in \
  'Lio/github/trickhook/shadowzap/hook/targets/CacheDocument$$serializer;' \
  'Lio/github/trickhook/shadowzap/hook/targets/CachedTarget$$serializer;' \
  'Lio/github/trickhook/shadowzap/hook/targets/CachedValue$$serializer;'; do
  has_class "$class" || fail "hook target cache serializer $class is missing from the dex files"
done

echo "APK OK: $apk"
