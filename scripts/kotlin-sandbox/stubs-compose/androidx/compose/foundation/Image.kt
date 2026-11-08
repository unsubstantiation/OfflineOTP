package androidx.compose.foundation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale

@Composable
fun Image(bitmap: ImageBitmap, contentDescription: String?, modifier: Modifier = Modifier,
          contentScale: ContentScale = ContentScale.Fit) {}
