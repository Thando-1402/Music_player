package com.thando1402.auralis;

import android.app.Application;

import com.google.firebase.FirebaseApp;

public class AuralisApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            FirebaseApp.initializeApp(this);
        } catch (Exception ignored) {
        }
        AuralisTelemetryManager.init(this);
    }
}
