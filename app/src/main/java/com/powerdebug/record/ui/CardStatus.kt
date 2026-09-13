package com.powerdebug.record.ui

import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.powerdebug.record.R

/**
 * 项目/柜子列表行的三态状态色：
 *  - 红 = 存在未消除故障（最优先）——阻断性问题，优先整个项目的排障
 *  - 黄 = 未测试完成（存在未测/未通过项）
 *  - 绿 = 测试完成（全部启用测试项已通过且无未消除故障）
 * 卡片用「背景色 + 描边」表达主状态；副标题里的数字再按细粒度着色
 * （待测/未通过黄、待处理故障红）补足另一维度，避免"半黄半红"的割裂观感。
 */
object CardStatus {

    const val STATE_PENDING = 0
    const val STATE_FAULT = 1
    const val STATE_DONE = 2

    fun state(totalTests: Int, pendingTests: Int, failedTests: Int, pendingFaults: Int): Int = when {
        pendingFaults > 0 -> STATE_FAULT
        totalTests == 0 || pendingTests > 0 || failedTests > 0 -> STATE_PENDING
        else -> STATE_DONE
    }

    fun bgRes(state: Int): Int = when (state) {
        STATE_FAULT -> R.color.pending_bg
        STATE_PENDING -> R.color.warn_bg
        else -> R.color.resolved_bg
    }

    fun fgRes(state: Int): Int = when (state) {
        STATE_FAULT -> R.color.pending_fg
        STATE_PENDING -> R.color.warn_fg
        else -> R.color.resolved_fg
    }

    fun apply(card: MaterialCardView, state: Int) {
        val ctx = card.context
        card.setCardBackgroundColor(ContextCompat.getColor(ctx, bgRes(state)))
        card.setStrokeColor(ColorStateList.valueOf(ContextCompat.getColor(ctx, fgRes(state))))
        card.strokeWidth = (1.5f * ctx.resources.displayMetrics.density).toInt()
    }
}