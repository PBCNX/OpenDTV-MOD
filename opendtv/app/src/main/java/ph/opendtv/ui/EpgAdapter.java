package ph.opendtv.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import ph.opendtv.R;
import ph.opendtv.ts.EpgEvent;
import ph.opendtv.ts.TsUtil;

public class EpgAdapter extends RecyclerView.Adapter<EpgAdapter.ViewHolder> {

    private final Context context;
    private final List<EpgEvent> events;
    private final long clockMs;

    public EpgAdapter(Context context, List<EpgEvent> events, long clockMs) {
        this.context = context;
        this.events = events;
        this.clockMs = clockMs;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_epg, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        EpgEvent event = events.get(position);
        holder.tvTitle.setText(event.name.isEmpty() ? "(no title)" : event.name);
        holder.tvTime.setText(TsUtil.formatTime(event.startMs) + " (" + (event.durationSec / 60) + "m)");
        holder.tvDescription.setText(event.text);

        long now = clockMs > 0 ? clockMs : System.currentTimeMillis();
        boolean isCurrent = event.startMs >= 0 && now >= event.startMs && now < event.endMs();

        if (isCurrent && event.durationSec > 0) {
            holder.progressBar.setVisibility(View.VISIBLE);
            long elapsed = now - event.startMs;
            int progress = (int) (elapsed * 100 / (event.durationSec * 1000L));
            holder.progressBar.setProgress(Math.max(0, Math.min(100, progress)));
        } else {
            holder.progressBar.setVisibility(View.GONE);
        }
    }

    @Override
    public int getItemCount() {
        return events.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvTitle;
        TextView tvTime;
        TextView tvDescription;
        ProgressBar progressBar;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvTitle = itemView.findViewById(R.id.tv_epg_title);
            tvTime = itemView.findViewById(R.id.tv_epg_time);
            tvDescription = itemView.findViewById(R.id.tv_epg_description);
            progressBar = itemView.findViewById(R.id.progress_epg);
        }
    }
}
