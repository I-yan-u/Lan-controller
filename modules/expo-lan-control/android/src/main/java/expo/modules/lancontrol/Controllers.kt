package expo.modules.lancontrol

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Flashlight via CameraManager. Needs no CAMERA permission. */
class TorchController(context: Context) {
  private val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
  private val cameraId: String? = try {
    val withFlash = cm.cameraIdList.filter {
      cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
    }
    withFlash.firstOrNull {
      cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
    } ?: withFlash.firstOrNull()
  } catch (e: Exception) {
    null
  }

  @Volatile var isOn = false
    private set

  val isSupported: Boolean get() = cameraId != null

  init {
    if (cameraId != null) {
      cm.registerTorchCallback(object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(id: String, enabled: Boolean) {
          if (id == cameraId) isOn = enabled
        }
        override fun onTorchModeUnavailable(id: String) {
          if (id == cameraId) isOn = false
        }
      }, Handler(Looper.getMainLooper()))
    }
  }

  fun set(enabled: Boolean) {
    val id = cameraId ?: throw ProtocolException(ErrorCode.UNSUPPORTED, "This phone has no flash")
    try {
      cm.setTorchMode(id, enabled)
    } catch (e: CameraAccessException) {
      // Typically CAMERA_IN_USE / MAX_CAMERAS_IN_USE: another app holds the camera.
      throw ProtocolException(ErrorCode.HARDWARE_BUSY, "Flash unavailable: camera is in use (${e.reason})")
    } catch (e: IllegalArgumentException) {
      throw ProtocolException(ErrorCode.UNSUPPORTED, "Flash unavailable on this camera")
    }
  }
}

class VibrationController(context: Context) {
  private val vibrator: Vibrator? =
    if (Build.VERSION.SDK_INT >= 31) {
      (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
      @Suppress("DEPRECATION")
      context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

  val isSupported: Boolean get() = vibrator?.hasVibrator() == true

  fun vibrate(durationMs: Long) {
    val v = vibrator?.takeIf { it.hasVibrator() }
      ?: throw ProtocolException(ErrorCode.UNSUPPORTED, "This phone has no vibrator")
    val ms = durationMs.coerceIn(1, Protocol.MAX_VIBRATE_MS)
    if (Build.VERSION.SDK_INT >= 26) {
      v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    } else {
      @Suppress("DEPRECATION")
      v.vibrate(ms)
    }
  }
}

/** Plays the alarm sound on the alarm stream so it is audible even when the ringer is quiet. */
class FindSoundController(private val context: Context, private val notifications: Notifications) {
  companion object {
    const val AUTO_STOP_MS = 15_000L
  }

  private val main = Handler(Looper.getMainLooper())
  private var player: MediaPlayer? = null
  private val autoStop = Runnable { stopOnMain() }

  val isPlaying: Boolean get() = player != null

  private fun soundUri(): Uri? =
    listOf(RingtoneManager.TYPE_ALARM, RingtoneManager.TYPE_RINGTONE, RingtoneManager.TYPE_NOTIFICATION)
      .firstNotNullOfOrNull { RingtoneManager.getActualDefaultRingtoneUri(context, it) }

  suspend fun play(peerName: String) = withContext(Dispatchers.Main) {
    stopOnMain()
    val uri = soundUri() ?: throw ProtocolException(ErrorCode.UNSUPPORTED, "No alarm or ringtone sound is set")
    val mp = MediaPlayer()
    try {
      mp.setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_ALARM)
          .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
          .build(),
      )
      mp.setDataSource(context, uri)
      mp.isLooping = true
      mp.prepare()
      mp.start()
    } catch (e: Exception) {
      mp.release()
      throw ProtocolException(ErrorCode.HARDWARE_BUSY, "Could not play sound: ${e.message}")
    }
    player = mp
    main.postDelayed(autoStop, AUTO_STOP_MS)
    notifications.showFindSound(peerName)
  }

  suspend fun stop() = withContext(Dispatchers.Main) { stopOnMain() }

  fun stopFromAnyThread() = main.post { stopOnMain() }

  private fun stopOnMain() {
    main.removeCallbacks(autoStop)
    player?.let { runCatching { it.stop() }; it.release() }
    player = null
    notifications.cancelFindSound()
  }
}

class StatusProvider(
  private val context: Context,
  private val torch: TorchController,
  private val vibration: VibrationController,
  private val sound: FindSoundController,
) {
  private val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
  private val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

  fun capabilities(): List<String> = buildList {
    add(Capability.STATUS)
    if (torch.isSupported) add(Capability.TORCH)
    add(Capability.SOUND)
    if (vibration.isSupported) add(Capability.VIBRATE)
    add(Capability.WAKE)
  }

  fun status(deviceId: String, deviceName: String, receiverRunning: Boolean): JSONObject {
    val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val state = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    return JSONObject()
      .put("deviceId", deviceId)
      .put("name", deviceName)
      .put("battery", JSONObject()
        .put("level", if (level >= 0 && scale > 0) Math.round(level * 100f / scale) else JSONObject.NULL)
        .put("charging", state == BatteryManager.BATTERY_STATUS_CHARGING || state == BatteryManager.BATTERY_STATUS_FULL))
      .put("screenInteractive", pm.isInteractive)
      .put("locked", keyguard.isKeyguardLocked)
      .put("torchOn", torch.isOn)
      .put("findSoundPlaying", sound.isPlaying)
      .put("receiverRunning", receiverRunning)
      .put("capabilities", JSONArray(capabilities()))
      .put("androidSdk", Build.VERSION.SDK_INT)
      .put("manufacturer", Build.MANUFACTURER)
      .put("model", Build.MODEL)
  }
}
