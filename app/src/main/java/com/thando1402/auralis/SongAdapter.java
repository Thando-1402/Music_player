package com.thando1402.auralis;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.imageview.ShapeableImageView;

import java.util.List;
import java.util.Locale;

public class SongAdapter extends RecyclerView.Adapter<SongAdapter.SongViewHolder> {

    public interface OnSongClickListener {
        void onSongClick(int position);
        default void onSongOptionsClick(int position) {}
    }

    private final List<Song> songs;
    private final OnSongClickListener listener;
    private int selectedPosition = -1;

    public SongAdapter(List<Song> songs, OnSongClickListener listener) {
        this.songs = songs;
        this.listener = listener;
        setHasStableIds(true);
    }

    @Override
    public long getItemId(int position) {
        Song s = songs.get(position);
        return s.getUri() != null ? s.getUri().hashCode() : position;
    }

    @NonNull
    @Override
    public SongViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_song, parent, false);
        return new SongViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull SongViewHolder holder, int position) {
        Song song = songs.get(position);
        holder.textTitle.setText(song.getTitle());

        String durationStr = formatDuration(song.getDuration());
        holder.textArtist.setText(song.getArtist() + " • " + durationStr);
        holder.textCodec.setText("LOSSLESS / HQ");

        if (song.getCustomCoverUri() != null) {
            holder.imageArt.setImageURI(song.getCustomCoverUri());
        } else {
            holder.imageArt.setImageResource(R.drawable.ic_album_placeholder);
        }

        boolean isCurrent = (position == selectedPosition);
        if (holder.indicator != null) {
            holder.indicator.setVisibility(isCurrent ? View.VISIBLE : View.GONE);
        }
        holder.textTitle.setTextColor(ContextCompat.getColor(
                holder.itemView.getContext(),
                isCurrent ? R.color.accent_blue : R.color.text_primary));

        holder.itemView.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) {
                int prev = selectedPosition;
                selectedPosition = pos;
                if (prev != -1) notifyItemChanged(prev);
                notifyItemChanged(selectedPosition);
                listener.onSongClick(pos);
            }
        });

        if (holder.btnOptions != null) {
            holder.btnOptions.setOnClickListener(v -> {
                int pos = holder.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;
                PopupMenu popup = new PopupMenu(v.getContext(), v);
                popup.inflate(R.menu.song_overflow_menu);
                popup.setOnMenuItemClickListener(item -> {
                    int id = item.getItemId();
                    if (id == R.id.action_play_now) {
                        listener.onSongClick(pos);
                        return true;
                    } else if (id == R.id.action_edit_tags) {
                        listener.onSongOptionsClick(pos);
                        return true;
                    }
                    return false;
                });
                popup.show();
            });
        }
    }

    private String formatDuration(long durationMs) {
        long totalSeconds = Math.max(0, durationMs / 1000);
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }

    @Override
    public int getItemCount() {
        return songs.size();
    }

    static class SongViewHolder extends RecyclerView.ViewHolder {
        TextView textTitle, textArtist, textCodec;
        ShapeableImageView imageArt;
        ImageView indicator;
        View btnOptions;

        public SongViewHolder(@NonNull View itemView) {
            super(itemView);
            textTitle = itemView.findViewById(R.id.text_song_title);
            textArtist = itemView.findViewById(R.id.text_song_artist_duration);
            textCodec = itemView.findViewById(R.id.text_song_codec);
            imageArt = itemView.findViewById(R.id.img_song_art);
            indicator = itemView.findViewById(R.id.icon_now_playing_indicator);
            btnOptions = itemView.findViewById(R.id.btn_song_overflow);
        }
    }
}