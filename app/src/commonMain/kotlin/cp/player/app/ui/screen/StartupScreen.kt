package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cp.player.app.ui.component.CpLoadingIndicator
import cp.player.app.ui.component.MorphingShape

class StartupScreen(private val message: String) : Screen {
    @Composable
    override fun Content() {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 启动页是用户看到的第一屏。用「持续变形的 MaterialShapes + 变形加载器」代替
            // 一个转圈的 CircularProgressIndicator —— 第一眼就该是 M3 Expressive，
            // 而不是一个 2014 年就有的转圈。
            MorphingShape(
                modifier = Modifier.size(96.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            )
            CpLoadingIndicator(
                modifier = Modifier.padding(top = 20.dp).size(44.dp),
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = message,
                modifier = Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}
