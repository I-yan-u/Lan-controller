import type { ReactNode } from 'react';
import {
  ActivityIndicator,
  Pressable,
  ScrollView,
  StyleSheet,
  Switch,
  View,
  type StyleProp,
  type ViewStyle,
} from 'react-native';

import { ThemedText } from '@/components/themed-text';
import { MaxContentWidth, Spacing } from '@/constants/theme';
import { useTheme } from '@/hooks/use-theme';

export function Screen({ children }: { children: ReactNode }) {
  const theme = useTheme();
  return (
    <ScrollView
      style={{ backgroundColor: theme.background }}
      contentContainerStyle={styles.screen}
      contentInsetAdjustmentBehavior="automatic">
      {children}
    </ScrollView>
  );
}

export function Section({ title, action, children }: { title: string; action?: ReactNode; children: ReactNode }) {
  return (
    <View style={styles.section}>
      <View style={styles.sectionHeader}>
        <ThemedText type="smallBold" themeColor="textSecondary" style={styles.sectionTitle}>
          {title}
        </ThemedText>
        {action}
      </View>
      {children}
    </View>
  );
}

export function Card({ children, style, onPress }: { children: ReactNode; style?: StyleProp<ViewStyle>; onPress?: () => void }) {
  const theme = useTheme();
  const content = <View style={[styles.card, { backgroundColor: theme.backgroundElement }, style]}>{children}</View>;
  if (!onPress) return content;
  return (
    <Pressable onPress={onPress} style={({ pressed }) => pressed && { opacity: 0.7 }} accessibilityRole="button">
      {content}
    </Pressable>
  );
}

type ButtonKind = 'primary' | 'secondary' | 'danger';

export function Button({
  title,
  onPress,
  kind = 'secondary',
  disabled,
  busy,
  style,
}: {
  title: string;
  onPress: () => void;
  kind?: ButtonKind;
  disabled?: boolean;
  busy?: boolean;
  style?: StyleProp<ViewStyle>;
}) {
  const theme = useTheme();
  const bg = kind === 'primary' ? theme.accent : kind === 'danger' ? theme.danger : theme.backgroundSelected;
  const fg = kind === 'secondary' ? theme.text : theme.onAccent;
  const inactive = disabled || busy;
  return (
    <Pressable
      onPress={onPress}
      disabled={inactive}
      accessibilityRole="button"
      accessibilityState={{ disabled: !!inactive, busy: !!busy }}
      style={({ pressed }) => [
        styles.button,
        { backgroundColor: bg, opacity: disabled ? 0.4 : pressed ? 0.75 : 1 },
        style,
      ]}>
      {busy ? <ActivityIndicator color={fg} size="small" /> : null}
      <ThemedText type="smallBold" style={{ color: fg }}>
        {title}
      </ThemedText>
    </Pressable>
  );
}

export function Row({ label, value, children }: { label: string; value?: ReactNode; children?: ReactNode }) {
  return (
    <View style={styles.row}>
      <ThemedText type="small" themeColor="textSecondary" style={styles.rowLabel}>
        {label}
      </ThemedText>
      {value !== undefined ? (
        typeof value === 'string' || typeof value === 'number' ? (
          <ThemedText type="small" style={styles.rowValue}>
            {value}
          </ThemedText>
        ) : (
          value
        )
      ) : null}
      {children}
    </View>
  );
}

export function SwitchRow({
  label,
  description,
  value,
  onValueChange,
  disabled,
}: {
  label: string;
  description?: string;
  value: boolean;
  onValueChange: (v: boolean) => void;
  disabled?: boolean;
}) {
  const theme = useTheme();
  return (
    <View style={styles.switchRow}>
      <View style={styles.flex}>
        <ThemedText type="default">{label}</ThemedText>
        {description ? (
          <ThemedText type="small" themeColor="textSecondary">
            {description}
          </ThemedText>
        ) : null}
      </View>
      <Switch
        value={value}
        onValueChange={onValueChange}
        disabled={disabled}
        trackColor={{ true: theme.accent }}
        accessibilityLabel={label}
      />
    </View>
  );
}

