import { Stack, useLocalSearchParams, useRouter } from 'expo-router';
import { useEffect } from 'react';
import { ActivityIndicator, Alert, StyleSheet, View } from 'react-native';

import { ReachabilityBadge } from '@/components/device-badges';
import { ThemedText } from '@/components/themed-text';
import { Button, Card, Notice, Row, Screen, Section, Segmented, timeAgo } from '@/components/ui';
import { Spacing } from '@/constants/theme';
import { useNow } from '@/hooks/use-now';
import { useTheme } from '@/hooks/use-theme';
import { sendCommand } from '@/services/command-service';
import { forgetDevice, setPolicy } from '@/services/pairing-service';
import type { CommandOutcome } from '@/stores/device-state';
import { useDeviceStore } from '@/stores/device-store';
import {
  CAPABILITIES,
  CAPABILITY_LABEL,
  describeError,
  parseWakeResult,
  wakeSteps,
  type Capability,
  type DeviceCommand,
  type Policy,
  type WakeStep,
} from '@/types/protocol';

const POLICIES = ['allow', 'ask', 'deny'] as const satisfies readonly Policy[];
const POLICY_LABELS: Record<Policy, string> = { allow: 'Allow', ask: 'Ask', deny: 'Deny' };

export default function DeviceScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const router = useRouter();
  const now = useNow();
  const device = useDeviceStore((s) => s.paired[id]);
  const status = useDeviceStore((s) => s.statuses[id]);
  const outcomes = useDeviceStore((s) => s.outcomes[id]);
  const receiverEnabled = useDeviceStore((s) => s.service?.receiverEnabled ?? false);
  const deviceKnown = !!device;

  useEffect(() => {
    if (deviceKnown) void sendCommand(id, { type: 'GET_STATUS' });
  }, [id, deviceKnown]);

  if (!device) {
    return (
      <Screen>
        <Notice>This device is no longer paired.</Notice>
        <Button title="Back to devices" onPress={() => router.replace('/')} />
      </Screen>
    );
  }

  // Before the first status arrives we don't know the capabilities; allow everything and let the
  // other phone answer UNSUPPORTED if needed.
  const caps = status?.capabilities ?? device.capabilities;
  const supports = (c: Capability) => caps.length === 0 || caps.includes(c);
  const send = (command: DeviceCommand) => void sendCommand(id, command);
  const pending = (type: DeviceCommand['type']) => outcomes?.[type]?.pending === true;

  const confirmForget = () =>
    Alert.alert(
      `Forget ${device.name}?`,
      'Both phones will stop trusting each other. You will need to pair again to send commands.',
      [
        { text: 'Cancel', style: 'cancel' },
        {
          text: 'Forget',
          style: 'destructive',
          onPress: async () => {
            await forgetDevice(id);
            router.replace('/');
          },
        },
      ],
    );

  const statusOutcome = outcomes?.GET_STATUS;

  return (
    <Screen>
      <Stack.Screen options={{ title: device.name }} />

      <Card>
        <View style={styles.header}>
          <ThemedText type="default" style={styles.flex} numberOfLines={1}>
            {device.name}
          </ThemedText>
          <ReachabilityBadge device={device} />
        </View>
        <Row label="Last seen" value={timeAgo(device.lastSeen, now)} />
        <Row label="Address" value={device.lastHost ? `${device.lastHost}:${device.lastPort}` : 'unknown'} />
        <Row label="Device ID" value={device.id.slice(0, 16)} />
        {status ? (
          <>
            <Row
              label="Battery"
              value={
                status.battery.level != null
                  ? `${status.battery.level}%${status.battery.charging ? ' (charging)' : ''}`
                  : 'unknown'
              }
            />
            <Row label="Screen" value={`${status.screenInteractive ? 'On' : 'Off'}${status.locked ? ', locked' : ''}`} />
            <Row label="Flashlight" value={status.torchOn ? 'On' : 'Off'} />
            <Row label="Model" value={`${status.manufacturer} ${status.model} · Android API ${status.androidSdk}`} />
          </>
        ) : null}
        <OutcomeLine outcome={statusOutcome} successText="Status updated" />
        <Button title="Refresh status" onPress={() => send({ type: 'GET_STATUS' })} busy={pending('GET_STATUS')} />
      </Card>

      {!device.trusted ? (
        <Notice tone="danger">
          {device.name} no longer accepts this phone. It was probably forgotten on that side. Forget it here and pair
          again.
        </Notice>
      ) : null}

      <Section title="Controls">
        <Card>
          <ThemedText type="smallBold">Flashlight</ThemedText>
          <View style={styles.row}>
            <Button
              title="On"
              kind={status?.torchOn ? 'primary' : 'secondary'}
              disabled={!supports('torch.control')}
              busy={pending('TORCH_SET')}
              onPress={() => send({ type: 'TORCH_SET', enabled: true })}
              style={styles.flex}
            />
            <Button
              title="Off"
              disabled={!supports('torch.control')}
              onPress={() => send({ type: 'TORCH_SET', enabled: false })}
              style={styles.flex}
            />
          </View>
          {!supports('torch.control') ? <ThemedText type="small" themeColor="textSecondary">No flash on this phone.</ThemedText> : null}
          <OutcomeLine outcome={outcomes?.TORCH_SET} />
        </Card>

        <Card>
          <ThemedText type="smallBold">Find phone</ThemedText>
          <View style={styles.row}>
            <Button
              title="Ring"
              disabled={!supports('sound.play')}
              busy={pending('PLAY_FIND_SOUND')}
              onPress={() => send({ type: 'PLAY_FIND_SOUND' })}
              style={styles.flex}
            />
            <Button
              title="Stop"
              disabled={!supports('sound.play')}
              busy={pending('STOP_FIND_SOUND')}
              onPress={() => send({ type: 'STOP_FIND_SOUND' })}
              style={styles.flex}
            />
          </View>
          <OutcomeLine outcome={outcomes?.PLAY_FIND_SOUND} successText="Ringing (stops after 15 s)" />
        </Card>

        <Card>
          <ThemedText type="smallBold">Vibrate</ThemedText>
          <View style={styles.row}>
            {[500, 2000].map((ms) => (
              <Button
                key={ms}
                title={`${ms / 1000} s`}
                disabled={!supports('vibrate')}
                busy={pending('VIBRATE') && outcomes?.VIBRATE?.command.type === 'VIBRATE' && outcomes.VIBRATE.command.durationMs === ms}
                onPress={() => send({ type: 'VIBRATE', durationMs: ms })}
                style={styles.flex}
              />
            ))}
          </View>
          {!supports('vibrate') ? <ThemedText type="small" themeColor="textSecondary">No vibrator on this phone.</ThemedText> : null}
          <OutcomeLine outcome={outcomes?.VIBRATE} />
        </Card>

        <Card>
          <ThemedText type="smallBold">Wake screen</ThemedText>
          <ThemedText type="small" themeColor="textSecondary">
            Best effort. Android only lets an app turn the screen on in some situations, so the result shows what
            actually happened.
          </ThemedText>
          <Button
            title="Wake screen"
            disabled={!supports('screen.wake.best_effort')}
            busy={pending('WAKE_SCREEN')}
            onPress={() => send({ type: 'WAKE_SCREEN' })}
          />
          <WakeProgress outcome={outcomes?.WAKE_SCREEN} />
        </Card>
      </Section>

      <Section title={`What ${device.name} supports`}>
        <Card>
          {caps.length === 0 ? (
            <ThemedText type="small" themeColor="textSecondary">
              Unknown until the device reports its status.
            </ThemedText>
          ) : (
            CAPABILITIES.map((c) => (
              <Row key={c} label={CAPABILITY_LABEL[c]} value={caps.includes(c) ? 'Yes' : 'No'} />
            ))
          )}
        </Card>
      </Section>

      <Section title={`What ${device.name} may do on this phone`}>
        <Card>
          {!receiverEnabled ? (
            <ThemedText type="small" themeColor="textSecondary">
              “Allow remote control” is off on this phone, so these only apply once you turn it on.
            </ThemedText>
          ) : null}
          {CAPABILITIES.map((c) => (
            <View key={c} style={styles.policy}>
              <ThemedText type="small">{CAPABILITY_LABEL[c]}</ThemedText>
              <Segmented
                options={POLICIES}
                labels={POLICY_LABELS}
                value={device.policies[c] ?? 'deny'}
                onChange={(p) => void setPolicy(id, c, p)}
              />
            </View>
          ))}
        </Card>
      </Section>

      <Button title="Forget device" kind="danger" onPress={confirmForget} />
    </Screen>
  );
}

