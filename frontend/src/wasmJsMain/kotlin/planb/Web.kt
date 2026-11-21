package planb

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import kotlinx.browser.window

@JsModule("@js-joda/timezone")
external object ZurichTimeZoneModule

private val timeZoneData = ZurichTimeZoneModule

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val api = document.querySelector("meta[name=api-base-url]")?.getAttribute("content").orEmpty()
    val reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches
    ComposeViewport(document.body!!) {
        PlanB(api, reduceMotion) { language ->
            document.documentElement?.setAttribute("lang", language.tag)
            document.title = language.text(Copy.WindowTitle)
        }
    }
}
