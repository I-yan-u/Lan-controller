# Lan-controller

An Android app for controlling phones on the same Wi-Fi network: flashlight, find-my-phone ring, vibration, status (battery, screen, lock), and best-effort screen wake. Every installation can both control other phones and be controlled. There is no server or cloud: phones find each other with mDNS and talk directly over mutually authenticated TLS.

Built with React Native + Expo (SDK 57, Expo Router) and a local Kotlin Expo module, [`modules/expo-lan-control`](modules/expo-lan-control).

## Quick start

Requirements: Node 22+, JDK 17+, the Android SDK, and two physical Android 7.0+ phones on the same Wi-Fi with USB debugging enabled. The app uses custom native code, so it can't run in Expo Go.

```bash
npm install
npx expo run:android --device   # pick the first phone; repeat for the second
```

On phone **B** (the phone to be controlled): **Pair a device → Show pairing code**. This turns on *Allow remote control*.
On phone **A** (the controller): **Pair a device → Scan pairing code**. Accept on B, and check that both phones show the same 6-digit code.
B now appears under **Devices** on A. Open it to send commands.

To control in both directions, repeat the process with the roles swapped.

## How it works

```
React Native UI (src/app, src/services, src/stores)
        │  typed module API + events
Kotlin LanControlCore (process-wide singleton, outlives the JS runtime)
  ├─ LanDiscoveryManager   NSD advertise/discover _lancontrol._tcp, multicast lock
  ├─ CommandServer         mutual-TLS server on a random port, rate limited
  ├─ CommandClient         pooled pinned connections, 10 s heartbeat, backoff
  ├─ PairingManager        QR / code pairing, short authentication string
  ├─ CommandExecutor       validation → per-device policy (allow/ask/deny) → hardware
  ├─ Torch / Vibration / FindSound / Status / ScreenWake controllers
  ├─ DeviceService         connectedDevice foreground service (+ BootReceiver)
  └─ PeerStore, CommandHistory, NetworkMonitor
```

### Identity and trust

- Each installation generates an ECDSA P-256 key in the **Android Keystore**. The key never leaves the device. Its self-signed certificate's SHA-256 fingerprint is the device ID.
  - Why not Ed25519 as originally proposed? Ed25519 TLS certificates aren't reliably supported by Android's TLS stack and Keystore across API levels, while P-256 is hardware-backed everywhere.
- All traffic is **TLS 1.3** (falling back to 1.2 on old devices) with **client certificates on both sides**. Trust is pinning only: a phone accepts a certificate if its fingerprint is in the paired-device list.
- **Discovery is not trust.** A phone's server rejects unknown certificates during the TLS handshake. The exception is while its pairing screen is open, and even then the only thing an unknown device may do is send a `PAIR_REQUEST`.
- **Pairing:**
  - The QR code carries the target's fingerprint, address and a single-use 128-bit token that expires after 2 minutes. The controller connects pinned to that fingerprint.
  - Both phones then display a 6-digit code derived from both fingerprints. A man-in-the-middle would cause the two codes to differ.
  - The target's user must accept the request. Without a camera, pick the phone from the *Nearby* list; then the controller's user must also confirm that the codes match.
- **Permissions are per device and per capability:** *Allow*, *Ask* (a notification with Allow/Deny, plus an in-app prompt; times out after 30 s) or *Deny*.
  - Accepting a pairing grants the controller status, flashlight, ringing, vibration and screen wake.
  - The reverse direction defaults to status only.
  - Change these on the device's page under *What … may do on this phone*.
- **Forget device** removes the pairing locally and tells the other phone to remove it too. If that phone is offline, it will reject the next handshake and show *Needs re-pairing*.
- Paired-device data (public fingerprints and policies) and command history are stored as JSON in app-private storage. The only secret is the Keystore key.

### Protocol (v1)

Each frame is a 4-byte big-endian length followed by UTF-8 JSON, with a 64 KB maximum. Every request gets a response with the same `requestId`.

```ts
type DeviceCommand =
  | { type: 'GET_STATUS' }
  | { type: 'TORCH_SET'; enabled: boolean }
  | { type: 'PLAY_FIND_SOUND' } | { type: 'STOP_FIND_SOUND' }
  | { type: 'VIBRATE'; durationMs: number }      // 1..5000
  | { type: 'WAKE_SCREEN' };
// Internal: PING (heartbeat), PAIR_REQUEST, UNPAIR.

type CommandRequest  = { protocolVersion: 1; requestId: string; command: DeviceCommand };
type CommandResponse = { requestId: string; success: boolean; error?: ErrorCode; message?: string; data?: object };
```

