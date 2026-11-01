// Ported to Kotlin from mg-ismart-india-client by John Lazarus (MIT licence):
// https://github.com/john-lazarus/mg-ismart-india-client. Thank you, John: this module would not exist without
// your work on the MG India protocol. The original licence is reproduced in NOTICE.md.

package dev.irshadm.volta.core.ismart

/**
 * Minimal ASN.1 PER-unaligned (X.691) writer/reader — just the constructs the MG India TAP
 * schemas use: constrained INTEGER, BOOLEAN, ENUMERATED, fixed/ranged IA5String and
 * NumericString, OCTET STRING and SEQUENCE OF with size constraints. No extension markers.
 * Constrained integers always use the minimum number of bits (unaligned variant).
 */
internal fun bitsFor(lb: Long, ub: Long): Int {
    val range = ub - lb + 1
    require(range >= 1)
    return if (range == 1L) 0 else 64 - java.lang.Long.numberOfLeadingZeros(range - 1)
}

class BitWriter {
    private val bits = ArrayList<Boolean>(512)

    fun bit(b: Boolean) = apply { bits += b }

    fun bits(value: Long, count: Int) = apply {
        require(count == 64 || (value ushr count) == 0L) { "value $value does not fit in $count bits" }
        for (i in count - 1 downTo 0) bits += ((value ushr i) and 1L) == 1L
    }

    fun int(value: Long, lb: Long, ub: Long) = apply {
        require(value in lb..ub) { "INTEGER $value outside $lb..$ub" }
        bits(value - lb, bitsFor(lb, ub))
    }

    fun ia5Fixed(s: String, size: Int) = apply {
        require(s.length == size) { "IA5String must be $size chars, was ${s.length}" }
        s.forEach { bits(it.code.toLong(), 7) }
    }

    fun ia5(s: String, minLen: Int, maxLen: Int) = apply {
        int(s.length.toLong(), minLen.toLong(), maxLen.toLong())
        s.forEach { bits(it.code.toLong(), 7) }
    }

    fun numericFixed(s: String, size: Int) = apply {
        require(s.length == size)
        s.forEach { c -> bits(if (c == ' ') 0 else (c - '0' + 1).toLong(), 4) }
    }

    fun octetsFixed(b: ByteArray) = apply { b.forEach { bits((it.toInt() and 0xff).toLong(), 8) } }

    fun octets(b: ByteArray, minLen: Int, maxLen: Int) = apply {
        int(b.size.toLong(), minLen.toLong(), maxLen.toLong())
        octetsFixed(b)
    }

    fun toByteArray(): ByteArray {
        val out = ByteArray((bits.size + 7) / 8)
        bits.forEachIndexed { i, b -> if (b) out[i / 8] = (out[i / 8].toInt() or (0x80 ushr (i % 8))).toByte() }
        return out
    }
}

class BitReader(private val data: ByteArray, private var pos: Int = 0) {
    fun bit(): Boolean {
        if (pos >= data.size * 8) throw TapFormatException("truncated PER data")
        val b = (data[pos / 8].toInt() shr (7 - pos % 8)) and 1
        pos++
        return b == 1
    }

    fun bits(count: Int): Long {
        var v = 0L
        repeat(count) { v = (v shl 1) or (if (bit()) 1L else 0L) }
        return v
    }

    fun int(lb: Long, ub: Long): Long = lb + bits(bitsFor(lb, ub))

    fun ia5Fixed(size: Int): String = buildString { repeat(size) { append(bits(7).toInt().toChar()) } }

    fun ia5(minLen: Int, maxLen: Int): String = ia5Fixed(int(minLen.toLong(), maxLen.toLong()).toInt())

    fun numericFixed(size: Int): String = buildString {
        repeat(size) { val v = bits(4).toInt(); append(if (v == 0) ' ' else '0' + (v - 1)) }
    }

    fun octetsFixed(size: Int): ByteArray = ByteArray(size) { bits(8).toByte() }

    fun octets(minLen: Int, maxLen: Int): ByteArray = octetsFixed(int(minLen.toLong(), maxLen.toLong()).toInt())
}

class TapFormatException(message: String) : Exception(message)

/** Raw MSB-first bit helpers used by the fixed-layout login frame. */
internal object FixedBits {
    fun set(buf: ByteArray, offset: Int, count: Int, value: Long) {
        for (i in 0 until count) {
            val abs = offset + i
            val mask = 0x80 ushr (abs % 8)
            buf[abs / 8] = if ((value ushr (count - 1 - i)) and 1L == 1L) (buf[abs / 8].toInt() or mask).toByte()
            else (buf[abs / 8].toInt() and mask.inv()).toByte()
        }
    }

    fun read(buf: ByteArray, offset: Int, count: Int): Long {
        var v = 0L
        for (i in 0 until count) {
            val abs = offset + i
            v = (v shl 1) or ((buf[abs / 8].toInt() shr (7 - abs % 8)) and 1).toLong()
        }
        return v
    }

    fun set7bit(buf: ByteArray, offset: Int, s: String) = s.forEachIndexed { i, c -> set(buf, offset + i * 7, 7, c.code.toLong()) }

    fun read7bit(buf: ByteArray, offset: Int, count: Int) = buildString { repeat(count) { append(read(buf, offset + it * 7, 7).toInt().toChar()) } }
}

internal fun ByteArray.hexUpper() = joinToString("") { "%02X".format(it) }
internal fun String.hexToBytes(): ByteArray {
    if (length % 2 != 0) throw TapFormatException("odd-length hex")
    return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
