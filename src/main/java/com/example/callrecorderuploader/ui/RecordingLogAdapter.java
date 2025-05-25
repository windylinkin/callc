package com.example.callrecorderuploader.ui; // Changed package

import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.callrecorderuploader.R;
import com.example.callrecorderuploader.model.RecordingEntry;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class RecordingLogAdapter extends RecyclerView.Adapter<RecordingLogAdapter.ViewHolder> {

    private List<RecordingEntry> recordingEntries = new ArrayList<>(); // Initialize
    private final Context context;
    private final OnManualUploadClickListener manualUploadClickListener;

    public interface OnManualUploadClickListener {
        void onManualUploadClick(RecordingEntry entry);
    }

    public RecordingLogAdapter(Context context, List<RecordingEntry> initialEntries, OnManualUploadClickListener listener) {
        this.context = context;
        if (initialEntries != null) {
            this.recordingEntries.addAll(initialEntries);
        }
        this.manualUploadClickListener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.list_item_recording, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        RecordingEntry entry = recordingEntries.get(position);
        holder.tvFileName.setText(entry.getFileName());

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        holder.tvTimestamp.setText(context.getString(R.string.created_at_template, sdf.format(new Date(entry.getCreationTimestamp()))));

        String statusText = context.getString(R.string.status_template, entry.getUploadStatus());
        if (!TextUtils.isEmpty(entry.getServerResponseMessage())) {
            statusText += "\n  " + context.getString(R.string.server_response_template, entry.getServerResponseMessage());
        }
        holder.tvUploadStatus.setText(statusText);


        // Color coding based on status
        if (entry.getUploadStatus().startsWith(context.getString(R.string.status_upload_success_prefix))) {
            holder.tvUploadStatus.setTextColor(Color.parseColor("#008000")); // Green for success
        } else if (entry.getUploadStatus().startsWith(context.getString(R.string.status_upload_failed_prefix))) {
            holder.tvUploadStatus.setTextColor(Color.RED); // Red for failed
        } else if (entry.getUploadStatus().contains("上传中") || entry.getUploadStatus().contains("排队中")) {
            holder.tvUploadStatus.setTextColor(Color.parseColor("#FFA500")); // Orange for in progress/queued
        }
        else {
            holder.tvUploadStatus.setTextColor(Color.DKGRAY); // Default
        }


        // Control manual upload button visibility
        // Show if failed, or if "not yet processed" or "pending manual"
        boolean showManualUpload = (entry.getUploadStatus() != null &&
                (entry.getUploadStatus().startsWith(context.getString(R.string.status_upload_failed_prefix)) ||
                 entry.getUploadStatus().equals(context.getString(R.string.status_not_yet_processed)) ||
                 entry.getUploadStatus().equals(context.getString(R.string.status_pending_manual_upload))
                ));

        if (showManualUpload) {
            holder.btnManualUpload.setVisibility(View.VISIBLE);
            holder.btnManualUpload.setOnClickListener(v -> {
                if (manualUploadClickListener != null) {
                    manualUploadClickListener.onManualUploadClick(entry);
                }
            });
        } else {
            holder.btnManualUpload.setVisibility(View.GONE);
        }
    }

    @Override
    public int getItemCount() {
        return recordingEntries.size();
    }

    public void updateData(List<RecordingEntry> newEntries) {
        // Could use DiffUtil here for better performance
        this.recordingEntries.clear();
        if (newEntries != null) {
            this.recordingEntries.addAll(newEntries);
        }
        notifyDataSetChanged();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvFileName, tvTimestamp, tvUploadStatus;
        Button btnManualUpload;

        ViewHolder(View itemView) {
            super(itemView);
            tvFileName = itemView.findViewById(R.id.tvRecordingFileName);
            tvTimestamp = itemView.findViewById(R.id.tvRecordingTimestamp);
            tvUploadStatus = itemView.findViewById(R.id.tvRecordingUploadStatus);
            btnManualUpload = itemView.findViewById(R.id.btnManualUpload);
        }
    }
}