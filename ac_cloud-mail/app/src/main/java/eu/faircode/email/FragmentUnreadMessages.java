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

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.Group;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.paging.PagedList;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

// comms: the unread messages of one folder, in the unread lane.
//
// Backed by ViewModelUnread + DaoMessage.pagedUnread, so "unread" is a property
// of the QUERY, not a filter applied to a general message list. A message read
// elsewhere leaves this list on its own, because the query stops matching it.
public class FragmentUnreadMessages extends FragmentBase
        implements AdapterUnreadMessage.IUnreadMessageSelected {
    private ViewGroup view;
    private SwipeRefreshLayout swipeRefresh;
    private RecyclerView rvMessage;
    private TextView tvNoMessages;
    private ContentLoadingProgressBar pbWait;
    private Group grpReady;

    private long folder;
    private String name;

    private AdapterUnreadMessage adapter;
    private ViewModelUnread model;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Bundle args = getArguments();
        folder = (args == null ? -1 : args.getLong("folder", -1));
        name = (args == null ? null : args.getString("name"));

        setTitle(R.string.title_unread_tab_unread);
        setSubtitle(name);
    }

    @Override
    @Nullable
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        view = (ViewGroup) inflater.inflate(R.layout.fragment_unread_messages, container, false);

        swipeRefresh = view.findViewById(R.id.swipeRefresh);
        rvMessage = view.findViewById(R.id.rvMessage);
        tvNoMessages = view.findViewById(R.id.tvNoMessages);
        pbWait = view.findViewById(R.id.pbWait);
        grpReady = view.findViewById(R.id.grpReady);

        // Nothing to pull: this list is a view over already-synced mail, and it
        // has no remote paging tier (see ViewModelUnread).
        swipeRefresh.setEnabled(false);

        rvMessage.setHasFixedSize(false);
        rvMessage.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new AdapterUnreadMessage(getContext(), this);
        rvMessage.setAdapter(adapter);

        pbWait.setVisibility(View.VISIBLE);
        grpReady.setVisibility(View.GONE);

        return view;
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        // Scoped to the parent fragment's activity so returning to the folder
        // list does not discard and re-run this folder's query.
        model = new ViewModelProvider(this).get(ViewModelUnread.class);

        model.getMessages(folder).observe(getViewLifecycleOwner(),
                new Observer<PagedList<TupleMessageEx>>() {
                    @Override
                    public void onChanged(@Nullable PagedList<TupleMessageEx> messages) {
                        if (messages == null)
                            return;

                        adapter.submitList(messages);

                        pbWait.setVisibility(View.GONE);
                        grpReady.setVisibility(View.VISIBLE);
                        // Reaching empty here is normal: it means everything in
                        // this folder has now been read.
                        tvNoMessages.setVisibility(
                                messages.isEmpty() ? View.VISIBLE : View.GONE);
                    }
                });
    }

    @Override
    public void onDestroyView() {
        // The next folder opened must not briefly show this one's messages.
        if (model != null)
            model.clearMessages();
        super.onDestroyView();
    }

    // Hand off to the app's real thread view rather than rendering the message
    // here: expansion, attachments, reply and move all already work there.
    @Override
    public void onUnreadMessageSelected(@NonNull TupleMessageEx message) {
        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(getContext());
        lbm.sendBroadcast(
                new Intent(ActivityView.ACTION_VIEW_THREAD)
                        .putExtra("account", message.account)
                        .putExtra("folder", message.folder)
                        .putExtra("thread", message.thread)
                        .putExtra("id", message.id)
                        .putExtra("found", false));
    }
}
