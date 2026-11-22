package alt

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "ALT — Swiss journey planner",
        state = rememberWindowState(size = DpSize(1180.dp, 900.dp)),
    ) {
        App()
    }
}
