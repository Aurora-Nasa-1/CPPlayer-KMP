package cp.player.app.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.alexzhirkevich.qrose.rememberQrCodePainter

@Composable
fun QrCodeImage(url: String, modifier: Modifier = Modifier) {
    val painter = rememberQrCodePainter(url)
    Canvas(
        modifier
            .size(220.dp)
            .background(Color.White),
    ) {
        drawRect(Color.White, topLeft = androidx.compose.ui.geometry.Offset.Zero, size = size)
        with(painter) { draw(size) }
    }
}