Error codes: `INVALID`, `UNSUPPORTED_VERSION`, `UNPAIRED`, `DENIED`, `UNSUPPORTED`, `RATE_LIMITED` (token bucket: burst of 10, then 5 per second per connection), `TIMEOUT`, `HARDWARE_BUSY`, `UNREACHABLE`, `HANDSHAKE_FAILED`, `PAIRING_REJECTED`, `INTERNAL`. Arbitrary shell commands, intents and scripts are deliberately not part of the protocol.

`GET_STATUS` returns the phone's **capabilities**, computed at runtime (for example, `torch.control` only if it has a flash), so the UI disables controls that phone can't perform.

### Screen wake

Android doesn't let ordinary apps turn the display on from the background, so `WAKE_SCREEN` is **best effort and verified**:

1. If the screen is already on, it reports `already_awake`.
2. If the user has granted **Display over other apps** on the target, it launches a transparent activity with `setTurnScreenOn` / `setShowWhenLocked`. That permission is a documented exemption from Android's background-launch restrictions.
3. On Android 13 and below, it also tries the legacy `ACQUIRE_CAUSES_WAKEUP` wake lock.
4. It always posts a "Wake requested" notification that the user can tap. Full-screen intents are not used.
5. After 1.5 s it re-checks `PowerManager.isInteractive` and reports `confirmed_awake`, `requested` or `blocked`.

The controller shows the three steps: **Command received → Wake requested → Screen confirmed awake**. Expect results to vary by manufacturer and Android version. Guaranteed unattended wake would need fully managed (device owner) provisioning, which this app doesn't do.

### Background operation

*Allow remote control* starts a `connectedDevice` foreground service with a persistent notification that includes a Stop action. It keeps the server and mDNS advertisement alive with the screen off.

- It's only started from an explicit user action. If the user opts in, it also starts after reboot.
- It re-advertises when Wi-Fi reconnects.
- Battery optimisation and OEM task killers (Samsung, Xiaomi, OnePlus, Huawei…) can still stop it. **Settings** shows the relevant permission states and links to fix them.

## Troubleshooting

- **The other phone isn't listed under Nearby.** Turn on *Allow remote control* on that phone and check both phones are on the same Wi-Fi network (not a guest network). **Settings → Network** shows the Wi-Fi IP and discovery state.
- **The phone is visible but not responding, or connecting fails.** Many routers isolate wireless clients from each other ("AP/client isolation", common on guest networks). Turn that off or use another network.
- **Needs re-pairing.** The other phone forgot this one. Forget it here as well and pair again.
- **Screen wake only shows a notification.** On the target phone, open **Settings → Display over other apps** and allow it.

## Development

```bash
npm run typecheck                       # tsc --noEmit
npm run lint                            # expo lint
npm test                                # Jest: protocol helpers and store reducers
npx expo prebuild --platform android    # regenerate android/ (never edit it by hand)
cd android && ./gradlew :expo-lan-control:testDebugUnitTest   # Kotlin: framing, protocol validation,
                                                              # SAS, rate limiter, stores
```

`android/` is generated from `app.json` and the module, and is git-ignored. Native permissions and components are declared in [the module's manifest](modules/expo-lan-control/android/src/main/AndroidManifest.xml).

### Two-phone test checklist

1. Both phones discover each other. Turning off Wi-Fi on one marks it offline on the other.
2. QR pairing with code confirmation works, and so does code-only pairing from Nearby. Confirm an unpaired client is rejected at the handshake, e.g. `openssl s_client -connect <ip>:<port>` from a laptop (the port is shown in Settings).
3. Spamming a command eventually returns `RATE_LIMITED`.
4. Flashlight on/off, ring/stop, vibrate and status all work. On a phone without a flash, the flashlight controls are disabled.
5. Screen wake, both with and without *Display over other apps*, in each of these states: screen on, screen off, locked, app in background, and Doze (`adb shell dumpsys deviceidle force-idle`). Note the reported state each time.
6. With remote control on, kill the app process from Android Settings and check whether the service restarts. Then reboot with *Start after reboot* on.
7. Forgetting the pairing on either side stops commands. An *Ask* policy prompts, and the command fails with `TIMEOUT` if nobody answers within 30 s.

## Project layout

```
src/app/            screens (Expo Router): devices, device/[id], pair/{index,show,scan,connect}, history, settings
src/components/     UI kit, prompt host (incoming pairing and permission prompts)
src/services/       device, pairing and command services over the native module
src/stores/         zustand store and pure state transitions
src/types/          protocol types and helpers
modules/expo-lan-control/   Kotlin module (android/) and its TypeScript API (src/)
```

## License

GPL-3.0. See [LICENSE](LICENSE).
