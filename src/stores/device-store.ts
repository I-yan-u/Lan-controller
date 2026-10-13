import { create } from 'zustand';

import type { DeviceCommand, CommandResponse, DeviceStatus, Identity, ServiceState } from '@/types/protocol';

import * as S from './device-state';

type Store = S.DeviceState & {
  identity: Identity | null;
  service: ServiceState | null;
  discoveryError: string | null;
  apply: (fn: (s: S.DeviceState) => S.DeviceState) => void;
  setIdentity: (identity: Identity) => void;
  setService: (service: ServiceState) => void;
  setDiscoveryError: (message: string | null) => void;
};

export const useDeviceStore = create<Store>((set) => ({
  ...S.initialDeviceState,
  identity: null,
  service: null,
  discoveryError: null,
  apply: (fn) => set((s) => fn(s)),
  setIdentity: (identity) => set({ identity }),
  setService: (service) => set({ service }),
  setDiscoveryError: (discoveryError) => set({ discoveryError }),
}));

export const deviceActions = {
  apply: (fn: (s: S.DeviceState) => S.DeviceState) => useDeviceStore.getState().apply(fn),
  commandStarted: (peerId: string, command: DeviceCommand) =>
    deviceActions.apply((s) => S.commandStarted(s, peerId, command, Date.now())),
  commandFinished: (peerId: string, command: DeviceCommand, response: CommandResponse, status: DeviceStatus | null) =>
    deviceActions.apply((s) => S.commandFinished(s, peerId, command, response, Date.now(), status)),
};
