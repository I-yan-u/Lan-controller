import { payloadFromParams } from '@/services/pairing-service';
import {
  commandSummary,
  describeError,
  parseStatus,
  parseWakeResult,
  wakeSteps,
} from '@/types/protocol';

jest.mock('@/services/lan-control', () => ({ __esModule: true, default: {}, errorMessage: String }));
jest.mock('@/services/device-service', () => ({ refreshPaired: jest.fn() }));

describe('parseWakeResult', () => {
  it('accepts valid results and rejects unknown states', () => {
    expect(parseWakeResult({ state: 'requested', methods: ['activity', 3], overlayPermission: true })).toEqual({
      state: 'requested',
      methods: ['activity'],
      overlayPermission: true,
      screenInteractive: false,
    });
    expect(parseWakeResult({ state: 'awake' })).toBeNull();
    expect(parseWakeResult(undefined)).toBeNull();
  });
});

describe('wakeSteps', () => {
  const r = (state: 'already_awake' | 'confirmed_awake' | 'requested' | 'blocked') =>
    parseWakeResult({ state, methods: [] });

  it('only claims confirmation when the target verified the screen', () => {
    expect(wakeSteps(r('confirmed_awake'), true)).toEqual(['received', 'requested', 'confirmed']);
    expect(wakeSteps(r('already_awake'), true)).toEqual(['received', 'requested', 'confirmed']);
    expect(wakeSteps(r('requested'), true)).toEqual(['received', 'requested']);
    expect(wakeSteps(r('blocked'), true)).toEqual(['received']);
    expect(wakeSteps(null, false)).toEqual([]);
  });
});

describe('parseStatus', () => {
  it('normalises a status payload', () => {
    const s = parseStatus({
      deviceId: 'abc',
      name: 'Pixel',
      battery: { level: 80, charging: true },
      screenInteractive: false,
      locked: true,
      torchOn: true,
      capabilities: ['status.read', 'torch.control'],
      androidSdk: 35,
    });
    expect(s?.battery).toEqual({ level: 80, charging: true });
    expect(s?.locked).toBe(true);
    expect(s?.capabilities).toEqual(['status.read', 'torch.control']);
    expect(parseStatus({ name: 'no id' })).toBeNull();
  });
});

describe('describeError', () => {
  it('prefers the specific message for DENIED and adds hints elsewhere', () => {
    expect(describeError({ error: 'DENIED', message: 'Pixel has not allowed it' })).toBe('Pixel has not allowed it');
    expect(describeError({ error: 'UNREACHABLE' })).toMatch(/isolate wireless clients/);
    expect(describeError({})).toBe('Unknown error');
  });
});

describe('commandSummary', () => {
  it('describes parameterised commands', () => {
    expect(commandSummary({ type: 'TORCH_SET', enabled: false })).toBe('Flashlight off');
    expect(commandSummary({ type: 'VIBRATE', durationMs: 500 })).toBe('Vibrate 500 ms');
  });
});

describe('payloadFromParams', () => {
  it('rebuilds a pairing link from deep-link params', () => {
    const p = payloadFromParams({ v: '1', id: 'ab', name: 'My Phone', host: '10.0.0.2', port: '4000', token: 'cd' });
    expect(p).toBe('lancontrol://pair?v=1&id=ab&name=My%20Phone&host=10.0.0.2&port=4000&token=cd');
    expect(payloadFromParams({ v: '1' })).toBeNull();
  });
});
