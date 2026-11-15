/*
 * MIGRATION SHIM: Temporary compatibility layer for Compose 1.13.0-beta02 + Nucleus.
 * Upstream PR: https://github.com/NucleusFramework/Nucleus/pull/934
 *
 * TODO(migration): Once Nucleus publishes an official release containing PR #934,
 * safely delete this file.
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "KotlinRedundantDiagnosticSuppress")

package androidx.compose.ui.node

import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.LayoutInfo

/**
 * Accessor for package-private/internal LayoutNode and coordinator members that
 * the Tao native-window damage-tracking logic consults.
 */
object TaoLayerTreeAccess {
    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun children(node: LayoutInfo): List<LayoutInfo> {
        return (node as LayoutNode).children as List<LayoutInfo>
    }

    @JvmStatic
    fun outerCoordinator(node: LayoutInfo): LayoutCoordinates {
        return (node as LayoutNode).outerCoordinator
    }

    @JvmStatic
    fun innerCoordinator(node: LayoutInfo): LayoutCoordinates {
        return (node as LayoutNode).innerCoordinator
    }

    @JvmStatic
    fun wrapped(coordinator: LayoutCoordinates): LayoutCoordinates? {
        return (coordinator as NodeCoordinator).wrapped
    }

    @JvmStatic
    fun ownedLayer(coordinator: LayoutCoordinates): Any? {
        return (coordinator as NodeCoordinator).layer
    }

    @JvmStatic
    fun graphicsLayer(ownedLayer: Any?): GraphicsLayer? {
        if (ownedLayer is GraphicsLayerOwnerLayer) {
            return ownedLayer.graphicsLayer
        }
        return null
    }

    @JvmStatic
    fun contentVersion(ownedLayer: Any?): Int {
        return -1
    }

    @JvmStatic
    fun graphicsContext(root: LayoutInfo): GraphicsContext? {
        val owner = (root as LayoutNode).owner
        return owner?.graphicsContext
    }
}
