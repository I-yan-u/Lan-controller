import LanControl from '../../modules/expo-lan-control';

export default LanControl;

/** Extracts a readable message from a rejected native call (Expo CodedException). */
export function errorMessage(e: unknown): string {
  if (e && typeof e === 'object' && 'message' in e && typeof e.message === 'string') return e.message;
  return String(e);
}
