package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.AppModel
import cp.player.app.ui.component.LegacyPageScaffold
import cp.player.app.platform.isPackageInstalled
import cp.player.app.platform.openTargetApp
import cp.player.app.platform.saveQrCodeToGallery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

class LoginScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel { LoginScreenModel() }
        val activeProvider by model.activeProvider.collectAsState()
        val isLogged by model.isLogged.collectAsState()
        val isLoading by model.isLoading.collectAsState()
        val message by model.message.collectAsState()
        val qrUrl by model.qrUrl.collectAsState()
        val qrImgBase64 by model.qrImgBase64.collectAsState()
        val targetAppName by model.targetAppName.collectAsState()
        val targetAppInstalled by model.targetAppInstalled.collectAsState()

        var method by remember { mutableStateOf(0) } // 0 qr, 1 email, 2 phone
        var email by remember { mutableStateOf("") }
        var phone by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var captcha by remember { mutableStateOf("") }

        LegacyPageScaffold(
            title = "登录 ${activeProvider?.name ?: ""}".trim(),
            navigationIcon = {
                IconButton(onClick = { navigator.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            },
        ) { pageModifier ->
            Column(
                pageModifier.verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (activeProvider == null) {
                    Text(
                        "当前没有活跃音源，请先在音源管理中导入并选择一个 Provider。",
                        color = MaterialTheme.colorScheme.error,
                    )
                    return@Column
                }

                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val items = listOf("扫码" to Icons.Filled.QrCode2, "邮箱" to Icons.Filled.Email, "手机" to Icons.Filled.Phone)
                    items.forEachIndexed { index, (label, icon) ->
                        SegmentedButton(
                            selected = method == index,
                            onClick = { method = index },
                            shape = SegmentedButtonDefaults.itemShape(index, items.size),
                            icon = { Icon(icon, null, Modifier.size(18.dp)) },
                            label = { Text(label) },
                        )
                    }
                }

                if (isLogged) {
                    Text("已登录当前音源 ✅", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    OutlinedButton(onClick = { model.logout() }, modifier = Modifier.fillMaxWidth()) { Text("退出登录") }
                } else when (method) {
                    0 -> QrLoginSection(
                        qrUrl = qrUrl,
                        qrImgBase64 = qrImgBase64,
                        isLoading = isLoading,
                        targetAppName = targetAppName,
                        targetAppInstalled = targetAppInstalled,
                        onSaveQr = { model.saveQrCode() },
                        onOpenTargetApp = { model.openTargetApp() },
                    )
                    1 -> {
                        OutlinedTextField(
                            value = email, onValueChange = { email = it }, label = { Text("邮箱") },
                            leadingIcon = { Icon(Icons.Filled.Email, null) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = password, onValueChange = { password = it }, label = { Text("密码") },
                            leadingIcon = { Icon(Icons.Filled.Lock, null) },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                        )
                        Button(
                            onClick = { model.loginEmail(email, password) },
                            enabled = email.isNotBlank() && password.isNotBlank() && !isLoading,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) { if (isLoading) Text("登录中…") else Text("邮箱登录") }
                    }
                    else -> {
                        OutlinedTextField(
                            value = phone, onValueChange = { phone = it }, label = { Text("手机号") },
                            leadingIcon = { Icon(Icons.Filled.Phone, null) },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            singleLine = true,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = captcha, onValueChange = { captcha = it }, label = { Text("验证码 / 密码") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                            )
                            OutlinedButton(onClick = { model.sendCaptcha(phone) }, enabled = phone.isNotBlank() && !isLoading) {
                                Text("发送验证码")
                            }
                        }
                        Button(
                            onClick = { model.loginPhone(phone, if (captcha.isNotBlank() && captcha.length <= 6) captcha else password) },
                            enabled = phone.isNotBlank() && !isLoading,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) { if (isLoading) Text("登录中…") else Text("手机登录") }
                    }
                }

                message?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }

                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { model.loginAnonymous() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading,
                ) { Text("游客登录 / 跳过") }
            }
        }
    }
}

