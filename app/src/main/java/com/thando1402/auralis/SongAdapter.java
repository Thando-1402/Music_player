package com.thando1402.auralis;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.imageview.ShapeableImageView;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SongAdapter extends RecyclerView.Adapter<SongAdapter.SongViewHolder> {

    public interface OnSongClickListener {
        void onSongClick(int position);
        default void onSongOptionsClick(int position) {}
        default void onSongAiCleanClick(int position) {}
    }

    private static final ExecutorService THUMB_EXECUTOR = Executors.newFixedThreadPool(2);
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

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
        holder.textArtist.setText(holder.itemView.getContext().getString(
                R.string.song_artist_duration_fmt, song.getArtist(), durationStr));
        holder.textCodec.setText(R.string.song_codec_lossless);

        bindSongCoverArt(holder.imageArt, song);

        boolean isCurrent = (position == selectedPosition);
        if (holder.indicator != null) {
            holder.indicator.setVisibility(isCurrent ? View.VISIBLE : View.GONE);
            holder.indicator.animateBars(isCurrent);
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
                    } else if (id == R.id.action_ai_clean_song) {
                        listener.onSongAiCleanClick(pos);
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

    private void bindSongCoverArt(ShapeableImageView imageArt, Song song) {
        if (imageArt == null) return;
        Uri audioUri = song.getUri();
        Uri customUri = song.getCustomCoverUri();
        if (audioUri == null && customUri == null) {
            imageArt.setTag(null);
            imageArt.setImageResource(R.drawable.ic_album_placeholder);
            return;
        }

        final String cacheKey = "row|" + (audioUri != null ? audioUri : "")
                + (customUri != null ? "|" + customUri : "");
        imageArt.setTag(cacheKey);

        Bitmap cached = ArtworkColorHelper.getCachedThumbnail(cacheKey);
        if (cached != null) {
            imageArt.setImageBitmap(cached);
            return;
        }

        imageArt.setImageResource(R.drawable.ic_album_placeholder);
        final Context appCtx = imageArt.getContext().getApplicationContext();
        THUMB_EXECUTOR.execute(() -> {
            Bitmap loaded = ArtworkColorHelper.loadThumbnailCached(appCtx, cacheKey, customUri, audioUri, 96);
            if (loaded != null) {
                MAIN_HANDLER.post(() -> {
                    if (cacheKey.equals(imageArt.getTag())) {
                        imageArt.setImageBitmap(loaded);
                    }
                });
            }
        });
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
        EqualizerBarsView indicator;
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
