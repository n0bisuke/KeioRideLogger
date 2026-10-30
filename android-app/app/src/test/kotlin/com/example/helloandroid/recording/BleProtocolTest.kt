package com.example.helloandroid.recording

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BleProtocolTest {
  private val body = ByteArray(100) { (it % 256).toByte() }

  @Test
  fun fragments_fitMtu_andCarryHeader() {
    val frags = BleProtocol.fragment(seq = 0x01020304, body = body, mtu = 23)
    // 23 - 3 ATT - 7 header = 13 bytes per fragment -> 8 fragments for 100 bytes.
    assertEquals(8, frags.size)
    assertTrue(frags.all { it.size <= 20 })
    val f = frags[2]
    assertEquals(BleProtocol.VERSION, f[0].toInt())
    assertEquals(listOf(0x04, 0x03, 0x02, 0x01), (1..4).map { f[it].toInt() and 0xFF })
    assertEquals(2, f[5].toInt())
    assertEquals(8, f[6].toInt())
  }

  @Test
  fun largeMtu_sendsOneFragment() {
    assertEquals(1, BleProtocol.fragment(1, body, mtu = 517).size)
  }

  @Test
  fun emptyBody_isOneFragment() {
    assertEquals(1, BleProtocol.fragment(1, ByteArray(0), mtu = 23).size)
  }

  @Test
  fun oversizedFrame_isRejected() {
    assertThrows(IllegalArgumentException::class.java) {
      BleProtocol.fragment(1, ByteArray(BleProtocol.MAX_FRAME_BYTES + 1), mtu = 517)
    }
  }

  @Test
  fun reassembly_inOrderAndOutOfOrder() {
    val r = BleReassembler()
    val frags = BleProtocol.fragment(5, body, mtu = 23)
    frags.dropLast(1).forEach { assertNull(r.accept(it)) }
    assertArrayEquals(body, r.accept(frags.last()))

    val next = BleProtocol.fragment(6, body, mtu = 23)
    next.reversed().dropLast(1).forEach { assertNull(r.accept(it)) }
    assertArrayEquals(body, r.accept(next.first()))
    assertEquals(6, r.lastCompleteSeq)
  }

  @Test
  fun duplicateAndOlderFrames_areIgnored() {
    val r = BleReassembler()
    val frags = BleProtocol.fragment(10, body, mtu = 517)
    assertArrayEquals(body, r.accept(frags[0]))
    assertNull("duplicate", r.accept(frags[0]))
    assertNull("older", r.accept(BleProtocol.fragment(9, body, 517)[0]))
  }

  @Test
  fun incompleteFrame_isDroppedWhenNewerStarts() {
    val r = BleReassembler()
    val partial = BleProtocol.fragment(1, body, mtu = 23)
    partial.take(3).forEach { r.accept(it) }
    val whole = BleProtocol.fragment(2, body, mtu = 23)
    var out: ByteArray? = null
    whole.forEach { out = r.accept(it) ?: out }
    assertArrayEquals(body, out)
    // Late fragments of the abandoned frame 1 never complete anything.
    partial.drop(3).forEach { assertNull(r.accept(it)) }
  }

  @Test
  fun wrongVersionOrBrokenHeader_isRejected() {
    val r = BleReassembler()
    val f = BleProtocol.fragment(1, body, 517)[0].clone()
    f[0] = 2
    assertNull(r.accept(f))
    assertNull(r.accept(ByteArray(3)))
    val badIndex = BleProtocol.fragment(1, body, 517)[0].clone().also { it[5] = 3; it[6] = 1 }
    assertNull(r.accept(badIndex))
  }

  @Test
  fun json_writesNullForMissing_andEscapes() {
    val json =
      JsonWriter()
        .num("a", null)
        .num("zero", 0.0)
        .num("nan", Double.NaN)
        .num("lon", 139.123456)
        .str("s", "x\"y\\z\n")
        .bool("b", null)
        .obj("o", null)
        .toString()
    assertEquals("""{"a":null,"zero":0,"nan":null,"lon":139.123456,"s":"x\"y\\z\u000a","b":null,"o":null}""", json)
  }

  @Test
  fun payload_keepsMissingValuesNull_notZero() {
    val body = BlePayload.build(RecorderUiState(recording = true), seq = 3, nowUtcMs = 1000).toString(Charsets.UTF_8)
    assertTrue(body.startsWith("""{"v":1,"seq":3,"t":1000,"recording":true"""))
    assertTrue(body.contains(""""gps":null"""))
    assertTrue(body.contains(""""cell":null"""))
    assertTrue(body.contains(""""probe":null"""))
    assertTrue(body.toByteArray().size <= BleProtocol.MAX_FRAME_BYTES)
  }

  @Test
  fun payload_includesGpsAndServingCell() {
    val serving =
      CellObservation("LTE", "1001", 12, 1, 1850, null, -95, true, 0, mapOf("lte_rsrq_db" to -11))
    val state =
      RecorderUiState(
        recording = true,
        gps = GpsReading(10.0, 20.0, 0f, null, 4.5f, 800),
        cell = CellReading(CellSnapshot(CellStatus.SERVING, serving = serving, servingSource = "request"), 300),
      )
    val body = BlePayload.build(state, 1, 0).toString(Charsets.UTF_8)
    assertTrue(body, body.contains(""""gps":{"lat":10,"lon":20,"kmh":0,"accM":4.5,"ageMs":800}"""))
    assertTrue(body, body.contains(""""rat":"LTE","id":"1001","pci":12,"arfcn":1850,"rsrp":-95,"rsrq":-11,"snr":null"""))
  }

  /** Golden vector shared with the browser test (web/csv-visualizer): keep both in sync. */
  @Test
  fun goldenVector_matchesBrowserDecoder() {
    val frag = BleProtocol.fragment(seq = 258, body = """{"v":1}""".toByteArray(), mtu = 23).single()
    val hex = frag.joinToString("") { "%02x".format(it) }
    assertEquals("01020100000001" + "7b2276223a317d", hex)
  }
}
