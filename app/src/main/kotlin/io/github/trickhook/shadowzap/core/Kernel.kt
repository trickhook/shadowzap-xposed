package io.github.trickhook.shadowzap.core

import io.github.trickhook.shadowzap.BuildConfig
import io.github.trickhook.shadowzap.api.host.HostEnvironment
import io.github.trickhook.shadowzap.api.host.Logger
import io.github.trickhook.shadowzap.core.seams.BasicRecoveryPresenter
import io.github.trickhook.shadowzap.core.seams.RecoveryPresenter
import io.github.trickhook.shadowzap.hook.HookBackend
import io.github.trickhook.shadowzap.hook.HookDispatcher
import io.github.trickhook.shadowzap.hook.targets.HostTargets

/**
 * The module's shared services, created once per process by [Bootstrap]. Features receive it through
 * [FeatureContext.kernel]; hook callbacks that outlive a feature's install may read [Kernel.current].
 */
internal class Kernel(
    val env: Env,
    backend: HookBackend,
    val logs: LogHub,
) {
    val log: Logger = logs.logger("kernel")

    val hostInfo = HostInfo()

    val environment: HostEnvironment = EnvironmentView(env, hostInfo)

    val paths = Paths(env.dataDir)

    val hooks = HookDispatcher(backend, logs.logger("hooks"))

    /** Finds hook targets that survive host renames; see [HostTargets]. */
    val targets = HostTargets(
        loader = env.hostClassLoader,
        paths = paths,
        env = env,
        hostInfo = hostInfo,
        log = logs.logger("targets"),
        forceDiscoveryFile = if (BuildConfig.DEBUG) paths.forceDiscoveryFile else null,
    )

    val lifecycle = Lifecycle(logs.logger("lifecycle"))

    val scope = ModuleScope(logs.logger("coroutines"))

    // ---- Seams (see docs/DESIGN.md) ----

    val recovery = Slot<RecoveryPresenter>(BasicRecoveryPresenter(paths, logs.logger("recovery")))

    /** Filled in by [Bootstrap] once every feature ran. */
    @Volatile
    var bootReport: BootReport? = null
        internal set

    /** Creates the scope of one owner; dispose it when the owner stops. */
    fun newScopedHost(owner: String): ScopedHost = ScopedHost(this, owner, logs.logger(owner))

    companion object {
        @Volatile
        private var instance: Kernel? = null

        /** The booted kernel, or `null` before boot (or when boot failed early). */
        val current: Kernel? get() = instance

        internal fun publish(kernel: Kernel) {
            instance = kernel
        }
    }
}
