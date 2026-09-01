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

// comms (my-features): Full-screen JMAP account editor — exact structural mirror of
// FragmentAccount.java adapted for JMAP. Same flow: fill fields → Check (validates JMAP
// connectivity and populates folder spinners) → Save. Options menu has Delete for edit mode.
// Folder spinners pre-populated with standard JMAP folders (no server-side discovery needed).
// On save: creates/updates account + identity + sets folder types from spinner selections.

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.Group;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class FragmentJmapAccount extends FragmentBase {

    // Connection
    private EditTextPlain etHost, etPort, etUser;
    private EditTextPlain etEmail;
    private TextInputLayout tilPassword;
    private TextInputEditText etPassword;
    private RadioGroup rgEncryption;
    private CheckBox cbInsecure;

    // Display / meta
    private EditTextPlain etName;
    private EditTextAutoComplete etCategory;
    private ViewButtonColor btnColor;
    private Button btnAvatar, btnCalendar;

    // Advanced
    private Button btnAdvanced;
    private CheckBox cbSynchronize, cbIgnoreSchedule, cbOnDemand, cbPrimary;
    private CheckBox cbNotify, cbSummary, cbBrowse, cbAutoSeen;
    private EditTextPlain etInterval;
    private CheckBox cbUnmeteredOnly, cbVpnOnly;

    // Check
    private Button btnCheck;
    private ContentLoadingProgressBar pbCheck;

    // Folders
    private SpinnerEx spDrafts, spSent, spArchive, spTrash, spJunk, spLeft, spRight, spMove;
    private TextView tvSentWarning;

    // Save
    private Button btnSave;
    private ContentLoadingProgressBar pbSave;
    private CheckBox cbIdentity;

    // Error
    private TextView tvError;
    private Button btnSupport;

    // Progress
    private ContentLoadingProgressBar pbWait;

    // Groups
    private Group grpAdvanced, grpFolders, grpError;

    // State
    private long id = -1;             // account row id; -1 = new
    private int color = 0; // 0 = no color (Color.TRANSPARENT)
    private String avatar = null;
    private String calendar = null;
    private boolean advancedVisible = false;

    private ArrayAdapter<EntityFolder> adapterFolder;
    private ArrayAdapter<EntityFolder> adapterSwipe;

    private static final int REQUEST_COLOR = 1;
    private static final int REQUEST_AVATAR = 2;
    private static final int REQUEST_CALENDAR = 3;
    private static final int REQUEST_DELETE = 4;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);

        Bundle args = getArguments();
        id = (args != null) ? args.getLong("id", -1) : -1;
    }

    @Override
    @Nullable
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_jmap_account, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Server card fields
        etHost        = view.findViewById(R.id.etHost);
        etPort        = view.findViewById(R.id.etPort);
        etUser        = view.findViewById(R.id.etUser);
        etEmail       = view.findViewById(R.id.etEmail);
        tilPassword   = view.findViewById(R.id.tilPassword);
        etPassword    = view.findViewById(R.id.etPassword);
        rgEncryption  = view.findViewById(R.id.rgEncryption);
        cbInsecure    = view.findViewById(R.id.cbInsecure);

        // Meta
        etName        = view.findViewById(R.id.etName);
        etCategory    = view.findViewById(R.id.etCategory);
        btnColor      = view.findViewById(R.id.btnColor);
        btnAvatar     = view.findViewById(R.id.btnAvatar);
        btnCalendar   = view.findViewById(R.id.btnCalendar);

        // Advanced
        btnAdvanced   = view.findViewById(R.id.btnAdvanced);
        cbSynchronize = view.findViewById(R.id.cbSynchronize);
        cbIgnoreSchedule = view.findViewById(R.id.cbIgnoreSchedule);
        cbOnDemand    = view.findViewById(R.id.cbOnDemand);
        cbPrimary     = view.findViewById(R.id.cbPrimary);
        cbNotify      = view.findViewById(R.id.cbNotify);
        cbSummary     = view.findViewById(R.id.cbSummary);
        cbBrowse      = view.findViewById(R.id.cbBrowse);
        cbAutoSeen    = view.findViewById(R.id.cbAutoSeen);
        etInterval    = view.findViewById(R.id.etInterval);
        cbUnmeteredOnly = view.findViewById(R.id.cbUnmeteredOnly);
        cbVpnOnly     = view.findViewById(R.id.cbVpnOnly);

        // Check
        btnCheck      = view.findViewById(R.id.btnCheck);
        pbCheck       = view.findViewById(R.id.pbCheck);

        // Folder spinners
        spDrafts      = view.findViewById(R.id.spDrafts);
        spSent        = view.findViewById(R.id.spSent);
        spArchive     = view.findViewById(R.id.spArchive);
        spTrash       = view.findViewById(R.id.spTrash);
        spJunk        = view.findViewById(R.id.spJunk);
        spLeft        = view.findViewById(R.id.spLeft);
        spRight       = view.findViewById(R.id.spRight);
        spMove        = view.findViewById(R.id.spMove);
        tvSentWarning = view.findViewById(R.id.tvSentWarning);

        // Save
        btnSave       = view.findViewById(R.id.btnSave);
        pbSave        = view.findViewById(R.id.pbSave);
        cbIdentity    = view.findViewById(R.id.cbIdentity);

        // Error
        tvError       = view.findViewById(R.id.tvError);
        btnSupport    = view.findViewById(R.id.btnSupport);

        // Progress
        pbWait        = view.findViewById(R.id.pbWait);

        // Groups
        grpAdvanced   = view.findViewById(R.id.grpAdvanced);
        grpFolders    = view.findViewById(R.id.grpFolders);
        grpError      = view.findViewById(R.id.grpError);

        // ── Initial visibility ──────────────────────────────────────────
        grpAdvanced.setVisibility(View.GONE);
        grpFolders.setVisibility(View.GONE);
        grpError.setVisibility(View.GONE);
        btnSave.setVisibility(View.GONE);
        cbIdentity.setVisibility(View.GONE);
        pbCheck.setVisibility(View.GONE);
        pbSave.setVisibility(View.GONE);
        tvSentWarning.setVisibility(View.GONE);

        // ── Default JMAP values ──────────────────────────────────────────
        if (id < 0) {
            etHost.setText("jmap.diegonmarcos.com");
            etPort.setText("443");
            rgEncryption.check(R.id.radio_ssl);
        }

        // ── Folder adapters ─────────────────────────────────────────────
        adapterFolder = new ArrayAdapter<EntityFolder>(getContext(),
                R.layout.spinner_item1, android.R.id.text1) {
            @NonNull
            @Override
            public View getView(int pos, @Nullable View v, @NonNull ViewGroup parent) {
                View view = super.getView(pos, v, parent);
                EntityFolder f = getItem(pos);
                ((TextView) view.findViewById(android.R.id.text1))
                        .setText(f == null ? "-" : f.name);
                return view;
            }

            @Override
            public View getDropDownView(int pos, @Nullable View v, @NonNull ViewGroup parent) {
                View view = super.getDropDownView(pos, v, parent);
                EntityFolder f = getItem(pos);
                ((TextView) view.findViewById(android.R.id.text1))
                        .setText(f == null ? "-" : f.name);
                return view;
            }
        };
        adapterFolder.setDropDownViewResource(R.layout.spinner_item1_dropdown);

        adapterSwipe = new ArrayAdapter<EntityFolder>(getContext(),
                R.layout.spinner_item1, android.R.id.text1) {
            @NonNull
            @Override
            public View getView(int pos, @Nullable View v, @NonNull ViewGroup parent) {
                View view = super.getView(pos, v, parent);
                EntityFolder f = getItem(pos);
                ((TextView) view.findViewById(android.R.id.text1))
                        .setText(f == null ? "-" : f.name);
                return view;
            }

            @Override
            public View getDropDownView(int pos, @Nullable View v, @NonNull ViewGroup parent) {
                View view = super.getDropDownView(pos, v, parent);
                EntityFolder f = getItem(pos);
                ((TextView) view.findViewById(android.R.id.text1))
                        .setText(f == null ? "-" : f.name);
                return view;
            }
        };
        adapterSwipe.setDropDownViewResource(R.layout.spinner_item1_dropdown);

        spDrafts.setAdapter(adapterFolder);
        spSent.setAdapter(adapterFolder);
        spArchive.setAdapter(adapterFolder);
        spTrash.setAdapter(adapterFolder);
        spJunk.setAdapter(adapterFolder);
        spLeft.setAdapter(adapterSwipe);
        spRight.setAdapter(adapterSwipe);
        spMove.setAdapter(adapterFolder);

        // Warn when Sent folder not selected
        spSent.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int pos, long rowId) {
                EntityFolder f = adapterFolder.getItem(pos);
                boolean none = (f == null || f.id == 0L);
                tvSentWarning.setVisibility(none ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                tvSentWarning.setVisibility(View.VISIBLE);
            }
        });

        // ── Advanced toggle ──────────────────────────────────────────────
        btnAdvanced.setOnClickListener(v -> {
            advancedVisible = !advancedVisible;
            grpAdvanced.setVisibility(advancedVisible ? View.VISIBLE : View.GONE);
        });

        // ── Color picker ─────────────────────────────────────────────────
        btnColor.setOnClickListener(v -> {
            Bundle args = new Bundle();
            args.putInt("color", color);
            args.putString("title", getString(R.string.title_color));
            FragmentDialogColor fragment = new FragmentDialogColor();
            fragment.setArguments(args);
            fragment.setTargetFragment(FragmentJmapAccount.this, REQUEST_COLOR);
            fragment.show(getParentFragmentManager(), "jmap:color");
        });

        // ── Avatar ──────────────────────────────────────────────────────
        btnAvatar.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            startActivityForResult(intent, REQUEST_AVATAR);
        });

        // ── Calendar ────────────────────────────────────────────────────
        btnCalendar.setOnClickListener(v -> {
            Bundle args = new Bundle();
            FragmentDialogCalendar fragment = new FragmentDialogCalendar();
            fragment.setArguments(args);
            fragment.setTargetFragment(FragmentJmapAccount.this, REQUEST_CALENDAR);
            fragment.show(getParentFragmentManager(), "jmap:calendar");
        });

        // ── Check ────────────────────────────────────────────────────────
        btnCheck.setOnClickListener(v -> onCheck());

        // ── Save ─────────────────────────────────────────────────────────
        btnSave.setOnClickListener(v -> onSave());

        // ── Support link ─────────────────────────────────────────────────
        btnSupport.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/M66B/FairEmail/discussions")));
            } catch (Exception ex) {
                Log.w(ex);
            }
        });
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        if (id >= 0) {
            // Edit mode — load existing account from DB
            pbWait.setVisibility(View.VISIBLE);
            new SimpleTask<Object[]>() {
                @Override
                protected Object[] onExecute(Context context, Bundle args) {
                    long accountId = args.getLong("id");
                    DB db = DB.getInstance(context);
                    EntityAccount account = db.account().getAccount(accountId);
                    List<EntityFolder> folders = db.folder().getFolders(accountId, false, true);
                    List<EntityIdentity> identities = db.identity().getIdentities(accountId);
                    return new Object[]{account, folders, identities};
                }

                @Override
                protected void onExecuted(Bundle args, Object[] result) {
                    pbWait.setVisibility(View.GONE);
                    EntityAccount account = (EntityAccount) result[0];
                    List<EntityFolder> folders = (List<EntityFolder>) result[1];
                    List<EntityIdentity> identities = (List<EntityIdentity>) result[2];
                    if (account == null) return;

                    // Server fields
                    etHost.setText(account.host);
                    etPort.setText(String.valueOf(account.port));
                    etUser.setText(account.user);
                    if (!TextUtils.isEmpty(account.password))
                        etPassword.setText(account.password);
                    if (account.encryption == EmailService.ENCRYPTION_STARTTLS)
                        rgEncryption.check(R.id.radio_starttls);
                    else if (account.encryption == EmailService.ENCRYPTION_NONE)
                        rgEncryption.check(R.id.radio_none);
                    else
                        rgEncryption.check(R.id.radio_ssl);
                    cbInsecure.setChecked(account.insecure);

                    // Meta
                    if (!TextUtils.isEmpty(account.name))
                        etName.setText(account.name);
                    if (!TextUtils.isEmpty(account.category))
                        etCategory.setText(account.category);
                    color = (account.color == null ? 0 : account.color);
                    btnColor.setColor(color);
                    avatar = account.avatar;
                    calendar = account.calendar;

                    // Advanced
                    cbSynchronize.setChecked(account.synchronize);
                    cbOnDemand.setChecked(account.ondemand);
                    cbPrimary.setChecked(account.primary);
                    cbNotify.setChecked(account.notify);
                    cbSummary.setChecked(account.summary);
                    cbBrowse.setChecked(account.browse);
                    cbAutoSeen.setChecked(account.auto_seen);
                    etInterval.setText(String.valueOf(account.poll_interval));

                    // Identity email
                    if (identities != null && !identities.isEmpty())
                        etEmail.setText(identities.get(0).email);

                    // Populate folder spinners from existing DB folders
                    if (folders != null && !folders.isEmpty())
                        setFolders(folders, account);
                }

                @Override
                protected void onException(Bundle args, Throwable ex) {
                    pbWait.setVisibility(View.GONE);
                    try { Log.unexpectedError(getParentFragmentManager(), ex); }
                    catch (Throwable ignored) { }
                }
            }.execute(this, getArguments(), "jmap:load");
        } else {
            pbWait.setVisibility(View.GONE);
        }
    }

    // ── Options menu (Delete for edit mode) ─────────────────────────────

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        inflater.inflate(R.menu.menu_account, menu);
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public void onPrepareOptionsMenu(@NonNull Menu menu) {
        MenuItem menuDelete = menu.findItem(R.id.menu_delete);
        if (menuDelete != null)
            menuDelete.setVisible(id >= 0);
        super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.menu_delete) {
            onDelete();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void onDelete() {
        Bundle args = new Bundle();
        args.putString("question", getString(R.string.title_account_delete));
        FragmentDialogAsk ask = new FragmentDialogAsk();
        ask.setArguments(args);
        ask.setTargetFragment(this, REQUEST_DELETE);
        ask.show(getParentFragmentManager(), "jmap:delete");
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != android.app.Activity.RESULT_OK) return;

        if (requestCode == REQUEST_COLOR && data != null) {
            color = data.getIntExtra("color", 0);
            btnColor.setColor(color);

        } else if (requestCode == REQUEST_AVATAR && data != null && data.getData() != null) {
            avatar = data.getData().toString();

        } else if (requestCode == REQUEST_CALENDAR && data != null) {
            calendar = data.getStringExtra("name");

        } else if (requestCode == REQUEST_DELETE) {
            if (id < 0) return;
            Bundle args = new Bundle();
            args.putLong("id", id);
            new SimpleTask<Void>() {
                @Override
                protected Void onExecute(Context context, Bundle args) {
                    long accountId = args.getLong("id");
                    DB db = DB.getInstance(context);
                    db.account().deleteAccount(accountId);
                    ServiceSynchronize.eval(context, "jmap account deleted");
                    return null;
                }

                @Override
                protected void onExecuted(Bundle args, Void data) {
                    finish();
                }

                @Override
                protected void onException(Bundle args, Throwable ex) {
                    try { Log.unexpectedError(getParentFragmentManager(), ex); }
                    catch (Throwable ignored) { }
                }
            }.execute(this, args, "jmap:delete");
        }
    }

    // ── Check connection ──────────────────────────────────────────────────

    private void onCheck() {
        grpError.setVisibility(View.GONE);

        String host = etHost.getText().toString().trim();
        String user = etUser.getText().toString().trim();
        CharSequence pwSeq = etPassword.getText();
        String password = (pwSeq != null) ? pwSeq.toString() : "";

        if (TextUtils.isEmpty(host) || TextUtils.isEmpty(user)) {
            ToastEx.makeText(getContext(), R.string.title_comms_jmap_host_user_required,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        int port = parsePort(etPort.getText().toString(), 443);
        int encryption = getEncryption();
        boolean insecure = cbInsecure.isChecked();

        Bundle args = new Bundle();
        args.putString("host", host);
        args.putString("user", user);
        args.putString("password", password);
        args.putInt("port", port);
        args.putInt("encryption", encryption);
        args.putBoolean("insecure", insecure);

        new SimpleTask<List<EntityFolder>>() {
            @Override
            protected void onPreExecute(Bundle args) {
                pbCheck.setVisibility(View.VISIBLE);
                Helper.setViewsEnabled((ViewGroup) requireView(), false);
            }

            @Override
            protected void onPostExecute(Bundle args) {
                pbCheck.setVisibility(View.GONE);
                Helper.setViewsEnabled((ViewGroup) requireView(), true);
            }

            @Override
            protected List<EntityFolder> onExecute(Context context, Bundle args) throws Throwable {
                String host = args.getString("host");
                String user = args.getString("user");
                String password = args.getString("password");
                int port = args.getInt("port", 443);
                int enc = args.getInt("encryption", EmailService.ENCRYPTION_SSL);
                boolean insecure = args.getBoolean("insecure");

                String scheme = (enc == EmailService.ENCRYPTION_NONE) ? "http" : "https";
                URL url = new URL(scheme + "://" + host + ":" + port + "/.well-known/jmap");

                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setConnectTimeout(15_000);
                c.setReadTimeout(15_000);
                c.setRequestProperty("Authorization", "Basic " +
                        android.util.Base64.encodeToString(
                                (user + ":" + password).getBytes("UTF-8"),
                                android.util.Base64.NO_WRAP));

                try {
                    int code = c.getResponseCode();
                    if (code == 401)
                        throw new SecurityException("Authentication failed (HTTP 401)");
                    if (code == 404)
                        throw new IOException("JMAP endpoint not found at /.well-known/jmap (HTTP 404)");
                    if (code != 200)
                        throw new IOException("Unexpected HTTP response: " + code);
                } finally {
                    c.disconnect();
                }

                return buildStandardFolders();
            }

            @Override
            protected void onExecuted(Bundle args, List<EntityFolder> folders) {
                setFolders(folders, null);
                ToastEx.makeText(getContext(), R.string.title_comms_jmap_connect_success,
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            protected void onException(Bundle args, Throwable ex) {
                tvError.setText(Log.formatThrowable(ex, false));
                grpError.setVisibility(View.VISIBLE);
            }
        }.execute(this, args, "jmap:check");
    }

    // ── Populate folder spinners ──────────────────────────────────────────

    private void setFolders(List<EntityFolder> serverFolders, @Nullable EntityAccount existing) {
        // Build folder list for the per-type spinners (none + all folders)
        List<EntityFolder> items = new ArrayList<>();
        EntityFolder none = new EntityFolder();
        none.id = 0L;
        none.name = "-";
        none.type = EntityFolder.USER;
        items.add(none);
        items.addAll(serverFolders);

        // Build swipe action list (none + all folders)
        List<EntityFolder> swipeItems = new ArrayList<>();
        swipeItems.add(none);
        swipeItems.addAll(serverFolders);

        adapterFolder.clear();
        adapterFolder.addAll(items);
        adapterSwipe.clear();
        adapterSwipe.addAll(swipeItems);

        // Auto-select standard types
        for (int i = 0; i < items.size(); i++) {
            String type = items.get(i).type;
            if (EntityFolder.DRAFTS.equals(type))  spDrafts.setSelection(i);
            if (EntityFolder.SENT.equals(type))    spSent.setSelection(i);
            if (EntityFolder.ARCHIVE.equals(type)) spArchive.setSelection(i);
            if (EntityFolder.TRASH.equals(type))   spTrash.setSelection(i);
            if (EntityFolder.JUNK.equals(type))    spJunk.setSelection(i);
        }

        // In edit mode, restore saved folder selections from the account's folders
        if (existing != null) {
            for (int i = 0; i < items.size(); i++) {
                EntityFolder f = items.get(i);
                if (EntityFolder.DRAFTS.equals(f.type))  spDrafts.setSelection(i);
                if (EntityFolder.SENT.equals(f.type))    spSent.setSelection(i);
                if (EntityFolder.ARCHIVE.equals(f.type)) spArchive.setSelection(i);
                if (EntityFolder.TRASH.equals(f.type))   spTrash.setSelection(i);
                if (EntityFolder.JUNK.equals(f.type))    spJunk.setSelection(i);
            }
        }

        grpFolders.setVisibility(View.VISIBLE);
        btnSave.setVisibility(View.VISIBLE);
        // comms: JMAP is a single endpoint (one host+credentials sends AND receives),
        // so there is NO separate outbound identity to configure — CommsAccounts
        // .createJmapAccount auto-creates the matching identity from the same account.
        // Keep the "create identity" control hidden (no redundant second panel).
        cbIdentity.setVisibility(View.GONE);
    }

    // ── Save ──────────────────────────────────────────────────────────────

    private void onSave() {
        grpError.setVisibility(View.GONE);

        String host = etHost.getText().toString().trim();
        String email = etEmail.getText().toString().trim();
        // comms: minimal input — username defaults to the email when left blank, so the
        // user typically only fills in email + password (host/port are prefilled defaults).
        String user = etUser.getText().toString().trim();
        if (TextUtils.isEmpty(user)) user = email;
        if (TextUtils.isEmpty(host) || TextUtils.isEmpty(user)) {
            ToastEx.makeText(getContext(), R.string.title_comms_jmap_host_user_required,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        CharSequence pwSeq = etPassword.getText();
        String password = (pwSeq != null) ? pwSeq.toString() : "";
        int port = parsePort(etPort.getText().toString(), 443);
        int encryption = getEncryption();
        boolean insecure = cbInsecure.isChecked();

        String name = etName.getText().toString().trim();
        String category = etCategory.getText().toString().trim();

        // Collect folder selections
        Bundle args = new Bundle();
        args.putLong("id", id);
        args.putString("host", host);
        args.putString("user", user);
        args.putString("password", password);
        args.putInt("port", port);
        args.putInt("encryption", encryption);
        args.putBoolean("insecure", insecure);
        args.putString("name", name);
        args.putString("email", email);
        args.putString("category", category);
        args.putInt("color", color);
        args.putString("avatar", avatar);
        args.putString("calendar", calendar);
        args.putBoolean("synchronize", cbSynchronize.isChecked());
        args.putBoolean("ondemand", cbOnDemand.isChecked());
        args.putBoolean("primary", cbPrimary.isChecked());
        args.putBoolean("notify", cbNotify.isChecked());
        args.putBoolean("summary", cbSummary.isChecked());
        args.putBoolean("browse", cbBrowse.isChecked());
        args.putBoolean("auto_seen", cbAutoSeen.isChecked());
        args.putInt("poll_interval", parsePort(etInterval.getText().toString(),
                EntityAccount.DEFAULT_KEEP_ALIVE_INTERVAL));
        args.putBoolean("identity", cbIdentity.isChecked());
        // Folder type selections
        args.putString("folder_drafts",  getFolderName(spDrafts,  adapterFolder));
        args.putString("folder_sent",    getFolderName(spSent,    adapterFolder));
        args.putString("folder_archive", getFolderName(spArchive, adapterFolder));
        args.putString("folder_trash",   getFolderName(spTrash,   adapterFolder));
        args.putString("folder_junk",    getFolderName(spJunk,    adapterFolder));
        args.putString("swipe_left",     getFolderName(spLeft,    adapterSwipe));
        args.putString("swipe_right",    getFolderName(spRight,   adapterSwipe));
        args.putString("folder_move",    getFolderName(spMove,    adapterFolder));

        new SimpleTask<Long>() {
            @Override
            protected void onPreExecute(Bundle args) {
                pbSave.setVisibility(View.VISIBLE);
                Helper.setViewsEnabled((ViewGroup) requireView(), false);
            }

            @Override
            protected void onPostExecute(Bundle args) {
                pbSave.setVisibility(View.GONE);
                Helper.setViewsEnabled((ViewGroup) requireView(), true);
            }

            @Override
            protected Long onExecute(Context context, Bundle args) throws Throwable {
                long accountId = args.getLong("id", -1);
                String host     = args.getString("host");
                String user     = args.getString("user");
                String password = args.getString("password");
                int port        = args.getInt("port", 443);
                int enc         = args.getInt("encryption", EmailService.ENCRYPTION_SSL);
                boolean insec   = args.getBoolean("insecure");
                String name     = args.getString("name");
                String email    = args.getString("email");
                String category = args.getString("category");
                int colorRaw = args.getInt("color", 0);
                Integer colorVal = (colorRaw == 0) ? null : colorRaw;
                String avatarVal   = args.getString("avatar");
                String calendarVal = args.getString("calendar");
                boolean sync       = args.getBoolean("synchronize", true);
                boolean onDemand   = args.getBoolean("ondemand");
                boolean primary    = args.getBoolean("primary");
                boolean notify     = args.getBoolean("notify");
                boolean summary    = args.getBoolean("summary");
                boolean browse     = args.getBoolean("browse", true);
                boolean autoSeen   = args.getBoolean("auto_seen", true);
                int interval       = args.getInt("poll_interval", EntityAccount.DEFAULT_KEEP_ALIVE_INTERVAL);
                String fDrafts     = args.getString("folder_drafts");
                String fSent       = args.getString("folder_sent");
                String fArchive    = args.getString("folder_archive");
                String fTrash      = args.getString("folder_trash");
                String fJunk       = args.getString("folder_junk");
                String swLeft      = args.getString("swipe_left");
                String swRight     = args.getString("swipe_right");
                String fMove       = args.getString("folder_move");

                DB db = DB.getInstance(context);
                long resultId;
                try {
                    db.beginTransaction();
                    if (accountId >= 0) {
                        // Update existing account
                        EntityAccount account = db.account().getAccount(accountId);
                        if (account == null) throw new IllegalStateException("Account not found");
                        account.host        = host;
                        account.port        = port;
                        account.encryption  = enc;
                        account.insecure    = insec;
                        account.user        = user;
                        if (!TextUtils.isEmpty(password)) account.password = password;
                        if (!TextUtils.isEmpty(name)) account.name = name;
                        account.category    = TextUtils.isEmpty(category) ? null : category;
                        account.color       = colorVal;
                        account.avatar      = avatarVal;
                        account.calendar    = calendarVal;
                        account.synchronize = sync;
                        account.ondemand    = onDemand;
                        account.primary     = primary;
                        account.notify      = notify;
                        account.summary     = summary;
                        account.browse      = browse;
                        account.auto_seen   = autoSeen;
                        account.poll_interval = interval;
                        db.account().updateAccount(account);

                        // Update identity
                        List<EntityIdentity> ids = db.identity().getIdentities(accountId);
                        if (ids != null)
                            for (EntityIdentity ident : ids) {
                                ident.host       = host;
                                ident.port       = port;
                                ident.encryption = enc;
                                ident.insecure   = insec;
                                ident.user       = user;
                                if (!TextUtils.isEmpty(password)) ident.password = password;
                                if (!TextUtils.isEmpty(name)) ident.name = name;
                                if (!TextUtils.isEmpty(email)) ident.email = email;
                                db.identity().updateIdentity(ident);
                            }

                        // Update folder types from spinner selections
                        applyFolderTypeSelections(db, accountId,
                                fDrafts, fSent, fArchive, fTrash, fJunk);
                        applySwipeSelections(db, account, fMove, swLeft, swRight);
                        CommsAccounts.ensureJmapFolders(db, accountId);
                        resultId = accountId;
                    } else {
                        // Create new account
                        resultId = CommsAccounts.createJmapAccount(db, null,
                                name, email, user, password, host, port, enc, primary);
                        if (resultId < 0)
                            throw new IllegalArgumentException(
                                    context.getString(R.string.title_comms_jmap_exists));

                        // Apply optional meta
                        EntityAccount account = db.account().getAccount(resultId);
                        if (account != null) {
                            account.category  = TextUtils.isEmpty(category) ? null : category;
                            account.color     = colorVal;
                            account.avatar    = avatarVal;
                            account.calendar  = calendarVal;
                            account.synchronize = sync;
                            account.ondemand  = onDemand;
                            account.notify    = notify;
                            account.summary   = summary;
                            account.browse    = browse;
                            account.auto_seen = autoSeen;
                            account.poll_interval = interval;
                            account.insecure  = insec;
                            db.account().updateAccount(account);
                            applySwipeSelections(db, account, fMove, swLeft, swRight);
                        }
                        applyFolderTypeSelections(db, resultId,
                                fDrafts, fSent, fArchive, fTrash, fJunk);
                    }
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
                ServiceSynchronize.eval(context,
                        accountId >= 0 ? "jmap account updated" : "jmap account added");
                return resultId;
            }

            @Override
            protected void onExecuted(Bundle args, Long resultId) {
                // comms: JMAP is one endpoint — the outbound identity is auto-created
                // by CommsAccounts.createJmapAccount from the same host+credentials.
                // No IMAP-style second "identity / outbound" panel: setup is done here.
                int msgId = args.getLong("id", -1) >= 0
                        ? R.string.title_comms_jmap_updated
                        : R.string.title_comms_jmap_added;
                ToastEx.makeText(getContext(), msgId, Toast.LENGTH_LONG).show();
                finish();
            }

            @Override
            protected void onException(Bundle args, Throwable ex) {
                tvError.setText(Log.formatThrowable(ex, false));
                grpError.setVisibility(View.VISIBLE);
            }
        }.execute(this, args, "jmap:save");
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private int getEncryption() {
        int checked = rgEncryption.getCheckedRadioButtonId();
        if (checked == R.id.radio_starttls) return EmailService.ENCRYPTION_STARTTLS;
        if (checked == R.id.radio_none)     return EmailService.ENCRYPTION_NONE;
        return EmailService.ENCRYPTION_SSL;
    }

    private static int parsePort(String s, int def) {
        try { return TextUtils.isEmpty(s) ? def : Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return def; }
    }

    private static String getFolderName(SpinnerEx sp, ArrayAdapter<EntityFolder> adapter) {
        int pos = sp.getSelectedItemPosition();
        if (pos <= 0) return null;
        EntityFolder f = adapter.getItem(pos);
        return (f == null || f.id == 0L) ? null : f.name;
    }

    private static void applyFolderTypeSelections(DB db, long accountId,
            String drafts, String sent, String archive, String trash, String junk) {
        List<EntityFolder> folders = db.folder().getFolders(accountId, false, true);
        if (folders == null) return;
        for (EntityFolder f : folders) {
            String newType = null;
            if (f.name.equals(drafts))   newType = EntityFolder.DRAFTS;
            else if (f.name.equals(sent))    newType = EntityFolder.SENT;
            else if (f.name.equals(archive)) newType = EntityFolder.ARCHIVE;
            else if (f.name.equals(trash))   newType = EntityFolder.TRASH;
            else if (f.name.equals(junk))    newType = EntityFolder.JUNK;
            if (newType != null && !newType.equals(f.type))
                db.folder().setFolderType(f.id, newType);
        }
    }

    // Swipe/move selections live on EntityAccount, not EntityFolder.
    private static void applySwipeSelections(DB db, EntityAccount account,
            String move, String left, String right) {
        List<EntityFolder> folders = db.folder().getFolders(account.id, false, true);
        if (folders == null) return;
        for (EntityFolder f : folders) {
            if (f.name.equals(left))  account.swipe_left  = f.id;
            if (f.name.equals(right)) account.swipe_right = f.id;
            if (f.name.equals(move))  account.move_to     = f.id;
        }
        db.account().updateAccount(account);
    }

    private static List<EntityFolder> buildStandardFolders() {
        String[][] types = {
                {EntityFolder.INBOX,   "Inbox"},
                {EntityFolder.DRAFTS,  "Drafts"},
                {EntityFolder.SENT,    "Sent"},
                {EntityFolder.ARCHIVE, "Archive"},
                {EntityFolder.TRASH,   "Trash"},
                {EntityFolder.JUNK,    "Junk"},
        };
        List<EntityFolder> list = new ArrayList<>();
        for (String[] pair : types) {
            EntityFolder f = new EntityFolder();
            f.id   = 0L;
            f.name = pair[1];
            f.type = pair[0];
            list.add(f);
        }
        return list;
    }
}
