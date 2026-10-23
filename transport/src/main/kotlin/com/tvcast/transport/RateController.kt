package com.tvcast.transport

/**
 * 码率档位控制器：按 NACK（丢包）频率在档位间升降。
 * - 每秒调用 [onSecondElapsed]（传入该秒新增的 nack 条数）
 * - 2 秒平均 nack 超过 [downgradeNacksPerSec] → 降一档；连续 [upgradeCleanSecs] 秒干净 → 升一档
 * - 升降都有限速（每次调整后至少间隔 [cooldownSecs] 秒），防止振荡
 */
class RateController(
  val tiersMbps: IntArray = intArrayOf(8, 4, 2),
  private val downgradeNacksPerSec: Int = 20,
  private val upgradeCleanSecs: Int = 8,
  private val cooldownSecs: Int = 3,
) {

  var tier: Int = 0 // 档位下标，0 = 最高码率
    private set

  val bitrateMbps: Int get() = tiersMbps[tier]

  private val recentNacks = ArrayDeque<Int>()
  private var cleanSeconds = 0
  private var cooldown = 0

  fun onSecondElapsed(nacksThisSecond: Int) {
    recentNacks.addLast(nacksThisSecond)
    if (recentNacks.size > 2) recentNacks.removeFirst()
    val avg = recentNacks.average()
    if (cooldown > 0) {
      cooldown--
      cleanSeconds = if (nacksThisSecond < downgradeNacksPerSec / 2) cleanSeconds + 1 else 0
      return
    }
    if (avg > downgradeNacksPerSec && tier < tiersMbps.size - 1) {
      tier++
      cooldown = cooldownSecs
      cleanSeconds = 0
      return
    }
    if (avg < downgradeNacksPerSec / 4.0) {
      cleanSeconds++
      if (cleanSeconds >= upgradeCleanSecs && tier > 0) {
        tier--
        cooldown = cooldownSecs
        cleanSeconds = 0
      }
    } else {
      cleanSeconds = 0
    }
  }
}
