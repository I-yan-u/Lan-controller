import { Modal, StyleSheet, View } from 'react-native';

import { ThemedText } from '@/components/themed-text';
import { Button, Card } from '@/components/ui';
import { Spacing } from '@/constants/theme';
import { useTheme } from '@/hooks/use-theme';
import { respondToPrompt } from '@/services/command-service';
import { respondToPairingRequest } from '@/services/pairing-service';
import * as S from '@/stores/device-state';
import { useDeviceStore } from '@/stores/device-store';

/**
 * Shows decisions this phone's user must make, wherever they are in the app: incoming pairing
 * requests and "ask" permission prompts. Prompts are also posted as notifications natively.
 */
export function PromptHost() {
  const theme = useTheme();
  const request = useDeviceStore((s) => s.pairingRequests[0]);
  const prompt = useDeviceStore((s) => s.prompts[0]);
  const apply = useDeviceStore((s) => s.apply);

  if (!request && !prompt) return null;

  const answerPairing = (accept: boolean) => {
    if (!request) return;
    apply((s) => S.removePairingRequest(s, request.requestId));
    void respondToPairingRequest(request.requestId, accept);
  };
  const answerPrompt = (allow: boolean) => {
    if (!prompt) return;
    apply((s) => S.removePrompt(s, prompt.promptId));
    void respondToPrompt(prompt.promptId, allow);
  };

  return (
    <Modal transparent animationType="fade" visible statusBarTranslucent onRequestClose={() => undefined}>
      <View style={styles.backdrop}>
        <Card style={[styles.card, { backgroundColor: theme.background, borderColor: theme.border }]}>
          {request ? (
            <>
              <ThemedText type="smallBold" themeColor="textSecondary">
                PAIRING REQUEST
              </ThemedText>
              <ThemedText type="default">
                <ThemedText type="smallBold">{request.name}</ThemedText> wants to control this phone.
              </ThemedText>
              <ThemedText type="small" themeColor="textSecondary">
                Check that the other phone shows the same code:
              </ThemedText>
              <ThemedText style={styles.code} accessibilityLabel={`Code ${request.sas.split('').join(' ')}`}>
                {request.sas.slice(0, 3)} {request.sas.slice(3)}
              </ThemedText>
              <ThemedText type="small" themeColor="textSecondary">
                {request.viaQr
                  ? 'It scanned this phone’s QR code.'
                  : 'It found this phone on the network. Only accept if the codes match.'}{' '}
                Accepting allows status, flashlight, ringing, vibration and screen wake. You can limit this later
                from its page under Devices.
              </ThemedText>
              <View style={styles.actions}>
                <Button title="Decline" onPress={() => answerPairing(false)} style={styles.flex} />
                <Button title="Accept" kind="primary" onPress={() => answerPairing(true)} style={styles.flex} />
              </View>
            </>
          ) : prompt ? (
            <>
              <ThemedText type="smallBold" themeColor="textSecondary">
                PERMISSION REQUEST
              </ThemedText>
              <ThemedText type="default">
                <ThemedText type="smallBold">{prompt.peerName}</ThemedText> wants to {prompt.description}.
              </ThemedText>
              <View style={styles.actions}>
                <Button title="Deny" onPress={() => answerPrompt(false)} style={styles.flex} />
                <Button title="Allow" kind="primary" onPress={() => answerPrompt(true)} style={styles.flex} />
              </View>
            </>
          ) : null}
        </Card>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  backdrop: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.5)',
    justifyContent: 'center',
    padding: Spacing.four,
  },
  card: { gap: Spacing.three, borderWidth: StyleSheet.hairlineWidth, padding: Spacing.four },
  code: { fontSize: 40, lineHeight: 48, fontWeight: 700, letterSpacing: 4, textAlign: 'center', fontVariant: ['tabular-nums'] },
  actions: { flexDirection: 'row', gap: Spacing.two },
  flex: { flex: 1 },
});
