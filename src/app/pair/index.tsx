import { Redirect, useLocalSearchParams, useRouter } from 'expo-router';
import { StyleSheet, View } from 'react-native';

import { ThemedText } from '@/components/themed-text';
import { Button, Card, Notice, Screen, Section } from '@/components/ui';
import { Spacing } from '@/constants/theme';
import { payloadFromParams } from '@/services/pairing-service';
import { useDeviceStore } from '@/stores/device-store';

export default function PairScreen() {
  const router = useRouter();
  const params = useLocalSearchParams();
  const discovered = useDeviceStore((s) => s.discovered);
  const paired = useDeviceStore((s) => s.paired);

  // Opened via a lancontrol://pair?... link (e.g. the QR code scanned with the system camera).
  const deepLinkPayload = payloadFromParams(params);
  if (deepLinkPayload) return <Redirect href={{ pathname: '/pair/connect', params: { qr: deepLinkPayload } }} />;

  const nearby = Object.values(discovered).filter((d) => !paired[d.id]);

  return (
    <Screen>
      <Card>
        <ThemedText type="smallBold">Control another phone</ThemedText>
        <ThemedText type="small" themeColor="textSecondary">
          On the other phone, open Pair a device → Show pairing code, then scan it here.
        </ThemedText>
        <Button title="Scan pairing code" kind="primary" onPress={() => router.push('/pair/scan')} />
      </Card>

      <Card>
        <ThemedText type="smallBold">Let another phone control this one</ThemedText>
        <ThemedText type="small" themeColor="textSecondary">
          Shows a QR code for the other phone to scan. This turns on remote control for this phone.
        </ThemedText>
        <Button title="Show pairing code" onPress={() => router.push('/pair/show')} />
      </Card>

      <Section title="Or pair without a camera">
        <Notice>
          Pick a phone below. Its pairing screen must be open (Show pairing code). Both phones will show a 6-digit code;
          only continue if they match.
        </Notice>
        {nearby.map((d) => (
          <Card key={d.id} onPress={() => router.push({ pathname: '/pair/connect', params: { deviceId: d.id } })}>
            <View style={styles.row}>
              <ThemedText type="default" style={styles.flex} numberOfLines={1}>
                {d.name}
              </ThemedText>
              <ThemedText type="small" themeColor="textSecondary">
                {d.host}
              </ThemedText>
            </View>
          </Card>
        ))}
        {nearby.length === 0 ? (
          <ThemedText type="small" themeColor="textSecondary">
            No unpaired phones found on this network yet.
          </ThemedText>
        ) : null}
      </Section>
    </Screen>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', gap: Spacing.two },
  flex: { flex: 1 },
});
