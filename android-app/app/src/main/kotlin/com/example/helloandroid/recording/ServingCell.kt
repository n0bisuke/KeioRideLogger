package com.example.helloandroid.recording

/** One observed cell. Null fields were not reported by the modem for this sample. */
data class CellObservation(
  val rat: String,
  val cellId: String?,
  val pci: Int?,
  val tac: Int?,
  val arfcn: Int?,
  val bands: String?,
  val rsrpDbm: Int?,
  val registered: Boolean,
  val elapsedNs: Long,
  /** RAT-specific quality metrics keyed by CSV column (e.g. lte_rsrq_db, nr_ss_sinr_db). */
  val metrics: Map<String, Int> = emptyMap(),
) {
  /** Short human-readable identity without the cell ID, for history lines. */
  val label: String
    get() = "$rat PCI ${pci ?: "?"} ARFCN ${arfcn ?: "?"}"
}

/**
 * Whether two serving-cell samples are the same cell. Compares RAT and every identity field
 * (cell ID, PCI, ARFCN) that both samples report. Returns null when nothing is comparable, so a
 * sample missing its identifiers is never counted as a switch.
 */
fun sameServingCell(a: CellObservation, b: CellObservation): Boolean? {
  if (a.rat != b.rat) return false
  val pairs = listOf(a.cellId to b.cellId, a.pci?.toString() to b.pci?.toString(), a.arfcn?.toString() to b.arfcn?.toString())
  val comparable = pairs.filter { (x, y) -> x != null && y != null }
  if (comparable.isEmpty()) return null
  return comparable.all { (x, y) -> x == y }
}

data class CellSwitch(val timeUtcMs: Long, val from: CellObservation, val to: CellObservation)

/**
 * Tracks the registered (serving) cell and counts observed switches. The first registered cell is
 * the baseline and is not a switch; repeats of the same cell and unregistered (neighbour) cells
 * never count. API update limits mean switches can be missed, so this is not a complete handover
 * log.
 */
class ServingCellTracker(private val historyLimit: Int = 20) {
  sealed interface Result {
    data class Baseline(val cell: CellObservation) : Result

    data object Same : Result

    data class Switched(val change: CellSwitch) : Result

    /** Same RAT but no identity field to compare; not counted and the baseline is kept. */
    data class Indeterminate(val cell: CellObservation) : Result

    data object NotRegistered : Result
  }

  var current: CellObservation? = null
    private set

  var switchCount = 0
    private set

  private val _history = ArrayDeque<CellSwitch>()
  val history: List<CellSwitch>
    get() = _history.toList()

  fun reset() {
    current = null
    switchCount = 0
    _history.clear()
  }

  fun observe(cell: CellObservation, timeUtcMs: Long): Result {
    if (!cell.registered) return Result.NotRegistered
    val previous = current ?: return Result.Baseline(cell).also { current = cell }
    return when (sameServingCell(previous, cell)) {
      true -> {
        current = cell // keep the freshest signal values
        Result.Same
      }
      null -> Result.Indeterminate(cell)
      false -> {
        val change = CellSwitch(timeUtcMs, previous, cell)
        current = cell
        switchCount++
        _history.addLast(change)
        while (_history.size > historyLimit) _history.removeFirst()
        Result.Switched(change)
      }
    }
  }
}
