package com.thando1402.auralis;

import android.net.Uri;

public class Song {
    private final String title;
    private final String artist;
    private final Uri uri;
    private final long duration;

    public Song(String title, String artist, Uri uri, long duration) {
        this.title = title;
        this.artist = artist;
        this.uri = uri;
        this.duration = duration;
    }

    public String getTitle() { return title; }
    public String getArtist() { return artist; }
    public Uri getUri() { return uri; }
    public long getDuration() { return duration; }
}