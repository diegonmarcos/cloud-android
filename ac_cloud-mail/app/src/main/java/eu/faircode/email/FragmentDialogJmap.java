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

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import java.util.List;

// comms (my-features): interactive "JMAP account" config window. Collects the same
// fields the JSON provisioner writes, then delegates to CommsAccounts.createJmapAccount
// (with the password filled). Host/port prefill the cloud defaults (jmap.diegonmarcos.com:443).
// Edit mode: pass "id" (account row id) in args → loads existing values, updates on OK.
public class FragmentDialogJmap extends FragmentDialogBase {
    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        final Context context = getContext();
        final View dview = LayoutInflater.from(context).inflate(R.layout.dialog_jmap, null);
        final EditText etName = dview.findViewById(R.id.etName);
        final EditText etEmail = dview.findViewById(R.id.etEmail);
        final EditText etUser = dview.findViewById(R.id.etUser);
        final EditText etPassword = dview.findViewById(R.id.etPassword);
        final EditText etHost = dview.findViewById(R.id.etHost);
        final EditText etPort = dview.findViewById(R.id.etPort);

        final long accountId = getArguments() != null ? getArguments().getLong("id", -1) : -1;
        final boolean editing = accountId >= 0;

        // Edit mode: load existing account values into fields
        if (editing) {
            new SimpleTask<EntityAccount>() {
                @Override
                protected EntityAccount onExecute(Context context, Bundle args) {
                    return DB.getInstance(context).account().getAccount(args.getLong("id"));
                }
                @Override
                protected void onExecuted(Bundle args, EntityAccount account) {
                    if (account == null) return;
                    if (!TextUtils.isEmpty(account.name)) etName.setText(account.name);
                    if (!TextUtils.isEmpty(account.user)) etUser.setText(account.user);
                    if (!TextUtils.isEmpty(account.host)) etHost.setText(account.host);
                    etPort.setText(String.valueOf(account.port));
                }
                @Override
                protected void onException(Bundle args, Throwable ex) { /* non-fatal pre-fill */ }
            }.execute(this, getArguments(), "comms:jmap-load");

            new SimpleTask<String>() {
                @Override
                protected String onExecute(Context context, Bundle args) {
                    List<EntityIdentity> ids = DB.getInstance(context).identity().getIdentities(args.getLong("id"));
                    return (ids != null && !ids.isEmpty()) ? ids.get(0).email : null;
                }
                @Override
                protected void onExecuted(Bundle args, String email) {
                    if (email != null) etEmail.setText(email);
                }
                @Override
                protected void onException(Bundle args, Throwable ex) { /* non-fatal pre-fill */ }
            }.execute(this, getArguments(), "comms:jmap-load-identity");
        }

        return new AlertDialog.Builder(context)
                .setView(dview)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    Bundle args = new Bundle();
                    args.putString("name", etName.getText().toString().trim());
                    args.putString("email", etEmail.getText().toString().trim());
                    args.putString("user", etUser.getText().toString().trim());
                    args.putString("password", etPassword.getText().toString());
                    args.putString("host", etHost.getText().toString().trim());
                    args.putString("port", etPort.getText().toString().trim());
                    args.putLong("accountId", accountId);

                    new SimpleTask<Void>() {
                        @Override
                        protected Void onExecute(Context context, Bundle args) throws Throwable {
                            String host = args.getString("host");
                            String user = args.getString("user");
                            String sport = args.getString("port");
                            if (TextUtils.isEmpty(host) || TextUtils.isEmpty(user))
                                throw new IllegalArgumentException("JMAP host and username are required");
                            int port = TextUtils.isEmpty(sport) ? 443 : Integer.parseInt(sport);
                            String name = args.getString("name");
                            String email = args.getString("email");
                            String password = args.getString("password");
                            long aid = args.getLong("accountId", -1);

                            DB db = DB.getInstance(context);
                            try {
                                db.beginTransaction();
                                if (aid >= 0) {
                                    EntityAccount account = db.account().getAccount(aid);
                                    if (account != null) {
                                        account.host = host;
                                        account.port = port;
                                        account.user = user;
                                        if (!TextUtils.isEmpty(password)) account.password = password;
                                        if (!TextUtils.isEmpty(name)) account.name = name;
                                        db.account().updateAccount(account);
                                        List<EntityIdentity> ids = db.identity().getIdentities(aid);
                                        if (ids != null)
                                            for (EntityIdentity id : ids) {
                                                id.host = host;
                                                id.port = port;
                                                id.user = user;
                                                if (!TextUtils.isEmpty(password)) id.password = password;
                                                if (!TextUtils.isEmpty(name)) id.name = name;
                                                if (!TextUtils.isEmpty(email)) id.email = email;
                                                db.identity().updateIdentity(id);
                                            }
                                    }
                                } else {
                                    long id = CommsAccounts.createJmapAccount(db, null,
                                            name, email, user, password,
                                            host, port, EmailService.ENCRYPTION_SSL, false);
                                    if (id < 0)
                                        throw new IllegalArgumentException("JMAP account already exists");
                                }
                                db.setTransactionSuccessful();
                            } finally {
                                db.endTransaction();
                            }

                            ServiceSynchronize.eval(context,
                                    aid >= 0 ? "jmap account updated" : "jmap account added");
                            return null;
                        }

                        @Override
                        protected void onExecuted(Bundle args, Void data) {
                            int resId = args.getLong("accountId", -1) >= 0
                                    ? R.string.title_comms_jmap_updated
                                    : R.string.title_comms_jmap_added;
                            ToastEx.makeText(context, resId, Toast.LENGTH_LONG).show();
                        }

                        @Override
                        protected void onException(Bundle args, Throwable ex) {
                            try {
                                Log.unexpectedError(getParentFragmentManager(), ex);
                            } catch (Throwable ignored) { }
                        }
                    }.execute(FragmentDialogJmap.this, args, "comms:jmap-save");
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
    }
}