@Composable
private fun QrLoginSection(
    qrUrl: String?,
    qrImgBase64: String?,
    isLoading: Boolean,
    targetAppName: String?,
    targetAppInstalled: Boolean,
    onSaveQr: () -> Unit,
    onOpenTargetApp: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("请使用音源对应 App 扫描二维码登录", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.size(240.dp),
        ) {
            Box(Modifier.size(240.dp).padding(10.dp), contentAlignment = Alignment.Center) {
                when {
                    isLoading -> CircularProgressIndicator()
                    qrUrl != null -> QrCodeImage(qrUrl, Modifier.size(220.dp))
                    else -> Text("二维码加载失败", color = MaterialTheme.colorScheme.outline)
                }
            }
        }

        if (qrImgBase64 != null || targetAppName != null) {
            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (qrImgBase64 != null) {
                    OutlinedButton(
                        onClick = onSaveQr,
                        enabled = !isLoading,
                    ) {
                        Icon(Icons.Filled.Download, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("保存二维码")
                    }
                }
                if (targetAppName != null) {
                    OutlinedButton(
                        onClick = onOpenTargetApp,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (targetAppInstalled) "打开$targetAppName" else "安装$targetAppName")
                    }
                }
            }
        }
    }
}

class LoginScreenModel : ScreenModel {
    val activeProvider: StateFlow<cp.player.kmp.provider.BackendProvider?> =
        AppModel.activeProviderFlow
    val isLoading = MutableStateFlow(false)
    val isLogged = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val qrUrl = MutableStateFlow<String?>(null)
    val qrImgBase64 = MutableStateFlow<String?>(null)
    val targetAppName = MutableStateFlow<String?>(null)
    val targetAppInstalled = MutableStateFlow(false)

    init {
        screenModelScope.launch {
            activeProvider.collect { provider ->
                val pkg = provider?.targetAppPackage
                if (!pkg.isNullOrEmpty()) {
                    targetAppName.value = provider.name
                    targetAppInstalled.value = isPackageInstalled(pkg)
                } else {
                    targetAppName.value = null
                    targetAppInstalled.value = false
                }
            }
        }
        screenModelScope.launch {
            checkLoginStatus()
            if (!isLogged.value) fetchQrCode()
        }
    }

    private fun providerId(): String = AppModel.activeProviderId()

    private suspend fun checkLoginStatus() {
        val hasCookie = AppModel.cookieStorage.getCookie(providerId())?.isNotEmpty() == true
        if (!hasCookie) return
        val api = AppModel.api
        val body = runCatching { api.getLoginStatus() }.getOrNull() ?: return
        val ok = body.asCodeOk()
        isLogged.value = ok
        if (ok) message.value = "已恢复登录"
    }

    fun fetchQrCode() {
        screenModelScope.launch {
            isLoading.value = true
            try {
                val api = AppModel.api
                val keyResp = api.getQrKey()
                val key = keyResp.uniCodeKey()
                if (key == null) { message.value = "获取二维码 key 失败"; return@launch }
                val qrResp = api.createQrCode(key)
                qrUrl.value = qrResp.uniQrUrl()
                qrImgBase64.value = qrResp.uniQrImage()
                isLoading.value = false
                // 轮询扫码状态
                pollQrStatus(key)
            } catch (e: Exception) {
                message.value = "二维码加载异常: ${e.message}"
                isLoading.value = false
            }
        }
    }

    private suspend fun pollQrStatus(key: String) {
        val api = AppModel.api
        repeat(120) {
            kotlinx.coroutines.delay(2000L)
            val resp = runCatching { api.checkQrStatus(key) }.getOrNull() ?: return@repeat
            val code = resp.asCode()
            when (code) {
                801 -> message.value = "等待扫码..."
                802 -> message.value = "已扫码，请在手机上确认登录"
                803 -> {
                    val cookie = resp.uniCookie()
                    if (!cookie.isNullOrEmpty()) AppModel.cookieStorage.saveCookie(providerId(), cookie)
                    isLogged.value = true
                    message.value = "扫码登录成功"
                    runCatching { AppModel.refreshUserProfile() }
                    return
                }
                800 -> {
                    message.value = "二维码已过期，请重新获取"
                    fetchQrCode()
                    return
                }
            }
            if (isLogged.value) return
        }
    }

