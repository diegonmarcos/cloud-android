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
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.paging.PagedListAdapter;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.text.DateFormat;
import java.util.Objects;

// comms: the unread lane's message adapter.
//
// Deliberately NOT AdapterMessage. That class renders the full conversation
// surface and is driven by IProperties -- 28 methods covering expansion state,
// body sizing and scroll positions, attachments, selection, reply, search and
// MOVE. Reimplementing those for this page would mean reimplementing mail
// mutation, which is the last thing that should be duplicated.
//
// So this adapter renders a LIST row only, and opening a row hands off to the
// app's existing thread view via ACTION_VIEW_THREAD, where all of the above
// already works correctly.
public class AdapterUnreadMessage
        extends PagedListAdapter<TupleMessageEx, AdapterUnreadMessage.ViewHolder> {
    private final Context context;
    private final LayoutInflater inflater;
    private final IUnreadMessageSelected listener;
    private final DateFormat DTF;

    // comms: typography/colour resolved at runtime, the same set AdapterMessage
    // resolves in its constructor -- baking these into the layout XML is
    // exactly what let this list drift out of sync with the compact/zoom pref
    // and the read/unread colour rule 7017d9d2 established for the real list.
    private final int dp4;
    private final int dp8;
    private final boolean compact;
    private final float textSize;
    private final int colorUnread;
    private final int colorSubject;
    private final boolean highlight_subject;

    interface IUnreadMessageSelected {
        void onUnreadMessageSelected(@NonNull TupleMessageEx message);
    }

    AdapterUnreadMessage(Context context, IUnreadMessageSelected listener) {
        super(DIFF_CALLBACK);
        this.context = context;
        this.inflater = LayoutInflater.from(context);
        this.listener = listener;
        this.DTF = Helper.getDateTimeInstance(context);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        this.compact = prefs.getBoolean("compact", false);
        int zoom = prefs.getInt("view_zoom", compact ? 0 : 1);
        if (zoom == 0)
            zoom = 1;
        this.textSize = Helper.getTextSize(context, zoom);

        this.dp4 = Helper.dp2pixels(context, 4);
        this.dp8 = Helper.dp2pixels(context, 8);

        boolean highlight_unread = prefs.getBoolean("highlight_unread", true);
        int colorHighlight = prefs.getInt("highlight_color", Helper.resolveColor(context, R.attr.colorUnreadHighlight));
        this.colorUnread = (highlight_unread ? colorHighlight : Helper.resolveColor(context, R.attr.colorUnread));
        this.highlight_subject = prefs.getBoolean("highlight_subject", false);
        this.colorSubject = Helper.resolveColor(context, highlight_subject ? R.attr.colorUnreadHighlight : R.attr.colorRead);
    }

    private static final DiffUtil.ItemCallback<TupleMessageEx> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<TupleMessageEx>() {
                @Override
                public boolean areItemsTheSame(@NonNull TupleMessageEx m1, @NonNull TupleMessageEx m2) {
                    return m1.id.equals(m2.id);
                }

                @Override
                public boolean areContentsTheSame(@NonNull TupleMessageEx m1, @NonNull TupleMessageEx m2) {
                    return (Objects.equals(m1.subject, m2.subject) &&
                            Objects.equals(m1.received, m2.received) &&
                            m1.ui_seen == m2.ui_seen);
                }
            };

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(inflater.inflate(R.layout.item_unread_message, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(getItem(position));
    }

    public class ViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener {
        private final View clItem;
        private final TextView tvFrom;
        private final TextView tvSubject;
        private final TextView tvDate;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            clItem = itemView.findViewById(R.id.clItem);
            tvFrom = itemView.findViewById(R.id.tvFrom);
            tvSubject = itemView.findViewById(R.id.tvSubject);
            tvDate = itemView.findViewById(R.id.tvDate);
            itemView.setOnClickListener(this);

            // comms: compact/normal density, same pref AdapterMessage picks its
            // row layout by (item_message_compact vs item_message_normal) --
            // this row is simple enough to vary from code instead of forking
            // the layout resource.
            int p = (compact ? dp4 : dp8);
            clItem.setPadding(clItem.getPaddingLeft(), p, clItem.getPaddingRight(), p);
        }

        // PagedList yields null for a not-yet-loaded placeholder row.
        void bind(TupleMessageEx message) {
            if (message == null) {
                tvFrom.setText(null);
                tvSubject.setText(null);
                tvDate.setText(null);
                return;
            }

            // comms: every row here comes from DaoMessage.pagedUnread, which
            // filters "AND NOT message.ui_seen" -- so unlike AdapterMessage,
            // there is no read state to branch on: this row is always the
            // unread visual (size bump, TYPEFACE_UNREAD, colorUnread).
            if (textSize != 0) {
                tvFrom.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize * 1.35f);
                tvSubject.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize * 1.05f);
            }

            tvFrom.setTypeface(Helper.TYPEFACE_UNREAD);
            tvFrom.setTextColor(colorUnread);
            tvDate.setTypeface(Helper.TYPEFACE_UNREAD);
            tvDate.setTextColor(colorUnread);

            tvFrom.setText(MessageHelper.formatAddresses(message.from));

            String subject = message.subject;
            tvSubject.setText(subject);
            tvSubject.setVisibility(TextUtils.isEmpty(subject) ? View.GONE : View.VISIBLE);
            tvSubject.setTypeface(Helper.TYPEFACE_UNREAD);
            tvSubject.setTextColor(highlight_subject ? colorSubject : colorUnread);

            tvDate.setText(message.received == null ? null : DTF.format(message.received));
        }

        @Override
        public void onClick(View v) {
            int pos = getAdapterPosition();
            if (pos == RecyclerView.NO_POSITION)
                return;
            TupleMessageEx message = getItem(pos);
            if (message != null && listener != null)
                listener.onUnreadMessageSelected(message);
        }
    }
}
