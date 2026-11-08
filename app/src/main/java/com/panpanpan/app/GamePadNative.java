package com.panpanpan.app;

import static com.panpanpan.app.MainActivity.TAG;

import android.app.IApplicationThread;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Scanner;

public class GamePadNative {
    static boolean isUHidCreated = false;

    public static void main(String[] args) {
        //检查权限
        int uid = android.os.Process.myUid();
        if (uid != 0 && uid != 2000) {
            System.err.printf("Insufficient permission! Need to be launched by adb (uid 2000) or root (uid 0), but your uid is %d \n", uid);
            System.exit(255);
            return;
        }

        System.loadLibrary("panpanpan");

        System.out.println("Start GamePad Service. Enter \"exit\" here at any time to exit.");
        sendBinderToAppByStickyBroadcast();//发送binder给APP // This is called ONLY once, it's a special type of broadcast called a sticky broadcast
        // This tells other applications that this script has started
        // Due to the type of broadcast, applications which weren't listening when the broadcast was sent initially will still receive
        // this broadcast as soon as they start listening and hence it's called a sticky broadcast


        //加入JVM异常关闭时的处理程序
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                if (isUHidCreated) isUHidCreated = !nativeCloseUHid();
            }
        });

        try {
            Scanner scanner = new Scanner(System.in);
            //用来保持进程不退出，同时如果用户输入exit则程序退出
            String inline;
            while ((inline = scanner.nextLine()) != null) {
                if (inline.equals("exit"))
                    break;
            }
            scanner.close();
        } catch (Exception unused) {
            //用户使用nohup命令启动，scanner捕捉不到任何输入,会抛出异常。
            // Block thread tanpa burn CPU. Object.wait() lebih ringan dari
            // Thread.sleep(MAX) karena gak perlu timer interrupt.
            try {
                final Object keepAlive = new Object();
                synchronized (keepAlive) {
                    keepAlive.wait(); // sleeps forever, 0% CPU
                }
            } catch (InterruptedException e) {
                // exit gracefully if interrupted
            }
        }


        if (isUHidCreated) isUHidCreated = !nativeCloseUHid();
        System.out.println("Stop GamePad Service.\n");
    }

    static native boolean nativeCreateUHid();

    static native boolean nativeCloseUHid();


    static native void nativeQwertyKey(int noteNumber, boolean isDown, int velocity);

    private static void sendBinderToAppByStickyBroadcast() {

        try {
            //生成binder
            IBinder binder = new IGamePad.Stub() {
                @Override
                public void qwertyKey(int noteNumber, boolean isDown, int velocity) throws RemoteException {
                    // QWERTY mode supports proper hold/sustain:
                    //   isDown=true  -> add note to held set, emit aggregated HID report
                    //   isDown=false -> remove note from held set, emit updated report
                    //
                    // velocity: 0 = velocity OFF (QWERTY polos). >0 = Visual Piano
                    // velocity mode -> native akan tap Alt+<velKey> (skema 32-step)
                    // sebelum not kalau level velocity berubah. Gating ON/OFF
                    // dilakukan di MidiBridgeService (kirim 0 saat toggle OFF).
                    nativeQwertyKey(noteNumber, isDown, velocity);
                }

                @Override
                public boolean close() throws RemoteException {
                    if (isUHidCreated) isUHidCreated = !nativeCloseUHid();
                    return !(isUHidCreated);
                }

                @Override
                // The following function creates an HID device only if it hasn't been created before
                // In case it has been created before, it just returns the boolean indicating it has already
                public boolean create() throws RemoteException {
                    Log.d(TAG, "GamePadNative has started successfully.");
                    if (!isUHidCreated)
                        isUHidCreated = nativeCreateUHid();
                    return isUHidCreated;
                }


                @Override
                public void closeAndExit() throws RemoteException {
                    if (isUHidCreated) isUHidCreated = !nativeCloseUHid();
                    System.out.println("Stop GamePad Service.\n");
                    System.exit(0);
                }
            };

            // Create an HID as soon as we start GamePadNative
            isUHidCreated = nativeCreateUHid();

            //把binder填到一个可以用Intent来传递的容器中
            BinderContainer binderContainer = new BinderContainer(binder);
            // 创建 Intent 对象，并将binder作为附加参数
            Intent intent = new Intent("intent.panpanpan.send_binder");
            intent.putExtra("binder", binderContainer);
            // Scope to our own package. Without this, ANY app on the device
            // that registers a receiver for this same (unscoped) action name
            // gets this sticky broadcast too — including forks of the same
            // upstream project using an identical action string but a
            // DIFFERENT BinderContainer class. That other app then crashes
            // with BadParcelableException/ClassNotFoundException trying to
            // unmarshal a Parcelable class it doesn't have (confirmed via a
            // real crash log from exactly that scenario). Scoping also stops
            // this app's own stale sticky broadcast from lingering visible
            // to every other app on the device indefinitely.
            intent.setPackage("com.panpanpan.app");

            Object iActivityManagerObj; // 获取 IActivityManager 类
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                iActivityManagerObj = Class.forName("android.app.IActivityManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, Class.forName("android.os.ServiceManager").getDeclaredMethod("getService", String.class).invoke(null, "activity"));
            } else {
                Class<?> activityManagerNativeClass = Class.forName("android.app.ActivityManagerNative");
                Method getDefaultMethod = activityManagerNativeClass.getMethod("getDefault");
                iActivityManagerObj = getDefaultMethod.invoke(activityManagerNativeClass);
            }
            // 获取 broadcastIntent 方法
            Method broadcastIntentMethod = Class.forName("android.app.IActivityManager").getDeclaredMethod(
                    "broadcastIntent",
                    IApplicationThread.class,
                    Intent.class,
                    String.class,
                    IIntentReceiver.class,
                    int.class,
                    String.class,
                    Bundle.class,
                    String[].class,
                    int.class,
                    Bundle.class,
                    boolean.class,
                    boolean.class,
                    int.class
            );
            // 调用 broadcastIntent 方法，发送粘性广播
            broadcastIntentMethod.invoke(
                    iActivityManagerObj,
                    null,
                    intent,
                    null,
                    null,
                    -1,
                    null,
                    null,
                    null,
                    0,
                    null,
                    false,
                    true, // Makes broadcast sticky(meaning even if no components were listening at the time, they will receive all what they missed upon listening)
                    -1
            );

        } catch (Exception e) {
            System.err.println("Failed to send broadcast!");
            System.exit(-1);
        }
    }
}
