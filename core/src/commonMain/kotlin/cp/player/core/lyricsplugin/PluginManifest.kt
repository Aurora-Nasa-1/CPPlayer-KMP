/*
 * Based on the Lyrico Plugin API host contract (https://github.com/Replica0110/Lyrico) — Apache-2.0.
 * Host contract reference: app/src/main/java/com/ella/music/plugin/model/PluginManifest.kt
 * Changes: package renamed to cp.player.core.lyricsplugin; i18n resource loading dropped
 *          (see THIRD_PARTY_LICENSES.md).
 */
package cp.player.core.lyricsplugin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Lyrico 兼容的插件清单（插件目录下的 `manifest.json`）。
 *
 * 字段与 Lyrico Plugin API 一致，因此**按该规范编写的插件无需修改即可导入本宿主**。
 */
@Serializable
data class PluginManifest(
    val id: String,
    val name: String,
    val versionCode: Int = 0,
    val versionName: String = "",
    val author: String = "",
    val description: String = "",
    val apiVersion: Int = 1,
    val minHostApiVersion: Int = 1,
    val entry: String = "source.js",
    val includeDirs: List<String> = emptyList(),
    val icon: String? = null,
    val capabilities: Set<PluginCapability> = emptySet(),
    val configFields: List<PluginConfigField> = emptyList(),
)

/** 插件声明支持的能力。未声明时按「仅 searchSongs」处理。 */
@Serializable
enum class PluginCapability {
    @SerialName("searchSongs")
    SEARCH_SONGS,

    @SerialName("getLyrics")
    GET_LYRICS,

    @SerialName("searchCovers")
    SEARCH_COVERS,
}

/** 插件自定义配置项（宿主渲染成设置表单）。 */
@Serializable
data class PluginConfigField(
    val key: String,
    val title: String,
    val summary: String? = null,
    val group: String = "",
    val type: PluginConfigFieldType = PluginConfigFieldType.TEXT,
    val required: Boolean = false,
    val defaultValue: JsonElement = JsonPrimitive(""),
    val options: List<PluginConfigOption> = emptyList(),
)

fun PluginConfigField.defaultValueString(): String =
    (defaultValue as? JsonPrimitive)?.let { primitive ->
        primitive.contentOrNull ?: primitive.booleanOrNull?.toString()
    }.orEmpty()

@Serializable
enum class PluginConfigFieldType {
    @SerialName("text")
    TEXT,

    @SerialName("password")
    PASSWORD,

    @SerialName("number")
    NUMBER,

    @SerialName("switch")
    SWITCH,

    @SerialName("dropdown")
    DROPDOWN,

    @SerialName("textarea")
    TEXTAREA,

    @SerialName("markdown")
    MARKDOWN,
}

@Serializable
data class PluginConfigOption(
    val value: String,
    val label: String,
    val summary: String = "",
)
