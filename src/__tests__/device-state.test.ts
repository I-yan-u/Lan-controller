import * as S from '@/stores/device-state';
import type { DiscoveredDevice, PairedDevice } from '@/types/protocol';

const discovered = (id: string): DiscoveredDevice => ({
  id,
  name: `Phone ${id}`,
  host: '10.0.0.2',
  port: 4000,
  paired: false,
  lastSeen: 1,
});

const paired = (id: string): PairedDevice => ({
  id,
  name: `Phone ${id}`,
  pairedAt: 1,
  policies: {},
  capabilities: [],
  lastHost: null,
  lastPort: null,
  lastSeen: null,
  reachable: false,
  trusted: true,
  visible: false,
});

describe('device state', () => {
  it('tracks visibility of paired devices through discovery', () => {
    let s = S.setPaired(S.initialDeviceState, [paired('a')]);
    s = S.deviceFound(s, discovered('a'));
    expect(s.paired.a.visible).toBe(true);
    expect(s.paired.a.lastHost).toBe('10.0.0.2');
    s = S.deviceLost(s, 'a');
    expect(s.discovered.a).toBeUndefined();
    expect(s.paired.a.visible).toBe(false);
  });

  it('marks discovered devices as paired and drops caches for forgotten devices', () => {
    let s = S.deviceFound(S.initialDeviceState, discovered('a'));
    s = S.setPaired(s, [paired('a')]);
    expect(s.discovered.a.paired).toBe(true);
    s = S.commandStarted(s, 'a', { type: 'GET_STATUS' }, 1);
    s = S.setPaired(s, []);
    expect(s.discovered.a.paired).toBe(false);
    expect(s.outcomes.a).toBeUndefined();
  });

  it('records command lifecycle and optimistic torch state', () => {
    let s = S.setPaired(S.initialDeviceState, [paired('a')]);
    const status = {
      deviceId: 'a',
      name: 'A',
      battery: { level: 50, charging: false },
      screenInteractive: true,
      locked: false,
      torchOn: false,
      findSoundPlaying: false,
      receiverRunning: true,
      capabilities: [],
      androidSdk: 35,
      manufacturer: 'x',
      model: 'y',
    };
    s = S.commandFinished(s, 'a', { type: 'GET_STATUS' }, { requestId: '1', success: true }, 1, status);
    const torch = { type: 'TORCH_SET', enabled: true } as const;
    s = S.commandStarted(s, 'a', torch, 2);
    expect(s.outcomes.a?.TORCH_SET?.pending).toBe(true);
    s = S.commandFinished(s, 'a', torch, { requestId: '2', success: true }, 3, null);
    expect(s.outcomes.a?.TORCH_SET?.pending).toBe(false);
    expect(s.statuses.a.torchOn).toBe(true);
  });

  it('ignores reachability for unknown devices and updates known ones', () => {
    let s = S.reachabilityChanged(S.initialDeviceState, { id: 'x', reachable: true, trusted: true, lastSeen: 5 });
    expect(s).toBe(S.initialDeviceState);
    s = S.setPaired(s, [paired('a')]);
    s = S.reachabilityChanged(s, { id: 'a', reachable: true, trusted: false, lastSeen: 5 });
    expect(s.paired.a).toMatchObject({ reachable: true, trusted: false, lastSeen: 5 });
  });

  it('dedupes and removes pending pairing requests and prompts', () => {
    const req = { requestId: 'r', peerId: 'p', name: 'n', sas: '123456', viaQr: true, alreadyPaired: false, expiresAt: 0 };
    let s = S.addPairingRequest(S.addPairingRequest(S.initialDeviceState, req), req);
    expect(s.pairingRequests).toHaveLength(1);
    s = S.removePairingRequest(s, 'r');
    expect(s.pairingRequests).toHaveLength(0);
    const prompt = {
      promptId: 'p1',
      peerId: 'p',
      peerName: 'n',
      command: 'WAKE_SCREEN' as const,
      capability: 'screen.wake.best_effort' as const,
      description: 'wake the screen',
      expiresAt: 0,
    };
    s = S.removePrompt(S.addPrompt(s, prompt), 'p1');
    expect(s.prompts).toHaveLength(0);
  });
});
