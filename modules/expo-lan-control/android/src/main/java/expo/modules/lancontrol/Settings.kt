package expo.modules.lancontrol

import android.content.Context
import android.os.Build
import android.provider.Settings as AndroidSettings

class LanSettings(context: Context) {
  private val prefs = context.getSharedPreferences("lancontrol", Context.MODE_PRIVATE)
  private val defaultName: String =
    AndroidSettings.Global.getString(context.contentResolver, AndroidSettings.Global.DEVICE_NAME)
      ?.takeIf { it.isNotBlank() }
      ?: "${Build.MANUFACTURER} ${Build.MODEL}"

  /** "Allow remote control of this phone": runs the command server + foreground service. */
  var receiverEnabled: Boolean
    get() = prefs.getBoolean("receiverEnabled", false)
    set(v) = prefs.edit().putBoolean("receiverEnabled", v).apply()

  var startOnBoot: Boolean
    get() = prefs.getBoolean("startOnBoot", false)
    set(v) = prefs.edit().putBoolean("startOnBoot", v).apply()

  var deviceName: String
    get() = prefs.getString("deviceName", null) ?: defaultName.take(Protocol.MAX_NAME_LENGTH)
    set(v) = prefs.edit().putString("deviceName", v.trim().take(Protocol.MAX_NAME_LENGTH)).apply()
}
