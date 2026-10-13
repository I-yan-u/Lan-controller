package expo.modules.lancontrol

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Foreground service that keeps the command server and NSD advertisement alive while the screen
 * is off or the app is in the background. Started only from an explicit user action (or from boot
 * when the user opted in); the persistent notification has a Stop action.
 */
class DeviceService : Service() {
  companion object {
    const val ACTION_STOP = "expo.modules.lancontrol.action.STOP_SERVICE"

    /** Throws if Android refuses the start (e.g. ForegroundServiceStartNotAllowedException). */
    fun start(context: Context) {
      val intent = Intent(context, DeviceService::class.java)
      if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
    }

    fun stop(context: Context) {
      context.stopService(Intent(context, DeviceService::class.java))
    }
  }

  private val core by lazy { LanControlCore.get(this) }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      core.setReceiverEnabled(false)
      stopSelf()
      return START_NOT_STICKY
    }
    if (!core.settings.receiverEnabled) {
      stopSelf()
      return START_NOT_STICKY
    }
    try {
      goForeground(null)
    } catch (e: Exception) {
      // E.g. a sticky restart in the background that Android does not allow to go foreground.
      core.recordEvent("SERVICE", null, null, false, "START_REFUSED", e.message ?: e.javaClass.simpleName)
      stopSelf()
      return START_NOT_STICKY
    }
    try {
      val port = core.startReceiver()
      goForeground(port)
    } catch (e: Exception) {
      core.recordEvent("SERVICE", null, null, false, ErrorCode.INTERNAL, "Server failed to start: ${e.message}")
      stopSelf()
      return START_NOT_STICKY
    }
    return START_STICKY
  }

  private fun goForeground(port: Int?) {
    val notification = core.notifications.serviceNotification(core.settings.deviceName, port)
    if (Build.VERSION.SDK_INT >= 29) {
      startForeground(Notifications.ID_SERVICE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    } else {
      startForeground(Notifications.ID_SERVICE, notification)
    }
  }

  override fun onDestroy() {
    core.stopReceiver()
    super.onDestroy()
  }
}

/** Restarts the receiver after reboot, only if the user enabled both remote control and start-on-boot. */
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
    val core = LanControlCore.get(context)
    if (!core.settings.receiverEnabled || !core.settings.startOnBoot) return
    try {
      DeviceService.start(context)
      core.recordEvent("SERVICE", null, null, true, null, "Started after ${if (intent.action == Intent.ACTION_BOOT_COMPLETED) "boot" else "app update"}")
    } catch (e: Exception) {
      core.recordEvent("SERVICE", null, null, false, "START_REFUSED", "Could not start after boot: ${e.message}")
    }
  }
}

/** Handles notification actions: Allow/Deny on permission prompts and Stop on find-my-phone. */
class NotificationActionReceiver : BroadcastReceiver() {
  companion object {
    const val ACTION_PROMPT_ALLOW = "expo.modules.lancontrol.action.PROMPT_ALLOW"
    const val ACTION_PROMPT_DENY = "expo.modules.lancontrol.action.PROMPT_DENY"
    const val ACTION_STOP_SOUND = "expo.modules.lancontrol.action.STOP_SOUND"
    const val EXTRA_ID = "id"
  }

  override fun onReceive(context: Context, intent: Intent) {
    val core = LanControlCore.get(context)
    when (intent.action) {
      ACTION_PROMPT_ALLOW -> intent.getStringExtra(EXTRA_ID)?.let { core.prompts.respond(it, true) }
      ACTION_PROMPT_DENY -> intent.getStringExtra(EXTRA_ID)?.let { core.prompts.respond(it, false) }
      ACTION_STOP_SOUND -> core.sound.stopFromAnyThread()
    }
  }
}
