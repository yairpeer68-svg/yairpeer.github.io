package com.magen.family.filter;

import org.junit.Test;
import static org.junit.Assert.*;

public class YouTubeEssentialHostsTest {
    @Test public void essentialYouTubeInfrastructureIsAllowed() {
        assertTrue(YouTubeEssentialHosts.isEssential("s.youtube.com"));
        assertTrue(YouTubeEssentialHosts.isEssential("i.ytimg.com"));
        assertTrue(YouTubeEssentialHosts.isEssential("r3---sn.example.googlevideo.com"));
        assertTrue(YouTubeEssentialHosts.isEssential("yt3.ggpht.com"));
        assertTrue(YouTubeEssentialHosts.isEssential("youtubei.googleapis.com"));
    }

    @Test public void unrelatedHostsAreNotAllowlisted() {
        assertFalse(YouTubeEssentialHosts.isEssential("youtube.example.com"));
        assertFalse(YouTubeEssentialHosts.isEssential("evilgooglevideo.com"));
        assertFalse(YouTubeEssentialHosts.isEssential("example.com"));
    }
}
