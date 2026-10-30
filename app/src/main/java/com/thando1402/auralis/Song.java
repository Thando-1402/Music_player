package com.thando1402.auralis;

import android.net.Uri;

public class Song {
    private String title;
    private String artist;
    private String album = "";
    private String trackNumber = "";
    private String yearGenre = "";
    private final Uri uri;
    private final long duration;
    private Uri customCoverUri;
    private String lyrics;
    private long lrcOffsetMs = 0L;

    public Song(String title, String artist, Uri uri, long duration) {
        this.title = title != null ? title : "Unknown Track";
        this.artist = artist != null ? artist : "Unknown Artist";
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

    public String getAlbum() { return album; }
    public void setAlbum(String album) { this.album = album != null ? album : ""; }

    public String getTrackNumber() { return trackNumber; }
    public void setTrackNumber(String trackNumber) { this.trackNumber = trackNumber != null ? trackNumber : ""; }

    public String getYearGenre() { return yearGenre; }
    public void setYearGenre(String yearGenre) { this.yearGenre = yearGenre != null ? yearGenre : ""; }

    public long getLrcOffsetMs() { return lrcOffsetMs; }
    public void setLrcOffsetMs(long lrcOffsetMs) { this.lrcOffsetMs = lrcOffsetMs; }

    public Uri getUri() { return uri; }
    public long getDuration() { return duration; }

    public Uri getCustomCoverUri() { return customCoverUri; }
    public void setCustomCoverUri(Uri customCoverUri) { this.customCoverUri = customCoverUri; }
}
