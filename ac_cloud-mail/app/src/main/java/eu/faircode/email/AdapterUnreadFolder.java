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

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// comms: the unread lane's folder adapter.
//
// Deliberately NOT AdapterFolder. That class renders a TREE and carries the
// whole folder-management surface: expanders, indentation, subscription
// filtering, hidden-folder rules and a large context menu -- all of which are
// meaningless for a flat list of "folders that have unread mail".
//
// It also could not be reused as-is. AdapterFolder switches into selection
// semantics whenever a listener is attached (isDisabled() treats read_only as
// unclickable once listener != null), so wiring a click listener onto it would
// have made a read-only folder holding unread mail impossible to open -- the
// exact rows this page exists to show.
public class AdapterUnreadFolder extends RecyclerView.Adapter<AdapterUnreadFolder.ViewHolder> {
    private final Context context;
    private final LayoutInflater inflater;
    private final IUnreadFolderSelected listener;
    private final NumberFormat NF = NumberFormat.getNumberInstance();

    // comms: typography/colour resolved at runtime, same as AdapterFolder --
    // baking these into the layout XML is exactly what let this list drift
    // out of sync with the user's compact/zoom/highlight prefs and the theme.
    private final float textSize;
    private final int textColorSecondary;
    private final int colorUnread;

    private List<TupleFolderEx> items = new ArrayList<>();

    interface IUnreadFolderSelected {
        void onUnreadFolderSelected(@NonNull TupleFolderEx folder);
    }

    AdapterUnreadFolder(Context context, IUnreadFolderSelected listener) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
        this.listener = listener;

        // comms: mirror AdapterFolder's constructor exactly (compact/zoom ->
        // text size, theme attrs -> colours) so this list tracks the same
        // prefs and theme as the real Folders page instead of a fixed look.
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean compact = prefs.getBoolean("compact", false);
        int zoom = prefs.getInt("view_zoom", compact ? 0 : 1);
        if (zoom == 0)
            zoom = 1;
        this.textSize = Helper.getTextSize(context, zoom);
        this.textColorSecondary = Helper.resolveColor(context, android.R.attr.textColorSecondary);

        boolean highlight_unread = prefs.getBoolean("highlight_unread", true);
        int colorHighlight = prefs.getInt("highlight_color", Helper.resolveColor(context, R.attr.colorUnreadHighlight));
        this.colorUnread = (highlight_unread ? colorHighlight : Helper.resolveColor(context, R.attr.colorUnread));

        setHasStableIds(true);
    }

    public void set(@NonNull List<TupleFolderEx> folders) {
        Log.i("Unread: set folders=" + folders.size());

        List<TupleFolderEx> sorted = new ArrayList<>(folders);
        // comms: the unread page must present folders in the same order as the
        // Folders page, so a folder does not move position just because its
        // unread count changed. Use the app's canonical folder ranking instead
        // of sorting by unread count.
        if (sorted.size() > 0)
            Collections.sort(sorted, sorted.get(0).getComparator(context));

        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffCallback(items, sorted), false);
        items = sorted;
        diff.dispatchUpdatesTo(this);
    }

    private static class DiffCallback extends DiffUtil.Callback {
        private final List<TupleFolderEx> prev;
        private final List<TupleFolderEx> next;

        DiffCallback(List<TupleFolderEx> prev, List<TupleFolderEx> next) {
            this.prev = new ArrayList<>(prev);
            this.next = new ArrayList<>(next);
        }

        @Override
        public int getOldListSize() {
            return prev.size();
        }

        @Override
        public int getNewListSize() {
            return next.size();
        }

        @Override
        public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
            return prev.get(oldItemPosition).id.equals(next.get(newItemPosition).id);
        }

        @Override
        public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
            TupleFolderEx f1 = prev.get(oldItemPosition);
            TupleFolderEx f2 = next.get(newItemPosition);
            // unseen is the whole point of this list, so it must take part in
            // the content comparison even though TupleFolderEx.equals covers it.
            return (f1.equals(f2) && f1.unseen == f2.unseen);
        }
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).id;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(inflater.inflate(R.layout.item_unread_folder, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(items.get(position));
    }

    public class ViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener {
        private final TextView tvName;
        private final TextView tvAccount;
        private final TextView tvUnread;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tvName);
            tvAccount = itemView.findViewById(R.id.tvAccount);
            tvUnread = itemView.findViewById(R.id.tvUnread);
            itemView.setOnClickListener(this);
        }

        void bind(TupleFolderEx folder) {
            tvName.setText(folder.getDisplayName(context));

            if (textSize != 0)
                tvName.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize);

            // Every row here is a folder that HAS unread mail (that is the
            // whole point of this page), so it always takes the unread
            // weight/colour -- same rule AdapterFolder applies per-row, just
            // without a read branch since one never occurs on this page.
            tvName.setTypeface(Helper.TYPEFACE_UNREAD);
            tvName.setTextColor(colorUnread);

            // The unified list mixes accounts, so the account name is what tells
            // two identically named folders apart.
            String account = folder.accountName;
            tvAccount.setText(account);
            tvAccount.setVisibility(TextUtils.isEmpty(account) ? View.GONE : View.VISIBLE);
            tvAccount.setTextColor(textColorSecondary);

            tvUnread.setText(NF.format(folder.unseen));
            tvUnread.setTextColor(colorUnread);
        }

        @Override
        public void onClick(View v) {
            int pos = getAdapterPosition();
            if (pos == RecyclerView.NO_POSITION)
                return;
            if (listener != null)
                listener.onUnreadFolderSelected(items.get(pos));
        }
    }
}
