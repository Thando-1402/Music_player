package com.thando1402.auralis;

import android.content.Context;
import android.content.Intent;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class AudioRouteHelper {

    public static class RouteInfo {
        public final String deviceName;
        public final String codecSubtitle;
        public final int iconRes;

        public RouteInfo(String deviceName, String codecSubtitle, int iconRes) {
            this.deviceName = deviceName;
            this.codecSubtitle = codecSubtitle;
            this.iconRes = iconRes;
        }
    }

    private AudioRouteHelper() {}

    @NonNull
    public static RouteInfo getActiveRouteInfo(Context context) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            AudioDeviceInfo[] devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            AudioDeviceInfo bestExternal = null;

            for (AudioDeviceInfo d : devices) {
                int type = d.getType();
                if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                        || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                        || type == AudioDeviceInfo.TYPE_BLE_HEADSET
                        || type == AudioDeviceInfo.TYPE_BLE_SPEAKER) {
                    bestExternal = d;
                    break;
                } else if (type == AudioDeviceInfo.TYPE_USB_HEADSET
                        || type == AudioDeviceInfo.TYPE_USB_DEVICE
                        || type == AudioDeviceInfo.TYPE_USB_ACCESSORY) {
                    bestExternal = d;
                } else if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                        || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                        || type == AudioDeviceInfo.TYPE_LINE_ANALOG) {
                    if (bestExternal == null) bestExternal = d;
                }
            }

            if (bestExternal != null) {
                CharSequence rawName = bestExternal.getProductName();
                String prodName = (rawName != null && rawName.length() > 0)
                        ? rawName.toString().trim() : "";
                int type = bestExternal.getType();

                if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                        || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                        || type == AudioDeviceInfo.TYPE_BLE_HEADSET
                        || type == AudioDeviceInfo.TYPE_BLE_SPEAKER) {
                    if (prodName.isEmpty() || prodName.equalsIgnoreCase(Build.MODEL)) {
                        prodName = "Bluetooth Audio Device";
                    }
                    return new RouteInfo(prodName, "BLUETOOTH A2DP • WIRELESS STREAM", R.drawable.ic_headphones_24);
                } else if (type == AudioDeviceInfo.TYPE_USB_HEADSET
                        || type == AudioDeviceInfo.TYPE_USB_DEVICE
                        || type == AudioDeviceInfo.TYPE_USB_ACCESSORY) {
                    if (prodName.isEmpty()) prodName = "External USB Audio DAC";
                    return new RouteInfo(prodName, "DIRECT USB DAC • HI-RES PCM", R.drawable.ic_headphones_24);
                } else {
                    return new RouteInfo("Wired Headphones / AUX", "3.5MM ANALOG • ZERO LATENCY", R.drawable.ic_headphones_24);
                }
            }
        }

        String model = (Build.MANUFACTURER + " " + Build.MODEL).trim();
        if (model.isEmpty()) model = "Device";
        return new RouteInfo(model + " Speaker", "INTERNAL DAC • 16/24-BIT PCM", R.drawable.ic_music_note_24);
    }

    public static void bindRouteViews(Context context,
                                      @Nullable TextView tvName,
                                      @Nullable TextView tvSubtitle,
                                      @Nullable ImageView ivIcon) {
        RouteInfo info = getActiveRouteInfo(context);
        if (tvName != null) tvName.setText(info.deviceName);
        if (tvSubtitle != null) tvSubtitle.setText(info.codecSubtitle);
        if (ivIcon != null) ivIcon.setImageResource(info.iconRes);
    }

    public static AudioDeviceCallback registerLiveRouteCallback(Context context, Runnable onRouteChanged) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return null;
        AudioDeviceCallback callback = new AudioDeviceCallback() {
            @Override
            public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
                if (onRouteChanged != null) onRouteChanged.run();
            }

            @Override
            public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
                if (onRouteChanged != null) onRouteChanged.run();
            }
        };
        am.registerAudioDeviceCallback(callback, new Handler(Looper.getMainLooper()));
        return callback;
    }

    public static void unregisterLiveRouteCallback(Context context, @Nullable AudioDeviceCallback callback) {
        if (callback == null) return;
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            am.unregisterAudioDeviceCallback(callback);
        }
    }

    public static void showAudioOutputSwitcher(Context context) {
        RouteInfo current = getActiveRouteInfo(context);
        String[] options = new String[]{
                "Active: " + current.deviceName + " (" + current.codecSubtitle + ")",
                "Pair or Switch Bluetooth Device",
                "System Sound & Output Settings"
        };

        new MaterialAlertDialogBuilder(context)
                .setTitle("Audio Output Route")
                .setItems(options, (dialog, which) -> {
                    try {
                        if (which == 1) {
                            Intent btIntent = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
                            btIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            context.startActivity(btIntent);
                        } else if (which == 2) {
                            Intent soundIntent = new Intent(Settings.ACTION_SOUND_SETTINGS);
                            soundIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            context.startActivity(soundIntent);
                        }
                    } catch (Exception e) {
                        Toast.makeText(context, "Unable to open system settings on this device", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }
}
