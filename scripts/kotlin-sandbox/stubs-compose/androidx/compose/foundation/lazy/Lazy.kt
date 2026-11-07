// Compose Multiplatform dependency stub: androidx.compose.foundation.lazy.
package androidx.compose.foundation.lazy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

interface LazyItemScope

interface LazyListScope

fun <T> LazyListScope.items(
    items: List<T>,
    key: ((item: T) -> Any)? = null,
    contentType: (item: T) -> Any? = { null },
    itemContent: @Composable LazyItemScope.(item: T) -> Unit
) {
}

@Composable
fun LazyColumn(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = object : PaddingValues {},
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    reverseLayout: Boolean = false,
    content: LazyListScope.() -> Unit
) {
}