    fun loginEmail(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) return
        screenModelScope.launch {
            isLoading.value = true
            try {
                val body = AppModel.api.login(email, password)
                handleLoginResult(body)
            } catch (e: Exception) {
                message.value = "登录失败: ${e.message}"
            } finally { isLoading.value = false }
        }
    }

    fun loginPhone(phone: String, codeOrPass: String) {
        if (phone.isBlank()) return
        screenModelScope.launch {
            isLoading.value = true
            try {
                val body = AppModel.api.loginWithPhone(phone, codeOrPass)
                handleLoginResult(body)
            } catch (e: Exception) {
                message.value = "登录失败: ${e.message}"
            } finally { isLoading.value = false }
        }
    }

    fun sendCaptcha(phone: String) {
        screenModelScope.launch {
            runCatching { AppModel.api.sendCaptcha(phone) }
                .onSuccess { message.value = "验证码已发送（如支持）" }
                .onFailure { message.value = "验证码发送失败: ${it.message}" }
        }
    }

    fun loginAnonymous() {
        screenModelScope.launch {
            isLoading.value = true
            try {
                val body = AppModel.api.loginAnonymous()
                handleLoginResult(body)
            } catch (e: Exception) {
                message.value = "游客登录失败: ${e.message}"
            } finally { isLoading.value = false }
        }
    }

    fun logout() {
        screenModelScope.launch {
            runCatching { AppModel.api.logout() }
            AppModel.cookieStorage.clear(providerId())
            isLogged.value = false
            message.value = "已退出登录"
            runCatching { AppModel.refreshUserProfile() }
        }
    }

    fun saveQrCode() {
        val base64 = qrImgBase64.value ?: return
        val providerName = activeProvider.value?.name ?: "qr"
        saveQrCodeToGallery(base64, "QR_$providerName")
    }

    fun openTargetApp() {
        val pkg = activeProvider.value?.targetAppPackage ?: return
        openTargetApp(pkg)
    }

    private fun handleLoginResult(body: JsonElement) {
        val code = body.asCode()
        val ok = body.asCodeOk()
        if (ok) {
            val cookie = body.uniCookie()
            if (!cookie.isNullOrEmpty()) AppModel.cookieStorage.saveCookie(providerId(), cookie)
            isLogged.value = true
            message.value = "登录成功"
            screenModelScope.launch { runCatching { AppModel.refreshUserProfile() } }
        } else {
            message.value = "登录失败 code=$code"
        }
    }
}

// ============ JSON 工具：跨 Provider 字段兼容提取 ============

private fun JsonElement.asObject(): JsonObject? = this as? JsonObject

private fun JsonElement.asCode(): Int? =
    (asObject()?.get("code") as? JsonPrimitive)?.intOrNull

private fun JsonElement.asCodeOk(): Boolean {
    val c = asCode() ?: return false
    return c == 200 || c == 0 || c == 201 || c == 301 || c == 803
}

private fun JsonElement.uniCodeKey(): String? {
    val obj = asObject() ?: return null
    (obj["unikey"] as? JsonPrimitive)?.contentOrNull?.let { return it }
    (obj["key"] as? JsonPrimitive)?.contentOrNull?.let { return it }
    val data = obj["data"] as? JsonObject ?: return null
    (data["unikey"] as? JsonPrimitive)?.contentOrNull?.let { return it }
    (data["key"] as? JsonPrimitive)?.contentOrNull?.let { return it }
    return null
}

private fun JsonElement.uniQrUrl(): String? {
    val obj = asObject() ?: return null
    (obj["qrurl"] as? JsonPrimitive)?.contentOrNull?.let { if (it.isNotBlank()) return it }
    (obj["qrUrl"] as? JsonPrimitive)?.contentOrNull?.let { if (it.isNotBlank()) return it }
    (obj["url"] as? JsonPrimitive)?.contentOrNull?.let { if (it.isNotBlank()) return it }
    val data = obj["data"] as? JsonObject ?: return null
    (data["qrurl"] as? JsonPrimitive)?.contentOrNull?.let { if (it.isNotBlank()) return it }
    (data["qrUrl"] as? JsonPrimitive)?.contentOrNull?.let { if (it.isNotBlank()) return it }
    (data["url"] as? JsonPrimitive)?.contentOrNull?.let { if (it.isNotBlank()) return it }
    return null
}

private fun JsonElement.uniQrImage(): String? {
    val obj = asObject() ?: return null
    (obj["qrimg"] as? JsonPrimitive)?.contentOrNull?.let { if (it.isNotBlank()) return it }
    (obj["qrcode"] as? JsonPrimitive)?.contentOrNull?.let { if (it.isNotBlank()) return it }
    (obj["base64"] as? JsonPrimitive)?.contentOrNull?.let { if (it.isNotBlank()) return it }
    val data = obj["data"] as? JsonObject ?: return null
    (data["qrimg"] as? JsonPrimitive)?.contentOrNull?.let { if (it.isNotBlank()) return it }
    (data["qrcode"] as? JsonPrimitive)?.contentOrNull?.let { if (it.isNotBlank()) return it }
    (data["base64"] as? JsonPrimitive)?.contentOrNull?.let { if (it.isNotBlank()) return it }
    return null
}

private fun JsonElement.uniCookie(): String? {
    val obj = asObject() ?: return null
    (obj["cookie"] as? JsonPrimitive)?.contentOrNull?.let { if (it.isNotBlank()) return it }
    val data = obj["data"] as? JsonObject ?: return null
    (data["cookie"] as? JsonPrimitive)?.contentOrNull?.let { if (it.isNotBlank()) return it }
    return null
}
