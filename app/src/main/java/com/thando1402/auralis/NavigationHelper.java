package com.thando1402.auralis;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.bottomnavigation.BottomNavigationView;

public final class NavigationHelper {

    private static volatile long lastLibraryMutationVersion = 0L;

    private NavigationHelper() {}

    public static void markLibraryDirty() {
        lastLibraryMutationVersion = System.currentTimeMillis();
    }

    public static long getLibraryMutationVersion() {
        return lastLibraryMutationVersion;
    }

    public static void setupBottomNavigation(Activity activity, BottomNavigationView bottomNav, int currentItemId) {
        if (activity == null || bottomNav == null) return;

        ColorStateList navSelector = ContextCompat.getColorStateList(activity, R.color.selector_nav_tint);
        if (navSelector != null) {
            bottomNav.setItemIconTintList(navSelector);
            bottomNav.setItemTextColor(navSelector);
        }
        int accent = ContextCompat.getColor(activity, R.color.accent_blue);
        bottomNav.setItemActiveIndicatorEnabled(true);
        bottomNav.setItemActiveIndicatorColor(ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 48)));

        syncSelectedTabSafely(activity, bottomNav, currentItemId);

        activity.getApplication().registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(@NonNull Activity resumedActivity) {
                if (resumedActivity == activity && !activity.isDestroyed()) {
                    if (bottomNav.getSelectedItemId() != currentItemId) {
                        syncSelectedTabSafely(activity, bottomNav, currentItemId);
                    }
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
            int size = bottomNav.getMenu().size();
            int i = 0;
            while (i < size) {
                bottomNav.getMenu().getItem(i).setChecked(false);
                i++;
            }
            bottomNav.getMenu().setGroupCheckable(0, true, true);
        }
        attachNavigationListener(host, bottomNav, currentItemId);
    }

    private static void attachNavigationListener(Activity host, BottomNavigationView bottomNav, int currentItemId) {
        bottomNav.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            View tabView = bottomNav.findViewById(itemId);
            if (tabView != null) {
                UiAnimationHelper.pulseBounce(tabView);
            }
            if (itemId == currentItemId) {
                return true;
            }

            Intent intent = createTabIntent(host, itemId);
            if (intent != null) {
                boolean slideForward = getTabOrder(itemId) >= getTabOrder(currentItemId);
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                host.startActivity(intent);
                applySlideTransition(host, slideForward);
                return true;
            }
            return false;
        });
    }

    @Nullable
    private static Intent createTabIntent(Activity host, int itemId) {
        if (itemId == R.id.nav_library) {
            return new Intent(host, MainActivity.class);
        } else if (itemId == R.id.nav_playlists) {
            return new Intent(host, PlaylistsActivity.class);
        } else if (itemId == R.id.nav_folders) {
            return new Intent(host, FoldersActivity.class);
        } else if (itemId == R.id.nav_eq) {
            return new Intent(host, EqualizerActivity.class);
        }
        return null;
    }

    private static int getTabOrder(int itemId) {
        if (itemId == R.id.nav_library) return 0;
        if (itemId == R.id.nav_playlists) return 1;
        if (itemId == R.id.nav_folders) return 2;
        if (itemId == R.id.nav_eq) return 3;
        return 0;
    }

    @SuppressWarnings("deprecation")
    public static void applySlideTransition(Activity activity, boolean forward) {
        if (activity == null) return;
        if (forward) {
            activity.overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
        } else {
            activity.overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
        }
    }
}
