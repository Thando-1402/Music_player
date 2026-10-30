package com.thando1402.auralis;

import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.PopupMenu;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class FolderAdapter extends RecyclerView.Adapter<FolderAdapter.FolderViewHolder> {

    public interface OnFolderClickListener {
        void onFolderClick(FolderItem folder);
        void onFolderLongClick(FolderItem folder);
    }

    private final List<FolderItem> folders;
    private final OnFolderClickListener listener;

    public FolderAdapter(List<FolderItem> folders, OnFolderClickListener listener) {
        this.folders = folders;
        this.listener = listener;
    }

    @NonNull
    @Override
    public FolderViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_folder, parent, false);
        return new FolderViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull FolderViewHolder holder, int position) {
        FolderItem folder = folders.get(position);
        holder.textName.setText(folder.getName());
        String sizeStr = Formatter.formatShortFileSize(holder.itemView.getContext(), folder.getTotalBytes());
        holder.textStats.setText(holder.itemView.getContext().getString(
                R.string.folder_stats_fmt, folder.getFileCount(), sizeStr));
        holder.itemView.setOnClickListener(v -> listener.onFolderClick(folder));
        holder.itemView.setOnLongClickListener(v -> {
            listener.onFolderLongClick(folder);
            return true;
        });

        if (holder.btnOptions != null) {
            holder.btnOptions.setOnClickListener(v -> {
                PopupMenu popup = new PopupMenu(v.getContext(), v);
                popup.getMenu().add(0, 1, 0, "Open Folder");
                popup.getMenu().add(0, 2, 1, "Hide Folder from Library");
                popup.setOnMenuItemClickListener(item -> {
                    if (item.getItemId() == 1) {
                        listener.onFolderClick(folder);
                        return true;
                    } else if (item.getItemId() == 2) {
                        listener.onFolderLongClick(folder);
                        return true;
                    }
                    return false;
                });
                popup.show();
            });
        }
    }

    @Override
    public int getItemCount() {
        return folders.size();
    }

    public static class FolderViewHolder extends RecyclerView.ViewHolder {
        TextView textName, textStats;
        View btnOptions;

        public FolderViewHolder(@NonNull View itemView) {
            super(itemView);
            textName = itemView.findViewById(R.id.text_folder_name);
            textStats = itemView.findViewById(R.id.text_folder_stats);
            btnOptions = itemView.findViewById(R.id.btn_folder_options);
        }
    }
}
