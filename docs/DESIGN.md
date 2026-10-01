# Shadowzap design

Shadowzap is an Xposed module for WhatsApp (`com.whatsapp`). This document describes the kernel every feature runs
on; feature areas document themselves next to their code.

## 1. Goals

- Never crash WhatsApp: every hook callback, feature install and lifecycle subscriber is isolated and logged.
- Survive WhatsApp updates: WhatsApp is R8-obfuscated and most class names change with every build, so hooks are
  described as data and resolved at runtime (section 4), with the outcome cached per build.
- Run on libxposed API 101+ (Vector >= 2.1, JingMatrix LSPatch >= 1.0), libxposed API 100 (LSPosed 1.9.x,
  Vector <= 2.0) and classic Xposed frameworks, from one APK.
- Only ever start inside WhatsApp's main process.

## 2. Modules

```
api/                  internal API features build on: hooks (+ reflection helpers), host scope, versions
compat-api100/        Api100Entry (libxposed API 100) + Api100Boot, implemented in :app
compat-api100-stubs/  compile-only API 100 shapes of io.github.libxposed.api; never packaged
app/                  the module
  entry/              ModernEntry (API 101+), Api100BootImpl, LegacyEntry, EntryBoot, BootGuard
  hook/               HookDispatcher, modern and legacy backends, owned hooks
  hook/targets/       HostTargets (Kernel.targets), Targets DSL, TargetSpec, HostIndex, TargetCache
  core/               Bootstrap, Kernel, Env, HostGate, HostInfo, Feature framework, Features, Lifecycle,
                      Logging, Paths, AtomicFiles, ModuleScope, ScopedHost, Disposables, seams/Recovery
  whatsapp/           host facts (WhatsAppInfoFeature)
  settings/           feature switches (Switches, SwitchStore)
  extras/             ActivityEvents
```

### 2.1 Entries

`META-INF/xposed/java_init.list` names `Api100Entry` first and `ModernEntry` second (API 100 frameworks construct
both, in order; API 101+ frameworks only use `ModernEntry`). `assets/xposed_init` names `LegacyEntry`.
`module.prop` declares `minApiVersion=101`, `targetApiVersion=102`. Because the target is 102, the modern class
loader refuses `de.robv.android.xposed`: only `LegacyEntry`, `Api100BootImpl` and `LegacyHookBackend` touch
XposedBridge. On the Galaxy M52 (Vector) the live path is `ModernEntry` -> `ModernHookBackend`.

## 3. Boot

entry -> `HostGate` (package `com.whatsapp` or the `com.whatsapp.AppShell` marker, main process only, not
system_server) -> `Env` + hook backend -> `BootGuard` (once per process) -> `Bootstrap` -> `FeatureRunner` over
`Features.ordered()`.

Each feature implements `Feature` (`id`, `isEnabled(env)`, `install(ctx)`) and gets its own `ScopedHost`: every
hook, lifecycle subscription and coroutine it creates is released if its install throws, and the next feature still
runs. `afterBoot { }` blocks run once all features are installed. The outcome of every feature is in the
`BootReport` (logged, and kept in `Kernel.bootReport`).

Order (`core/Features.kt`): `LifecycleFeature` (captures the Application and the current activity), then
`WhatsAppInfoFeature` (version name and code from the package manager), then the user-facing features.

## 4. Hook targets

A hook never hard-codes a class name. It declares a `TargetSpec` with the `Targets` DSL (exact candidates, member
constraints, an optional structural `discover { }` block) and calls `ctx.targets.resolve(spec)`. Resolution, first
success wins: cached result for this build (re-verified) -> exact candidates -> discovery over the `HostIndex`
(every class name of the host APKs, parsed straight from the dex files) -> missing (logged; the feature skips that
hook). The cache key combines the module version code and a hash of the host and module APK stamps, so a WhatsApp
or module update starts fresh. Debug builds honour `files/shadowzap/debug/force-discovery` to simulate an update.

WhatsApp keeps semantic names for classes its manifest and layouts reference (`com.whatsapp.*` activities, views,
the `com.whatsapp.settings` package); everything else lives in the flat obfuscated `X` package. Discovery by package,
name pattern, superclass and member shape covers the first group; see MIGRATION.md for the second.

## 5. Paths

Everything the module stores lives below `files/shadowzap/` and `cache/shadowzap/` inside WhatsApp's data
directory (`core/Paths.kt`): the hook target caches, the debug force-discovery list, the feature switches
(`files/shadowzap/switches.json`) and a media staging directory. WhatsApp's own files are never touched.

## 6. Settings

`settings/Switches` reads the switch file leniently (bad values fall back to defaults) and `SwitchStore` keeps the
process' copy; the in-app settings page writes through `SwitchStore.set`, so features that read a switch at call
time follow a change immediately.
