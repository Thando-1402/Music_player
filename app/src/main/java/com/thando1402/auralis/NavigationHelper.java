package com.thando1402.auralis;

import android.app.Activity;
import android.content.Intent;
import com.google.android.material.bottomnavigation.BottomNavigationView;

public class NavigationHelper {
    public static void setupBottomNavigation(Activity activity, BottomNavigationView bottomNav, int currentItemId) {
        bottomNav.setSelectedItemId(currentItemId);
        bottomNav.setOnItemSelectedListener(item -> {
            if (item.getItemId() == currentItemId) {
                return true;
            }
            
            Intent intent = null;
            int itemId = item.getItemId();
            
            if (itemId == R.id.nav_library) {
                intent = new Intent(activity, MainActivity.class);
            } else if (itemId == R.id.nav_playlists) {
                intent = new Intent(activity, PlaylistsActivity.class);
            } else if (itemId == R.id.nav_folders) {
                intent = new Intent(activity, FoldersActivity.class);
            } else if (itemId == R.id.nav_eq) {
                intent = new Intent(activity, EqualizerActivity.class);
            }
            
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                activity.startActivity(intent);
                activity.overridePendingTransition(0, 0);
                return true;
            }
            return false;
        });
    }
}