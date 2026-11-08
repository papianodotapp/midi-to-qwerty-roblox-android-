package com.panpanpan.app;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.ProgressBar;

/**
 * Splash screen with fade-in logo animation + purple progress bar.
 * Transitions to MainActivity after ~2.5s.
 */
public class SplashActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        View logo = findViewById(R.id.splash_logo);
        View title = findViewById(R.id.splash_title);
        View subtitle = findViewById(R.id.splash_subtitle);
        ProgressBar progress = findViewById(R.id.splash_progress);

        // Logo fade in + scale up
        ObjectAnimator logoAlpha = ObjectAnimator.ofFloat(logo, "alpha", 0f, 1f);
        logoAlpha.setDuration(800);
        ObjectAnimator logoScale = ObjectAnimator.ofFloat(logo, "scaleX", 0.6f, 1f);
        logoScale.setDuration(800);
        ObjectAnimator logoScaleY = ObjectAnimator.ofFloat(logo, "scaleY", 0.6f, 1f);
        logoScaleY.setDuration(800);

        // Title fade in (delayed)
        ObjectAnimator titleAlpha = ObjectAnimator.ofFloat(title, "alpha", 0f, 1f);
        titleAlpha.setStartDelay(400);
        titleAlpha.setDuration(600);
        ObjectAnimator titleTransY = ObjectAnimator.ofFloat(title, "translationY", 30f, 0f);
        titleTransY.setStartDelay(400);
        titleTransY.setDuration(600);

        // Subtitle fade in (more delayed)
        ObjectAnimator subAlpha = ObjectAnimator.ofFloat(subtitle, "alpha", 0f, 1f);
        subAlpha.setStartDelay(700);
        subAlpha.setDuration(500);

        // Progress bar fade in
        ObjectAnimator progAlpha = ObjectAnimator.ofFloat(progress, "alpha", 0f, 1f);
        progAlpha.setStartDelay(900);
        progAlpha.setDuration(400);

        AnimatorSet set = new AnimatorSet();
        set.playTogether(logoAlpha, logoScale, logoScaleY, titleAlpha,
                titleTransY, subAlpha, progAlpha);
        set.setInterpolator(new DecelerateInterpolator());
        set.start();

        // Animate progress bar
        ValueAnimator progAnim = ValueAnimator.ofInt(0, 100);
        progAnim.setDuration(1800);
        progAnim.setStartDelay(1000);
        progAnim.setInterpolator(new AccelerateDecelerateInterpolator());
        progAnim.addUpdateListener(a -> progress.setProgress((int) a.getAnimatedValue()));
        progAnim.start();

        // Navigate to PermissionGateActivity after animation completes
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            startActivity(new Intent(this, PermissionGateActivity.class));
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            finish();
        }, 2800);
    }
}
