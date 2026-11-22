package alt

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import kotlin.system.exitProcess

/** Installed in [AltApplication.onCreate]: shows the crash instead of a silent "app has stopped". */
class CrashActivity : Activity() {
    companion object {
        const val EXTRA_TRACE = "trace"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val trace = intent.getStringExtra(EXTRA_TRACE) ?: "Unknown error"
        val text = TextView(this).apply {
            text = "ALT crashed:\n\n$trace"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
            textSize = 12f
            setPadding(32, 64, 32, 64)
        }
        setContentView(ScrollView(this).apply { addView(text) })
    }
}

class AltApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            try {
                val trace = Log.getStackTraceString(throwable)
                Log.e("ALT", "Uncaught exception", throwable)
                startActivity(
                    Intent(this, CrashActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        .putExtra(CrashActivity.EXTRA_TRACE, trace)
                )
            } catch (_: Throwable) {
                // If even showing the crash screen fails, fall through to the process kill below.
            }
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }
}
