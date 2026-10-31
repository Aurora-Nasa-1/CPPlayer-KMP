package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.SettingsClickItem
import cp.player.app.ui.component.SettingsDropdownItem
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsPage
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.component.SettingsSwitchItem
import cp.player.app.ui.util.popOrNotify
import cp.player.core.provider.ProviderSettingItemSchema
import cp.player.core.util.defaultSettingsStorage
import cp.player.app.ui.component.CpText

/**
 * 声明式音源自定义设置页面。
 *
 * 宿主根据音源声明的 [ProviderSettingItemSchema] 列表，
 * 自动使用标准的 Material 3 表单规范动态渲染配置表单，
 * 并以 `provider_config_{providerId}` 命名空间隔离持久化用户配置。
 */
class ProviderCustomSettingsScreen(
    private val providerId: String,
    private val providerName: String,
    private val schemas: List<ProviderSettingItemSchema>
) : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val storage = remember(providerId) { defaultSettingsStorage("provider_config_$providerId") }

        // 将当前持久化的配置读取到 Compose 响应式状态 Map 中
        val values = remember(providerId, schemas) {
            mutableStateMapOf<String, String>().apply {
                schemas.forEach { item ->
                    val stored = storage.getString(item.key) ?: item.default.orEmpty()
                    put(item.key, stored)
                }
            }
        }

        // 当前正在编辑文本的条目
        var editingItem by remember { mutableStateOf<ProviderSettingItemSchema?>(null) }
        var editingText by remember { mutableStateOf("") }

        CpRouteScaffold(
            title = "$providerName 配置",
            onBack = { navigator.popOrNotify() }
        ) { pageModifier ->
            SettingsPage(pageModifier) {
                SettingsNote(
                    text = "在此配置该音源专属参数。保存后将在发起 API 调用时自动注入。",
                    color = MaterialTheme.colorScheme.primary
                )

                SettingsSection(title = "参数设置") {
                    schemas.forEachIndexed { index, item ->
                        when (item.type.lowercase()) {
                            "switch", "boolean" -> {
                                val checked = values[item.key]?.toBooleanStrictOrNull()
                                    ?: item.default?.toBooleanStrictOrNull()
                                    ?: false
                                SettingsSwitchItem(
                                    title = item.label,
                                    subtitle = item.description ?: item.placeholder,
                                    checked = checked,
                                    index = index,
                                    total = schemas.size,
                                    onCheckedChange = { v ->
                                        values[item.key] = v.toString()
                                        storage.putString(item.key, v.toString())
                                        cp.player.app.AppModel.onProviderConfigurationChanged(providerId)
                                    }
                                )
                            }
                            "select" -> {
                                val options = item.options.orEmpty()
                                val labels = options.map { it.label }
                                val currentVal = values[item.key] ?: item.default ?: options.firstOrNull()?.value.orEmpty()
                                val selectedIndex = options.indexOfFirst { it.value == currentVal }.coerceAtLeast(0)
                                SettingsDropdownItem(
                                    title = item.label,
                                    subtitle = item.description,
                                    options = labels,
                                    selectedIndex = selectedIndex,
                                    index = index,
                                    total = schemas.size,
                                    onSelect = { idx ->
                                        val selectedVal = options.getOrNull(idx)?.value.orEmpty()
                                        values[item.key] = selectedVal
                                        storage.putString(item.key, selectedVal)
                                        cp.player.app.AppModel.onProviderConfigurationChanged(providerId)
                                    }
                                )
                            }
                            else -> {
                                // string, password 或其他文本类型
                                val isPassword = item.type.equals("password", ignoreCase = true)
                                val currentVal = values[item.key] ?: item.default.orEmpty()
                                val displayValue = when {
                                    isPassword && currentVal.isNotEmpty() -> "••••••••"
                                    currentVal.isNotEmpty() -> currentVal
                                    else -> "未设置"
                                }
                                val subtitle = buildString {
                                    item.description?.let { append(it).append("\n") }
                                    append("当前: ").append(displayValue)
                                }
                                SettingsClickItem(
                                    title = item.label,
                                    subtitle = subtitle,
                                    icon = Icons.Filled.Tune,
                                    index = index,
                                    total = schemas.size,
                                    onClick = {
                                        editingItem = item
                                        editingText = values[item.key] ?: item.default.orEmpty()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // 文本编辑对话框
        editingItem?.let { item ->
            val isPassword = item.type.equals("password", ignoreCase = true)
            AlertDialog(
                onDismissRequest = { editingItem = null },
                title = { CpText(text = "编辑 ${item.label}") },
                text = {
                    Column {
                        item.description?.let {
                            CpText(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        OutlinedTextField(
                            value = editingText,
                            onValueChange = { editingText = it },
                            label = { CpText(item.label) },
                            placeholder = item.placeholder?.let { { CpText(it) } },
                            singleLine = true,
                            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
                            keyboardOptions = if (isPassword) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            values[item.key] = editingText
                            storage.putString(item.key, editingText)
                            cp.player.app.AppModel.onProviderConfigurationChanged(providerId)
                            editingItem = null
                        }
                    ) {
                        CpText("保存")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { editingItem = null }) {
                        CpText("取消")
                    }
                }
            )
        }
    }
}
