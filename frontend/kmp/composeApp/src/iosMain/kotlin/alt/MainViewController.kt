package alt

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/** Entry point used by the Swift host app (frontend/kmp/iosApp). The UI is the shared `App()`. */
fun MainViewController(): UIViewController = ComposeUIViewController { App() }
