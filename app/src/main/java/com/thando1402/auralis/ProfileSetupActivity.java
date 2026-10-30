package com.thando1402.auralis;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.EmailAuthProvider;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.UserProfileChangeRequest;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ProfileSetupActivity extends AppCompatActivity {

    private EditText inputDisplayName, inputEmail, inputPassword;
    private TextView tvAuthStatus;
    private MaterialButton btnSignIn, btnRegister, btnGuest, btnSignOut;
    private boolean authRequestInFlight = false;
    private ExecutorService syncExecutor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile_setup);
        syncExecutor = Executors.newSingleThreadExecutor();

        MaterialToolbar toolbar = findViewById(R.id.toolbar_profile_setup);
        if (toolbar != null) {
            toolbar.setNavigationOnClickListener(v -> {
                finish();
                NavigationHelper.applySlideTransition(this, false);
            });
        }

        inputDisplayName = findViewById(R.id.input_profile_display_name);
        inputEmail = findViewById(R.id.input_auth_email);
        inputPassword = findViewById(R.id.input_auth_password);
        tvAuthStatus = findViewById(R.id.tv_auth_status_banner);

        MaterialButton btnSaveLocal = findViewById(R.id.btn_save_local_profile);
        btnSignIn = findViewById(R.id.btn_auth_sign_in);
        btnRegister = findViewById(R.id.btn_auth_register);
        btnGuest = findViewById(R.id.btn_auth_anonymous);
        btnSignOut = findViewById(R.id.btn_auth_sign_out);

        String currentName = OpenRouterAiHelper.getProfileName(this);
        if (inputDisplayName != null && !Objects.equals(currentName, getString(R.string.profile_default_name))) {
            inputDisplayName.setText(currentName);
        }

        if (btnSaveLocal != null) {
            btnSaveLocal.setOnClickListener(v -> {
                String enteredName = inputDisplayName != null && inputDisplayName.getText() != null
                        ? inputDisplayName.getText().toString().trim() : "";
                if (enteredName.isEmpty()) {
                    Toast.makeText(this, "Enter a display name", Toast.LENGTH_SHORT).show();
                    return;
                }
                OpenRouterAiHelper.setProfileName(this, enteredName);
                syncDisplayNameToFirebaseUser(enteredName);
                syncProfileToFirestore();
                Toast.makeText(this, "Profile saved (" + enteredName + ")", Toast.LENGTH_SHORT).show();
                finish();
                NavigationHelper.applySlideTransition(this, false);
            });
        }

        if (btnSignIn != null) {
            btnSignIn.setOnClickListener(v -> handleEmailAuth(false));
        }

        if (btnRegister != null) {
            btnRegister.setOnClickListener(v -> handleEmailAuth(true));
        }

        if (btnGuest != null) {
            btnGuest.setOnClickListener(v -> {
                if (authRequestInFlight) return;
                saveTypedNameIfPresent();
                try {
                    FirebaseApp.initializeApp(this);
                    FirebaseAuth auth = FirebaseAuth.getInstance();
                    FirebaseUser existing = auth.getCurrentUser();
                    if (existing != null) {
                        syncProfileToFirestore();
                        refreshAuthStatusUI();
                        Toast.makeText(this, "Already signed in — profile synced!", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    setAuthRequestInFlight(true, R.string.setup_status_connecting_guest);
                    auth.signInAnonymously()
                            .addOnCompleteListener(this, task -> {
                                setAuthRequestInFlight(false, 0);
                                if (task.isSuccessful()) {
                                    if (!OpenRouterAiHelper.isProfileConfigured(this)) {
                                        OpenRouterAiHelper.setProfileName(this, getString(R.string.profile_default_name));
                                    }
                                    syncProfileToFirestore();
                                    refreshAuthStatusUI();
                                    Toast.makeText(this, "Guest Cloud Sync active!", Toast.LENGTH_SHORT).show();
                                } else {
                                    refreshAuthStatusUI();
                                    String err = task.getException() != null ? task.getException().getLocalizedMessage() : "Auth unavailable";
                                    Toast.makeText(this, "Offline profile kept (" + err + ")", Toast.LENGTH_SHORT).show();
                                }
                            });
                } catch (Exception e) {
                    setAuthRequestInFlight(false, 0);
                    refreshAuthStatusUI();
                    Toast.makeText(this, "Firebase unavailable — local profile saved", Toast.LENGTH_SHORT).show();
                }
            });
        }

        if (btnSignOut != null) {
            btnSignOut.setOnClickListener(v -> {
                if (authRequestInFlight) return;
                try {
                    FirebaseAuth.getInstance().signOut();
                    refreshAuthStatusUI();
                    Toast.makeText(this, "Signed out of cloud account", Toast.LENGTH_SHORT).show();
                } catch (Exception ignored) {
                }
            });
        }

        UiAnimationHelper.attachSpringTouch(btnSaveLocal, btnSignIn, btnRegister, btnGuest, btnSignOut);
        refreshAuthStatusUI();
    }

    private void setAuthRequestInFlight(boolean inFlight, @StringRes int statusResId) {
        authRequestInFlight = inFlight;
        if (btnSignIn != null) btnSignIn.setEnabled(!inFlight);
        if (btnRegister != null) btnRegister.setEnabled(!inFlight);
        if (btnGuest != null) btnGuest.setEnabled(!inFlight);
        if (btnSignOut != null) btnSignOut.setEnabled(!inFlight);
        if (statusResId != 0 && tvAuthStatus != null) {
            tvAuthStatus.setText(statusResId);
        }
    }

    private void saveTypedNameIfPresent() {
        if (inputDisplayName != null && inputDisplayName.getText() != null) {
            String name = inputDisplayName.getText().toString().trim();
            if (!name.isEmpty()) {
                OpenRouterAiHelper.setProfileName(this, name);
                syncDisplayNameToFirebaseUser(name);
            }
        }
    }

    private void applyDefaultDisplayNameFromEmailIfNeeded(String email) {
        String typedName = inputDisplayName != null && inputDisplayName.getText() != null
                ? inputDisplayName.getText().toString().trim() : "";
        if (!typedName.isEmpty()) {
            OpenRouterAiHelper.setProfileName(this, typedName);
            syncDisplayNameToFirebaseUser(typedName);
            return;
        }
        try {
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user != null && user.getDisplayName() != null && !user.getDisplayName().trim().isEmpty()) {
                OpenRouterAiHelper.setProfileName(this, user.getDisplayName().trim());
                return;
            }
        } catch (Exception ignored) {
        }
        if (!OpenRouterAiHelper.isProfileConfigured(this) && email != null) {
            int atIdx = email.indexOf('@');
            String derived = atIdx > 0 ? email.substring(0, atIdx) : email;
            OpenRouterAiHelper.setProfileName(this, derived);
            syncDisplayNameToFirebaseUser(derived);
        }
    }

    private void syncDisplayNameToFirebaseUser(String displayName) {
        if (displayName == null || displayName.isEmpty()) return;
        try {
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user != null) {
                UserProfileChangeRequest profileUpdates = new UserProfileChangeRequest.Builder()
                        .setDisplayName(displayName)
                        .build();
                user.updateProfile(profileUpdates);
            }
        } catch (Exception ignored) {
        }
    }

    private void handleEmailAuth(boolean isRegister) {
        if (authRequestInFlight) return;
        saveTypedNameIfPresent();

        String email = inputEmail != null && inputEmail.getText() != null ? inputEmail.getText().toString().trim() : "";
        String password = inputPassword != null && inputPassword.getText() != null ? inputPassword.getText().toString() : "";

        if (!email.contains("@")) {
            Toast.makeText(this, "Enter a valid email address", Toast.LENGTH_SHORT).show();
            return;
        }
        if (password.length() < 6) {
            Toast.makeText(this, "Password must be at least 6 characters", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            FirebaseApp.initializeApp(this);
            FirebaseAuth auth = FirebaseAuth.getInstance();
            FirebaseUser currentUser = auth.getCurrentUser();

            // Prevent duplicate login/registration if already signed in with this email
            if (currentUser != null && email.equalsIgnoreCase(currentUser.getEmail())) {
                applyDefaultDisplayNameFromEmailIfNeeded(email);
                syncProfileToFirestore();
                refreshAuthStatusUI();
                Toast.makeText(this, "Already signed in as " + email, Toast.LENGTH_SHORT).show();
                return;
            }

            setAuthRequestInFlight(true,
                    isRegister ? R.string.setup_status_creating_account : R.string.setup_status_signing_in);

            if (isRegister) {
                // Upgrade existing anonymous session instead of creating a duplicate UID
                if (currentUser != null && currentUser.isAnonymous()) {
                    AuthCredential credential = EmailAuthProvider.getCredential(email, password);
                    currentUser.linkWithCredential(credential)
                            .addOnCompleteListener(this, task -> {
                                if (task.isSuccessful()) {
                                    setAuthRequestInFlight(false, 0);
                                    applyDefaultDisplayNameFromEmailIfNeeded(email);
                                    syncProfileToFirestore();
                                    refreshAuthStatusUI();
                                    Toast.makeText(this, "Account created & linked!", Toast.LENGTH_SHORT).show();
                                } else if (task.getException() instanceof FirebaseAuthUserCollisionException) {
                                    signInExistingAccount(auth, email, password);
                                } else {
                                    setAuthRequestInFlight(false, 0);
                                    refreshAuthStatusUI();
                                    String err = task.getException() != null ? task.getException().getLocalizedMessage() : "Registration failed";
                                    Toast.makeText(this, err, Toast.LENGTH_LONG).show();
                                }
                            });
                } else {
                    auth.createUserWithEmailAndPassword(email, password)
                            .addOnCompleteListener(this, task -> {
                                if (task.isSuccessful()) {
                                    setAuthRequestInFlight(false, 0);
                                    applyDefaultDisplayNameFromEmailIfNeeded(email);
                                    syncProfileToFirestore();
                                    refreshAuthStatusUI();
                                    Toast.makeText(this, "Cloud account created!", Toast.LENGTH_SHORT).show();
                                } else if (task.getException() instanceof FirebaseAuthUserCollisionException) {
                                    signInExistingAccount(auth, email, password);
                                } else {
                                    setAuthRequestInFlight(false, 0);
                                    refreshAuthStatusUI();
                                    String err = task.getException() != null ? task.getException().getLocalizedMessage() : "Registration failed";
                                    Toast.makeText(this, err, Toast.LENGTH_LONG).show();
                                }
                            });
                }
            } else {
                signInExistingAccount(auth, email, password);
            }
        } catch (Exception e) {
            setAuthRequestInFlight(false, 0);
            refreshAuthStatusUI();
            Toast.makeText(this, "Cloud Auth error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void signInExistingAccount(FirebaseAuth auth, String email, String password) {
        auth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, task -> {
                    setAuthRequestInFlight(false, 0);
                    if (task.isSuccessful()) {
                        applyDefaultDisplayNameFromEmailIfNeeded(email);
                        syncProfileToFirestore();
                        refreshAuthStatusUI();
                        Toast.makeText(this, "Signed in to Auralis Cloud!", Toast.LENGTH_SHORT).show();
                    } else {
                        refreshAuthStatusUI();
                        String err = task.getException() != null ? task.getException().getLocalizedMessage() : "Sign-in failed";
                        Toast.makeText(this, err, Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void syncProfileToFirestore() {
        if (syncExecutor == null) return;
        final Context appCtx = getApplicationContext();
        syncExecutor.execute(() -> {
            try {
                FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
                if (user == null) return;

                Map<String, Object> profileDoc = new HashMap<>();
                profileDoc.put("uid", user.getUid());
                profileDoc.put("displayName", OpenRouterAiHelper.getProfileName(appCtx));
                profileDoc.put("email", user.getEmail() != null ? user.getEmail() : "anonymous");
                profileDoc.put("favoritesCount", SongMetadataManager.getFavoriteUris(appCtx).size());
                profileDoc.put("updatedAtMs", System.currentTimeMillis());

                FirebaseFirestore.getInstance()
                        .collection("users")
                        .document(user.getUid())
                        .set(profileDoc, SetOptions.merge());
            } catch (Exception ignored) {
            }
        });
    }

    private void refreshAuthStatusUI() {
        if (tvAuthStatus == null) return;
        try {
            FirebaseApp.initializeApp(this);
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user != null) {
                if (user.isAnonymous()) {
                    String shortUid = user.getUid().substring(0, Math.min(8, user.getUid().length()));
                    tvAuthStatus.setText(getString(R.string.setup_status_guest_fmt, shortUid));
                } else {
                    String identity = user.getEmail() != null ? user.getEmail() : user.getUid();
                    tvAuthStatus.setText(getString(R.string.setup_status_signed_in_fmt, identity));
                }
                if (btnSignOut != null) {
                    btnSignOut.setVisibility(View.VISIBLE);
                    btnSignOut.setEnabled(!authRequestInFlight);
                }
            } else {
                tvAuthStatus.setText(R.string.setup_status_offline);
                if (btnSignOut != null) {
                    btnSignOut.setVisibility(View.GONE);
                    btnSignOut.setEnabled(false);
                }
            }
        } catch (Exception e) {
            tvAuthStatus.setText(R.string.setup_status_offline);
            if (btnSignOut != null) {
                btnSignOut.setVisibility(View.GONE);
                btnSignOut.setEnabled(false);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (syncExecutor != null) {
            syncExecutor.shutdownNow();
        }
    }
}
