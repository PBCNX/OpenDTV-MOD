package com.pbcnx.opendtvmod.ui.watch;

import android.view.KeyEvent;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import java.util.function.Supplier;

import com.pbcnx.opendtvmod.R;
import com.pbcnx.opendtvmod.tuner.ChannelStore;
import com.pbcnx.opendtvmod.ui.ChannelAdapter;

/**
 * Favourites reorder: the drag helper, the remote key mode and the order write-back.
 *
 * <p>MainActivity keeps the tab switch and the key routing; the list mechanics live here.
 */
public final class FavoritesReorder {

    private final RecyclerView recyclerView;
    private final ChannelStore store;
    private final Supplier<ChannelStore.Storage> prefs;
    private ItemTouchHelper drag;
    private ChannelAdapter adapter;
    private int movingPosition = -1;

    public FavoritesReorder(RecyclerView recyclerView, ChannelStore store,
                            Supplier<ChannelStore.Storage> prefs) {
        this.recyclerView = recyclerView;
        this.store = store;
        this.prefs = prefs;
    }

    /** Called when the Favorites tab is built; any other tab calls {@link #detach()}. */
    public void attach(ChannelAdapter list) {
        adapter = list;
        if (drag == null) {
            drag = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                    ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
                @Override
                public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh,
                                      @NonNull RecyclerView.ViewHolder target) {
                    int from = vh.getAdapterPosition();
                    int to = target.getAdapterPosition();
                    if (from < 0 || to < 0 || adapter == null) return false;
                    // Belt and braces with detach(): a row may only be picked up while the
                    // favourites list is the one on screen.
                    if (rv.getAdapter() != adapter) return false;
                    adapter.moveItem(from, to);
                    movingPosition = to;
                    saveOrder();
                    return true;
                }

                @Override
                public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {
                    // intentionally empty
                }
            });
        }
        drag.attachToRecyclerView(recyclerView);
    }

    /** Puts the drag away with the tab; a stale attachment would lift rows on other tabs. */
    public void detach() {
        if (drag != null) drag.attachToRecyclerView(null);
        adapter = null;
        movingPosition = -1;
    }

    /** Remote reorder mode: -1 when not moving. */
    public int movingPosition() {
        return movingPosition;
    }

    /** Long OK on a row enters move mode. */
    public void enterAtFocused() {
        if (adapter == null) return;
        View focused = recyclerView.findFocus();
        if (focused == null) return;
        int position = recyclerView.getChildAdapterPosition(focused);
        if (position == RecyclerView.NO_POSITION) return;
        movingPosition = position;
        Toast.makeText(recyclerView.getContext(), R.string.toast_move_favorite,
                Toast.LENGTH_SHORT).show();
    }

    /** @return true when the key was consumed by move mode */
    public boolean handleKey(KeyEvent e) {
        if (movingPosition < 0 || e.getAction() != KeyEvent.ACTION_DOWN) return false;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
                move(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                move(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BACK:
                movingPosition = -1;
                saveOrder();
                // No toast on BACK: the rows moved, the mode is over, and a message reads
                // like being told off for leaving.
                return true;
            default:
                return false;
        }
    }

    private void move(int delta) {
        if (adapter == null || movingPosition < 0) return;
        int from = movingPosition;
        int to = from + delta;
        if (to < 0 || to >= adapter.getItemCount()) return;
        adapter.moveItem(from, to);
        movingPosition = to;
        saveOrder();
    }

    private void saveOrder() {
        if (adapter == null) return;
        store.setOrderFrom(adapter.items());
        store.saveOrder(prefs.get());
    }
}
