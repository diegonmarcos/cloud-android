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
public class FragmentUnread extends FragmentBase
        implements AdapterUnreadFolder.IUnreadFolderSelected {
    private ViewGroup view;
    private SwipeRefreshLayout swipeRefresh;
    private TabLayout tabUnread;
    private RecyclerView rvFolder;
    private TextView tvNoUnread;
    private ContentLoadingProgressBar pbWait;
    private Group grpReady;

    private long account;
    private boolean primary;
    private boolean loaded = false;

    private AdapterUnreadFolder adapter;
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

        rvFolder.setHasFixedSize(false);
        rvFolder.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new AdapterUnreadFolder(getContext(), this);
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

                        loaded = true;
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

    @Override
    public void onUnreadFolderSelected(@NonNull TupleFolderEx folder) {
        Bundle args = new Bundle();
        args.putLong("folder", folder.id);
        args.putString("name", folder.getDisplayName(getContext()));

        FragmentUnreadMessages fragment = new FragmentUnreadMessages();
        fragment.setArguments(args);

        FragmentTransaction ft = getParentFragmentManager().beginTransaction();
        ft.replace(R.id.content_frame, fragment).addToBackStack("unread:messages");
        ft.commit();
    }
}
