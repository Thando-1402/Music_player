package com.thando1402.auralis;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.bottomnavigation.BottomNavigationView;

public class ProfileSettingsActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile_settings);
        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav_profile);
        // Note: Profile isn't in the bottom nav menu, so we pass 0 as the selected ID to leave all tabs inactive
        NavigationHelper.setupBottomNavigation(this, bottomNav, 0); 
    }
}