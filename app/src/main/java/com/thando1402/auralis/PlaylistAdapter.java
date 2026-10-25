package com.thando1402.auralis;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.imageview.ShapeableImageView;

import java.util.List;

public class PlaylistAdapter extends RecyclerView.Adapter<PlaylistAdapter.PlaylistViewHolder> {

    public interface OnPlaylistClickListener {
        void onPlaylistClick(Playlist playlist);
        void onPlaylistOptionsClick(Playlist playlist, View anchor);
    }

    private final List<Playlist> playlists;
    private final OnPlaylistClickListener listener;

    public PlaylistAdapter(List<Playlist> playlists, OnPlaylistClickListener listener) {
        this.playlists = playlists;
        this.listener = listener;
    }

    @NonNull
    @Override
    public PlaylistViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_playlist, parent, false);
        return new PlaylistViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PlaylistViewHolder holder, int position) {
        Playlist item = playlists.get(position);
        holder.textTitle.setText(item.getTitle());
        holder.textSub.setText(item.getSubtitle());
        holder.imgCover.setImageResource(item.getIconRes() != 0 ? item.getIconRes() : R.drawable.ic_playlist_placeholder);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onPlaylistClick(item);
            }
        });

        holder.btnOptions.setOnClickListener(v -> {
            if (listener != null) {
                listener.onPlaylistOptionsClick(item, v);
            }
        });
    }

    @Override
    public int getItemCount() {
        return playlists.size();
    }

    static class PlaylistViewHolder extends RecyclerView.ViewHolder {
        ShapeableImageView imgCover;
        TextView textTitle, textSub;
        AppCompatImageButton btnOptions;

        public PlaylistViewHolder(@NonNull View itemView) {
            super(itemView);
            imgCover = itemView.findViewById(R.id.img_playlist_cover);
            textTitle = itemView.findViewById(R.id.text_playlist_title);
            textSub = itemView.findViewById(R.id.text_playlist_sub);
            btnOptions = itemView.findViewById(R.id.btn_playlist_options);
        }
    }
}
