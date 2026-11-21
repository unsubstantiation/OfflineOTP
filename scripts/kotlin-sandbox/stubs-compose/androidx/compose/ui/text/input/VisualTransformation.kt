// Compose Multiplatform dependency stub: androidx.compose.ui.text.input.
package androidx.compose.ui.text.input

interface VisualTransformation {
    companion object {
        val None: VisualTransformation = object : VisualTransformation {}
    }
}

class PasswordVisualTransformation : VisualTransformation

enum class ImeAction { Default, Next, Done }
enum class KeyboardType { Text, Number, NumberPassword }
