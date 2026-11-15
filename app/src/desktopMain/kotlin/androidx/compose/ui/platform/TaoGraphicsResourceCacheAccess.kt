/*
 * MIGRATION SHIM: Temporary compatibility layer for Compose 1.13.0-beta02 + Nucleus.
 * Upstream PR: https://github.com/NucleusFramework/Nucleus/pull/934
 *
 * TODO(migration): Once Nucleus publishes an official release containing PR #934,
 * safely delete this file.
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "KotlinRedundantDiagnosticSuppress")

package androidx.compose.ui.platform

import androidx.compose.runtime.ProvidableCompositionLocal

/**
 * Accessor for Compose's internal `LocalGraphicsResourceCache` (Compose 1.13+).
 */
internal object TaoGraphicsResourceCacheAccess {
    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun localGraphicsResourceCache(): ProvidableCompositionLocal<Any?> {
        return LocalGraphicsResourceCache as ProvidableCompositionLocal<Any?>
    }
}
