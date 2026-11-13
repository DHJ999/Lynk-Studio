package com.duan.lynkcluster

import kotlin.math.roundToInt

/**
 * 零分配数字格式化缓冲。
 *
 * `onDraw` 每帧要画 6~8 个数字，`Int.toString()` / `String.format("%.1f")`
 * 每次都会新建 String —— 60 fps 下就是每秒几百个短命对象，24/7 常驻后
 * 会退化成「跑几小时后偶发卡一下」，而且极难复现。
 *
 * 这里把数字直接写进 CharArray，再用
 * `Canvas.drawText(chars, start, count, x, y, paint)` 画出去，全程不分配。
 *
 * 数字右对齐写在数组末尾：`chars` 前段可能留有上一次的脏字符，
 * 所以绘制时必须用 [start] / [length] 限定区间，不能整块丢给 Canvas。
 */
internal class NumberBuffer(private val capacity: Int = 24) {

    val chars = CharArray(capacity)

    /** 有效区间起点。 */
    var start = 0
        private set

    /** 有效字符数。 */
    var length = 0
        private set

    /** 写一个整数（可负）。 */
    fun setInt(value: Int): NumberBuffer {
        // Int.MIN_VALUE 取负会溢出成自己，单独走字面量。
        if (value == Int.MIN_VALUE) return setLiteral(MIN_INT)

        var v = value
        val negative = v < 0
        if (negative) v = -v

        var i = capacity
        do {
            chars[--i] = DIGITS[v % 10]
            v /= 10
        } while (v != 0)
        if (negative) chars[--i] = '-'

        start = i
        length = capacity - i
        return this
    }

    /** 保留一位小数（四舍五入）：胎压 2.4、行程 12.6、转速 0.9。 */
    fun setOneDecimal(value: Float): NumberBuffer {
        val scaled = (value * 10f).roundToInt()
        val negative = scaled < 0
        var v = if (negative) -scaled else scaled

        var i = capacity
        chars[--i] = DIGITS[v % 10]
        v /= 10
        chars[--i] = '.'
        do {
            chars[--i] = DIGITS[v % 10]
            v /= 10
        } while (v != 0)
        if (negative) chars[--i] = '-'

        start = i
        length = capacity - i
        return this
    }

    /** 写一段现成文本（`--`、挡位标签等）。 */
    fun setLiteral(text: String): NumberBuffer {
        val n = if (text.length > capacity) capacity else text.length
        text.toCharArray(chars, 0, 0, n)
        start = 0
        length = n
        return this
    }

    private companion object {
        val DIGITS = "0123456789".toCharArray()
        const val MIN_INT = "-2147483648"
    }
}