function OutcomeLine({ outcome, successText }: { outcome?: CommandOutcome; successText?: string }) {
  const theme = useTheme();
  if (!outcome) return null;
  if (outcome.pending) {
    return (
      <View style={styles.row}>
        <ActivityIndicator size="small" />
        <ThemedText type="small" themeColor="textSecondary">
          Sending…
        </ThemedText>
      </View>
    );
  }
  const r = outcome.response;
  if (!r) return null;
  return (
    <ThemedText type="small" style={{ color: r.success ? theme.success : theme.danger }}>
      {r.success ? `✓ ${successText ?? 'Done'}` : `✕ ${describeError(r)}`}
    </ThemedText>
  );
}

const STEP_LABEL: Record<WakeStep, string> = {
  received: 'Command received',
  requested: 'Wake requested',
  confirmed: 'Screen confirmed awake',
};

function WakeProgress({ outcome }: { outcome?: CommandOutcome }) {
  const theme = useTheme();
  if (!outcome) return null;
  if (outcome.pending || !outcome.response) return <OutcomeLine outcome={outcome} />;
  const r = outcome.response;
  if (!r.success) return <OutcomeLine outcome={outcome} />;
  const result = parseWakeResult(r.data);
  const done = wakeSteps(result, true);
  return (
    <View style={styles.steps}>
      {(['received', 'requested', 'confirmed'] as const).map((step) => {
        const ok = done.includes(step);
        return (
          <ThemedText key={step} type="small" style={{ color: ok ? theme.success : theme.textSecondary }}>
            {ok ? '✓' : '○'} {STEP_LABEL[step]}
          </ThemedText>
        );
      })}
      {result?.state === 'already_awake' ? (
        <ThemedText type="small" themeColor="textSecondary">
          The screen was already on.
        </ThemedText>
      ) : null}
      {result && (result.state === 'requested' || result.state === 'blocked') ? (
        <ThemedText type="small" themeColor="textSecondary">
          {result.overlayPermission
            ? 'Android did not turn the screen on. A notification was shown instead.'
            : 'A notification was shown instead. To let this work with the screen off, open Settings on the other phone and allow “Display over other apps”.'}
        </ThemedText>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  header: { flexDirection: 'row', alignItems: 'center', gap: Spacing.two },
  flex: { flex: 1 },
  row: { flexDirection: 'row', alignItems: 'center', gap: Spacing.two },
  policy: { gap: Spacing.one, paddingVertical: Spacing.one },
  steps: { gap: Spacing.half },
});
