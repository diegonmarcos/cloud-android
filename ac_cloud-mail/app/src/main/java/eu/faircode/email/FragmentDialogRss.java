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

// comms (my-features): interactive "RSS feed" config window. Collects a feed URL
// (+ optional title) and delegates to CommsAccounts.createRssFeed, which adds a
// folder under the synthetic local Feeds account and re-arms the periodic worker.
public class FragmentDialogRss extends FragmentDialogBase {
    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        final Context context = getContext();
        final View dview = LayoutInflater.from(context).inflate(R.layout.dialog_rss, null);
        final EditText etUrl = dview.findViewById(R.id.etUrl);
        final EditText etTitle = dview.findViewById(R.id.etTitle);

        return new AlertDialog.Builder(context)
                .setView(dview)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    Bundle args = new Bundle();
                    args.putString("url", etUrl.getText().toString().trim());
                    args.putString("title", etTitle.getText().toString().trim());

                    new SimpleTask<Void>() {
                        @Override
                        protected Void onExecute(Context context, Bundle args) throws Throwable {
                            String url = args.getString("url");
                            if (TextUtils.isEmpty(url))
                                throw new IllegalArgumentException("Feed URL is required");

                            DB db = DB.getInstance(context);
                            try {
                                db.beginTransaction();
                                long id = CommsAccounts.createRssFeed(db, url, args.getString("title"));
                                if (id < 0)
                                    throw new IllegalArgumentException("Feed already exists");
                                db.setTransactionSuccessful();
                            } finally {
                                db.endTransaction();
                            }

                            WorkerFeedSync.init(context);
                            return null;
                        }

                        @Override
                        protected void onExecuted(Bundle args, Void data) {
                            ToastEx.makeText(context, R.string.title_comms_rss_added, Toast.LENGTH_LONG).show();
                        }

                        @Override
                        protected void onException(Bundle args, Throwable ex) {
                            try {
                                Log.unexpectedError(getParentFragmentManager(), ex);
                            } catch (Throwable ignored) { }
                        }
                    }.execute(FragmentDialogRss.this, args, "comms:rss-create");
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
    }
}
