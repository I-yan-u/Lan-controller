import { useLocalSearchParams, useRouter } from 'expo-router';
import { useEffect, useRef, useState } from 'react';
import { ActivityIndicator, StyleSheet, View } from 'react-native';

import { ThemedText } from '@/components/themed-text';
import { Button, Card, Notice, Screen } from '@/components/ui';
import { Spacing } from '@/constants/theme';
import { errorMessage } from '@/services/lan-control';
import { finishPairing, startPairingWithDevice, startPairingWithQr } from '@/services/pairing-service';
import { describeError, type OutgoingPairing, type PairingFinishResult } from '@/types/protocol';

type Phase =
  | { kind: 'connecting' }
  | { kind: 'compare'; session: OutgoingPairing }
  | { kind: 'waiting'; session: OutgoingPairing }
  | { kind: 'done'; result: PairingFinishResult; session: OutgoingPairing | null }
  | { kind: 'error'; message: string };

export default function ConnectScreen() {
  const { deviceId, qr } = useLocalSearchParams<{ deviceId?: string; qr?: string }>();
  const router = useRouter();
  const [phase, setPhase] = useState<Phase>({ kind: 'connecting' });
  const open = useRef<string | null>(null);

  const finish = async (session: OutgoingPairing, codesMatch: boolean) => {
    if (codesMatch) setPhase({ kind: 'waiting', session });
    open.current = null;
    const result = await finishPairing(session.sessionId, codesMatch).catch(
      (e): PairingFinishResult => ({ success: false, error: 'INTERNAL', message: errorMessage(e) }),
    );
    setPhase({ kind: 'done', result, session });
  };

  useEffect(() => {
    let cancelled = false;
    const start = qr ? startPairingWithQr(qr) : deviceId ? startPairingWithDevice(deviceId) : null;
    if (!start) return;
    start
      .then((session) => {
        if (cancelled) {
          void finishPairing(session.sessionId, false);
          return;
        }
        open.current = session.sessionId;
        if (session.viaQr) {
          // The QR code already pinned the other phone's identity; just wait for it to accept.
          void finish(session, true);
        } else {
          setPhase({ kind: 'compare', session });
        }
      })
      .catch((e) => !cancelled && setPhase({ kind: 'error', message: errorMessage(e) }));
    return () => {
      cancelled = true;
      if (open.current) void finishPairing(open.current, false);
    };
  }, [deviceId, qr]);

  if (!qr && !deviceId) {
    return (
      <Screen>
        <Notice tone="danger">Nothing to pair with.</Notice>
        <Button title="Back" onPress={() => router.back()} />
      </Screen>
    );
  }

  return (
    <Screen>
      {phase.kind === 'connecting' ? (
        <Card style={styles.center}>
          <ActivityIndicator />
          <ThemedText type="small" themeColor="textSecondary">
            Connecting securely…
          </ThemedText>
        </Card>
      ) : null}

      {phase.kind === 'compare' || phase.kind === 'waiting' ? (
        <Card style={styles.center}>
          <ThemedText type="default">Pairing with {phase.session.peerName}</ThemedText>
          <Code sas={phase.session.sas} />
          {phase.kind === 'compare' ? (
            <>
              <ThemedText type="small" themeColor="textSecondary" style={styles.text}>
                Does {phase.session.peerName} show exactly this code? If not, cancel. Someone may be impersonating it.
              </ThemedText>
              <View style={styles.row}>
                <Button title="Cancel" onPress={() => void finish(phase.session, false)} style={styles.flex} />
                <Button
                  title="Codes match"
                  kind="primary"
                  onPress={() => void finish(phase.session, true)}
                  style={styles.flex}
                />
              </View>
            </>
          ) : (
            <View style={styles.row}>
              <ActivityIndicator />
              <ThemedText type="small" themeColor="textSecondary" style={styles.flex}>
                Accept the request on {phase.session.peerName}. It should show the same code.
              </ThemedText>
            </View>
          )}
        </Card>
      ) : null}

      {phase.kind === 'done' ? (
        phase.result.success ? (
          <>
            <Notice tone="success">
              Paired with {phase.result.name}. You can now control it from this phone.
            </Notice>
            <Button
              title={`Open ${phase.result.name}`}
              kind="primary"
              onPress={() =>
                phase.result.success &&
                router.replace({ pathname: '/device/[id]', params: { id: phase.result.peerId } })
              }
            />
          </>
        ) : (
          <>
            <Notice tone={phase.result.error === 'CANCELLED' ? 'neutral' : 'danger'}>
              {describeError({ error: phase.result.error ?? undefined, message: phase.result.message ?? undefined })}
            </Notice>
            <Button title="Back" onPress={() => router.back()} />
          </>
        )
      ) : null}

      {phase.kind === 'error' ? (
        <>
          <Notice tone="danger">{phase.message}</Notice>
          <Notice>
            Make sure both phones are on the same Wi-Fi and the other phone has its pairing screen open. Some routers
            isolate wireless clients from each other.
          </Notice>
          <Button title="Back" onPress={() => router.back()} />
        </>
      ) : null}
    </Screen>
  );
}

function Code({ sas }: { sas: string }) {
  return (
    <ThemedText style={styles.code} accessibilityLabel={`Code ${sas.split('').join(' ')}`}>
      {sas.slice(0, 3)} {sas.slice(3)}
    </ThemedText>
  );
}

const styles = StyleSheet.create({
  center: { alignItems: 'center', gap: Spacing.three },
  text: { textAlign: 'center' },
  row: { flexDirection: 'row', alignItems: 'center', gap: Spacing.two, alignSelf: 'stretch' },
  flex: { flex: 1 },
  code: { fontSize: 40, lineHeight: 48, fontWeight: 700, letterSpacing: 4, fontVariant: ['tabular-nums'] },
});
