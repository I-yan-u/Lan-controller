import { NativeModule, requireNativeModule } from 'expo';

import type {
  Capability,
  CommandResponse,
  DeviceCommand,
  Diagnostics,
  DiscoveredDevice,
  ExpoLanControlModuleEvents,
  HistoryEntry,
  Identity,
  OutgoingPairing,
  PairedDevice,
  PairingFinishResult,
  PairingSession,
  Policy,
  ServiceState,
  SettingsKind,
} from './ExpoLanControl.types';

declare class ExpoLanControlModule extends NativeModule<ExpoLanControlModuleEvents> {
  getIdentity(): Promise<Identity>;
  setDeviceName(name: string): Promise<void>;

  /** Turns the receiver (command server + foreground service) on or off. */
  setReceiverEnabled(enabled: boolean): Promise<ServiceState>;
  setStartOnBoot(enabled: boolean): Promise<ServiceState>;
  getServiceState(): Promise<ServiceState>;

  /** True while the UI is foregrounded: runs discovery and heartbeats paired devices. */
  setUiActive(active: boolean): Promise<void>;
  getDiscoveredDevices(): Promise<DiscoveredDevice[]>;
  getPairedDevices(): Promise<PairedDevice[]>;

  /** Target side: opens a 2-minute pairing window and returns the QR payload. */
  openPairingSession(): Promise<PairingSession>;
  closePairingSession(): Promise<void>;
  respondToPairingRequest(requestId: string, accept: boolean): Promise<boolean>;

  /** Controller side: connect and send a pairing request; returns the code to compare. */
  startPairingWithQr(payload: string): Promise<OutgoingPairing>;
  startPairingWithDevice(deviceId: string): Promise<OutgoingPairing>;
  finishPairing(sessionId: string, codesMatch: boolean): Promise<PairingFinishResult>;

  forgetDevice(deviceId: string): Promise<void>;
  setPolicy(peerId: string, capability: Capability, policy: Policy): Promise<void>;

  sendCommand(peerId: string, command: DeviceCommand): Promise<CommandResponse>;
  respondToPrompt(promptId: string, allow: boolean): Promise<boolean>;

  getHistory(): Promise<HistoryEntry[]>;
  clearHistory(): Promise<void>;
  getLocalStatus(): Promise<Record<string, unknown>>;
  getDiagnostics(): Promise<Diagnostics>;
  openSettings(kind: SettingsKind): Promise<void>;
}

export default requireNativeModule<ExpoLanControlModule>('ExpoLanControl');
