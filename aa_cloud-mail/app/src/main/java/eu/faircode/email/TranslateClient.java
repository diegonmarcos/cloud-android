package eu.faircode.email;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

import com.diegonmarcos.cloudkeyboardlibs.ITranslateEngine;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

// Thin client that binds the standalone cloud-mail-libs translation engine over AIDL.
public class TranslateClient {
    private static final String ENGINE_PACKAGE = "com.diegonmarcos.cloudkeyboardlibs";
    private static final String ENGINE_ACTION = "com.diegonmarcos.cloudkeyboardlibs.ITranslateEngine";

    // ML Kit on-device target languages (BCP-47). Static so the language list is available
    // without binding the engine; includes the defaults en/pt/es/de plus common others.
    static final List<String> SUPPORTED_TAGS = Arrays.asList(
        "en","pt","es","de","fr","it","nl","ru","pl","tr","ar","zh","ja","ko",
        "hi","id","sv","da","no","fi","cs","el","he","th","uk","ro","hu","vi","ca");

    static boolean isEngineInstalled(Context context) {
        Intent i = new Intent(ENGINE_ACTION).setPackage(ENGINE_PACKAGE);
        return context.getPackageManager().resolveService(i, 0) != null;
    }

    // Blocking; call off the main thread. Returns {detected, translated}.
    static String[] translate(Context context, String text, String targetTag) throws IOException {
        final Context app = context.getApplicationContext();
        final CountDownLatch bound = new CountDownLatch(1);
        final ITranslateEngine[] engine = new ITranslateEngine[1];
        ServiceConnection conn = new ServiceConnection() {
            public void onServiceConnected(ComponentName name, IBinder service) {
                engine[0] = ITranslateEngine.Stub.asInterface(service);
                bound.countDown();
            }
            public void onServiceDisconnected(ComponentName name) { engine[0] = null; }
        };
        Intent intent = new Intent(ENGINE_ACTION).setPackage(ENGINE_PACKAGE);
        boolean ok;
        try {
            ok = app.bindService(intent, conn, Context.BIND_AUTO_CREATE);
        } catch (Throwable ex) {
            ok = false;
        }
        if (!ok) {
            try { app.unbindService(conn); } catch (Throwable ignored) {}
            throw new IOException("Translate engine not installed (install Cloud Translate from the app store)");
        }
        try {
            if (!bound.await(15, TimeUnit.SECONDS) || engine[0] == null)
                throw new IOException("Translate engine did not respond");
            String[] r = engine[0].translate(text, targetTag);
            if (r == null || r.length < 2)
                throw new IOException("Translation failed");
            return r;
        } catch (IOException ex) {
            throw ex;
        } catch (Throwable ex) {
            throw new IOException(ex);
        } finally {
            try { app.unbindService(conn); } catch (Throwable ignored) {}
        }
    }
}
