package com.example.helloandroid.ui.main

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class SpriteFrameTest {
  @Test
  fun frames_tileTheSheetWithoutOverlap() {
    val w = 1774
    val h = 887
    assertEquals(IntOffset(0, 0) to IntSize(443, 443), spriteFrame(w, h, 0))
    assertEquals(IntOffset(887, 443) to IntSize(443, 444), spriteFrame(w, h, 6))
    assertEquals(IntOffset(1330, 443) to IntSize(444, 444), spriteFrame(w, h, 7))
    // Each frame starts exactly where the previous one in its row ends, and stays inside the sheet.
    for (row in 0..1) {
      var expectedX = 0
      for (col in 0..3) {
        val (offset, size) = spriteFrame(w, h, row * 4 + col)
        assertEquals(expectedX, offset.x)
        expectedX = offset.x + size.width
      }
      assertEquals(w, expectedX)
    }
    val (bottomOffset, bottomSize) = spriteFrame(w, h, 4)
    assertEquals(h, bottomOffset.y + bottomSize.height)
  }
}
