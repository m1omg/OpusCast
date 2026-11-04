package io.github.m1omg.opuscast.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SubtitleTest {
    private fun srt(b: ByteArray) = String(b, Charsets.UTF_8)

    @Test fun belongsToTheLongestMatchingVideo() {
        val folder = listOf("Movie.mp4", "Movie 2.mp4", "Movie 2.sk.srt", "Movie.en.srt", "notes.txt", "Other.srt")
        assertEquals(listOf("Movie.en.srt"), findSubtitleFiles("Movie.mp4", folder).map { it.name })
        assertEquals(listOf("Movie 2.sk.srt"), findSubtitleFiles("Movie 2.mp4", folder).map { it.name })
        assertEquals("en", findSubtitleFiles("Movie.mp4", folder)[0].lang)
    }

    @Test fun singleVideoTakesEverything() {
        val found = findSubtitleFiles("Film.mkv", listOf("Film.mkv", "Film.Slovak.srt", "cz.srt"),
            mapOf("Subs" to listOf("English.srt", "2_Czech.ass"), "Extras" to listOf("x.srt")))
        assertEquals(listOf(null to "Film.Slovak.srt", null to "cz.srt", "Subs" to "2_Czech.ass", "Subs" to "English.srt"),
            found.map { it.dir to it.name })
        assertEquals(listOf("sk", "cs", "cs", "en"), found.map { it.lang })
    }

    @Test fun subsFolderSkipsOtherEpisodes() {
        val found = findSubtitleFiles("Show.S01E01.mkv", listOf("Show.S01E01.mkv", "Show.S01E02.mkv"),
            mapOf("Subs" to listOf("Show.S01E01.en.srt", "Show.S01E02.en.srt", "English.srt")))
        assertEquals(listOf("English.srt", "Show.S01E01.en.srt"), found.map { it.name })
    }

    @Test fun vobsubIsPictureBased() {
        val found = findSubtitleFiles("A.mkv", listOf("A.mkv", "A.sub", "A.idx"))
        assertTrue(found.single().pictureBased)
    }

    @Test fun languageCodes() {
        assertEquals("sk", languageCode("slo"))
        assertEquals("sk", languageCode("slk"))
        assertEquals("cs", languageCode("cze"))
        assertEquals("de", languageCode("ger"))
        assertEquals("el", languageCode("gre"))
        assertEquals("sk", languageCode("sk-SK"))
        assertEquals("", languageCode("und"))
        assertEquals("pt", languageCode("por"))
    }

    @Test fun auto() {
        val e = listOf(SubtitleEntry("a", SubtitleEntry.Kind.FILE, lang = "en"),
            SubtitleEntry("b", SubtitleEntry.Kind.TRACK, lang = "sk", ok = false),
            SubtitleEntry("c", SubtitleEntry.Kind.TRACK, lang = "cs"))
        assertEquals("c", autoSubtitle(e, listOf("sk", "cs", "en"))!!.id)
        assertEquals("a", autoSubtitle(e, listOf("de"))!!.id)
    }

    @Test fun encodings() {
        val slovak = "čížňa ľ"
        assertEquals(slovak, decodeText(slovak.toByteArray(charset("windows-1250"))))
        assertEquals(slovak, decodeText(slovak.toByteArray(Charsets.UTF_8)))
        assertEquals(slovak, decodeText(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + slovak.toByteArray()))
        assertEquals(slovak, decodeText(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + slovak.toByteArray(Charsets.UTF_16LE)))
        assertEquals("Hello there", decodeText("Hello there".toByteArray(Charsets.UTF_16LE)))
        assertEquals("Привет", decodeText("Привет".toByteArray(charset("windows-1251")), "ru"))
    }

    @Test fun srtIsCleanedUp() {
        val raw = "1\r\n00:00:01.000 --> 00:00:02,500\r\nAhoj\r\n\r\n\r\n2\r\n00:00:03,000 --> 00:00:04,000\r\nDva\r\nriadky\r\n"
            .toByteArray(charset("windows-1250"))
        assertEquals("1\n00:00:01,000 --> 00:00:02,500\nAhoj\n\n2\n00:00:03,000 --> 00:00:04,000\nDva\nriadky\n\n",
            srt(convertSubtitle("a.srt", raw)))
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "1\n00:00:01,000 --> 00:00:02,000\nčau\n".toByteArray()
        assertEquals("1\n00:00:01,000 --> 00:00:02,000\nčau\n\n", srt(convertSubtitle("b.srt", bom)))
    }

    @Test fun vtt() {
        val vtt = "WEBVTT\n\nNOTE a comment\nmore\n\nSTYLE\n::cue { color: red }\n\nintro\n00:01.000 --> 00:02.000 align:start\n" +
            "<v Bob><i>Hi</i> &amp; <c.loud>bye</c></v>\n\n01:00:00.250 --> 01:00:01.000\n<00:00:00.500>Late\n"
        assertEquals("1\n00:00:01,000 --> 00:00:02,000\n<i>Hi</i> & bye\n\n2\n01:00:00,250 --> 01:00:01,000\nLate\n\n",
            srt(convertSubtitle("a.vtt", vtt.toByteArray())))
    }

    @Test fun ass() {
        val ass = "[Script Info]\nTitle: x\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
            "Comment: 0,0:00:00.00,0:00:09.00,Default,,0,0,0,,ignored\n" +
            "Dialogue: 0,0:00:02.00,0:00:03.00,Default,,0,0,0,,{\\pos(1,2)}Second, with comma\n" +
            "Dialogue: 0,0:00:00.50,0:00:01.50,Default,,0,0,0,,{\\i1}Hello{\\i0}\\NWorld\\hnow\n" +
            "Dialogue: 0,0:00:04.00,0:00:05.00,Default,,0,0,0,,{\\p1}m 0 0 l 10 10{\\p0}\n"
        assertEquals("1\n00:00:00,500 --> 00:00:01,500\n<i>Hello</i>\nWorld now\n\n2\n00:00:02,000 --> 00:00:03,000\nSecond, with comma\n\n",
            srt(convertSubtitle("a.ass", ass.toByteArray())))
    }

    @Test fun sami() {
        val smi = "<SAMI><BODY>\n<SYNC Start=1000><P Class=SKCC>Prvý<br>riadok&nbsp;\n<SYNC Start=2500><P Class=SKCC>&nbsp;\n" +
            "<SYNC Start=3000><P Class=SKCC><i>Druhý</i>\n</BODY></SAMI>"
        assertEquals("1\n00:00:01,000 --> 00:00:02,500\nPrvý\nriadok\n\n2\n00:00:03,000 --> 00:00:07,000\n<i>Druhý</i>\n\n",
            srt(convertSubtitle("a.smi", smi.toByteArray())))
    }

    @Test fun microDvd() {
        val sub = "{1}{1}25.000\n{25}{50}Ahoj|svet\n{75}{100}{y:i}Kurzíva\n"
        assertEquals("1\n00:00:01,000 --> 00:00:02,000\nAhoj\nsvet\n\n2\n00:00:03,000 --> 00:00:04,000\n<i>Kurzíva</i>\n\n",
            srt(convertSubtitle("a.sub", sub.toByteArray())))
    }

    @Test fun subViewer() {
        val sub = "[INFORMATION]\n[TITLE]x\n[END INFORMATION]\n\n00:00:01.00,00:00:02.50\nRiadok[br]druhý\n\n00:00:03.00,00:00:04.00\nTri\n"
        assertEquals("1\n00:00:01,000 --> 00:00:02,500\nRiadok\ndruhý\n\n2\n00:00:03,000 --> 00:00:04,000\nTri\n\n",
            srt(convertSubtitle("a.sub", sub.toByteArray())))
    }

    @Test fun garbageIsRefused() {
        assertFailsWith<TvException> { convertSubtitle("a.ass", "just some words".toByteArray()) }
    }
}
