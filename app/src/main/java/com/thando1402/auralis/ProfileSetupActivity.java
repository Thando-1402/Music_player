package com.thando1402.auralis;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.UserProfileChangeRequest;

public class ProfileSetupActivity extends AppCompatActivity {

    private TextInputEditText inputDisplayName;
    private TextInputEditText inputEmail;
    private TextInputEditText inputPassword;
    private TextView tvAuthStatusBanner;
    private MaterialButton btnSignOut;
    private FirebaseAuth firebaseAuth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile_setup);

        try {
            FirebaseApp.initializeApp(this);
            firebaseAuth = FirebaseAuth.getInstance();
        } catch (Exception ignored) {
        }

        MaterialToolbar toolbar = findViewById(R.id.toolbar_profile_setup);
        if (toolbar != null) {
            toolbar.setNavigationOnClickListener(v -> finish());
        }

        inputDisplayName = findViewById(R.id.input_profile_display_name);
        inputEmail = findViewById(R.id.input_auth_email);
        inputPassword = findViewById(R.id.input_auth_password);
        tvAuthStatusBanner = findViewById(R.id.tv_auth_status_banner);
        btnSignOut = findViewById(R.id.btn_auth_sign_out);

        MaterialButton btnSaveLocal = findViewById(R.id.btn_save_local_profile);
        MaterialButton btnSignIn = findViewById(R.id.btn_auth_sign_in);
        MaterialButton btnRegister = findViewById(R.id.btn_auth_register);
        MaterialButton btnAnonymous = findViewById(R.id.btn_auth_anonymous);

        UiAnimationHelper.attachSpringTouch(btnSaveLocal, btnSignIn, btnRegister, btnAnonymous);

        String currentName = OpenRouterAiHelper.getProfileName(this);
        if (inputDisplayName != null && !currentName.equals(getString(R.string.profile_default_name))) {
            inputDisplayName.setText(currentName);
        }

        refreshAuthBanner();

        if (btnSaveLocal != null) {
            btnSaveLocal.setOnClickListener(v -> {
                String enteredName = inputDisplayName != null && inputDisplayName.getText() != null
                        ? inputDisplayName.getText().toString().trim() : "";
                if (enteredName.isEmpty()) {
                    Toast.makeText(this, "Please enter a display name", Toast.LENGTH_SHORT).show();
                    return;
                }
                OpenRouterAiHelper.setProfileName(this, enteredName);
                syncDisplayNameToFirebaseUser(enteredName);
                Toast.makeText(this, "Profile saved as " + enteredName, Toast.LENGTH_SHORT).show();
                finish();
            });
        }

        if (btnSignIn != null) {
            btnSignIn.setOnClickListener(v -> handleEmailAuth(false));
        }

        if (btnRegister != null) {
            btnRegister.setOnClickListener(v -> handleEmailAuth(true));
        }

        if (btnAnonymous != null) {
            btnAnonymous.setOnClickListener(v -> {
                if (firebaseAuth == null) {
                    Toast.makeText(this, "Firebase Auth unavailable offline", Toast.LENGTH_SHORT).show();
                    return;
                }
                firebaseAuth.signInAnonymously().addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        String typedName = inputDisplayName != null && inputDisplayName.getText() != null
                                ? inputDisplayName.getText().toString().trim() : "";
                        if (!typedName.isEmpty()) {
                            OpenRouterAiHelper.setProfileName(this, typedName);
                            syncDisplayNameToFirebaseUser(typedName);
                        } else if (!OpenRouterAiHelper.isProfileConfigured(this)) {
                            OpenRouterAiHelper.setProfileName(this, getString(R.string.profile_default_name));
                        }
                        refreshAuthBanner();
                        Toast.makeText(this, "Signed in with Anonymous Cloud Session", Toast.LENGTH_SHORT).show();
                        finish();
                    } else {
                        String err = task.getException() != null ? task.getException().getLocalizedMessage() : "Check connection or Firebase Console";
                        Toast.makeText(this, "Anonymous sign-in failed: " + err, Toast.LENGTH_LONG).show();
                    }
                });
            });
        }

        if (btnSignOut != null) {
            btnSignOut.setOnClickListener(v -> {
                if (firebaseAuth != null) {
                    firebaseAuth.signOut();
                }
                refreshAuthBanner();
                Toast.makeText(this, "Signed out of cloud session", Toast.LENGTH_SHORT).show();
            });
        }
    }

    private void handleEmailAuth(boolean isRegistration) {
        if (firebaseAuth == null) {
            Toast.makeText(this, "Firebase Auth unavailable", Toast.LENGTH_SHORT).show();
            return;
        }
        String email = inputEmail != null && inputEmail.getText() != null
                ? inputEmail.getText().toString().trim() : "";
        String password = inputPassword != null && inputPassword.getText() != null
                ? inputPassword.getText().toString() : "";

        if (!email.contains("@")) {
            Toast.makeText(this, "Enter a valid email address", Toast.LENGTH_SHORT).show();
            return;
        }
        if (password.length() < 6) {
            Toast.makeText(this, "Password must be at least 6 characters", Toast.LENGTH_SHORT).show();
            return;
        }

        if (isRegistration) {
            firebaseAuth.createUserWithEmailAndPassword(email, password)
                    .addOnCompleteListener(this, task -> {
                        if (task.isSuccessful()) {
                            applyAuthenticatedProfile(email);
                            Toast.makeText(this, "Account created & synced!", Toast.LENGTH_SHORT).show();
                            finish();
                        } else {
                            String err = task.getException() != null ? task.getException().getLocalizedMessage() : "Registration error";
                            Toast.makeText(this, "Registration failed: " + err, Toast.LENGTH_LONG).show();
                        }
                    });
        } else {
            firebaseAuth.signInWithEmailAndPassword(email, password)
                    .addOnCompleteListener(this, task -> {
                        if (task.isSuccessful()) {
                            applyAuthenticatedProfile(email);
                            Toast.makeText(this, "Signed in successfully!", Toast.LENGTH_SHORT).show();
                            finish();
                        } else {
                            String err = task.getException() != null ? task.getException().getLocalizedMessage() : "Sign-in error";
                            Toast.makeText(this, "Sign-in failed: " + err, Toast.LENGTH_LONG).show();
                        }
                    });
        }
    }

    private void applyAuthenticatedProfile(String fallbackEmail) {
        FirebaseUser user = firebaseAuth != null ? firebaseAuth.getCurrentUser() : null;
        String typedName = inputDisplayName != null && inputDisplayName.getText() != null
                ? inputDisplayName.getText().toString().trim() : "";

        if (!typedName.isEmpty()) {
            OpenRouterAiHelper.setProfileName(this, typedName);
            syncDisplayNameToFirebaseUser(typedName);
        } else if (user != null && user.getDisplayName() != null && !user.getDisplayName().trim().isEmpty()) {
            OpenRouterAiHelper.setProfileName(this, user.getDisplayName().trim());
        } else {
            int atIdx = fallbackEmail.indexOf('@');
            String derived = atIdx > 0 ? fallbackEmail.substring(0, atIdx) : fallbackEmail;
            OpenRouterAiHelper.setProfileName(this, derived);
            syncDisplayNameToFirebaseUser(derived);
        }
        refreshAuthBanner();
    }

    private void syncDisplayNameToFirebaseUser(String displayName) {
        if (firebaseAuth == null) return;
        FirebaseUser user = firebaseAuth.getCurrentUser();
        if (user != null && displayName != null && !displayName.isEmpty()) {
            UserProfileChangeRequest profileUpdates = new UserProfileChangeRequest.Builder()
                    .setDisplayName(displayName)
                    .build();
            user.updateProfile(profileUpdates);
        }
    }

    private void refreshAuthBanner() {
        if (tvAuthStatusBanner == null) return;
        FirebaseUser user = firebaseAuth != null ? firebaseAuth.getCurrentUser() : null;
        if (user != null) {
            String label = user.isAnonymous()
                    ? "Cloud Sync Active • Anonymous Session"
                    : "Cloud Sync Active • " + (user.getEmail() != null ? user.getEmail() : "Authenticated");
            tvAuthStatusBanner.setText(label);
            if (btnSignOut != null) btnSignOut.setVisibility(View.VISIBLE);
        } else {
            tvAuthStatusBanner.setText(R.string.setup_status_offline);
            if (btnSignOut != null) btnSignOut.setVisibility(View.GONE);
        }
    }
}
