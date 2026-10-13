/** Wire protocol v1 types, mirrored from the Kotlin implementation (Protocol.kt). */

export type Capability =
  | 'status.read'
  | 'torch.control'
  | 'sound.play'
  | 'vibrate'
  | 'screen.wake.best_effort';

export const CAPABILITIES: readonly Capability[] = [
  'status.read',
  'torch.control',
  'sound.play',
  'vibrate',
  'screen.wake.best_effort',
];

export type Policy = 'allow' | 'ask' | 'deny';

/** Commands the UI may send to a paired device. */
export type DeviceCommand =
  | { type: 'GET_STATUS' }
  | { type: 'TORCH_SET'; enabled: boolean }
  | { type: 'PLAY_FIND_SOUND' }
  | { type: 'STOP_FIND_SOUND' }
  | { type: 'VIBRATE'; durationMs: number }
  | { type: 'WAKE_SCREEN' };

export type CommandType = DeviceCommand['type'];

export type ErrorCode =
  | 'INVALID'
  | 'UNSUPPORTED_VERSION'
  | 'UNPAIRED'
  | 'DENIED'
  | 'UNSUPPORTED'
  | 'RATE_LIMITED'
  | 'TIMEOUT'
  | 'HARDWARE_BUSY'
  | 'UNREACHABLE'
  | 'HANDSHAKE_FAILED'
  | 'PAIRING_REJECTED'
  | 'CANCELLED'
  | 'INTERNAL';

export type CommandResponse = {
  requestId: string;
  success: boolean;
  error?: ErrorCode;
  message?: string;
  data?: Record<string, unknown>;
};

export type WakeState = 'already_awake' | 'confirmed_awake' | 'requested' | 'blocked';

export type WakeResult = {
  state: WakeState;
  methods: string[];
  overlayPermission: boolean;
  screenInteractive: boolean;
};

export type DeviceStatus = {
  deviceId: string;
  name: string;
  battery: { level: number | null; charging: boolean };
  screenInteractive: boolean;
  locked: boolean;
  torchOn: boolean;
  findSoundPlaying: boolean;
  receiverRunning: boolean;
  capabilities: Capability[];
  androidSdk: number;
  manufacturer: string;
  model: string;
};

export type Identity = { deviceId: string; shortId: string; name: string };

export type DiscoveredDevice = {
  id: string;
  name: string;
  host: string;
  port: number;
  paired: boolean;
  lastSeen: number;
};

export type PairedDevice = {
  id: string;
  name: string;
  pairedAt: number;
  /** What this phone grants the other device. */
  policies: Partial<Record<Capability, Policy>>;
  /** What the other device reported it supports. */
  capabilities: Capability[];
  lastHost: string | null;
  lastPort: number | null;
  lastSeen: number | null;
  reachable: boolean;
  /** False when the device answered but no longer trusts this phone. */
  trusted: boolean;
  visible: boolean;
};

export type ServiceState = {
  receiverEnabled: boolean;
  receiverRunning: boolean;
  startOnBoot: boolean;
  port: number | null;
  advertising: boolean;
};

export type PairingSession = { payload: string; expiresAt: number; host: string; port: number };

export type IncomingPairingRequest = {
  requestId: string;
  peerId: string;
  name: string;
  sas: string;
  viaQr: boolean;
  alreadyPaired: boolean;
  expiresAt: number;
};

export type OutgoingPairing = {
  sessionId: string;
  peerId: string;
  peerName: string;
  sas: string;
  viaQr: boolean;
};

export type PairingFinishResult =
  | { success: true; peerId: string; name: string }
  | { success: false; error: ErrorCode | null; message: string | null };

export type PermissionPrompt = {
  promptId: string;
  peerId: string;
  peerName: string;
  command: CommandType;
  capability: Capability;
  description: string;
  expiresAt: number;
};

export type HistoryEntry = {
  timestamp: number;
  direction: 'in' | 'out' | 'event';
  peerId?: string;
  peerName?: string;
  command: string;
  success: boolean;
  error?: string;
  detail?: string;
};

export type Diagnostics = ServiceState & {
  notificationsEnabled: boolean;
  overlayPermission: boolean;
  ignoringBatteryOptimizations: boolean;
  localNetworkPermissionRequired: boolean;
  wifiConnected: boolean;
  wifiIp: string | null;
  discovering: boolean;
  multicastLockHeld: boolean;
  torchSupported: boolean;
  vibratorSupported: boolean;
  androidSdk: number;
  targetSdk: number;
  manufacturer: string;
  model: string;
};

export type SettingsKind = 'overlay' | 'battery' | 'notifications' | 'wifi' | 'app';

export type ExpoLanControlModuleEvents = {
  onDeviceFound: (device: DiscoveredDevice) => void;
  onDeviceLost: (event: { id: string }) => void;
  onDiscoveryError: (event: { message: string }) => void;
  onReachabilityChanged: (event: {
    id: string;
    reachable: boolean;
    trusted: boolean;
    lastSeen: number | null;
  }) => void;
  onPeersChanged: (event: Record<string, never>) => void;
  onPairingRequest: (request: IncomingPairingRequest) => void;
  onPairingRequestResolved: (event: { requestId: string; accepted: boolean }) => void;
  onPairingResult: (event: {
    role: 'target' | 'controller';
    success: boolean;
    peerId: string;
    name: string;
  }) => void;
  onCommandReceived: (event: {
    peerId: string;
    peerName: string;
    command: CommandType;
    success: boolean;
    error: string | null;
    detail: string | null;
  }) => void;
  onPermissionPrompt: (prompt: PermissionPrompt) => void;
  onPermissionPromptResolved: (event: { promptId: string; allowed: boolean | null }) => void;
  onServiceStateChanged: (state: ServiceState) => void;
};
