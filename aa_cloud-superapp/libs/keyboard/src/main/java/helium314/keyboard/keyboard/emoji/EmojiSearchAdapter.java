/*
 * patch 0010: search results adapter for emoji palette search bar.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package helium314.keyboard.keyboard.emoji;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import helium314.keyboard.latin.R;

/**
 * Search-results adapter for the emoji palette ViewPager2.
 * Renders matched emoji strings in a single {@link EmojiPageKeyboardView} page.
 * The keyboard is supplied by the caller (built by EmojiPalettesView from the search results).
 * Tap on a result is routed through {@link EmojiViewCallback#onReleaseKey(Key)} so it lands
 * in recents and inputs the emoji exactly like a normal emoji tap.
 */
final class EmojiSearchAdapter extends RecyclerView.Adapter<EmojiSearchAdapter.ViewHolder> {

    private final DynamicGridKeyboard mResultKeyboard;
    private final EmojiViewCallback mViewCallback;

    /**
     * @param resultKeyboard pre-built DynamicGridKeyboard containing matched emoji keys
     * @param callback       EmojiViewCallback (usually EmojiPalettesView) — routes taps via onReleaseKey
     */
    EmojiSearchAdapter(final DynamicGridKeyboard resultKeyboard,
                       final EmojiViewCallback callback) {
        mResultKeyboard = resultKeyboard;
        mViewCallback = callback;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        final EmojiPageKeyboardView kv = (EmojiPageKeyboardView)
                LayoutInflater.from(parent.getContext())
                              .inflate(R.layout.emoji_keyboard_page, parent, false);
        kv.setEmojiViewCallback(mViewCallback);
        return new ViewHolder(kv);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.getView().setKeyboard(mResultKeyboard);
    }

    @Override
    public int getItemCount() {
        return 1; // all results fit in a single scrollable page (DynamicGridKeyboard)
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull ViewHolder holder) {
        holder.getView().releaseCurrentKey(false);
        holder.getView().deallocateMemory();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        private final EmojiPageKeyboardView mView;
        ViewHolder(View v) {
            super(v);
            mView = (EmojiPageKeyboardView) v;
        }
        EmojiPageKeyboardView getView() { return mView; }
    }
}
