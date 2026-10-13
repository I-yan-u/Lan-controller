import { deviceActions } from '@/stores/device-store';
import { parseStatus, type CommandResponse, type DeviceCommand } from '@/types/protocol';

import { refreshPaired } from './device-service';
import LanControl, { errorMessage } from './lan-control';

export async function sendCommand(peerId: string, command: DeviceCommand): Promise<CommandResponse> {
  deviceActions.commandStarted(peerId, command);
  let response: CommandResponse;
  try {
    response = await LanControl.sendCommand(peerId, command);
  } catch (e) {
    response = { requestId: 'local', success: false, error: 'INTERNAL', message: errorMessage(e) };
  }
  const status = command.type === 'GET_STATUS' && response.success ? parseStatus(response.data) : null;
  deviceActions.commandFinished(peerId, command, response, status);
  // GET_STATUS refreshes the peer's reported capabilities natively.
  if (status) void refreshPaired();
  return response;
}

export const respondToPrompt = (promptId: string, allow: boolean) => LanControl.respondToPrompt(promptId, allow);
