package eu.faircode.email;

/*
    This file is part of FairEmail.
*/

import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.sqlite.db.SupportSQLiteDatabase;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * comms: on-device debug tap, for pulling state off a phone with no root, no adb
 * and no Shizuku running.
 *
 *   am broadcast -a com.diegonmarcos.comms.mail.DEBUG_DUMP
 *
 * writes a dump to public Downloads/mail-debug/, which an unprivileged shell
 * (Termux) can read -- unlike getExternalFilesDir(), which Android 11+ hides
 * from other uids, and unlike logcat, which only ever shows the caller's own
 * process. Both halves of that round trip were verified on the device before
 * this was written.
 *
 * ponytail: exported with no shared secret. Single-user, self-signed device,
 * and the dump deliberately carries no credentials -- logcat, EntityLog and row
 * counts only, never account/identity rows. Gate it on a secret extra if this
 * ever ships to a phone where someone else installs apps.
 */
public class DebugDumpReceiver extends BroadcastReceiver {
    private static final long LOG_WINDOW = 24 * 3600 * 1000L; // 24h of EntityLog
    private static final String LOGCAT_LINES = "2000";

    @Override
    public void onReceive(Context context, Intent intent) {
        final Context ctx = context.getApplicationContext();
        final PendingResult result = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    write(ctx, collect(ctx));
                } catch (Throwable ex) {
                    Log.e(ex);
                } finally {
                    result.finish();
                }
            }
        }, "debug-dump").start();
    }

    private static String collect(Context context) {
        StringBuilder sb = new StringBuilder();
        sb.append("time=").append(new Date()).append("\r\n");
        sb.append("package=").append(BuildConfig.APPLICATION_ID).append("\r\n");
        sb.append("version=").append(BuildConfig.VERSION_NAME)
                .append(" (").append(BuildConfig.VERSION_CODE).append(")\r\n");
        sb.append("device=").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
                .append(" sdk=").append(Build.VERSION.SDK_INT).append("\r\n\r\n");

        // Why the list is (not) empty: does the data exist, and does threading
        // collapse it away? These mirror the shape of the paged list queries.
        sb.append("--- counts ---\r\n");
        counts(context, sb);

        sb.append("\r\n--- prefs ---\r\n");
        try {
            sb.append("inline_threads=")
                    .append(androidx.preference.PreferenceManager
                            .getDefaultSharedPreferences(context)
                            .getBoolean("inline_threads", false)).append("\r\n");
            sb.append("threading=")
                    .append(androidx.preference.PreferenceManager
                            .getDefaultSharedPreferences(context)
                            .getBoolean("threading", true)).append("\r\n");
        } catch (Throwable ex) {
            sb.append(ex).append("\r\n");
        }

        sb.append("\r\n--- log (24h) ---\r\n");
        try {
            DB db = DB.getInstance(context);
            for (EntityLog entry : db.log().getLogs(new Date().getTime() - LOG_WINDOW, null))
                sb.append(entry.time == null ? "?" : new Date(entry.time).toString())
                        .append(' ').append(entry.data).append("\r\n");
        } catch (Throwable ex) {
            sb.append(stack(ex));
        }

        sb.append("\r\n--- logcat ---\r\n");
        sb.append(logcat());

        return sb.toString();
    }

    private static void counts(Context context, StringBuilder sb) {
        // Raw SQL rather than the DAOs: the point is to see what the database
        // actually holds, independent of the Room queries under suspicion.
        String[][] probes = {
                {"messages", "SELECT COUNT(*) FROM message"},
                {"visible", "SELECT COUNT(*) FROM message WHERE NOT ui_hide"},
                {"threads", "SELECT COUNT(DISTINCT COALESCE(thread, CAST(id AS TEXT)))" +
                        " FROM message WHERE NOT ui_hide"},
                {"unified", "SELECT COUNT(*) FROM message" +
                        " JOIN folder ON folder.id = message.folder" +
                        " JOIN account ON account.id = message.account" +
                        " WHERE folder.unified AND NOT message.ui_hide" +
                        " AND account.synchronize"},
                {"folders", "SELECT COUNT(*) FROM folder WHERE synchronize"},
                {"accounts", "SELECT COUNT(*) FROM account WHERE synchronize"},
        };

        try {
            SupportSQLiteDatabase db = DB.getInstance(context).getOpenHelper().getReadableDatabase();
            for (String[] probe : probes)
                sb.append(probe[0]).append('=').append(scalar(db, probe[1])).append("\r\n");

            sb.append("per-folder:\r\n");
            try (Cursor cursor = db.query("SELECT folder.type, COUNT(*) FROM message" +
                    " JOIN folder ON folder.id = message.folder" +
                    " WHERE NOT message.ui_hide GROUP BY folder.type")) {
                while (cursor.moveToNext())
                    sb.append("  ").append(cursor.getString(0)).append('=')
                            .append(cursor.getLong(1)).append("\r\n");
            }
        } catch (Throwable ex) {
            sb.append(stack(ex));
        }
    }

    private static String scalar(SupportSQLiteDatabase db, String sql) {
        try (Cursor cursor = db.query(sql)) {
            return cursor.moveToNext() ? Long.toString(cursor.getLong(0)) : "?";
        } catch (Throwable ex) {
            return "ERR " + ex;
        }
    }

    private static String logcat() {
        // Own process only -- that is all an unprivileged uid may read, and it
        // is where AdapterMessage's swallowed Log.e(ex) lands.
        StringBuilder sb = new StringBuilder();
        Process proc = null;
        try {
            proc = new ProcessBuilder("logcat",
                    "-d",
                    "-v", "threadtime",
                    "-t", LOGCAT_LINES,
                    "--pid=" + android.os.Process.myPid())
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null)
                    sb.append(line).append("\r\n");
            }
        } catch (Throwable ex) {
            sb.append(stack(ex));
        } finally {
            if (proc != null)
                proc.destroy();
        }
        return sb.toString();
    }

    private static String stack(Throwable ex) {
        StringWriter sw = new StringWriter();
        ex.printStackTrace(new PrintWriter(sw));
        return sw + "\r\n";
    }

    private static void write(Context context, String content) throws Exception {
        String name = "mail-debug-" +
                new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".txt";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
            cv.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            cv.put(MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/mail-debug");
            Uri uri = context.getContentResolver()
                    .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null)
                throw new IllegalStateException("MediaStore insert failed");
            try (OutputStream os = context.getContentResolver().openOutputStream(uri)) {
                os.write(bytes);
            }
            Log.i("Debug dump written to " + uri);
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "mail-debug");
            dir.mkdirs();
            File file = new File(dir, name);
            try (OutputStream os = new FileOutputStream(file)) {
                os.write(bytes);
            }
            Log.i("Debug dump written to " + file);
        }
    }
}
