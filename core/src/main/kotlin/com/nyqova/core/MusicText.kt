package com.nyqova.core

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

object MusicText {
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2 && ((bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte()) ||
                (bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte()))) return String(bytes, Charsets.UTF_16)
        return runCatching { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF") }
            .getOrElse { String(bytes, charset("GB18030")) }
    }
}
