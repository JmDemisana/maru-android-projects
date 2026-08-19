package io.maru.lastnotif;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP client that calls the Maru site's existing Last.fm proxy endpoints.
 *
 * Now-playing: GET /api/auth?route=lastfm/now-playing&username={user}&fast=1
 * Lyrics:      GET /api/auth?route=lastfm/lyrics&username={user}
 */
public class LastNotifSiteClient {

    private static final String TAG = "LastNotifSiteClient";
    private static final String BASE = "https://maruchansquigle.vercel.app/api/auth";
    private static final int TIMEOUT_MS = 10_000;

    // ─── Result types ─────────────────────────────────────────────────────────

    public static class NowPlayingResult {
        public final String title;
        public final String artist;
        public final String album;
        public final boolean isPlaying;

        public NowPlayingResult(String title, String artist, String album, boolean isPlaying) {
            this.title = title != null ? title : "";
            this.artist = artist != null ? artist : "";
            this.album = album != null ? album : "";
            this.isPlaying = isPlaying;
        }

        /** Stable key used to detect track changes */
        public String trackKey() {
            return artist + " - " + title;
        }
    }

    public static class LyricLine {
        public final long timestampMs;
        public final String text;

        public LyricLine(long timestampMs, String text) {
            this.timestampMs = timestampMs;
            this.text = text;
        }
    }

    public static class LyricsResult {
        public final List<LyricLine> lines;
        public final long syncStartedAtMs;

        public LyricsResult(List<LyricLine> lines, long syncStartedAtMs) {
            this.lines = lines;
            this.syncStartedAtMs = syncStartedAtMs;
        }
    }

    // ─── Now-playing ──────────────────────────────────────────────────────────

    /**
     * Fetches the now-playing track for the given username.
     * Returns null on network/parse error.
     */
    public static NowPlayingResult getNowPlaying(String username) {
        try {
            String urlStr = BASE
                + "?route=lastfm/now-playing"
                + "&username=" + encode(username)
                + "&fast=1";

            String json = fetchJson(urlStr);
            if (json == null) return null;

            JSONObject root = new JSONObject(json);
            JSONObject track = root.optJSONObject("track");
            if (track == null) return null;

            String title    = track.optString("title", "");
            String artist   = track.optString("artist", "");
            String album    = track.optString("album", "");
            boolean playing = track.optBoolean("nowPlaying", false);

            return new NowPlayingResult(title, artist, album, playing);

        } catch (Exception e) {
            Log.w(TAG, "getNowPlaying error: " + e.getMessage());
            return null;
        }
    }

    // ─── Lyrics ───────────────────────────────────────────────────────────────

    /**
     * Fetches synced lyrics for the given username's current track.
     * Returns null if no lyrics found or on error.
     */
    public static LyricsResult getLyrics(String username) {
        try {
            String urlStr = BASE
                + "?route=lastfm/lyrics"
                + "&username=" + encode(username);

            String json = fetchJson(urlStr);
            if (json == null) return null;

            JSONObject root = new JSONObject(json);
            JSONObject lyricsObj = root.optJSONObject("lyrics");
            if (lyricsObj == null) return null;

            String synced = lyricsObj.optString("synced", "");
            if (synced.isEmpty()) return null;

            long syncStartedAtMs = root.optLong("syncStartedAtMs", System.currentTimeMillis());

            List<LyricLine> lines = parseLrc(synced);
            if (lines.isEmpty()) return null;

            return new LyricsResult(lines, syncStartedAtMs);

        } catch (Exception e) {
            Log.w(TAG, "getLyrics error: " + e.getMessage());
            return null;
        }
    }

    // ─── LRC parser ───────────────────────────────────────────────────────────

    /** Parses LRC format "[mm:ss.xx] text" into a list of LyricLine objects */
    private static List<LyricLine> parseLrc(String lrc) {
        List<LyricLine> result = new ArrayList<>();
        Pattern p = Pattern.compile("\\[(\\d+):(\\d+\\.\\d+)\\](.*)");

        int start = 0;
        int len = lrc.length();
        while (start < len) {
            int end = lrc.indexOf('\n', start);
            if (end == -1) {
                end = len;
            }
            String line = lrc.substring(start, end).trim();
            if (!line.isEmpty()) {
                Matcher m = p.matcher(line);
                if (m.matches()) {
                    try {
                        long minutes = Long.parseLong(m.group(1));
                        double seconds = Double.parseDouble(m.group(2));
                        long tsMs = (minutes * 60_000L) + (long)(seconds * 1000L);
                        String text = m.group(3).trim();
                        result.add(new LyricLine(tsMs, text));
                    } catch (NumberFormatException ignored) {}
                }
            }
            start = end + 1;
        }

        return result;
    }

    // ─── HTTP helper ──────────────────────────────────────────────────────────

    private static String fetchJson(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Cache-Control", "no-cache");

        int code = conn.getResponseCode();
        if (code != 200) {
            Log.w(TAG, "HTTP " + code + " from " + urlStr);
            conn.disconnect();
            return null;
        }

        BufferedReader reader = new BufferedReader(
            new InputStreamReader(conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();
        conn.disconnect();

        return sb.toString();
    }

    private static String encode(String s) {
        if (s == null) return "";
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
