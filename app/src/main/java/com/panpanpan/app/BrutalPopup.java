package com.panpanpan.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

/**
 * Shared neo-brutalist popups so app feedback does not fall back to the
 * default Android/Kotlin Java toast/dialog chrome.
 */
public final class BrutalPopup {
    public static final int LENGTH_SHORT = Toast.LENGTH_SHORT;
    public static final int LENGTH_LONG = Toast.LENGTH_LONG;

    private BrutalPopup() {}

    public static void toast(Context context, int messageRes, int duration) {
        toast(context, context.getString(messageRes), duration);
    }

    public static void toast(Context context, CharSequence message, int duration) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            new Handler(Looper.getMainLooper()).post(() -> toast(context, message, duration));
            return;
        }
        View view = LayoutInflater.from(context).inflate(R.layout.toast_brutal, null, false);
        TextView messageView = view.findViewById(R.id.toast_message);
        messageView.setText(message);

        Toast toast = new Toast(context.getApplicationContext());
        toast.setDuration(duration);
        toast.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, dp(context, 72));
        toast.setView(view);
        toast.show();
    }

    public static void dialog(
            Activity activity,
            CharSequence title,
            CharSequence message,
            CharSequence positiveText,
            Runnable positiveAction,
            CharSequence negativeText,
            Runnable negativeAction,
            CharSequence neutralText,
            Runnable neutralAction,
            boolean cancelable
    ) {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(cancelable);
        dialog.setCanceledOnTouchOutside(cancelable);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.dialog_brutal_bg);
        int outer = dp(activity, 8);
        int side = dp(activity, 24);
        root.setPadding(side, dp(activity, 22), side + outer, dp(activity, 24) + outer);

        TextView titleView = new TextView(activity);
        titleView.setText(title);
        titleView.setTextColor(ContextCompat.getColor(activity, R.color.brutal_black));
        titleView.setTextSize(22);
        titleView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleView.setLetterSpacing(0.04f);
        root.addView(titleView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView badge = new TextView(activity);
        badge.setText("PAPIANO POPUP");
        badge.setTextColor(ContextCompat.getColor(activity, R.color.accent_glow));
        badge.setTextSize(10);
        badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        badge.setLetterSpacing(0.12f);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeLp.setMargins(0, dp(activity, 4), 0, dp(activity, 12));
        root.addView(badge, badgeLp);

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        TextView messageView = new TextView(activity);
        messageView.setText(message);
        messageView.setTextColor(ContextCompat.getColor(activity, R.color.text_secondary));
        messageView.setTextSize(14);
        messageView.setLineSpacing(dp(activity, 3), 1.0f);
        messageView.setMaxHeight(Math.min(dp(activity, 320),
                activity.getResources().getDisplayMetrics().heightPixels / 2));
        scroll.addView(messageView, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        scrollLp.setMargins(0, 0, 0, dp(activity, 16));
        root.addView(scroll, scrollLp);

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.VERTICAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        addButton(activity, buttons, dialog, positiveText, positiveAction, R.drawable.button_gradient);
        if (neutralText != null) {
            addButton(activity, buttons, dialog, neutralText, neutralAction, R.drawable.button_muted);
        }
        if (negativeText != null) {
            addButton(activity, buttons, dialog, negativeText, negativeAction, R.drawable.button_danger);
        }
        root.addView(buttons, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
        }
        dialog.setOnShowListener(d -> {
            Window shownWindow = dialog.getWindow();
            if (shownWindow != null) {
                shownWindow.setBackgroundDrawableResource(android.R.color.transparent);
                shownWindow.setDimAmount(0.55f);
                shownWindow.setLayout(
                        Math.min(dp(activity, 360), activity.getResources().getDisplayMetrics().widthPixels - dp(activity, 32)),
                        ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        });
        dialog.show();
    }

    private static void addButton(
            Context context,
            LinearLayout parent,
            Dialog dialog,
            CharSequence text,
            Runnable action,
            int backgroundRes
    ) {
        Button button = new Button(context);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(ContextCompat.getColor(context, R.color.brutal_black));
        button.setTextSize(13);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setMinHeight(dp(context, 48));
        button.setBackgroundResource(backgroundRes);
        button.setOnClickListener(v -> {
            dialog.dismiss();
            if (action != null) action.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, 52));
        lp.setMargins(0, dp(context, 6), 0, 0);
        parent.addView(button, lp);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
