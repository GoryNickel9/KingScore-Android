package app.kingscore.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ImageNotSupported
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kingscore.android.core.images.*
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

internal val LocalAccountImages = staticCompositionLocalOf<AccountImages?> { null }

@Composable
internal fun GameCover(url: String?, name: String, size: Dp = 64.dp) {
    RemoteThumbnail(httpsImageUrl(url), name, false, size)
}

@Composable
internal fun PlayerAvatar(path: String?, name: String, size: Dp = 48.dp) {
    val images = LocalAccountImages.current
    RemoteThumbnail(avatarImageUrl(path, images?.endpoint ?: ""), name, true, size)
}

@Composable
private fun RemoteThumbnail(url: String?, name: String, avatar: Boolean, size: Dp) {
    val images = LocalAccountImages.current
    val context = LocalContext.current
    key(images, url) {
        var failed by remember { mutableStateOf(false) }
        var loading by remember { mutableStateOf(url != null && images != null) }
        var attempt by remember { mutableIntStateOf(0) }
        val request = remember(context, url, attempt) {
            ImageRequest.Builder(context).data(url).build()
        }
        val phase = when { failed -> "error"; url == null || images == null -> "placeholder"; loading -> "loading"; else -> "ready" }
        Column(Modifier.testTag("image-$phase-$name"), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(size).clip(if (avatar) CircleShape else RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant).clearAndSetSemantics {},
                contentAlignment = Alignment.Center) {
                if (url == null || images == null || failed) {
                    if (avatar) Text(imageInitials(name), style = MaterialTheme.typography.titleMedium)
                    else Icon(if (failed) Icons.Outlined.ImageNotSupported else Icons.Outlined.SportsEsports,
                        null, modifier = Modifier.size(size * 0.45f))
                }
                if (url != null && images != null && !failed) {
                    key(attempt) {
                        AsyncImage(model = request, imageLoader = images.loader, contentDescription = null,
                            contentScale = if (avatar) ContentScale.Crop else ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().testTag("remote-image-$name"),
                            onLoading = { loading = true }, onSuccess = { loading = false },
                            onError = { failed = true; loading = false })
                    }
                    if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            if (failed) TextButton(onClick = { attempt++; failed = false; loading = true },
                modifier = Modifier.testTag("retry-image-$name")) { Text("Riprova immagine") }
        }
    }
}
