package io.github.m1omg.opuscast.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FormatsTest {
    @Test fun kindsAndMimeTypes() {
        assertEquals(MediaKind.VIDEO, kindOf("Film.MKV"))
        assertEquals(MediaKind.AUDIO, kindOf("01 - Song.flac"))
        assertEquals(MediaKind.AUDIO, kindOf("talk.opus"))
        assertEquals(MediaKind.IMAGE, kindOf("IMG_0001.HEIC"))
        assertEquals(MediaKind.IMAGE, kindOf("document", "image/webp"))
        assertEquals(MediaKind.AUDIO, kindOf("msf:1234", "audio/mpeg; charset=binary"))
        assertEquals(MediaKind.VIDEO, kindOf("clip", "application/x-matroska"))
        assertNull(kindOf("notes.txt", "text/plain"))
        assertEquals("audio/ogg", mimeFor("a.opus"))
        assertEquals("image/heic", mimeFor("b.heif"))
        assertEquals("audio/mpeg", mimeFor("track", "audio/mpeg"))
        assertEquals("video/mp4", mimeFor("whatever"))
        assertEquals(".mp3", urlExtension("track", "audio/mpeg"))
        assertEquals(".jpg", urlExtension("photo", "image/jpeg"))
        assertEquals(".flac", urlExtension("Song.FLAC", "audio/x-flac"))
    }

    @Test fun usesTheTvsSpelling() {
        val lg = SinkFormats.parse("http-get:*:video/x-mkv:*, http-get:*:video/MP2T:DLNA.ORG_PN=AVC_TS_HD_EU_ISO," +
            "http-get:*:audio/x-m4a:*,http-get:*:audio/vnd.dlna.adts:*,http-get:*:image/jpg:*,http-get:*:audio/x-wav:*")
        assertTrue(lg.known)
        assertEquals("video/x-mkv", lg.spell("video/x-mkv"))
        assertEquals("video/x-mkv", lg.spell("video/x-matroska"))
        assertEquals("video/MP2T", lg.spell("video/vnd.dlna.mpeg-tts"))
        assertEquals("audio/x-m4a", lg.spell("audio/mp4"))
        assertEquals("audio/vnd.dlna.adts", lg.spell("audio/aac")) // ADTS AAC is not an M4A file
        assertEquals("image/jpg", lg.spell("image/jpeg"))
        assertEquals("audio/x-wav", lg.spell("audio/wav"))
        assertEquals("video/webm", lg.spell("video/webm")) // not listed: ours
        assertEquals(false, lg.accepts("video/webm"))
        assertEquals(false, lg.accepts("image/png"))
        assertEquals(true, lg.accepts("audio/wav"))

        // transport streams go by a fixed order, among what the TV lists
        val ts = SinkFormats.parse("http-get:*:video/vnd.dlna.mpeg-tts:*,http-get:*:video/mpeg:*")
        assertEquals("video/mpeg", ts.spell("video/vnd.dlna.mpeg-tts"))

        // L16 is raw PCM: a WAV file labelled that way would play as noise
        val pcm = SinkFormats.parse("http-get:*:audio/L16;rate=48000;channels=2:DLNA.ORG_PN=LPCM")
        assertEquals(false, pcm.accepts("audio/wav"))
        assertEquals("audio/wav", pcm.spell("audio/wav"))
        assertEquals(false, pcm.playsVideo)
        assertEquals(true, pcm.playsAudio)
    }

    @Test fun unknownAndAnything() {
        for (s in listOf(SinkFormats.UNKNOWN, SinkFormats.parse(""), SinkFormats.parse("garbage"))) {
            assertFalse(s.known)
            assertNull(s.accepts("video/mp4"))
            assertNull(s.playsVideo)
            assertEquals("video/x-mkv", s.spell("video/x-mkv"))
        }
        for (any in listOf("http-get:*:*:*", "*", "http-get:*:*/*:*")) {
            val s = SinkFormats.parse(any)
            assertTrue(s.known)
            assertEquals(true, s.accepts("image/heic"))
            assertEquals(true, s.playsVideo)
            assertEquals("video/x-mkv", s.spell("video/x-mkv"))
        }
    }

    @Test fun dlnaFeatures() {
        assertEquals(DLNA_FEATURES, Dlna.AV)
        assertEquals("DLNA.ORG_PN=MP3;$DLNA_FEATURES", Dlna.audio("audio/mpeg"))
        assertEquals(DLNA_FEATURES, Dlna.audio("audio/flac"))
        assertEquals("DLNA.ORG_PN=JPEG_SM;DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=00f00000000000000000000000000000",
            Dlna.image("image/jpeg", 640, 480, false))
        assertEquals("JPEG_MED", Dlna.jpegProfile(1024, 768))
        assertEquals("JPEG_MED", Dlna.jpegProfile(480, 640))
        assertEquals("JPEG_LRG", Dlna.jpegProfile(4000, 3000))
        assertEquals("JPEG_LRG", Dlna.jpegProfile(0, 0))
        assertTrue(Dlna.image("image/png", 100, 100, true).startsWith("DLNA.ORG_PN=PNG_LRG;DLNA.ORG_OP=00;DLNA.ORG_CI=1;"))
        assertTrue(Dlna.image("image/gif", 100, 100, false).startsWith("DLNA.ORG_OP=00;"))
        assertEquals("DLNA.ORG_OP=00;DLNA.ORG_CI=1;DLNA.ORG_FLAGS=01700000000000000000000000000000", Dlna.LIVE)
        assertEquals("0:03:25.000", didlDuration(205_000))
        assertEquals("1:02:03.450", didlDuration(3_723_450))
    }

    @Test fun didlPerKind() {
        val video = didl("http://x/v.mkv", ItemInfo("Film <1>", "video/x-mkv", size = 10, subUrl = "http://x/s.srt"))
        assertTrue("<upnp:class>object.item.videoItem</upnp:class>" in video)
        assertTrue("<dc:title>Film &lt;1&gt;</dc:title>" in video)
        assertTrue("pv:subtitleFileUri=\"http://x/s.srt\"" in video)
        assertTrue("<sec:CaptionInfoEx sec:type=\"srt\">http://x/s.srt</sec:CaptionInfoEx>" in video)
        // a subtitle never goes with music
        val song = didl("http://x/a.mp3", ItemInfo("Song", "audio/mpeg", Dlna.audio("audio/mpeg"), 5, MediaKind.AUDIO,
            subUrl = "http://x/s.srt", artist = " ", coverUrl = "http://x/c.png"))
        assertTrue("CaptionInfo" !in song && "pv:subtitle" !in song)
        assertTrue("upnp:artist" !in song) // blank
        assertTrue("<upnp:albumArtURI>http://x/c.png</upnp:albumArtURI>" in song, song) // no JPEG_TN for a PNG
        assertTrue("xmlns:dlna=\"urn:schemas-dlna-org:metadata-1-0/\"" in song)
    }
}
