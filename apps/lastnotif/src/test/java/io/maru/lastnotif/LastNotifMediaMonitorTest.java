package io.maru.lastnotif;

import org.junit.Test;
import static org.junit.Assert.*;

public class LastNotifMediaMonitorTest {

    @Test
    public void testTrackInfoInitialization_ValidData() {
        LastNotifMediaMonitor.TrackInfo trackInfo = new LastNotifMediaMonitor.TrackInfo("Song Title", "Artist Name", "Album Name", true);

        assertEquals("Song Title", trackInfo.title);
        assertEquals("Artist Name", trackInfo.artist);
        assertEquals("Album Name", trackInfo.album);
        assertTrue(trackInfo.isPlaying);
    }

    @Test
    public void testTrackInfoInitialization_NullHandling() {
        LastNotifMediaMonitor.TrackInfo trackInfo = new LastNotifMediaMonitor.TrackInfo(null, null, null, false);

        assertEquals("", trackInfo.title);
        assertEquals("", trackInfo.artist);
        assertEquals("", trackInfo.album);
        assertFalse(trackInfo.isPlaying);
    }

    @Test
    public void testTrackKey() {
        LastNotifMediaMonitor.TrackInfo trackInfo = new LastNotifMediaMonitor.TrackInfo("Title", "Artist", "Album", true);

        assertEquals("Artist - Title", trackInfo.trackKey());
    }

    @Test
    public void testTrackKey_WithNulls() {
        LastNotifMediaMonitor.TrackInfo trackInfo = new LastNotifMediaMonitor.TrackInfo(null, null, null, true);

        // Nulls become empty strings, so trackKey should be " - "
        assertEquals(" - ", trackInfo.trackKey());
    }
}
