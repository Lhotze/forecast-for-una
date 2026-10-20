package dev.forecastsync.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FindRuleTest {

    @Test fun parsesTheWatchFile() {
        assertEquals(1792447276L, FindRule.parse("ts=1792447276\n"))
        assertEquals(1792447276L, FindRule.parse("ts=1792447276"))
    }

    @Test fun rejectsIncompleteOrForeignText() {
        assertNull(FindRule.parse(""))
        assertNull(FindRule.parse("ts="))
        assertNull(FindRule.parse("ts=0"))
        assertNull(FindRule.parse("hello"))
        assertNull(FindRule.parse("{\"ts\":1792447276}"))
    }

    @Test fun recentRequestRings() {
        val now = 1_800_000_000L
        assertEquals(FindRule.Verdict.RING, FindRule.decide(now - 5, now, 0, 30))
        assertEquals(FindRule.Verdict.RING, FindRule.decide(now - 600, now, 0, 0))
    }

    @Test fun limitGrowsWithTheInterval() {
        val now = 1_800_000_000L
        // 12 minutes old: too old for a 30 s check, still fine when the check only runs every 10 minutes
        assertEquals(FindRule.Verdict.STALE, FindRule.decide(now - 720, now, 0, 30))
        assertEquals(FindRule.Verdict.RING, FindRule.decide(now - 720, now, 0, 600))
        assertEquals(FindRule.Verdict.STALE, FindRule.decide(now - 1300, now, 0, 600))
    }

    @Test fun watchClockAheadCountsAsNew() {
        val now = 1_800_000_000L
        assertEquals(FindRule.Verdict.RING, FindRule.decide(now + 90, now, 0, 30))
    }

    @Test fun sameRequestNeverRingsTwice() {
        val now = 1_800_000_000L
        assertEquals(FindRule.Verdict.ALREADY_HANDLED, FindRule.decide(now - 5, now, now - 5, 30))
        assertEquals(FindRule.Verdict.ALREADY_HANDLED, FindRule.decide(now - 5000, now, now - 5000, 30))
    }

    @Test fun pathsAreRestrictedToTheOneRequestFile() {
        assertEquals(true, FtsClient.isAllowedRingPath("/Apps/B8E0979895A97AFD/ring.txt"))
        assertEquals(false, FtsClient.isAllowedRingPath("/Apps/B8E0979895A97AFD/weather.json"))
        assertEquals(false, FtsClient.isAllowedRingPath("/Apps/B8E0979895A97AFD/../ring.txt"))
        assertEquals(false, FtsClient.isAllowedRingPath("/ring.txt"))
        assertEquals(false, FtsClient.isAllowedRingPath("/Apps/B8E0979895A97AFD/sub/ring.txt"))
        assertEquals(true, FtsClient.isAllowedAppDir("/Apps/B8E0979895A97AFD"))
        assertEquals(false, FtsClient.isAllowedAppDir("/Apps"))
        assertEquals(false, FtsClient.isAllowedAppDir("/Apps/B8E0979895A97AFD/"))
        assertEquals(false, FtsClient.isAllowedAppDir("/"))
        // the writer is still limited to the two data files
        assertEquals(false, FtsClient.isAllowedPath("/Apps/B8E0979895A97AFD/ring.txt"))
    }
}

/** The byte layouts of the FTS messages, checked against the tables in una-sdk/Docs/BLE-File-Transfer-Service.md. */
class FtsWireTest {
    private fun le(n: Int) = java.nio.ByteBuffer.allocate(n).order(java.nio.ByteOrder.LITTLE_ENDIAN)

    @Test fun listAndDeleteRequests() {
        val path = "/Apps/B8E0979895A97AFD"
        val m = FtsClient.Wire.pathRequest(FtsClient.CMD_LISTDIR, path)
        assertEquals(0x50, m[0].toInt())
        assertEquals(0, m[1].toInt())
        assertEquals(path.length, (m[2].toInt() and 0xFF) or ((m[3].toInt() and 0xFF) shl 8))
        assertEquals(path, String(m, 4, m.size - 4, Charsets.UTF_8))
        assertEquals(0x30, FtsClient.Wire.pathRequest(FtsClient.CMD_DELETE, path)[0].toInt())
    }