export type Tone = 'success' | 'warning' | 'danger' | 'neutral';

export function Dot({ tone }: { tone: Tone }) {
  const theme = useTheme();
  const color = tone === 'neutral' ? theme.textSecondary : theme[tone];
  return <View style={[styles.dot, { backgroundColor: color }]} />;
}

export function Badge({ tone, label }: { tone: Tone; label: string }) {
  const theme = useTheme();
  const color = tone === 'neutral' ? theme.textSecondary : theme[tone];
  return (
    <View style={[styles.badge, { borderColor: color }]}>
      <Dot tone={tone} />
      <ThemedText type="small" style={{ color }}>
        {label}
      </ThemedText>
    </View>
  );
}

export function Segmented<T extends string>({
  options,
  value,
  onChange,
  labels,
}: {
  options: readonly T[];
  value: T;
  onChange: (v: T) => void;
  labels?: Partial<Record<T, string>>;
}) {
  const theme = useTheme();
  return (
    <View style={[styles.segmented, { backgroundColor: theme.backgroundSelected }]}>
      {options.map((o) => {
        const selected = o === value;
        return (
          <Pressable
            key={o}
            onPress={() => onChange(o)}
            accessibilityRole="button"
            accessibilityState={{ selected }}
            style={[styles.segment, selected && { backgroundColor: theme.accent }]}>
            <ThemedText type="small" style={{ color: selected ? theme.onAccent : theme.text }}>
              {labels?.[o] ?? o}
            </ThemedText>
          </Pressable>
        );
      })}
    </View>
  );
}

export function Notice({ tone = 'neutral', children }: { tone?: Tone; children: ReactNode }) {
  const theme = useTheme();
  const color = tone === 'neutral' ? theme.border : theme[tone];
  return (
    <View style={[styles.notice, { borderLeftColor: color, backgroundColor: theme.backgroundElement }]}>
      {typeof children === 'string' ? <ThemedText type="small">{children}</ThemedText> : children}
    </View>
  );
}

export function timeAgo(ts: number | null | undefined, now = Date.now()): string {
  if (!ts) return 'never';
  const s = Math.max(0, Math.round((now - ts) / 1000));
  if (s < 10) return 'just now';
  if (s < 60) return `${s}s ago`;
  if (s < 3600) return `${Math.round(s / 60)} min ago`;
  if (s < 86400) return `${Math.round(s / 3600)} h ago`;
  return new Date(ts).toLocaleDateString();
}

const styles = StyleSheet.create({
  screen: {
    padding: Spacing.three,
    gap: Spacing.four,
    width: '100%',
    maxWidth: MaxContentWidth,
    alignSelf: 'center',
    paddingBottom: Spacing.six,
  },
  section: { gap: Spacing.two },
  sectionHeader: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  sectionTitle: { textTransform: 'uppercase', letterSpacing: 0.5 },
  card: { borderRadius: Spacing.three, padding: Spacing.three, gap: Spacing.two },
  button: {
    minHeight: 44,
    paddingHorizontal: Spacing.three,
    borderRadius: Spacing.two + 2,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: Spacing.two,
  },
  row: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: Spacing.two },
  rowLabel: { flexShrink: 0 },
  rowValue: { flexShrink: 1, textAlign: 'right' },
  switchRow: { flexDirection: 'row', alignItems: 'center', gap: Spacing.three, minHeight: 44 },
  flex: { flex: 1 },
  dot: { width: 8, height: 8, borderRadius: 4 },
  badge: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: Spacing.one + 2,
    borderWidth: 1,
    borderRadius: 999,
    paddingHorizontal: Spacing.two,
    paddingVertical: Spacing.half,
    alignSelf: 'flex-start',
  },
  segmented: { flexDirection: 'row', borderRadius: Spacing.two, padding: Spacing.half },
  segment: { flex: 1, alignItems: 'center', paddingVertical: Spacing.one + 2, borderRadius: Spacing.two - 2 },
  notice: { borderLeftWidth: 3, padding: Spacing.three, borderRadius: Spacing.two, gap: Spacing.one },
});
