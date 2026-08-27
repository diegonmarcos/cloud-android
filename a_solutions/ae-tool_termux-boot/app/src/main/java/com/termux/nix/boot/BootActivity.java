package com.termux.nix.boot;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

/**
 * Exists for two reasons, both mandatory rather than cosmetic:
 *
 *   1. RUN_COMMAND is a "dangerous" permission, so on API 23+ it is not granted
 *      by installing -- it must be requested at runtime from an Activity.
 *   2. Android 3.1+ keeps an app in a "stopped" state, in which it receives NO
 *      broadcasts including BOOT_COMPLETED, until it is launched at least once.
 *      An installed-but-never-opened Cloud Unix Termux Boot would silently do nothing.
 *
 * So the launcher icon is not decoration: tapping it once is what arms the app.
 */
public class BootActivity extends Activity {

    private static final String PERMISSION =
        BuildConfig.NOD_PACKAGE_NAME + ".permission.RUN_COMMAND";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        boolean granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
            || checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;

        if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{PERMISSION}, 1);
        }

        TextView view = new TextView(this);
        view.setGravity(Gravity.CENTER);
        view.setPadding(48, 48, 48, 48);
        view.setText(
            "Cloud Unix Termux Boot\n\n"
            + "Runs ~/.termux/boot/ scripts in " + BuildConfig.NOD_PACKAGE_NAME
            + " at device boot.\n\n"
            + "Opening this screen once is required: until then Android keeps "
            + "the app stopped and delivers it no boot broadcast.\n\n"
            + "Also needs allow-external-apps=true in termux.properties, which "
            + "the Nix flake deploys."
        );
        setContentView(view);
    }
}
