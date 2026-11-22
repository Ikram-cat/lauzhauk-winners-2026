package alt

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // Signal that the Kotlin/Wasm module booted (and hide the HTML loading hint).
    runCatching {
        document.getElementById("boot")?.let { boot ->
            boot.setAttribute("data-state", "main")
            boot.classList.add("hidden")
        }
    }
    ComposeViewport(document.body!!) {
        App()
    }
}
