package com.thando1402.auralis;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.bottomnavigation.BottomNavigationView;

public class PlaylistsActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_playlists);
        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav_playlists);
        NavigationHelper.setupBottomNavigation(this, bottomNav, R.id.nav_playlists);
    }
}