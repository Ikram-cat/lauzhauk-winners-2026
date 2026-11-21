package planb

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    var title by remember { mutableStateOf(Language.French.text(Copy.WindowTitle)) }
    Window(
        onCloseRequest = ::exitApplication,
        title = title,
        state = rememberWindowState(width = 1200.dp, height = 850.dp)
    ) {
        PlanB(System.getenv("API_BASE_URL").orEmpty()) { title = it.text(Copy.WindowTitle) }
    }
}
