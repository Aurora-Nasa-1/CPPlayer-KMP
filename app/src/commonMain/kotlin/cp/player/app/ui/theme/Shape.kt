package cp.player.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Material 3 Expressive 形状令牌（加大圆角以体现 Expressive 风格）

val AppShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

object CpShapes {
    val full = RoundedCornerShape(percent = 50)
    val sheet = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    val miniPlayer = RoundedCornerShape(
        topStart = 28.dp,
        topEnd = 28.dp,
        bottomStart = 16.dp,
        bottomEnd = 16.dp,
    )
}
