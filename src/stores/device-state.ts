import type {
  CommandResponse,
  DeviceCommand,
  DeviceStatus,
  DiscoveredDevice,
  IncomingPairingRequest,
  PairedDevice,
  PermissionPrompt,
} from '@/types/protocol';

/** Pure state transitions, kept separate from zustand so they can be unit tested. */

export type CommandOutcome = {
  command: DeviceCommand;
  pending: boolean;
  response?: CommandResponse;
  at: number;
};

export type DeviceState = {
  discovered: Record<string, DiscoveredDevice>;
  paired: Record<string, PairedDevice>;
  statuses: Record<string, DeviceStatus>;
  /** Last outcome per device per command type. */
  outcomes: Record<string, Partial<Record<DeviceCommand['type'], CommandOutcome>>>;
  pairingRequests: IncomingPairingRequest[];
  prompts: PermissionPrompt[];
};

export const initialDeviceState: DeviceState = {
  discovered: {},
  paired: {},
  statuses: {},
  outcomes: {},
  pairingRequests: [],
  prompts: [],
};

export function deviceFound(s: DeviceState, d: DiscoveredDevice): DeviceState {
  const discovered = { ...s.discovered, [d.id]: d };
  const peer = s.paired[d.id];
  const paired = peer ? { ...s.paired, [d.id]: { ...peer, visible: true, lastHost: d.host, lastPort: d.port } } : s.paired;
  return { ...s, discovered, paired };
}

export function deviceLost(s: DeviceState, id: string): DeviceState {
  if (!s.discovered[id]) return s;
  const { [id]: _removed, ...discovered } = s.discovered;
  const peer = s.paired[id];
  const paired = peer ? { ...s.paired, [id]: { ...peer, visible: false } } : s.paired;
  return { ...s, discovered, paired };
}

export function setPaired(s: DeviceState, list: PairedDevice[]): DeviceState {
  const paired = Object.fromEntries(list.map((p) => [p.id, p]));
  // Keep cached status/outcomes only for devices that are still paired.
  const keep = <T,>(rec: Record<string, T>) => Object.fromEntries(Object.entries(rec).filter(([id]) => id in paired));
  const discovered = Object.fromEntries(
    Object.entries(s.discovered).map(([id, d]) => [id, { ...d, paired: id in paired }]),
  );
  return { ...s, paired, discovered, statuses: keep(s.statuses), outcomes: keep(s.outcomes) };
}

export function reachabilityChanged(
  s: DeviceState,
  e: { id: string; reachable: boolean; trusted: boolean; lastSeen: number | null },
): DeviceState {
  const peer = s.paired[e.id];
  if (!peer) return s;
  return {
    ...s,
    paired: { ...s.paired, [e.id]: { ...peer, reachable: e.reachable, trusted: e.trusted, lastSeen: e.lastSeen ?? peer.lastSeen } },
  };
}

export function commandStarted(s: DeviceState, peerId: string, command: DeviceCommand, at: number): DeviceState {
  const forPeer = { ...s.outcomes[peerId], [command.type]: { command, pending: true, at } };
  return { ...s, outcomes: { ...s.outcomes, [peerId]: forPeer } };
}

export function commandFinished(
  s: DeviceState,
  peerId: string,
  command: DeviceCommand,
  response: CommandResponse,
  at: number,
  status: DeviceStatus | null,
): DeviceState {
  const forPeer = { ...s.outcomes[peerId], [command.type]: { command, pending: false, response, at } };
  let statuses = s.statuses;
  if (status) {
    statuses = { ...statuses, [peerId]: status };
  } else if (response.success && statuses[peerId] && command.type === 'TORCH_SET') {
    statuses = { ...statuses, [peerId]: { ...statuses[peerId], torchOn: command.enabled } };
  }
  return { ...s, outcomes: { ...s.outcomes, [peerId]: forPeer }, statuses };
}

export function addPairingRequest(s: DeviceState, r: IncomingPairingRequest): DeviceState {
  return { ...s, pairingRequests: [...s.pairingRequests.filter((x) => x.requestId !== r.requestId), r] };
}

export function removePairingRequest(s: DeviceState, requestId: string): DeviceState {
  return { ...s, pairingRequests: s.pairingRequests.filter((x) => x.requestId !== requestId) };
}

export function addPrompt(s: DeviceState, p: PermissionPrompt): DeviceState {
  return { ...s, prompts: [...s.prompts.filter((x) => x.promptId !== p.promptId), p] };
}

export function removePrompt(s: DeviceState, promptId: string): DeviceState {
  return { ...s, prompts: s.prompts.filter((x) => x.promptId !== promptId) };
}
