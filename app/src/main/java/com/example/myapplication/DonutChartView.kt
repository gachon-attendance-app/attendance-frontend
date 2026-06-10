package com.example.myapplication

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class DonutChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val attendanceColor = Color.parseColor("#0281F6") // 출석 파랑
    private val absentColor = Color.parseColor("#E53935")     // 결석 빨강
    private val lateColor = Color.parseColor("#9C27B0")       // 지각 보라

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }

    private var attendanceCount = 0
    private var lateCount = 0
    private var absentCount = 0

    fun setData(attendance: Int, late: Int, absent: Int) {
        attendanceCount = attendance
        lateCount = late
        absentCount = absent
        invalidate()
    }

    fun clearData() {
        attendanceCount = 0
        lateCount = 0
        absentCount = 0
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val total = attendanceCount + lateCount + absentCount
        if (total <= 0) return

        val size = min(width, height).toFloat()
        val strokeWidth = size * 0.22f
        val padding = strokeWidth / 2f + 2f

        paint.strokeWidth = strokeWidth

        val rect = RectF(
            padding,
            padding,
            width - padding,
            height - padding
        )

        var startAngle = -90f

        fun drawPart(count: Int, color: Int) {
            if (count <= 0) return

            val sweepAngle = 360f * count / total
            paint.color = color
            canvas.drawArc(rect, startAngle, sweepAngle, false, paint)
            startAngle += sweepAngle
        }

        drawPart(attendanceCount, attendanceColor)
        drawPart(lateCount, lateColor)
        drawPart(absentCount, absentColor)
    }
}