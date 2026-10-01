# Provenance and kernel changes

The kernel (`entry/`, `hook/`, `hook/targets/`, most of `core/`, `:api` hooks and host, `:compat-api100*`, the
Gradle setup and `tools/`) comes from our earlier module `shadowcord-xposed`. Its host-specific areas were not
carried over; Shadowzap's host areas (`whatsapp/`, `settings/`, the media features) are new.

Changes made while forking:

- `HostGate` targets `com.whatsapp` with `com.whatsapp.AppShell` as marker class.
- `Paths` only knows the module's own `files/shadowzap/` and `cache/shadowzap/` directories.
- The hook target cache key no longer reads a host `BuildConfig` (WhatsApp has none): `x-<module version code>-<APK
  stamp hash>`.
- `HostScope` and `HostEnvironment` lost the members that only served the previous host; versions moved to
  `api.version`.
- The JSON feature switch reader became `settings/Switches` with a writer (`SwitchStore.set`) for the in-app page.

## Kernel change requests

1. **String-constant discovery.** WhatsApp's media pipeline lives in the flat obfuscated `X` package, where classes
   have no stable name, package or superclass. The durable fingerprint of such a class is a string literal one of
   its methods uses (a log tag, a preference key, an error message). Add a discovery primitive that matches classes
   or methods by the string constants their bytecode references (and optionally by the methods they call), behind
   the existing `TargetSpec` / `HostTargets.resolve` / cache machinery, so cached results keep working unchanged.
