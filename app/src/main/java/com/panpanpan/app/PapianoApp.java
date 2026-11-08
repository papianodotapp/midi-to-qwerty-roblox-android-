package com.panpanpan.app;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Date;

/**
 * App Application class. Tujuan utama: pasang crash handler di
 * attachBaseContext (paling awal di lifecycle process) supaya stack trace
 * crash tetep ke-tangkep walaupun kejadian di luar Activity (mis. broadcast
 * receiver yg fire sebelum onResume, IntentService, dll).
 *
 * last_crash.txt ditulis ke external files dir untuk debugging.
 */
public class PapianoApp extends Application {
    public static final String TAG = "PapianoMidi";

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        installEarlyCrashHandler(base.getApplicationContext() != null
                ? base.getApplicationContext() : base);
    }

    private void installEarlyCrashHandler(final Context appCtx) {
        final Thread.UncaughtExceptionHandler prev =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                Log.e(TAG, "FATAL UNCAUGHT thread=" + thread.getName(), throwable);
                writeCrash(appCtx, thread, throwable);
            } catch (Throwable ignored) {}
            if (prev != null) {
                try { prev.uncaughtException(thread, throwable); }
                catch (Throwable ignored) {}
            }
        });
    }

    private void writeCrash(Context base, Thread thread, Throwable t) {
        File dir = base.getFilesDir();
        if (dir == null) return;
        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            pw.println("Thread  : " + thread.getName());
            pw.println("Time    : " + new Date());
            pw.println("Device  : " + Build.MANUFACTURER + " " + Build.MODEL);
            pw.println("Android : " + Build.VERSION.RELEASE
                    + " (api " + Build.VERSION.SDK_INT + ")");
            pw.println();
            t.printStackTrace(pw);
            pw.flush();

            FileWriter w = new FileWriter(new File(dir, "last_crash.txt"), false);
            w.write(sw.toString());
            w.flush();
            w.close();
        } catch (Throwable ignored) {}
    }
}
