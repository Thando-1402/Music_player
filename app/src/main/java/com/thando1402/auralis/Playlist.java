package com.thando1402.auralis;

public class Playlist {
    private String id;
    private String title;
    private String subtitle;
    private int trackCount;
    private int iconRes;
    private String type; // "favorites", "smart", "recent", "custom"

    public Playlist(String id, String title, String subtitle, int trackCount, int iconRes, String type) {
        this.id = id;
        this.title = title;
        this.subtitle = subtitle;
        this.trackCount = trackCount;
        this.iconRes = iconRes;
        this.type = type;
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSubtitle() {
        return subtitle;
    }

    public void setSubtitle(String subtitle) {
        this.subtitle = subtitle;
    }

    public int getTrackCount() {
        return trackCount;
    }

    public void setTrackCount(int trackCount) {
        this.trackCount = trackCount;
    }

    public int getIconRes() {
        return iconRes;
    }

    public void setIconRes(int iconRes) {
        this.iconRes = iconRes;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }
}
