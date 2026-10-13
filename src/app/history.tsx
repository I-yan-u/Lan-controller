import { useFocusEffect } from 'expo-router';
import { useCallback, useState } from 'react';
import { StyleSheet, View } from 'react-native';

import { ThemedText } from '@/components/themed-text';
import { Button, Card, Notice, Screen } from '@/components/ui';
import { Spacing } from '@/constants/theme';
import { useTheme } from '@/hooks/use-theme';
import LanControl from '@/services/lan-control';
import { COMMAND_LABEL, type HistoryEntry } from '@/types/protocol';

const DIRECTION_LABEL: Record<HistoryEntry['direction'], string> = {
  in: 'Received from',
  out: 'Sent to',
  event: '',
};

export default function HistoryScreen() {
  const theme = useTheme();
  const [entries, setEntries] = useState<HistoryEntry[] | null>(null);

  const load = useCallback(() => {
    void LanControl.getHistory().then(setEntries);
  }, []);
  useFocusEffect(load);

  return (
    <Screen>
      <View style={styles.row}>
        <Button title="Refresh" onPress={load} style={styles.flex} />
        <Button
          title="Clear"
          onPress={() => void LanControl.clearHistory().then(load)}
          disabled={!entries?.length}
          style={styles.flex}
        />
      </View>
      {entries && entries.length === 0 ? <Notice>No commands yet.</Notice> : null}
      {entries?.map((e, i) => (
        <Card key={`${e.timestamp}-${i}`}>
          <View style={styles.row}>
            <ThemedText type="smallBold" style={styles.flex}>
              {COMMAND_LABEL[e.command] ?? e.command}
            </ThemedText>
            <ThemedText type="small" style={{ color: e.success ? theme.success : theme.danger }}>
              {e.success ? 'OK' : (e.error ?? 'Failed')}
            </ThemedText>
          </View>
          <ThemedText type="small" themeColor="textSecondary">
            {new Date(e.timestamp).toLocaleString()}
            {e.peerName ? ` · ${[DIRECTION_LABEL[e.direction], e.peerName].filter(Boolean).join(' ')}` : ''}
          </ThemedText>
          {e.detail ? <ThemedText type="small">{e.detail}</ThemedText> : null}
        </Card>
      ))}
    </Screen>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', gap: Spacing.two },
  flex: { flex: 1 },
});
