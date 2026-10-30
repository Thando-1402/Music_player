package com.thando1402.auralis;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.bottomnavigation.BottomNavigationView;

public final class NavigationHelper {

    private NavigationHelper() {}

    public static void setupBottomNavigation(Activity activity, BottomNavigationView bottomNav, int currentItemId) {
        if (activity == null || bottomNav == null) return;

        // Apply Material 3 active icon/text tint selector and glowing pill indicator
        ColorStateList navSelector = ContextCompat.getColorStateList(activity, R.color.selector_nav_tint);
        if (navSelector != null) {
            bottomNav.setItemIconTintList(navSelector);
            bottomNav.setItemTextColor(navSelector);
        }
        int accent = ContextCompat.getColor(activity, R.color.accent_blue);
        bottomNav.setItemActiveIndicatorEnabled(true);
        bottomNav.setItemActiveIndicatorColor(ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 42)));

        syncSelectedTabSafely(activity, bottomNav, currentItemId);

        // Ensure returning to this Activity via Back button or Reorder-To-Front always restores its own tab icon
        activity.getApplication().registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(@NonNull Activity resumedActivity) {
                if (resumedActivity == activity && !activity.isDestroyed()) {
                    syncSelectedTabSafely(activity, bottomNav, currentItemId);
                }
            }

            @Override
            public void onActivityDestroyed(@NonNull Activity destroyedActivity) {
                if (destroyedActivity == activity) {
                    activity.getApplication().unregisterActivityLifecycleCallbacks(this);
                }
            }

            @Override public void onActivityCreated(@NonNull Activity a, @Nullable Bundle b) {}
            @Override public void onActivityStarted(@NonNull Activity a) {}
            @Override public void onActivityPaused(@NonNull Activity a) {}
            @Override public void onActivityStopped(@NonNull Activity a) {}
            @Override public void onActivitySaveInstanceState(@NonNull Activity a, @Nullable Bundle b) {}
        });
    }

    private static void syncSelectedTabSafely(Activity host, BottomNavigationView bottomNav, int currentItemId) {
        bottomNav.setOnItemSelectedListener(null);
        if (currentItemId != 0 && bottomNav.getMenu().findItem(currentItemId) != null) {
            bottomNav.setSelectedItemId(currentItemId);
        } else {
            bottomNav.getMenu().setGroupCheckable(0, true, false);
            for (int i = 0; i < bottomNav.getMenu().size(); i++) {
                bottomNav.getMenu().getItem(i).setChecked(false);
            }
            bottomNav.getMenu().setGroupCheckable(0, true, true);
        }
        attachNavigationListener(host, bottomNav, currentItemId);
    }

    @SuppressWarnings("deprecation")
    private static void attachNavigationListener(Activity host, BottomNavigationView bottomNav, int currentItemId) {
        bottomNav.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == currentItemId) {
                return true;
            }

            Intent intent = null;
            if (itemId == R.id.nav_library) {
                intent = new Intent(host, MainActivity.class);
            } else if (itemId == R.id.nav_playlists) {
                intent = new Intent(host, PlaylistsActivity.class);
            } else if (itemId == R.id.nav_folders) {
                intent = new Intent(host, FoldersActivity.class);
            } else if (itemId == R.id.nav_eq) {
                intent = new Intent(host, EqualizerActivity.class);
            }

            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                host.startActivity(intent);
                host.overridePendingTransition(0, 0);
            }
            // Return false so the departing Activity does not mutate its own checked tab to the next tab
            return false;
        });
    }
}
