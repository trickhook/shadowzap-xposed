package io.github.trickhook.shadowzap.core

import io.github.trickhook.shadowzap.api.host.Disposable
import io.github.trickhook.shadowzap.api.host.HostScope
import io.github.trickhook.shadowzap.core.seams.RecoveryPresenter
import io.github.trickhook.shadowzap.hook.targets.HostTargets

/**
 * One self-contained part of the module (host facts, the settings entry, a media feature...).
 *
 * Features are installed once, in the fixed order of [Features.ordered], on the thread that boots the module
 * (before the host's Application exists). [install] must be quick: register hooks and callbacks, and defer real
 * work to [FeatureContext.onContext] or [FeatureContext.coroutineScope].
 *
 * A feature that throws from [install] is recorded as failed in the [BootReport] and everything it registered
 * through its context (hooks, subscriptions, seam registrations) is released; the next feature still runs.
 */
internal interface Feature {
    /** Stable id used in logs, the boot report and as the owner tag `feature:<id>`. */
    val id: String

    /** Whether to install in this environment (for example debug-only features). */
    fun isEnabled(env: Env): Boolean = true

    fun install(ctx: FeatureContext)
}

/**
 * What a feature gets during [Feature.install]: the public [HostScope] (owned by the feature) plus kernel services
 * and the seams used to cooperate with other areas without importing their code.
 */
internal class FeatureContext(
    val kernel: Kernel,
    val featureId: String,
    private val host: ScopedHost,
    private val registerAfterBoot: (String, () -> Unit) -> Unit,
) : HostScope by host {
    val env: Env get() = kernel.env
    val paths: Paths get() = kernel.paths
    val hostInfo: HostInfo get() = kernel.hostInfo
    val moduleScope: ModuleScope get() = kernel.scope

    /** Hook target resolution ([io.github.trickhook.shadowzap.hook.targets.HostTargets.resolve]). */
    val targets: HostTargets get() = kernel.targets

    /** Ties [resource] to this feature: it is released if the feature fails to install. */
    fun <T : Disposable> track(resource: T): T = host.track(resource)

    /** Installs a richer recovery UI than the kernel's fallback. */
    fun provideRecovery(presenter: RecoveryPresenter): Disposable = track(kernel.recovery.install(presenter))

    /**
     * Runs [block] after every feature has been installed, in registration order, each isolated. Use it for work
     * that needs other features' registrations.
     */
    fun afterBoot(block: () -> Unit) = registerAfterBoot(featureId, block)
}

/** Outcome of one feature at boot. */
internal class FeatureOutcome(
    val id: String,
    val status: Status,
    val durationMillis: Long,
    val error: Throwable? = null,
) {
    enum class Status { INSTALLED, FAILED, SKIPPED }
}

/** What happened during boot, for logs and the recovery UI. */
internal class BootReport(
    val entry: EntryKind,
    val features: List<FeatureOutcome>,
    val afterBootFailures: List<Pair<String, Throwable>>,
    val totalMillis: Long,
) {
    val failed: List<FeatureOutcome> get() = features.filter { it.status == FeatureOutcome.Status.FAILED }

    fun summary(): String = buildString {
        append("Boot via ").append(entry).append(" in ").append(totalMillis).append(" ms: ")
        append(features.joinToString { "${it.id}=${it.status.name.lowercase()}(${it.durationMillis}ms)" })
        if (afterBootFailures.isNotEmpty()) {
            append("; after-boot failures: ").append(afterBootFailures.joinToString { it.first })
        }
    }
}

/** Installs features in order, each isolated, and produces the [BootReport]. */
internal class FeatureRunner(private val kernel: Kernel) {
    private val afterBootBlocks = ArrayList<Pair<String, () -> Unit>>()

    fun run(features: List<Feature>): BootReport {
        val started = System.nanoTime()
        val outcomes = features.map(::installOne)
        val afterBootFailures = runAfterBoot()
        val report = BootReport(kernel.env.entry, outcomes, afterBootFailures, elapsedMillis(started))
        kernel.bootReport = report
        return report
    }

    private fun installOne(feature: Feature): FeatureOutcome {
        val log = kernel.log
        val started = System.nanoTime()
        val enabled = try {
            feature.isEnabled(kernel.env)
        } catch (t: Throwable) {
            log.e("Feature '${feature.id}' could not decide whether it is enabled", t)
            return FeatureOutcome(feature.id, FeatureOutcome.Status.FAILED, elapsedMillis(started), t)
        }
        if (!enabled) return FeatureOutcome(feature.id, FeatureOutcome.Status.SKIPPED, 0)

        val host = kernel.newScopedHost("feature:${feature.id}")
        val ctx = FeatureContext(kernel, feature.id, host) { id, block -> afterBootBlocks += id to block }
        return try {
            feature.install(ctx)
            FeatureOutcome(feature.id, FeatureOutcome.Status.INSTALLED, elapsedMillis(started))
        } catch (t: Throwable) {
            log.e("Feature '${feature.id}' failed to install; releasing what it registered", t)
            host.dispose()
            afterBootBlocks.removeAll { it.first == feature.id }
            FeatureOutcome(feature.id, FeatureOutcome.Status.FAILED, elapsedMillis(started), t)
        }
    }

    private fun runAfterBoot(): List<Pair<String, Throwable>> {
        val failures = ArrayList<Pair<String, Throwable>>()
        for ((id, block) in afterBootBlocks) {
            try {
                block()
            } catch (t: Throwable) {
                kernel.log.e("After-boot step of feature '$id' failed", t)
                failures += id to t
            }
        }
        afterBootBlocks.clear()
        return failures
    }

    private fun elapsedMillis(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000
}