    @Test fun readRequestAndPacing() {
        val path = "/Apps/B8E0979895A97AFD/ring.txt"
        val m = FtsClient.Wire.readRequest(path, 14)
        val bb = java.nio.ByteBuffer.wrap(m).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x10, m[0].toInt())
        assertEquals(path.length, bb.getShort(2).toInt())
        assertEquals(0, bb.getInt(4))          // chunkOffset
        assertEquals(14, bb.getInt(8))         // chunkSize
        assertEquals(path, String(m, 12, m.size - 12, Charsets.UTF_8))

        val pace = java.nio.ByteBuffer.wrap(FtsClient.Wire.readPacing(5, 9)).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x12, pace.get(0).toInt())
        assertEquals(1, pace.get(1).toInt())
        assertEquals(5, pace.getInt(4))
        assertEquals(9, pace.getInt(8))
    }

    private fun listEntry(number: Int, total: Int, name: String, dir: Boolean, size: Int, mtime: Long): ByteArray {
        val n = name.toByteArray()
        return le(28 + n.size).put(0x51).put(1).putShort(n.size.toShort()).putInt(number).putInt(total)
            .putInt(if (dir) 1 else 0).putLong(mtime).putInt(size).put(n).array()
    }

    @Test fun listReplies() {
        val e = FtsClient.Wire.parseListReply(listEntry(0, 2, "ring.txt", false, 14, 1_792_447_276_000_000_000L))
        assertEquals(false, e.terminal)
        assertEquals("ring.txt", e.entry!!.name)
        assertEquals(false, e.entry!!.isDir)
        assertEquals(14, e.entry!!.size)
        assertEquals(1_792_447_276_000_000_000L, e.entry!!.mtimeNs)
        assertEquals(true, FtsClient.Wire.parseListReply(listEntry(1, 2, "sub", true, 0, 0)).entry!!.isDir)
        // the terminating entry: number == total, no name
        assertEquals(true, FtsClient.Wire.parseListReply(listEntry(2, 2, "", false, 0, 0)).terminal)
        // an empty folder answers with the terminating entry right away
        assertEquals(true, FtsClient.Wire.parseListReply(listEntry(0, 0, "", false, 0, 0)).terminal)
        // an error status is passed on
        val err = listEntry(0, 0, "", false, 0, 0).also { it[1] = 3 }
        assertEquals(3, FtsClient.Wire.parseListReply(err).status)
    }

    @Test fun readReplies() {
        val body = "ts=1792447276\n".toByteArray()
        val m = le(16 + body.size).put(0x11).put(1).putShort(0).putInt(0).putInt(body.size).putInt(body.size).put(body).array()
        val r = FtsClient.Wire.parseReadReply(m)
        assertEquals(1, r.status)
        assertEquals(0, r.offset)
        assertEquals(body.size, r.total)
        assertEquals("ts=1792447276\n", String(r.data))
        // a chunkLength larger than what really arrived must not be trusted
        val liar = le(16 + 3).put(0x11).put(1).putShort(0).putInt(0).putInt(50).putInt(50).put("abc".toByteArray()).array()
        assertEquals("abc", String(FtsClient.Wire.parseReadReply(liar).data))
    }
}

class FindIntervalsTest {
    @Test fun intervalsAreOffThenAscending() {
        assertEquals(listOf(0, 30, 60, 120, 180, 240, 300, 600), FindRule.INTERVALS.map { it.first })
        assertEquals("3 minutes", FindRule.intervalLabel(180))
        assertEquals("Off", FindRule.intervalLabel(7))     // an unknown stored value shows as Off
    }
}
