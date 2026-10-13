import { DarkTheme, DefaultTheme, Stack, ThemeProvider } from 'expo-router';
import * as SplashScreen from 'expo-splash-screen';
import { useEffect } from 'react';
import { useColorScheme } from 'react-native';

import { PromptHost } from '@/components/prompt-host';
import { startDeviceService } from '@/services/device-service';

SplashScreen.preventAutoHideAsync();

export default function RootLayout() {
  const colorScheme = useColorScheme();

  useEffect(() => {
    const stop = startDeviceService();
    void SplashScreen.hideAsync();
    return stop;
  }, []);

  return (
    <ThemeProvider value={colorScheme === 'dark' ? DarkTheme : DefaultTheme}>
      <Stack>
        <Stack.Screen name="index" options={{ title: 'Devices' }} />
        <Stack.Screen name="device/[id]" options={{ title: 'Device' }} />
        <Stack.Screen name="pair/index" options={{ title: 'Pair a device' }} />
        <Stack.Screen name="pair/show" options={{ title: 'Show pairing code' }} />
        <Stack.Screen name="pair/scan" options={{ title: 'Scan pairing code' }} />
        <Stack.Screen name="pair/connect" options={{ title: 'Pairing' }} />
        <Stack.Screen name="history" options={{ title: 'History' }} />
        <Stack.Screen name="settings" options={{ title: 'Settings' }} />
      </Stack>
      <PromptHost />
    </ThemeProvider>
  );
}
