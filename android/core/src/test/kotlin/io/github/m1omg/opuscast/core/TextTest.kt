package io.github.m1omg.opuscast.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TextTest {
    @Test fun times() {
        assertEquals("1:02:03", fmtTime(3723.9))
        assertEquals("0:00:00", fmtTime(-5.0))
        assertEquals(3723, parseTime("1:02:03.500"))
        assertEquals(3723, parseTime("62:03"))
        assertNull(parseTime("NOT_IMPLEMENTED"))
        assertEquals("01:02:03", upnpTime(3723))
    }

    @Test fun xml() {
        assertEquals("a &amp; &lt;b&gt; &quot;c&quot; &#x27;", xmlEscape("a & <b> \"c\" '"))
        assertEquals("a & <b> é ü", xmlUnescape("a &amp; &lt;b&gt; &#233; &#xFC;"))
        assertEquals("PLAYING", xmlTag("<s:Body><CurrentTransportState>PLAYING</CurrentTransportState>", "CurrentTransportState"))
    }

    @Test fun ranges() {
        assertEquals(0L to 99L, parseRange("bytes=0-", 100))
        assertEquals(90L to 99L, parseRange("bytes=-10", 100))
        assertEquals(50L to 99L, parseRange("bytes=50-200", 100))
        assertNull(parseRange(null, 100))
        assertNull(parseRange("items=0-1", 100))
        for (bad in listOf("bytes=200-", "bytes=-0", "bytes=-", "bytes=x-1")) {
            assertFailsWith<IllegalArgumentException>(bad) { parseRange(bad, 100) }
        }
    }

    @Test fun lastChange() {
        val volume = "<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\"><e:property><LastChange>" +
            xmlEscape("<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/RCS/\"><InstanceID val=\"0\">" +
                "<Volume channel=\"Master\" val=\"17\"/><Volume channel=\"LF\" val=\"3\"/>" +
                "<X_EnabledCaptions val=\"&lt;captionFileInfo uri=&quot;http://1.2.3.4:8765/media/ab.srt&quot;/&gt;\"/>" +
                "</InstanceID></Event>") + "</LastChange></e:property></e:propertyset>"
        val c = parseLastChange(volume)!!
        assertEquals("17", c["Volume"])
        assertEquals(listOf("http://1.2.3.4:8765/media/ab.srt"), captionUris(c["X_EnabledCaptions"]))
        assertEquals(emptyMap(), parseLastChange("<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\"><e:property><LastChange></LastChange></e:property></e:propertyset>"))
        assertNull(parseLastChange("<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\"><e:property><Other>1</Other></e:property></e:propertyset>"))
        assertNull(parseLastChange("not xml"))
    }

    @Test fun ssdpReply() {
        assertEquals("http://192.168.1.5:9197/dmr",
            ssdpLocation("HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLocation: http://192.168.1.5:9197/dmr\r\nST: x\r\n\r\n"))
    }

    @Test fun urls() {
        assertEquals("http://x:9197/upnp/control/AVTransport1", resolveUrl("http://x:9197/dmr", "/upnp/control/AVTransport1"))
        assertEquals("http://x:9197/AVTransport/ctrl", resolveUrl("http://x:9197/dmr", "AVTransport/ctrl"))
        assertEquals("http://y/z", resolveUrl("http://x:9197/dmr", "http://y/z"))
    }

    @Test fun recent() {
        val r = RecentList(limit = 3)
        r.remember("a", "A.mkv", pos = 100.04, dur = 5000.0)
        assertEquals(100.0, r.resumeAt("a"))
        r.remember("a", sub = "track:3")
        assertEquals("track:3", r["a"]!!.sub)
        r.remember("a", pos = 4950.0, watched = true)
        assertEquals("track:3", r["a"]!!.sub)
        assertEquals(0.0, r.resumeAt("a"))
        r.remember("b", "B")
        r.remember("c", "C")
        val dropped = r.remember("d", "D")
        assertEquals(listOf("a"), dropped.map { it.ref })
        assertEquals(listOf("d", "c", "b"), r.items.map { it.ref })
        assert(RecentList.isWatched(4950.0, 5000.0))
        assert(!RecentList.isWatched(100.0, 5000.0))
    }
}
