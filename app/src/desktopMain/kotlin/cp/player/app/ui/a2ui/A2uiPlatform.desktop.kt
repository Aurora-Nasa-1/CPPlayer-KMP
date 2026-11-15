package cp.player.app.ui.a2ui

import ai.littlepress.a2ui.compat.android.content.Context
import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1
import androidx.compose.material3.a2ui.catalog.A2uiImageRenderer
import androidx.compose.material3.a2ui.catalog.MaterialA2uiBasicCatalogV1Defaults
import androidx.compose.material3.a2ui.catalog.materialA2uiBasicCatalogV1
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import coil3.compose.AsyncImage

@Composable
internal actual fun rememberA2uiMessageProcessor(): androidx.a2ui.model.processor.A2uiMessageProcessor {
    return remember {
        val catalog = materialA2uiBasicCatalogV1(
            image = MaterialA2uiBasicCatalogV1Defaults.image(
                A2uiImageRenderer { url, contentDescription, contentScale, modifier, _ ->
                    AsyncImage(
                        model = url,
                        contentDescription = contentDescription,
                        contentScale = contentScale,
                        modifier = modifier,
                    )
                },
            ),
            video = MaterialA2uiBasicCatalogV1Defaults.video { _, _, _ -> },
            audioPlayer = MaterialA2uiBasicCatalogV1Defaults.audioPlayer { _, _, _, _ -> },
            functions = A2uiBasicCatalogV1.Functions(Context()),
        )
        androidx.a2ui.compose.ui.A2uiMessageProcessor(catalogs = listOf(catalog))
    }
}
