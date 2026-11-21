package planb

import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIViewController

object PlanBIos {
    @OptIn(ExperimentalForeignApi::class)
    fun makeViewController(apiBaseUrl: String): UIViewController =
        ComposeUIViewController {
            PlanB(apiBaseUrl, reduceMotion = UIAccessibilityIsReduceMotionEnabled())
        }
}
