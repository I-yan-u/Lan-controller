import type { Capability, Policy } from '@/types/protocol';

import { refreshPaired } from './device-service';
import LanControl from './lan-control';

export const openPairingSession = () => LanControl.openPairingSession();
export const closePairingSession = () => LanControl.closePairingSession();
export const respondToPairingRequest = (requestId: string, accept: boolean) =>
  LanControl.respondToPairingRequest(requestId, accept);

export const startPairingWithQr = (payload: string) => LanControl.startPairingWithQr(payload);
export const startPairingWithDevice = (deviceId: string) => LanControl.startPairingWithDevice(deviceId);
export const finishPairing = (sessionId: string, codesMatch: boolean) => LanControl.finishPairing(sessionId, codesMatch);

export async function forgetDevice(deviceId: string) {
  await LanControl.forgetDevice(deviceId);
  await refreshPaired();
}

export async function setPolicy(peerId: string, capability: Capability, policy: Policy) {
  await LanControl.setPolicy(peerId, capability, policy);
  await refreshPaired();
}

/** Rebuilds a pairing payload from deep-link params (lancontrol://pair?...) opened by the system camera. */
export function payloadFromParams(params: Record<string, string | string[] | undefined>): string | null {
  const keys = ['v', 'id', 'name', 'host', 'port', 'token'] as const;
  const values: Record<string, string> = {};
  for (const k of keys) {
    const v = params[k];
    const s = Array.isArray(v) ? v[0] : v;
    if (!s) return null;
    values[k] = s;
  }
  const query = keys.map((k) => `${k}=${encodeURIComponent(values[k])}`).join('&');
  return `lancontrol://pair?${query}`;
}
