package expo.modules.lancontrol

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Best-effort screen wake. Android does not let ordinary apps turn the display on from the
 * background, so this tries the documented paths that are available and then *verifies* the
 * result instead of assuming success:
 *
 *  1. Already interactive → `already_awake`.
 *  2. With "Display over other apps" granted (a documented background-activity-launch exemption),
 *     launch [WakeActivity], which uses setTurnScreenOn/setShowWhenLocked.
 *  3. On API < 34, also try the legacy ACQUIRE_CAUSES_WAKEUP wake lock.
 *  4. Always post a notification the user can tap (the user-visible fallback).
 *  5. Re-check PowerManager.isInteractive → `confirmed_awake`, `requested` or `blocked`.
 */
class ScreenWakeController(private val context: Context, private val notifications: Notifications) {
  companion object {
    const val VERIFY_DELAY_MS = 1500L
  }

  private val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager

  val canLaunchWakeActivity: Boolean get() = Settings.canDrawOverlays(context)

  suspend fun wake(peerName: String): JSONObject {
    if (pm.isInteractive) return result("already_awake", listOf("none"))

    val methods = mutableListOf<String>()
    if (canLaunchWakeActivity) {
      try {
        context.startActivity(
          Intent(context, WakeActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
          ),
        )
        methods += "activity"
      } catch (e: Exception) {
        // Background launch was still refused; fall through to the other paths.
      }
    }
    if (Build.VERSION.SDK_INT < 34) {
      try {
        @Suppress("DEPRECATION")
        pm.newWakeLock(
          PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
          "lancontrol:wake",
        ).acquire(3000)
        methods += "wakelock"
      } catch (e: Exception) {
        // Not permitted on this build.
      }
    }
    notifications.showWakeRequested(peerName)
    methods += "notification"

    delay(VERIFY_DELAY_MS)
    val state = when {
      pm.isInteractive -> "confirmed_awake"
      methods.any { it == "activity" || it == "wakelock" } -> "requested"
      else -> "blocked"
    }
    return result(state, methods)
  }

  private fun result(state: String, methods: List<String>) = JSONObject()
    .put("state", state)
    .put("methods", org.json.JSONArray(methods))
    .put("overlayPermission", canLaunchWakeActivity)
    .put("screenInteractive", pm.isInteractive)
}

/** Transparent activity whose only job is to turn the screen on, then get out of the way. */
class WakeActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (Build.VERSION.SDK_INT >= 27) {
      setShowWhenLocked(true)
      setTurnScreenOn(true)
    } else {
      @Suppress("DEPRECATION")
      window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
    }
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    Handler(Looper.getMainLooper()).postDelayed({ finish() }, 3000)
  }
}
