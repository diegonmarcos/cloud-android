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

import java.util.List;

// comms: the unread lane's view model.
//
// Down to the folder query only: the message side (a paged, folder-scoped,
// no-boundary-callback query, once served straight to a custom message
// adapter) is gone now that opening an unread folder lands in the app's real
// FragmentMessages/ViewModelMessages -- see FragmentUnread and AdapterFolder's
// unread_only handling. DaoMessage.pagedUnread stays (its own call), but
// nothing in this class wraps it any more.
//
// Holding the LiveData here (rather than rebuilding it in the fragment) keeps a
// rotation or a fragment re-attach from tearing down and re-running the query.
public class ViewModelUnread extends AndroidViewModel {
    private LiveData<List<TupleFolderEx>> folders = null;
    private Long foldersAccount = null;
    private boolean foldersPrimary = false;

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
}
