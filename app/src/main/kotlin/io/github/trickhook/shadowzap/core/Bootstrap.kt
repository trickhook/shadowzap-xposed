package io.github.trickhook.shadowzap.core

import io.github.trickhook.shadowzap.api.host.LogLevel
import io.github.trickhook.shadowzap.hook.HookBackend

/** Boots the loader once per process. Called by an entry point after it won the [io.github.trickhook.shadowzap.entry.BootGuard]. */
internal object Bootstrap {
    /**
     * Creates the [Kernel] and installs every feature in order. Never throws: a failure here is logged and the host
     * keeps running unmodified.
     *
     * @return the kernel, or `null` when boot failed before features could be installed.
     */
    fun start(
        env: Env,
        backend: HookBackend,
        features: () -> List<Feature> = Features::ordered,
        extraSinks: List<LogSink> = emptyList(),
    ): Kernel? {
        val logs = LogHub(minLevel = if (env.isDebugBuild) LogLevel.DEBUG else LogLevel.INFO)
        extraSinks.forEach(logs::addSink)
        val log = logs.logger("boot")
        return try {
            log.i(
                "${LoaderIdentity.NAME} ${env.loaderVersion} starting in ${env.processName} " +
                    "(entry ${env.entry}, ${env.frameworkDescription}, hooks via ${backend.name})",
            )
            val kernel = Kernel(env, backend, logs)
            Kernel.publish(kernel)
            val report = FeatureRunner(kernel).run(features())
            val targets = try {
                kernel.targets.summary()
            } catch (t: Throwable) {
                "targets: unavailable ($t)"
            }
            val line = "${report.summary()}; $targets"
            if (report.failed.isEmpty() && report.afterBootFailures.isEmpty()) {
                log.i(line)
            } else {
                log.w(line)
            }
            kernel
        } catch (t: Throwable) {
            log.e("Boot failed; the host continues without the loader", t)
            null
        }
    }
}
