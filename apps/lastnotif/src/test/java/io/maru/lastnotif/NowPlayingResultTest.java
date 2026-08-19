package io.maru.lastnotif;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.maru.lastnotif.LastNotifSiteClient.NowPlayingResult;

public class NowPlayingResultTest {

    @Test
    public void testConstructorNormalValues() {
        NowPlayingResult result = new NowPlayingResult("Never Gonna Give You Up", "Rick Astley", "Whenever You Need Somebody", true);

        assertEquals("Never Gonna Give You Up", result.title);
        assertEquals("Rick Astley", result.artist);
        assertEquals("Whenever You Need Somebody", result.album);
        assertTrue(result.isPlaying);
    }

    @Test
    public void testConstructorNullValues() {
        NowPlayingResult result = new NowPlayingResult(null, null, null, false);

        assertEquals("", result.title);
        assertEquals("", result.artist);
        assertEquals("", result.album);
        assertFalse(result.isPlaying);
    }

    @Test
    public void testTrackKey() {
        NowPlayingResult result = new NowPlayingResult("Bohemian Rhapsody", "Queen", "A Night at the Opera", true);

        assertEquals("Queen - Bohemian Rhapsody", result.trackKey());
    }

    @Test
    public void testTrackKeyWithNulls() {
        NowPlayingResult result = new NowPlayingResult(null, null, null, true);

        assertEquals(" - ", result.trackKey());
    }
}
