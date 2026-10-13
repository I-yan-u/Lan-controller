import { useFocusEffect } from 'expo-router';
import { useCallback, useState } from 'react';
import { AppState, StyleSheet, TextInput, View } from 'react-native';

import { ThemedText } from '@/components/themed-text';
import { Badge, Button, Card, Notice, Row, Screen, Section, SwitchRow } from '@/components/ui';
import { Spacing } from '@/constants/theme';
import { useTheme } from '@/hooks/use-theme';
import { ensureNotificationPermission, setDeviceName, setReceiverEnabled } from '@/services/device-service';
import LanControl, { errorMessage } from '@/services/lan-control';
import { useDeviceStore } from '@/stores/device-store';
import type { Diagnostics, SettingsKind } from '@/types/protocol';

export default function SettingsScreen() {
  const theme = useTheme();
  const identity = useDeviceStore((s) => s.identity);
  const service = useDeviceStore((s) => s.service);
  const setService = useDeviceStore((s) => s.setService);
  const [diag, setDiag] = useState<Diagnostics | null>(null);
  const [name, setName] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    void LanControl.getDiagnostics().then(setDiag);
  }, []);
  useFocusEffect(
    useCallback(() => {
      load();
      // Coming back from Android Settings: re-check permissions.
      const sub = AppState.addEventListener('change', (s) => s === 'active' && load());
      return () => sub.remove();
    }, [load]),
  );

  const run = async (fn: () => Promise<unknown>) => {
    setError(null);
    try {
      await fn();
    } catch (e) {
      setError(errorMessage(e));
    }
    load();
  };
  const open = (kind: SettingsKind) => void LanControl.openSettings(kind);

  return (
    <Screen>
      {error ? <Notice tone="danger">{error}</Notice> : null}

      <Section title="This phone">
        <Card>
          <ThemedText type="small" themeColor="textSecondary">
            Name shown to other phones
          </ThemedText>
          <TextInput
            value={name ?? identity?.name ?? ''}
            onChangeText={setName}
            maxLength={64}
            style={[styles.input, { color: theme.text, borderColor: theme.border }]}
            accessibilityLabel="Device name"
          />
          <Button
            title="Save name"
            disabled={!name || name.trim() === identity?.name}
            onPress={() => void run(async () => {
              await setDeviceName(name!.trim());
              setName(null);
            })}
          />
          <Row label="Device ID" value={identity?.deviceId.slice(0, 32)} />
        </Card>
      </Section>

      <Section title="Remote control">
        <Card>
          <SwitchRow
            label="Allow remote control"
            description="Runs a background service with a persistent notification so paired phones can send commands."
            value={service?.receiverEnabled ?? false}
            onValueChange={(v) => void run(() => setReceiverEnabled(v))}
          />
          <SwitchRow
            label="Start after reboot"
            description="Restart remote control automatically when the phone boots."
            value={service?.startOnBoot ?? false}
            disabled={!service?.receiverEnabled}
            onValueChange={(v) => void run(async () => setService(await LanControl.setStartOnBoot(v)))}
          />
          <Row label="Status" value={service?.receiverRunning ? `Running on port ${service.port}` : 'Stopped'} />
          <Row label="Advertising on network" value={service?.advertising ? 'Yes' : 'No'} />
        </Card>
      </Section>

      <Section title="Permissions" action={<Button title="Re-check" onPress={load} />}>
        <Card>
          <PermissionRow
            label="Notifications"
            ok={diag?.notificationsEnabled}
            help="Needed for the service notification, wake requests and permission prompts."
            onFix={() => void run(async () => (await ensureNotificationPermission()) || open('notifications'))}
          />
          <PermissionRow
            label="Display over other apps"
            ok={diag?.overlayPermission}
            optional
            help="Lets “Wake screen” turn this phone’s screen on from the background. Without it, a notification is shown instead."
            onFix={() => open('overlay')}
          />
          <PermissionRow
            label="Unrestricted battery"
            ok={diag?.ignoringBatteryOptimizations}
            optional
            help="Battery optimisation can stop the service while the screen is off. Some manufacturers (Samsung, Xiaomi, OnePlus, Huawei) need extra steps in their own battery settings."
            onFix={() => open('battery')}
          />
          {diag?.localNetworkPermissionRequired ? (
            <PermissionRow
              label="Local network access"
              ok={undefined}
              help="This Android version controls local network access per app. Allow it in app settings if discovery fails."
              onFix={() => open('app')}
            />
          ) : null}
        </Card>
      </Section>

      <Section title="Network">
        <Card>
          <Row label="Wi-Fi" value={diag?.wifiConnected ? `Connected (${diag.wifiIp ?? 'no IPv4'})` : 'Not connected'} />
          <Row label="Discovery" value={diag?.discovering ? 'Searching' : 'Stopped'} />
          <Row label="Multicast lock" value={diag?.multicastLockHeld ? 'Held' : 'Not held'} />
          {!diag?.wifiConnected ? <Button title="Open Wi-Fi settings" onPress={() => open('wifi')} /> : null}
        </Card>
        <Notice>
          If phones can see each other but commands fail, the router may isolate wireless clients (often called “AP
          isolation” or “client isolation”, common on guest networks). Turn it off or use another network.
        </Notice>
      </Section>

      <Section title="This phone’s hardware">
        <Card>
          <Row label="Model" value={diag ? `${diag.manufacturer} ${diag.model}` : '…'} />
          <Row label="Android API" value={diag ? `${diag.androidSdk} (target ${diag.targetSdk})` : '…'} />
          <Row label="Flashlight" value={diag?.torchSupported ? 'Supported' : 'Not available'} />
          <Row label="Vibration" value={diag?.vibratorSupported ? 'Supported' : 'Not available'} />
          <Row label="Screen wake" value={diag?.overlayPermission ? 'Best effort (overlay allowed)' : 'Notification only'} />
        </Card>
      </Section>
    </Screen>
  );
}

function PermissionRow({
  label,
  ok,
  help,
  optional,
  onFix,
}: {
  label: string;
  ok: boolean | undefined;
  help: string;
  optional?: boolean;
  onFix: () => void;
}) {
  return (
    <View style={styles.permission}>
      <View style={styles.permissionHeader}>
        <ThemedText type="default" style={styles.flex}>
          {label}
        </ThemedText>
        {ok === undefined ? null : ok ? (
          <Badge tone="success" label="Allowed" />
        ) : (
          <Badge tone={optional ? 'warning' : 'danger'} label={optional ? 'Recommended' : 'Required'} />
        )}
      </View>
      <ThemedText type="small" themeColor="textSecondary">
        {help}
      </ThemedText>
      {!ok ? <Button title="Open settings" onPress={onFix} /> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  input: { borderWidth: 1, borderRadius: Spacing.two, paddingHorizontal: Spacing.three, minHeight: 44, fontSize: 16 },
  permission: { gap: Spacing.one, paddingVertical: Spacing.two },
  permissionHeader: { flexDirection: 'row', alignItems: 'center', gap: Spacing.two },
  flex: { flex: 1 },
});
