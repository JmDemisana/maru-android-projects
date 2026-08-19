package io.maru.lastnotif;

import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.URLStreamHandlerFactory;

public class LastNotifSiteClientTest {

    private static boolean factorySet = false;

    @BeforeClass
    public static void setup() {
        if (!factorySet) {
            try {
                URL.setURLStreamHandlerFactory(new URLStreamHandlerFactory() {
                    @Override
                    public URLStreamHandler createURLStreamHandler(String protocol) {
                        if ("https".equals(protocol) || "http".equals(protocol)) {
                            return new URLStreamHandler() {
                                @Override
                                protected URLConnection openConnection(URL u) throws IOException {
                                    throw new IOException("Simulated network error");
                                }
                            };
                        }
                        return null;
                    }
                });
                factorySet = true;
            } catch (Error e) {
                // Factory already set, ignore
            }
        }
    }

    @Test
    public void testGetNowPlayingError() {
        // Since the URLStreamHandlerFactory is set to always throw an IOException
        // upon opening a connection, fetchJson will fail and throw an Exception.
        // This will test the catch block in getNowPlaying.
        LastNotifSiteClient.NowPlayingResult result = LastNotifSiteClient.getNowPlaying("testuser");
        assertNull("Expected null result when exception is thrown in fetchJson", result);
    }
}
