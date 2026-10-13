import type {
  Capability,
  CommandResponse,
  CommandType,
  DeviceCommand,
  DeviceStatus,
  WakeResult,
  WakeState,
} from '../../modules/expo-lan-control';

export * from '../../modules/expo-lan-control/src/ExpoLanControl.types';

export const COMMAND_CAPABILITY: Record<CommandType, Capability> = {
  GET_STATUS: 'status.read',
  TORCH_SET: 'torch.control',
  PLAY_FIND_SOUND: 'sound.play',
  STOP_FIND_SOUND: 'sound.play',
  VIBRATE: 'vibrate',
  WAKE_SCREEN: 'screen.wake.best_effort',
};

export const CAPABILITY_LABEL: Record<Capability, string> = {
  'status.read': 'Read status',
  'torch.control': 'Flashlight',
  'sound.play': 'Ring (find phone)',
  vibrate: 'Vibrate',
  'screen.wake.best_effort': 'Wake screen',
};

export const COMMAND_LABEL: Record<string, string> = {
  GET_STATUS: 'Status',
  TORCH_SET: 'Flashlight',
  PLAY_FIND_SOUND: 'Ring',
  STOP_FIND_SOUND: 'Stop ringing',
  VIBRATE: 'Vibrate',
  WAKE_SCREEN: 'Wake screen',
  PING: 'Ping',
  UNPAIR: 'Unpair',
  PAIRING: 'Pairing',
  SERVICE: 'Service',
};

const ERROR_HINT: Record<string, string> = {
  DENIED: 'The other phone has not allowed this. Change it in that phone’s device settings.',
  UNSUPPORTED: 'The other phone does not support this.',
  UNPAIRED: 'The other phone no longer recognises this one. Pair again.',
  HANDSHAKE_FAILED: 'The other phone rejected this one. It may have forgotten the pairing; pair again.',
  UNREACHABLE:
    'Could not reach the phone. Check it is on the same Wi-Fi with remote control on. Some routers isolate wireless clients.',
  TIMEOUT: 'No answer in time.',
  RATE_LIMITED: 'Too many commands. Wait a moment.',
  HARDWARE_BUSY: 'The hardware is busy (for example, the camera is in use).',
  INVALID: 'The other phone rejected the request as invalid.',
  UNSUPPORTED_VERSION: 'The two phones run incompatible app versions.',
  PAIRING_REJECTED: 'Pairing was declined or the code expired.',
  CANCELLED: 'Cancelled.',
  INTERNAL: 'Something went wrong on the other phone.',
};

export function describeError(response: Pick<CommandResponse, 'error' | 'message'>): string {
  const hint = response.error ? ERROR_HINT[response.error] : undefined;
  if (response.message && hint && response.error !== 'DENIED') return `${hint} (${response.message})`;
  return response.message ?? hint ?? 'Unknown error';
}

export function isWakeState(v: unknown): v is WakeState {
  return v === 'already_awake' || v === 'confirmed_awake' || v === 'requested' || v === 'blocked';
}

export function parseWakeResult(data: Record<string, unknown> | undefined): WakeResult | null {
  if (!data || !isWakeState(data.state)) return null;
  return {
    state: data.state,
    methods: Array.isArray(data.methods) ? data.methods.filter((m): m is string => typeof m === 'string') : [],
    overlayPermission: data.overlayPermission === true,
    screenInteractive: data.screenInteractive === true,
  };
}

export function parseStatus(data: Record<string, unknown> | undefined): DeviceStatus | null {
  if (!data || typeof data.deviceId !== 'string') return null;
  const battery = (data.battery ?? {}) as Record<string, unknown>;
  return {
    deviceId: data.deviceId,
    name: String(data.name ?? ''),
    battery: {
      level: typeof battery.level === 'number' ? battery.level : null,
      charging: battery.charging === true,
    },
    screenInteractive: data.screenInteractive === true,
    locked: data.locked === true,
    torchOn: data.torchOn === true,
    findSoundPlaying: data.findSoundPlaying === true,
    receiverRunning: data.receiverRunning === true,
    capabilities: Array.isArray(data.capabilities) ? (data.capabilities as Capability[]) : [],
    androidSdk: Number(data.androidSdk ?? 0),
    manufacturer: String(data.manufacturer ?? ''),
    model: String(data.model ?? ''),
  };
}

/** The three wake states the UI distinguishes (received → requested → confirmed). */
export type WakeStep = 'received' | 'requested' | 'confirmed';

export function wakeSteps(result: WakeResult | null, success: boolean): WakeStep[] {
  if (!success) return [];
  if (!result) return ['received'];
  switch (result.state) {
    case 'already_awake':
    case 'confirmed_awake':
      return ['received', 'requested', 'confirmed'];
    case 'requested':
      return ['received', 'requested'];
    case 'blocked':
      return ['received'];
  }
}

export function commandSummary(command: DeviceCommand): string {
  switch (command.type) {
    case 'TORCH_SET':
      return command.enabled ? 'Flashlight on' : 'Flashlight off';
    case 'VIBRATE':
      return `Vibrate ${command.durationMs} ms`;
    default:
      return COMMAND_LABEL[command.type] ?? command.type;
  }
}
