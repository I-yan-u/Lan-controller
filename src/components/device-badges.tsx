import { Badge } from '@/components/ui';
import type { PairedDevice } from '@/types/protocol';

export function ReachabilityBadge({ device }: { device: PairedDevice }) {
  if (!device.trusted) return <Badge tone="danger" label="Needs re-pairing" />;
  if (device.reachable) return <Badge tone="success" label="Reachable" />;
  if (device.visible) return <Badge tone="warning" label="Visible, not responding" />;
  return <Badge tone="neutral" label="Offline" />;
}
