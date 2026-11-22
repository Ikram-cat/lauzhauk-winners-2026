package alt

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/** Entry point used by the Swift host app (frontend/kmp/iosApp). The UI is the shared `App()`. */
fun MainViewController(): UIViewController = ComposeUIViewController(
    // Info.plist carries CADisableMinimumFrameDurationOnPhone; do not crash at launch if a host lacks it.
    configure = { enforceStrictPlistSanityCheck = false },
) { App() }
