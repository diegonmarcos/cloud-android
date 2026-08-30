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

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.paging.DataSource;
import androidx.paging.LivePagedListBuilder;
import androidx.paging.PagedList;

import java.util.List;

// comms: the unread lane's view model.
//
// Separate from ViewModelMessages on purpose. That class keys its models by
// AdapterMessage.ViewType and carries the whole filter/sort/threading argument
// surface plus a BoundaryCallback that pages the SERVER for more history. The
// unread lane wants none of that: it is one query per folder, no threading, no
// user-facing sort, and no remote paging -- reaching the end of the unread list
// means the folder has no more unread mail, not that more should be fetched.
//
// Holding the LiveData here (rather than rebuilding it in the fragment) keeps a
// rotation or a fragment re-attach from tearing down and re-running the query.
public class ViewModelUnread extends AndroidViewModel {
    private LiveData<List<TupleFolderEx>> folders = null;
    private Long foldersAccount = null;
    private boolean foldersPrimary = false;

    private LiveData<PagedList<TupleMessageEx>> messages = null;
    private long messagesFolder = -1;

    // Local-only paging: the unread set is bounded by what is already synced,
    // so there is no prefetch-into-the-network tier to size for.
    private static final int PAGE_SIZE = 50;
    private static final int MAX_CACHED_ITEMS = PAGE_SIZE * 10;

    public ViewModelUnread(@NonNull Application application) {
        super(application);
    }

    // The unread folders of an account, or of the unified inbox when account is
    // null. Re-requesting the same (account, primary) pair returns the SAME
    // LiveData: the fragment observes it across its own lifecycle, and handing
    // back a fresh instance would leave the old query running with no observer
    // until it was garbage collected.
    LiveData<List<TupleFolderEx>> getFolders(Long account, boolean primary) {
        boolean same = (folders != null &&
                foldersPrimary == primary &&
                (foldersAccount == null ? account == null : foldersAccount.equals(account)));
        if (!same) {
            Log.i("Unread: folders account=" + account + " primary=" + primary);
            DB db = DB.getInstance(getApplication());
            folders = db.folder().liveUnreadFolders(account, primary);
            foldersAccount = account;
            foldersPrimary = primary;
        }
        return folders;
    }

    // The unread messages of one folder, newest first.
    LiveData<PagedList<TupleMessageEx>> getMessages(long folder) {
        if (messages == null || messagesFolder != folder) {
            Log.i("Unread: messages folder=" + folder);
            DB db = DB.getInstance(getApplication());

            PagedList.Config config = new PagedList.Config.Builder()
                    .setInitialLoadSizeHint(PAGE_SIZE)
                    .setPageSize(PAGE_SIZE)
                    .setMaxSize(MAX_CACHED_ITEMS)
                    .build();

            DataSource.Factory<Integer, TupleMessageEx> pager =
                    db.message().pagedUnread(folder, false, true, BuildConfig.DEBUG);

            // No setBoundaryCallback: see the class comment -- the end of this
            // list is the end of the unread mail, not a cue to fetch history.
            messages = new LivePagedListBuilder<>(pager, config).build();
            messagesFolder = folder;
        }
        return messages;
    }

    // Called when leaving a folder so the next open re-runs the query instead of
    // showing the previous folder's list for a frame.
    void clearMessages() {
        messages = null;
        messagesFolder = -1;
    }
}
