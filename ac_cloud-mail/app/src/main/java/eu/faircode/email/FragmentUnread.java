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

import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.Group;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.tabs.TabLayout;

import java.util.List;

// comms: the unread lane's folder page -- a flat list of folders that hold
// unread mail, with an All | Unread switcher back to FragmentFolders.
//
// A separate fragment rather than a mode inside FragmentFolders: that class is
// built around the folder TREE and folder management (create, subscribe, sync,
// export, context menus). None of it applies here, and threading a mode flag
// through all of it is how the first attempt at this went wrong.
//
// The rows themselves are the app's real AdapterFolder, not a lookalike: a
// null listener makes AdapterFolder broadcast ActivityView.ACTION_VIEW_MESSAGES
// on row click (see AdapterFolder.onClick), exactly as FragmentFolders' own
// rows do, so opening a folder from here lands in the real FragmentMessages
// for that folder with no navigation code of our own.
public class FragmentUnread extends FragmentBase {
    private ViewGroup view;
    private SwipeRefreshLayout swipeRefresh;
    private TabLayout tabUnread;
    private RecyclerView rvFolder;
    private TextView tvNoUnread;
    private ContentLoadingProgressBar pbWait;
    private Group grpReady;

    private long account;
    private boolean primary;

    private AdapterFolder adapter;
    private ViewModelUnread model;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Bundle args = getArguments();
        account = (args == null ? -1 : args.getLong("account", -1));
        primary = (args != null && args.getBoolean("primary"));

        setTitle(R.string.page_folders);
    }

    @Override
    @Nullable
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // comms: without this the fragment's onOptionsItemSelected is never
        // called, so FragmentBase's android.R.id.home handler -- the one that
        // turns the top-left button into a back press -- never runs and the
        // button does nothing on this page. FragmentFolders declares the same
        // thing at the top of its own onCreateView.
        setHasOptionsMenu(true);

        view = (ViewGroup) inflater.inflate(R.layout.fragment_unread, container, false);

        swipeRefresh = view.findViewById(R.id.swipeRefresh);
        tabUnread = view.findViewById(R.id.tabUnread);
        rvFolder = view.findViewById(R.id.rvFolder);
        tvNoUnread = view.findViewById(R.id.tvNoUnread);
        pbWait = view.findViewById(R.id.pbWait);
        grpReady = view.findViewById(R.id.grpReady);

        // The unread set is derived from what is already synced, so a pull here
        // has nothing of its own to fetch. Disabled rather than hidden so the
        // gesture does not silently do nothing halfway through a drag.
        swipeRefresh.setEnabled(false);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
        boolean cards = prefs.getBoolean("cards", true);
        boolean dividers = prefs.getBoolean("dividers", true);
        boolean compact = prefs.getBoolean("compact_folders", true);
        boolean show_hidden = false; // matches FragmentFolders: hidden folders stay hidden here too
        boolean show_flagged = prefs.getBoolean("flagged_folders", false);
        boolean unified = (account < 0);

        rvFolder.setHasFixedSize(false);
        LinearLayoutManager llm = new LinearLayoutManager(getContext());
        rvFolder.setLayoutManager(llm);

        if (!cards && dividers) {
            DividerItemDecoration itemDecorator = new DividerItemDecoration(getContext(), llm.getOrientation()) {
                @Override
                public void getItemOffsets(Rect outRect, View view, RecyclerView parent, RecyclerView.State state) {
                    View clItem = view.findViewById(R.id.clItem);
                    if (clItem == null || clItem.getVisibility() == View.GONE)
                        outRect.setEmpty();
                    else
                        super.getItemOffsets(outRect, view, parent, state);
                }
            };
            itemDecorator.setDrawable(getContext().getDrawable(R.drawable.divider));
            rvFolder.addItemDecoration(itemDecorator);
        }

        // comms: same construction as FragmentFolders.java -- listener null is
        // required, not incidental: it is what makes AdapterFolder broadcast
        // ACTION_VIEW_MESSAGES on click instead of calling back into a listener
        // (a non-null listener would also disable read-only folders, which the
        // unread lane must not do). AdapterFolder orders and (for unified)
        // groups the folders itself; the unread lane must not compete with that
        // by sorting on unread count. The trailing "true" is the only thing
        // that differs from FragmentFolders: it makes the broadcast carry
        // unread_only=true, which ActivityView threads into FragmentMessages so
        // the opened folder shows only its unread thread, and it queues the
        // per-folder server read-state probe on open (see AdapterFolder).
        adapter = new AdapterFolder(this, account, unified, primary, compact, show_hidden, show_flagged, null, true);
        rvFolder.setAdapter(adapter);

        // Tab 1 is selected before the listener is attached, so arriving on this
        // page does not immediately count as the user switching tabs.
        tabUnread.addTab(tabUnread.newTab().setText(R.string.title_unread_tab_all));
        tabUnread.addTab(tabUnread.newTab().setText(R.string.title_unread_tab_unread));
        TabLayout.Tab initial = tabUnread.getTabAt(1);
        if (initial != null)
            initial.select();
        tabUnread.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                if (tab.getPosition() == 0)
                    showAllFolders();
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
                // Do nothing
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                // Do nothing
            }
        });

        pbWait.setVisibility(View.VISIBLE);
        grpReady.setVisibility(View.GONE);

        return view;
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        model = new ViewModelProvider(this).get(ViewModelUnread.class);

        model.getFolders(account < 0 ? null : account, primary)
                .observe(getViewLifecycleOwner(), new Observer<List<TupleFolderEx>>() {
                    @Override
                    public void onChanged(@Nullable List<TupleFolderEx> folders) {
                        if (folders == null)
                            return;

                        adapter.set(folders);

                        pbWait.setVisibility(View.GONE);
                        grpReady.setVisibility(View.VISIBLE);
                        // An empty unread list is the good outcome, not a
                        // failure, so it gets a message rather than a blank page.
                        tvNoUnread.setVisibility(
                                folders.isEmpty() ? View.VISIBLE : View.GONE);
                    }
                });
    }

    // Switch back to the full folder tree, replacing this page rather than
    // stacking on top of it: popping "folders" first keeps repeated tab
    // switching from growing the back stack.
    private void showAllFolders() {
        if (!getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
            return;

        Bundle args = new Bundle();
        args.putLong("account", account);
        args.putBoolean("primary", primary);

        FragmentFolders fragment = new FragmentFolders();
        fragment.setArguments(args);

        getParentFragmentManager().popBackStack("folders", androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);

        FragmentTransaction ft = getParentFragmentManager().beginTransaction();
        ft.replace(R.id.content_frame, fragment).addToBackStack("folders");
        ft.commit();
    }
}
