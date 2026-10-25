package com.thando1402.auralis;

import android.net.Uri;

public class Song {
    private String title;
    private String artist;
    private final Uri uri;
    private final long duration;
    private Uri customCoverUri;
    private String lyrics;

    public Song(String title, String artist, Uri uri, long duration) {
        this.title = title;
        this.artist = artist;
        this.uri = uri;
        this.duration = duration;
    }

    public String getLyrics() {
        return lyrics;
    }

    public void setLyrics(String lyrics) {
        this.lyrics = lyrics;
    }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getArtist() { return artist; }
    public void setArtist(String artist) { this.artist = artist; }

    public Uri getUri() { return uri; }
    public long getDuration() { return duration; }

    public Uri getCustomCoverUri() { return customCoverUri; }
    public void setCustomCoverUri(Uri customCoverUri) { this.customCoverUri = customCoverUri; }
}