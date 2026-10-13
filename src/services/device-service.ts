import { AppState, PermissionsAndroid, Platform } from 'react-native';

import * as S from '@/stores/device-state';
import { useDeviceStore } from '@/stores/device-store';

import LanControl from './lan-control';

const apply = (fn: (s: S.DeviceState) => S.DeviceState) => useDeviceStore.getState().apply(fn);

export async function refreshPaired() {
  const list = await LanControl.getPairedDevices();
  apply((s) => S.setPaired(s, list));
}

export async function refreshService() {
  useDeviceStore.getState().setService(await LanControl.getServiceState());
}

/**
 * Wires native events into the store and keeps discovery/heartbeats running only while the app
 * is in the foreground. Returns a cleanup function.
 */
export function startDeviceService(): () => void {
  const store = useDeviceStore.getState();
  const subs = [
    LanControl.addListener('onDeviceFound', (d) => {
      store.setDiscoveryError(null);
      apply((s) => S.deviceFound(s, d));
    }),
    LanControl.addListener('onDeviceLost', ({ id }) => apply((s) => S.deviceLost(s, id))),
    LanControl.addListener('onDiscoveryError', ({ message }) => store.setDiscoveryError(message)),
    LanControl.addListener('onReachabilityChanged', (e) => apply((s) => S.reachabilityChanged(s, e))),
    LanControl.addListener('onPeersChanged', () => void refreshPaired()),
    LanControl.addListener('onPairingRequest', (r) => apply((s) => S.addPairingRequest(s, r))),
    LanControl.addListener('onPairingRequestResolved', ({ requestId }) =>
      apply((s) => S.removePairingRequest(s, requestId)),
    ),
    LanControl.addListener('onPermissionPrompt', (p) => apply((s) => S.addPrompt(s, p))),
    LanControl.addListener('onPermissionPromptResolved', ({ promptId }) => apply((s) => S.removePrompt(s, promptId))),
    LanControl.addListener('onServiceStateChanged', (state) => store.setService(state)),
  ];

  void (async () => {
    store.setIdentity(await LanControl.getIdentity());
    await Promise.all([refreshPaired(), refreshService()]);
    const discovered = await LanControl.getDiscoveredDevices();
    discovered.forEach((d) => apply((s) => S.deviceFound(s, d)));
  })();

  let active = AppState.currentState === 'active';
  void LanControl.setUiActive(active);
  const appState = AppState.addEventListener('change', (next) => {
    const nowActive = next === 'active';
    if (nowActive === active) return;
    active = nowActive;
    void LanControl.setUiActive(active);
    if (active) void Promise.all([refreshPaired(), refreshService()]);
  });

  return () => {
    subs.forEach((s) => s.remove());
    appState.remove();
    void LanControl.setUiActive(false);
  };
}

/** Android 13+ needs runtime consent for the service notification and remote prompts. */
export async function ensureNotificationPermission(): Promise<boolean> {
  if (Platform.OS !== 'android' || Platform.Version < 33) return true;
  const perm = PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS;
  if (await PermissionsAndroid.check(perm)) return true;
  return (await PermissionsAndroid.request(perm)) === PermissionsAndroid.RESULTS.GRANTED;
}

export async function setReceiverEnabled(enabled: boolean) {
  if (enabled) await ensureNotificationPermission();
  useDeviceStore.getState().setService(await LanControl.setReceiverEnabled(enabled));
}

export async function setDeviceName(name: string) {
  await LanControl.setDeviceName(name);
  useDeviceStore.getState().setIdentity(await LanControl.getIdentity());
}
