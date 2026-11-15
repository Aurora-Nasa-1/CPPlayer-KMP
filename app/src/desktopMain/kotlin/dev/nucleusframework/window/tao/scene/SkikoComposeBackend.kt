/*
 * MIGRATION SHIM: Temporary compatibility layer for Compose 1.13.0-beta02 + Nucleus.
 * Upstream PR: https://github.com/NucleusFramework/Nucleus/pull/934
 *
 * TODO(migration): Once Nucleus publishes an official release containing PR #934,
 * safely delete this file.
 */

package dev.nucleusframework.window.tao.scene

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.registerSkikoComposeImplementation

/**
 * Ensures Compose 1.13 Skiko graphics/text backends are registered.
 */
@OptIn(InternalComposeUiApi::class)
internal fun ensureSkikoComposeBackend() {
    registerSkikoComposeImplementation()
}
