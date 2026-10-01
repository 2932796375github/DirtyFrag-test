package df.root;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.PowerManager;
import android.util.Log;

import java.io.File;

/**
 * Auto-root on boot. Safety rules learned in the field:
 *  - NEVER soft-reboot during a boot-time run (that was the brick vector).
 *  - A failed run disables the receiver; the user re-enables it in the app.
 */
public class BootReceiver extends BroadcastReceiver implements IReporter {
    private static final String TAG = "dfroot";

    @Override
    public void report(String msg) {
        Log.i(TAG, msg.trim());
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (new File("/dev/df").exists()) {
            Log.i(TAG, "boot: already hooked, skipping");
            return;
        }
        Log.i(TAG, "boot: " + intent.getAction());
        final Context deCtx = context.createDeviceProtectedStorageContext();
        boolean autoSoftReboot = deCtx.getSharedPreferences("dfroot", Context.MODE_PRIVATE)
                .getBoolean("auto_soft_reboot", false);

        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dfroot:boot");
        wl.acquire();
        new Thread(() -> {
            boolean ok = false;
            try {
                // auto_soft_reboot=true: on success, ksud triggers its OWN soft
                // reboot after late-load completes - so Zygisk/LSPosed modules
                // (which need a fresh Zygote with the module loaded) become
                // active in the same power session. With it off, the exploit
                // still roots the device but nothing reboots. Manual button
                // runs are never affected by this setting.
                int rc = ExploitRunner.run(deCtx, this, autoSoftReboot);
                Log.i(TAG, "boot: exploit rc=" + rc);
                ok = rc == 0;
            } catch (Exception e) {
                Log.e(TAG, "boot: exploit exception", e);
            } finally {
                wl.release();
            }
            if (!ok) {
                Log.i(TAG, "boot: failed - disabling autorun interlock");
                context.getPackageManager().setComponentEnabledSetting(
                        new ComponentName(context, BootReceiver.class),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP);
            }
        }, "dfroot-boot").start();
    }
}