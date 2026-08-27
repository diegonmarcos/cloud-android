package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    FairEmail is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with FairEmail.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2018-2026 by Marcel Bokhorst (M66B)
*/

// comms (my-features): Cloud Mail branded About tab inside FragmentOptions.
// "Check for updates" runs INLINE (SimpleTask) — not via WorkManager — so it
// can download the APK and hand off to the system installer directly, without
// the notification-loop bug where the notification sends the user back to the
// settings screen they are already on.

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;

import java.io.File;

public class FragmentOptionsCommsAbout extends FragmentBase {

    @Override
    @Nullable
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_options_comms_about, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        TextView tvVersion = view.findViewById(R.id.comms_tv_version);
        tvVersion.setText(versionText(view.getContext()));

        // comms: channel label derived from the SAME BuildConfig coords the
        // updater queries — the layout used to hardcode the retired GHCR
        // channel, which misidentified installed builds.
        TextView tvChannel = view.findViewById(R.id.comms_tv_channel);
        tvChannel.setText("github.com/" + BuildConfig.COMMS_GH_OWNER
                + "/" + BuildConfig.COMMS_GH_REPO + "/releases");

        Button btnCheck = view.findViewById(R.id.comms_btn_check_update);
        btnCheck.setOnClickListener(v -> checkAndInstall(btnCheck));

        Button btnOverride = view.findViewById(R.id.comms_btn_stamp_override);
        btnOverride.setOnClickListener(v -> editStampOverride(tvVersion));

        // comms: Auto-update (silent) toggle + "Install unknown apps" grant.
        // ON (default) → CommsInstaller commits with USER_ACTION_NOT_REQUIRED;
        // the grant is what makes that actually skip the install prompt.
        Button btnAuto = view.findViewById(R.id.comms_btn_auto_update);
        Button btnGrant = view.findViewById(R.id.comms_btn_grant_install);
        refreshUpdateButtons(view.getContext(), btnAuto, btnGrant);
        btnAuto.setOnClickListener(v -> {
            Context ctx = v.getContext();
            CommsInstaller.setAutoSilent(ctx, !CommsInstaller.autoSilent(ctx));
            refreshUpdateButtons(ctx, btnAuto, btnGrant);
        });
        btnGrant.setOnClickListener(v -> openUnknownAppSources(v.getContext()));
    }

    private void refreshUpdateButtons(Context ctx, Button btnAuto, Button btnGrant) {
        btnAuto.setText(getString(CommsInstaller.autoSilent(ctx)
                ? R.string.title_comms_auto_update_on
                : R.string.title_comms_auto_update_off));
        btnGrant.setText(getString(CommsInstaller.canInstallSilently(ctx)
                ? R.string.title_comms_install_granted
                : R.string.title_comms_install_grant));
    }

    /** Open Settings → "Install unknown apps" scoped to Cloud Mail so the user
     *  grants REQUEST_INSTALL_PACKAGES. Falls back to the global list, then app
     *  details. */
    private void openUnknownAppSources(Context ctx) {
        Uri self = Uri.fromParts("package", ctx.getPackageName(), null);
        Intent scoped = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, self);
        if (scoped.resolveActivity(ctx.getPackageManager()) != null) {
            startActivity(scoped.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        Intent list = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
        if (list.resolveActivity(ctx.getPackageManager()) != null) {
            startActivity(list.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, self)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    // comms: version line shows the EFFECTIVE stamp the updater compares with;
    // when a user override is active, the baked stamp is shown alongside.
    private String versionText(Context ctx) {
        String effective = CommsUpdateWorker.installedStamp(ctx);
        String baked = BuildConfig.COMMS_BUILD_TIMESTAMP;
        String text = BuildConfig.VERSION_NAME + " · build " + effective;
        if (!effective.equals(baked))
            text += " (override; baked " + baked + ")";
        return text;
    }

    // comms: user-editable update-channel stamp override. A bad baked stamp
    // (e.g. "dev" from pre-0045 CI) must never dead-end the updater again —
    // the user can re-point it to anything without reinstalling.
    private void editStampOverride(TextView tvVersion) {
        final Context ctx = getContext();
        if (ctx == null)
            return;
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        final EditText input = new EditText(ctx);
        input.setHint(BuildConfig.COMMS_BUILD_TIMESTAMP);
        input.setText(prefs.getString(CommsUpdateWorker.PREF_STAMP_OVERRIDE, ""));
        new AlertDialog.Builder(ctx)
                .setTitle(R.string.title_comms_stamp_override)
                .setMessage(R.string.title_comms_stamp_override_hint)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String value = input.getText().toString().trim();
                    if (value.isEmpty())
                        prefs.edit().remove(CommsUpdateWorker.PREF_STAMP_OVERRIDE).apply();
                    else
                        prefs.edit().putString(CommsUpdateWorker.PREF_STAMP_OVERRIDE, value).apply();
                    tvVersion.setText(versionText(ctx));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void checkAndInstall(Button btn) {
        btn.setEnabled(false);
        ToastEx.makeText(getContext(),
                getString(R.string.title_comms_checking_update), Toast.LENGTH_SHORT).show();

        new SimpleTask<File>() {
            @Override
            protected File onExecute(Context ctx, Bundle args) throws Throwable {
                CommsUpdateWorker.ReleaseMeta rel = CommsUpdateWorker.fetchLatestRelease();
                // Same comparison as CommsUpdateWorker.doWork: effective stamp
                // (pref override wins over baked), "dev" = always outdated.
                String installed = CommsUpdateWorker.installedStamp(ctx);
                if (!CommsUpdateWorker.isOutdated(installed, rel.buildStamp))
                    return null; // null = up to date

                File dest = new File(ctx.getCacheDir(), "updates/cloud-mail-update.apk");
                CommsUpdateWorker.downloadApk(rel.downloadUrl, dest);
                return dest;
            }

            @Override
            protected void onExecuted(Bundle args, File apk) {
                Context ctx = getContext();
                if (ctx == null) return;
                btn.setEnabled(true);
                if (apk == null) {
                    ToastEx.makeText(ctx,
                            getString(R.string.title_comms_up_to_date), Toast.LENGTH_LONG).show();
                    return;
                }
                // comms: install via PackageInstaller — silent (no tap) when the
                // Auto-update toggle is ON and "install unknown apps" is granted;
                // otherwise Android shows the confirm prompt (CommsInstallReceiver).
                try {
                    CommsInstaller.install(ctx, apk, CommsInstaller.autoSilent(ctx));
                } catch (Throwable ex) {
                    Log.w(ex);
                    ToastEx.makeText(ctx, ex.toString(), Toast.LENGTH_LONG).show();
                }
            }

            @Override
            protected void onException(Bundle args, Throwable ex) {
                btn.setEnabled(true);
                try {
                    Log.unexpectedError(getParentFragmentManager(), ex);
                } catch (Throwable ignored) { }
            }
        }.execute(this, new Bundle(), "comms:update-check");
    }
}
