package com.homektv.library;

/** Android-compatible replacement for Home KTV's Java record. MIT, Home KTV contributors. */
public final class ParsedMeta {
    private final String title, artist;
    private final boolean recognized;
    private ParsedMeta(String title, String artist, boolean recognized) {
        this.title = title; this.artist = artist; this.recognized = recognized;
    }
    public String title() { return title; }
    public String artist() { return artist; }
    public boolean recognized() { return recognized; }
    public static ParsedMeta unrecognized(String title) { return new ParsedMeta(title, "", false); }
    public static ParsedMeta of(String title, String artist) {
        boolean ok = title != null && !title.trim().isEmpty();
        return new ParsedMeta(ok ? title.trim() : "", artist == null ? "" : artist.trim(), ok);
    }
}
