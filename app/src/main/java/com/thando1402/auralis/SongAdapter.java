package com.thando1402.auralis;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class SongAdapter extends RecyclerView.Adapter<SongAdapter.SongViewHolder> {

    public interface OnSongClickListener {
        void onSongClick(int position);
    }

    private final List<Song> songs;
    private final OnSongClickListener listener;

    public SongAdapter(List<Song> songs, OnSongClickListener listener) {
        this.songs = songs;
        this.listener = listener;
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
        holder.titleText.setText(song.getTitle());
        holder.artistText.setText(song.getArtist());
        
        holder.itemView.setOnClickListener(v -> listener.onSongClick(position));
    }

    @Override
    public int getItemCount() {
        return songs.size();
    }

    public static class SongViewHolder extends RecyclerView.ViewHolder {
        TextView titleText, artistText;

        public SongViewHolder(@NonNull View itemView) {
            super(itemView);
            titleText = itemView.findViewById(R.id.text_song_title);
            artistText = itemView.findViewById(R.id.text_song_artist);
        }
    }
}