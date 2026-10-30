package com.example.helloandroid.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServingCellTrackerTest {
  private fun cell(
    rat: String = "LTE",
    id: String? = "1001",
    pci: Int? = 10,
    arfcn: Int? = 1850,
    registered: Boolean = true,
  ) = CellObservation(rat, id, pci, tac = 1, arfcn = arfcn, bands = null, rsrpDbm = -90,
    registered = registered, elapsedNs = 0)

  @Test
  fun firstRegisteredCell_isBaseline_notCounted() {
    val tracker = ServingCellTracker()
    assertTrue(tracker.observe(cell(), 0) is ServingCellTracker.Result.Baseline)
    assertEquals(0, tracker.switchCount)
    assertTrue(tracker.history.isEmpty())
  }

  @Test
  fun sameCellRepeated_isNotCounted() {
    val tracker = ServingCellTracker()
    tracker.observe(cell(), 0)
    repeat(5) { assertEquals(ServingCellTracker.Result.Same, tracker.observe(cell(), it + 1L)) }
    assertEquals(0, tracker.switchCount)
  }

  @Test
  fun registeredCellChange_isCountedWithHistory() {
    val tracker = ServingCellTracker()
    tracker.observe(cell(), 0)
    val result = tracker.observe(cell(id = "1002", pci = 11), 1_000)
    assertTrue(result is ServingCellTracker.Result.Switched)
    assertEquals(1, tracker.switchCount)
    val change = tracker.history.single()
    assertEquals(1_000, change.timeUtcMs)
    assertEquals("1001", change.from.cellId)
    assertEquals("1002", change.to.cellId)
  }

  @Test
  fun neighbourCells_neverCountOrBecomeBaseline() {
    val tracker = ServingCellTracker()
    assertEquals(ServingCellTracker.Result.NotRegistered, tracker.observe(cell(registered = false), 0))
    assertNull(tracker.current)
    tracker.observe(cell(), 1)
    assertEquals(
      ServingCellTracker.Result.NotRegistered,
      tracker.observe(cell(id = "9999", pci = 99, registered = false), 2),
    )
    assertEquals(0, tracker.switchCount)
  }

  @Test
  fun missingIdentifiers_areIndeterminate_notASwitch() {
    val tracker = ServingCellTracker()
    tracker.observe(cell(), 0)
    val result = tracker.observe(cell(id = null, pci = null, arfcn = null), 1)
    assertTrue(result is ServingCellTracker.Result.Indeterminate)
    assertEquals(0, tracker.switchCount)
    assertEquals("1001", tracker.current?.cellId)
  }

  @Test
  fun partiallyMissingField_comparesOnlyWhatBothReport() {
    // Cell ID missing in the second sample but PCI and ARFCN match: same cell.
    assertEquals(true, sameServingCell(cell(), cell(id = null)))
    // PCI differs: different cell even without cell IDs.
    assertEquals(false, sameServingCell(cell(id = null), cell(id = null, pci = 11)))
  }

  @Test
  fun ratChange_isASwitch() {
    assertFalse(sameServingCell(cell(rat = "LTE"), cell(rat = "NR"))!!)
  }

  @Test
  fun history_isBounded() {
    val tracker = ServingCellTracker(historyLimit = 3)
    tracker.observe(cell(pci = 0), 0)
    for (i in 1..10) tracker.observe(cell(pci = i), i.toLong())
    assertEquals(10, tracker.switchCount)
    assertEquals(listOf(8, 9, 10), tracker.history.map { it.to.pci })
  }

  @Test
  fun reset_clearsBaselineAndCount() {
    val tracker = ServingCellTracker()
    tracker.observe(cell(pci = 1), 0)
    tracker.observe(cell(pci = 2), 1)
    tracker.reset()
    assertNull(tracker.current)
    assertEquals(0, tracker.switchCount)
    assertTrue(tracker.observe(cell(pci = 3), 2) is ServingCellTracker.Result.Baseline)
  }
}
