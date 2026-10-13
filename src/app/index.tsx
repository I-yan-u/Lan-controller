import { useRouter } from 'expo-router';
import { StyleSheet, View } from 'react-native';

import { ReachabilityBadge } from '@/components/device-badges';
import { ThemedText } from '@/components/themed-text';
import { Button, Card, Notice, Screen, Section, SwitchRow, timeAgo } from '@/components/ui';
import { Spacing } from '@/constants/theme';
import { useNow } from '@/hooks/use-now';
import { setReceiverEnabled } from '@/services/device-service';
import { errorMessage } from '@/services/lan-control';
import { useDeviceStore } from '@/stores/device-store';
import { useState } from 'react';

export default function DevicesScreen() {
  const router = useRouter();
  const now = useNow();
  const identity = useDeviceStore((s) => s.identity);
  const service = useDeviceStore((s) => s.service);
  const paired = useDeviceStore((s) => s.paired);
  const discovered = useDeviceStore((s) => s.discovered);
  const statuses = useDeviceStore((s) => s.statuses);
  const discoveryError = useDeviceStore((s) => s.discoveryError);
  const [receiverError, setReceiverError] = useState<string | null>(null);

  const pairedList = Object.values(paired).sort((a, b) => a.name.localeCompare(b.name));
  const nearby = Object.values(discovered)
    .filter((d) => !paired[d.id])
    .sort((a, b) => a.name.localeCompare(b.name));
  const visibleButSilent = pairedList.some((p) => p.visible && !p.reachable && p.trusted);

  const toggleReceiver = async (enabled: boolean) => {
    setReceiverError(null);
    try {
      await setReceiverEnabled(enabled);
    } catch (e) {
      setReceiverError(errorMessage(e));
    }
  };

  return (
    <Screen>
      <Card>
        <ThemedText type="smallBold" themeColor="textSecondary">
          THIS PHONE
        </ThemedText>
        <ThemedText type="default">
          {identity?.name ?? '…'}{' '}
          <ThemedText type="code" themeColor="textSecondary">
            {identity?.shortId}
          </ThemedText>
        </ThemedText>
        <SwitchRow
          label="Allow remote control"
          description={
            service?.receiverRunning
              ? 'Paired devices can send commands, even with the screen off.'
              : 'Off: this phone can control others but cannot be controlled.'
          }
          value={service?.receiverEnabled ?? false}
          onValueChange={toggleReceiver}
        />
        {receiverError ? <Notice tone="danger">{receiverError}</Notice> : null}
      </Card>

      <Section title="Paired devices">
        {pairedList.length === 0 ? (
          <Notice>No paired devices yet. Tap “Pair a device” to connect a phone on the same Wi-Fi.</Notice>
        ) : (
          pairedList.map((d) => {
            const status = statuses[d.id];
            return (
              <Card key={d.id} onPress={() => router.push({ pathname: '/device/[id]', params: { id: d.id } })}>
                <View style={styles.cardHeader}>
                  <ThemedText type="default" style={styles.flex} numberOfLines={1}>
                    {d.name}
                  </ThemedText>
                  <ReachabilityBadge device={d} />
                </View>
                <ThemedText type="small" themeColor="textSecondary">
                  Last seen {timeAgo(d.lastSeen, now)}
                  {status?.battery.level != null
                    ? ` · Battery ${status.battery.level}%${status.battery.charging ? ' (charging)' : ''}`
                    : ''}
                </ThemedText>
              </Card>
            );
          })
        )}
        {visibleButSilent ? (
          <Notice tone="warning">
            A paired phone is visible on the network but not answering. Make sure “Allow remote control” is on there.
            If it stays like this, your router may be isolating wireless clients from each other.
          </Notice>
        ) : null}
      </Section>

      <Section title="Nearby">
        {discoveryError ? <Notice tone="danger">{discoveryError}</Notice> : null}
        {nearby.length === 0 ? (
          <Notice>
            Searching the local network… Other phones appear here when they have “Allow remote control” turned on.
          </Notice>
        ) : (
          nearby.map((d) => (
            <Card key={d.id}>
              <View style={styles.cardHeader}>
                <View style={styles.flex}>
                  <ThemedText type="default" numberOfLines={1}>
                    {d.name}
                  </ThemedText>
                  <ThemedText type="small" themeColor="textSecondary">
                    {d.host} · {d.id.slice(0, 8)}
                  </ThemedText>
                </View>
                <Button
                  title="Pair"
                  kind="primary"
                  onPress={() => router.push({ pathname: '/pair/connect', params: { deviceId: d.id } })}
                />
              </View>
            </Card>
          ))
        )}
      </Section>

      <View style={styles.actions}>
        <Button title="Pair a device" kind="primary" onPress={() => router.push('/pair')} />
        <View style={styles.row}>
          <Button title="History" onPress={() => router.push('/history')} style={styles.flex} />
          <Button title="Settings" onPress={() => router.push('/settings')} style={styles.flex} />
        </View>
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  cardHeader: { flexDirection: 'row', alignItems: 'center', gap: Spacing.two },
  flex: { flex: 1 },
  actions: { gap: Spacing.two },
  row: { flexDirection: 'row', gap: Spacing.two },
});
