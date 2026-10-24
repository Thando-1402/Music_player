package com.thando1402.auralis;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.bottomnavigation.BottomNavigationView;

public class EqualizerActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_equalizer);
        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav_eq);
        NavigationHelper.setupBottomNavigation(this, bottomNav, R.id.nav_eq);
    }
}