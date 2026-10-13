package expo.modules.lancontrol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/** All user-visible notifications. Uses platform APIs only (no androidx dependency). */
class Notifications(private val context: Context) {
  companion object {
    const val CHANNEL_SERVICE = "lancontrol.service"
    const val CHANNEL_ALERTS = "lancontrol.alerts"
    const val ID_SERVICE = 1001
    const val ID_WAKE = 1002
    const val ID_FIND_SOUND = 1003
    private const val ID_PROMPT_BASE = 2000
  }

  private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

  fun ensureChannels() {
    if (Build.VERSION.SDK_INT < 26) return
    nm.createNotificationChannel(
      NotificationChannel(CHANNEL_SERVICE, "Remote control service", NotificationManager.IMPORTANCE_LOW).apply {
        description = "Shown while this phone accepts commands from paired devices"
      },
    )
    nm.createNotificationChannel(
      NotificationChannel(CHANNEL_ALERTS, "Remote requests", NotificationManager.IMPORTANCE_HIGH).apply {
        description = "Wake requests, find-my-phone and permission prompts from paired devices"
      },
    )
  }

  val enabled: Boolean get() = nm.areNotificationsEnabled()

  private fun builder(channel: String): Notification.Builder =
    if (Build.VERSION.SDK_INT >= 26) {
      Notification.Builder(context, channel)
    } else {
      @Suppress("DEPRECATION")
      Notification.Builder(context).setPriority(
        if (channel == CHANNEL_ALERTS) Notification.PRIORITY_HIGH else Notification.PRIORITY_LOW,
      )
    }

  private fun launchAppIntent(): PendingIntent? {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
    launch.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
    return PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  }

  private fun broadcast(action: String, requestCode: Int, extra: String? = null): PendingIntent {
    val intent = Intent(context, NotificationActionReceiver::class.java).setAction(action)
    extra?.let { intent.putExtra(NotificationActionReceiver.EXTRA_ID, it) }
    return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  }

  @Suppress("DEPRECATION")
  private fun action(title: String, intent: PendingIntent) = Notification.Action.Builder(null, title, intent).build()

  fun serviceNotification(deviceName: String, port: Int?): Notification {
    val stop = PendingIntent.getService(
      context, 1,
      Intent(context, DeviceService::class.java).setAction(DeviceService.ACTION_STOP),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    return builder(CHANNEL_SERVICE)
      .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
      .setContentTitle("Remote control active")
      .setContentText(if (port != null) "$deviceName is accepting commands from paired devices" else "Starting…")
      .setOngoing(true)
      .setContentIntent(launchAppIntent())
      .addAction(action("Stop", stop))
      .build()
  }

  fun showWakeRequested(peerName: String) {
    nm.notify(
      ID_WAKE,
      builder(CHANNEL_ALERTS)
        .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
        .setContentTitle("Wake requested")
        .setContentText("$peerName asked this phone to wake its screen. Tap to open.")
        .setCategory(Notification.CATEGORY_REMINDER)
        .setAutoCancel(true)
        .setContentIntent(launchAppIntent())
        .setTimeoutAfterCompat(60_000)
        .build(),
    )
  }

  fun showFindSound(peerName: String) {
    nm.notify(
      ID_FIND_SOUND,
      builder(CHANNEL_ALERTS)
        .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
        .setContentTitle("Find my phone")
        .setContentText("$peerName is ringing this phone")
        .setCategory(Notification.CATEGORY_ALARM)
        .setOngoing(true)
        .addAction(action("Stop", broadcast(NotificationActionReceiver.ACTION_STOP_SOUND, 3)))
        .build(),
    )
  }

  fun cancelFindSound() = nm.cancel(ID_FIND_SOUND)

  fun showPrompt(promptId: String, peerName: String, description: String, slot: Int) {
    nm.notify(
      ID_PROMPT_BASE + slot,
      builder(CHANNEL_ALERTS)
        .setSmallIcon(android.R.drawable.ic_dialog_alert)
        .setContentTitle("Allow $peerName?")
        .setContentText("$peerName wants to $description")
        .setCategory(Notification.CATEGORY_CALL)
        .setAutoCancel(true)
        .setContentIntent(launchAppIntent())
        .addAction(action("Deny", broadcast(NotificationActionReceiver.ACTION_PROMPT_DENY, 100 + slot * 2, promptId)))
        .addAction(action("Allow", broadcast(NotificationActionReceiver.ACTION_PROMPT_ALLOW, 101 + slot * 2, promptId)))
        .setTimeoutAfterCompat(PromptManager.TIMEOUT_MS)
        .build(),
    )
  }

  fun cancelPrompt(slot: Int) = nm.cancel(ID_PROMPT_BASE + slot)

  private fun Notification.Builder.setTimeoutAfterCompat(ms: Long): Notification.Builder =
    if (Build.VERSION.SDK_INT >= 26) setTimeoutAfter(ms) else this
}
