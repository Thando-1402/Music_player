package com.thando1402.auralis;

public class FolderItem {
    private final String name;
    private final String path;
    private final int fileCount;
    private final long totalBytes;

    public FolderItem(String name, String path, int fileCount, long totalBytes) {
        this.name = name;
        this.path = path;
        this.fileCount = fileCount;
        this.totalBytes = totalBytes;
    }

    public String getName() { return name; }
    public String getPath() { return path; }
    public int getFileCount() { return fileCount; }
    public long getTotalBytes() { return totalBytes; }
}