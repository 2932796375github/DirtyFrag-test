package df.root;

import android.content.Context;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import androidx.core.content.res.ResourcesCompat;

import androidx.appcompat.app.AppCompatActivity;

import df.root.databinding.ActivityMainBinding;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements IReporter {

    private static final String TAG = "dfroot";

    private ActivityMainBinding binding;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Executor mExec = Executors.newSingleThreadExecutor();
    private final StringBuilder logBuffer = new StringBuilder();
    private File lastLogFile;

    @Override
    public void report(String msg) {
        Log.i(TAG, msg.trim());
        mMain.post(() -> {
            for (String line : msg.split("\n", -1)) {
                String t = line.trim();
                // Skip empty lines to keep the log compact.
                if (t.isEmpty()) {
                    continue;
                }
                // Drop byte-progress counters ("0 …", "512 …", ...).
                if (t.matches("\\d+\\s*(…|\\.{3})?")) {
                    continue;
                }
                // Drop internal patch/hook details.
                if (t.contains("hook=") || t.matches("\\*+SUCCESS\\*+")) {
                    continue;
                }
                // Strip hex file offsets: ".../libc.so+0x6e8b0" -> ".../libc.so"
                t = t.replaceAll("\\+0x[0-9a-fA-F]+$", "");
                appendLog(t);
            }
            binding.outputScroll.post(() -> binding.outputScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void appendLog(String line) {
        binding.outputView.append(styleLogLine(line));
        binding.outputView.append("\n");
        logBuffer.append(line).append('\n');
        saveLog();
    }

    /** Section headers ("=== x ===") render big, white and bold; the rest is dimmed. */
    private CharSequence styleLogLine(String line) {
        SpannableString ss = new SpannableString(line);
        if (line.startsWith("===") && line.endsWith("===")) {
            ss.setSpan(new StyleSpan(Typeface.BOLD), 0, line.length(), 0);
            ss.setSpan(new RelativeSizeSpan(1.3f), 0, line.length(), 0);
            ss.setSpan(new ForegroundColorSpan(0xFFFFFFFF), 0, line.length(), 0);
        } else {
            ss.setSpan(new ForegroundColorSpan(0xFFB3B3B3), 0, line.length(), 0);
        }
        return ss;
    }

    private void saveLog() {
        try (FileOutputStream out = new FileOutputStream(lastLogFile)) {
            out.write(logBuffer.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
        // Remember which boot this log came from - it is invalidated on reboot.
        createDeviceProtectedStorageContext()
                .getSharedPreferences("dfroot", MODE_PRIVATE)
                .edit().putInt("log_boot_count", bootCount()).apply();
    }

    private int bootCount() {
        try {
            return android.provider.Settings.Global.getInt(getContentResolver(),
                    android.provider.Settings.Global.BOOT_COUNT, -1);
        } catch (Exception e) {
            return -1;
        }
    }

    private String readLastLog() {
        if (lastLogFile == null || !lastLogFile.exists()) return "";
        try (java.io.FileInputStream in = new java.io.FileInputStream(lastLogFile);
             java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) != -1; ) bos.write(buf, 0, n);
            String log = bos.toString(java.nio.charset.StandardCharsets.UTF_8.name()).trim();
            // Normalize exploit-result headers to lowercase (older runs saved
            // them uppercase); the file gets rewritten on next save.
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(===\\s*exploit\\s+(?:success|failed[^=]*)\\s*===)",
                            java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(log);
            StringBuilder sb = new StringBuilder();
            while (m.find()) m.appendReplacement(sb, java.util.regex.Matcher
                    .quoteReplacement(m.group().toLowerCase()));
            m.appendTail(sb);
            return sb.toString();
        } catch (IOException e) {
            return "";
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        // NOTE: no setSupportActionBar() - it makes the ActionBar delegate draw
        // the title and ignore the toolbar's titleTextAppearance (breaks bold).
        // The toolbar renders its own title via app:titleTextAppearance.

        // Force real bold (wght 700) One UI Sans on the toolbar title TextView.
        binding.toolbar.post(() -> {
            Typeface base = ResourcesCompat.getFont(this, R.font.inter_vf);
            if (base == null) return;
            Typeface bold = Typeface.create(base, 700, false);
            for (int i = 0; i < binding.toolbar.getChildCount(); i++) {
                View child = binding.toolbar.getChildAt(i);
                if (child instanceof TextView) {
                    ((TextView) child).setTypeface(bold);
                }
            }
        });

        if (new File("/dev/df").exists()) {
            setRootedState();
        }

        // Show the log from the last run, if any. No run yet -> log hidden.
        // A log saved before the current boot is stale: delete it so a reboot
        // always starts with a clean screen (log + share icon hidden).
        lastLogFile = new File(createDeviceProtectedStorageContext().getFilesDir(), "last_run.log");
        int savedBoot = createDeviceProtectedStorageContext()
                .getSharedPreferences("dfroot", MODE_PRIVATE).getInt("log_boot_count", -1);
        if (lastLogFile.exists() && savedBoot != bootCount()) {
            lastLogFile.delete();
        }
        String last = readLastLog();
        boolean hasLastLog = !last.isEmpty();
        if (hasLastLog) {
            appendLog("=== last run ===");
            for (String l : last.split("\n")) {
                String t = l.trim();
                // Skip old headers - they were saved by previous app opens.
                if (t.isEmpty() || t.equals("=== last run ===")) continue;
                appendLog(t);
            }
        }
        binding.outputScroll.setVisibility(hasLastLog ? View.VISIBLE : View.GONE);
        binding.btnShareLog.setVisibility(hasLastLog ? View.VISIBLE : View.GONE);

        binding.btnRun.setOnClickListener(v -> {
            binding.btnRun.setEnabled(false);
            binding.outputView.setText("");
            logBuffer.setLength(0);
            if (lastLogFile.exists()) lastLogFile.delete();
            binding.outputScroll.setVisibility(View.VISIBLE);
            binding.btnShareLog.setVisibility(View.VISIBLE);
            mExec.execute(() -> runExploit(false));
        });

        binding.btnShareLog.setOnClickListener(v -> shareLog());

        ComponentName bootReceiver = new ComponentName(this, BootReceiver.class);
        int state = getPackageManager().getComponentEnabledSetting(bootReceiver);
        boolean bootEnabled = state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        binding.switchBootStart.setChecked(bootEnabled);
        binding.switchBootStart.setOnCheckedChangeListener((btn, checked) -> {
            getPackageManager().setComponentEnabledSetting(bootReceiver,
                checked ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
            binding.switchAutoSoftReboot.setEnabled(checked);
        });

        boolean autoSoftReboot = createDeviceProtectedStorageContext()
                .getSharedPreferences("dfroot", MODE_PRIVATE)
                .getBoolean("auto_soft_reboot", false);
        binding.switchAutoSoftReboot.setChecked(autoSoftReboot);
        binding.switchAutoSoftReboot.setEnabled(bootEnabled);
        binding.switchAutoSoftReboot.setOnCheckedChangeListener((btn, checked) ->
            createDeviceProtectedStorageContext()
                .getSharedPreferences("dfroot", MODE_PRIVATE)
                .edit().putBoolean("auto_soft_reboot", checked).apply());
    }

    private void setRootedState() {
        setRootedState(true);
    }

    private void setRootedState(boolean rooted) {
        binding.btnRun.setEnabled(!rooted);
        binding.btnRun.setText(rooted ? "Rooted" : "Run exploit");
        binding.btnRun.setTextColor(
                rooted ? 0xFF6E6E6E : 0xFFC9C9C9);
        binding.btnRun.setBackgroundTintList(ColorStateList.valueOf(
                rooted ? 0xFF1F1F1F : 0xFF262626));
        ((com.google.android.material.button.MaterialButton) binding.btnRun)
                .setStrokeColor(ColorStateList.valueOf(
                        rooted ? 0xFF1F1F1F : 0x33FFFFFF));
    }

    /** Saves the current log to Downloads and opens the Downloads screen. */
    private void shareLog() {
        String log = logBuffer.length() > 0 ? logBuffer.toString() : readLastLog();
        if (log.trim().isEmpty()) {
            return;
        }
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, "dirtyfrag_log.txt");
            values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            Uri uri = getContentResolver().insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
                    out.write(log.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "save log failed", e);
        }
        try {
            startActivity(new Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS));
        } catch (Exception e) {
            Log.e(TAG, "open downloads failed", e);
        }
    }

    private void runExploit(boolean softReboot) {
        try {
            int rc = ExploitRunner.run(this, this, softReboot);
            if (rc != 0) {
                String why = rc == 1 ? "ksud exited with error" : "check logs";
                report("\n=== exploit failed: " + why + " ===\n");
            } else {
                report("\n=== exploit success ===\n");
            }
        } catch (Exception e) {
            Log.e(TAG, "exploit exception", e);
            report("\nexception: " + e + "\n");
        } finally {
            mMain.post(() -> setRootedState(new File("/dev/df").exists()));
        }
    }
}
