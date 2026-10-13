import { CameraView, useCameraPermissions } from 'expo-camera';
import { useRouter } from 'expo-router';
import { useRef } from 'react';
import { StyleSheet, View } from 'react-native';

import { ThemedText } from '@/components/themed-text';
import { Button, Notice, Screen } from '@/components/ui';
import { Spacing } from '@/constants/theme';

export default function ScanScreen() {
  const router = useRouter();
  const [permission, requestPermission] = useCameraPermissions();
  const handled = useRef(false);

  if (!permission) return null;

  if (!permission.granted) {
    return (
      <Screen>
        <Notice>The camera is only used to read the pairing QR code on the other phone.</Notice>
        <Button title="Allow camera" kind="primary" onPress={() => void requestPermission()} />
        {!permission.canAskAgain ? (
          <ThemedText type="small" themeColor="textSecondary">
            Camera access was denied. Enable it in Android Settings, or pair from the Nearby list instead.
          </ThemedText>
        ) : null}
      </Screen>
    );
  }

  return (
    <View style={styles.container}>
      <CameraView
        style={StyleSheet.absoluteFill}
        facing="back"
        barcodeScannerSettings={{ barcodeTypes: ['qr'] }}
        onBarcodeScanned={({ data }) => {
          if (handled.current || !data.startsWith('lancontrol://pair')) return;
          handled.current = true;
          router.replace({ pathname: '/pair/connect', params: { qr: data } });
        }}
      />
      <View style={styles.hint}>
        <ThemedText type="smallBold" style={styles.hintText}>
          Point the camera at the pairing code on the other phone
        </ThemedText>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#000' },
  hint: {
    position: 'absolute',
    bottom: Spacing.six,
    left: Spacing.three,
    right: Spacing.three,
    padding: Spacing.three,
    borderRadius: Spacing.three,
    backgroundColor: 'rgba(0,0,0,0.6)',
  },
  hintText: { color: '#fff', textAlign: 'center' },
});
