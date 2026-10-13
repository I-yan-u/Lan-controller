import { useRouter } from 'expo-router';
import { useEffect, useState } from 'react';
import { StyleSheet, View } from 'react-native';
import QRCode from 'react-native-qrcode-svg';

import { ThemedText } from '@/components/themed-text';
import { Button, Card, Notice, Screen } from '@/components/ui';
import { Spacing } from '@/constants/theme';
import { useNow } from '@/hooks/use-now';
import { setReceiverEnabled } from '@/services/device-service';
import LanControl, { errorMessage } from '@/services/lan-control';
import { closePairingSession, openPairingSession } from '@/services/pairing-service';
import { useDeviceStore } from '@/stores/device-store';
import type { PairingSession } from '@/types/protocol';

/** Refresh a little before the native 2-minute expiry so the QR code shown is always valid. */
const REFRESH_MS = 100_000;

export default function ShowPairingCodeScreen() {
  const router = useRouter();
  const now = useNow(1000);
  const identity = useDeviceStore((s) => s.identity);
  const running = useDeviceStore((s) => s.service?.receiverRunning ?? false);
  const [session, setSession] = useState<PairingSession | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [paired, setPaired] = useState<{ name: string; peerId: string } | null>(null);
  const [generation, setGeneration] = useState(0);

  // Remote control must be on: the server is what the other phone connects to.
  useEffect(() => {
    if (running) return;
    setReceiverEnabled(true).catch((e) => setError(errorMessage(e)));
  }, [running]);
  const starting = !running && !error;

  useEffect(() => {
    if (!running) return;
    let cancelled = false;
    openPairingSession()
      .then((s) => {
        if (!cancelled) {
          setSession(s);
          setError(null);
        }
      })
      .catch((e) => !cancelled && setError(errorMessage(e)));
    const t = setTimeout(() => setGeneration((g) => g + 1), REFRESH_MS);
    return () => {
      cancelled = true;
      clearTimeout(t);
    };
  }, [running, generation]);

  useEffect(() => {
    const sub = LanControl.addListener('onPairingResult', (e) => {
      if (e.role !== 'target') return;
      if (e.success) {
        setPaired({ name: e.name, peerId: e.peerId });
        // The QR token is single use; show a fresh one in case another phone wants to pair.
        setGeneration((g) => g + 1);
      }
    });
    return () => {
      sub.remove();
      void closePairingSession();
    };
  }, []);

  const secondsLeft = session ? Math.max(0, Math.round((session.expiresAt - now) / 1000)) : 0;

  return (
    <Screen>
      {paired ? (
        <Notice tone="success">
          Paired with {paired.name}. It can now control this phone. Adjust what it may do from its page under Devices.
        </Notice>
      ) : null}
      {error ? <Notice tone="danger">{error}</Notice> : null}

      <Card style={styles.center}>
        <ThemedText type="default">{identity?.name}</ThemedText>
        {session ? (
          <>
            <View style={styles.qr}>
              <QRCode value={session.payload} size={240} backgroundColor="#ffffff" color="#000000" />
            </View>
            <ThemedText type="small" themeColor="textSecondary">
              {session.host}:{session.port} · code refreshes in {secondsLeft}s
            </ThemedText>
          </>
        ) : (
          <ThemedText type="small" themeColor="textSecondary">
            {starting ? 'Turning on remote control…' : 'Preparing pairing code…'}
          </ThemedText>
        )}
      </Card>

      <Notice>
        On the other phone choose Pair a device → Scan pairing code. You will be asked to accept here and to check that
        both phones show the same 6-digit code. Keep this screen open while pairing.
      </Notice>

      <Button title="Done" onPress={() => router.back()} />
    </Screen>
  );
}

const styles = StyleSheet.create({
  center: { alignItems: 'center', gap: Spacing.three },
  qr: { padding: Spacing.three, backgroundColor: '#ffffff', borderRadius: Spacing.two },
});
