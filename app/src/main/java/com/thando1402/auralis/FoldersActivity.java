package com.thando1402.auralis;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.bottomnavigation.BottomNavigationView;

public class FoldersActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_folders);
        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav_folders);
        NavigationHelper.setupBottomNavigation(this, bottomNav, R.id.nav_folders);
    }
}