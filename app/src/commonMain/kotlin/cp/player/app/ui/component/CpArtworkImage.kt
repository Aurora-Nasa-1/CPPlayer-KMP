package cp.player.app.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.decode.DataSource
import coil3.request.ImageRequest
import kotlinx.coroutines.delay

private class ArtworkPainterHolder {
    var previous: Painter? = null
    var current: Painter? = null
    var lastRequest: ImageRequest? = null

    fun reset() {
        previous = null
        current = null
        lastRequest = null
    }
}

/**
 * 播放器 / 焦点封面专用防闪烁图位：
 * - 切歌加载中保留上一首已成功绘制的封面在底层，新图就绪后由 Coil Crossfade 平滑接管，
 *   彻底避免「先闪一下空白/占位块、再跳出新封面」；
 * - 内存缓存命中时 Coil 自动跳过 crossfade，首帧零延迟直出；
 * - 无 URL 或加载失败时立即回落到 [placeholder]（默认静态 [CpCoverPlaceholder]）。
 */
@Composable
fun CpArtworkImage(
    request: ImageRequest?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholderCorner: Dp = 0.dp,
    imageModifier: Modifier = Modifier.fillMaxSize(),
    placeholder: @Composable () -> Unit = {
        CpCoverPlaceholder(
            modifier = Modifier.fillMaxSize(),
            corner = placeholderCorner,
            animated = false,
        )
    },
) {
    val holder = remember { ArtworkPainterHolder() }
    var crossfadeSettled by remember(request) { mutableStateOf(false) }

    if (request == null) {
        holder.reset()
        Box(modifier) { placeholder() }
        return
    }
    if (request !== holder.lastRequest) {
        holder.previous = holder.current
        holder.lastRequest = request
    }

    val painter = rememberAsyncImagePainter(
        model = request,
        contentScale = contentScale,
    )
    val state by painter.state.collectAsState()
    val successState = state as? AsyncImagePainter.State.Success
    val isMemoryHit = successState?.result?.dataSource == DataSource.MEMORY_CACHE
    val loadingState = state as? AsyncImagePainter.State.Loading
    val hasLoadingMemPlaceholder = loadingState?.painter != null

    when (val s = state) {
        is AsyncImagePainter.State.Success -> {
            holder.current = s.painter
            if (isMemoryHit) {
                holder.previous = null
            }
        }
        is AsyncImagePainter.State.Error -> {
            holder.previous = null
            holder.current = null
        }
        else -> Unit
    }

    LaunchedEffect(request, state) {
        if (successState != null && !isMemoryHit) {
            delay(180)
            holder.previous = null
            crossfadeSettled = true
        }
    }

    val needUnderlay = when {
        state is AsyncImagePainter.State.Error -> true
        isMemoryHit || crossfadeSettled -> false
        hasLoadingMemPlaceholder -> false
        else -> true
    }

    Box(modifier) {
        if (needUnderlay) {
            val prev = holder.previous
            if (prev != null && state !is AsyncImagePainter.State.Error) {
                Image(
                    painter = prev,
                    contentDescription = null,
                    contentScale = contentScale,
                    modifier = imageModifier,
                )
            } else {
                placeholder()
            }
        }
        Image(
            painter = painter,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = imageModifier,
        )
    }
}
