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

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

// comms: read-only rows for the "Server rules (cloud)" section — the sorted,
// parsed Sieve summary fetched by FragmentRules. Deliberately a plain
// non-selectable list (no DiffUtil, no popup menu, no click target): unlike
// AdapterRule's device rules these aren't editable from the app, they're a
// server config the app can only read (see JmapService#fetchSieveScript).
public class AdapterServerRule extends RecyclerView.Adapter<AdapterServerRule.ViewHolder> {
    private final LayoutInflater inflater;
    private List<SieveRules.Row> rows = new ArrayList<>();

    AdapterServerRule(LayoutInflater inflater) {
        this.inflater = inflater;
        setHasStableIds(false);
    }

    void set(List<SieveRules.Row> rows) {
        this.rows = (rows == null ? new ArrayList<>() : rows);
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = inflater.inflate(R.layout.item_server_rule, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        SieveRules.Row row = rows.get(position);
        holder.tvTitle.setText(row.title);
        holder.tvSubtitle.setText(row.subtitle);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvTitle;
        private final TextView tvSubtitle;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvTitle = itemView.findViewById(R.id.tvTitle);
            tvSubtitle = itemView.findViewById(R.id.tvSubtitle);
        }
    }
}
