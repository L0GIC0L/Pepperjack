package io.github.l0gic0l.pepperjack.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class TombstoneTest {
    private class Proto {
        val out = ByteArrayOutputStream()
        private fun varint(value: Long) {
            var v = value
            while (v and 0x7FL.inv() != 0L) {
                out.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
            out.write(v.toInt())
        }
        fun int(field: Int, value: Long) = apply { varint((field shl 3).toLong()); varint(value) }
        fun bytes(field: Int, value: ByteArray) = apply {
            varint(((field shl 3) or 2).toLong())
            varint(value.size.toLong())
            out.write(value)
        }
        fun string(field: Int, value: String) = bytes(field, value.toByteArray())
        fun message(field: Int, value: Proto) = bytes(field, value.out.toByteArray())
    }

    @Test
    fun formatsSignalBacktraceAndLogs() {
        val frame = Proto().int(1, 0x1234).string(4, "ngSpice_Init").int(5, 40).string(6, "/data/app/lib/arm64/libngspice.so")
        val thread = Proto().int(1, 42).string(2, "DefaultDispatch").message(4, frame)
        val entry = Proto().int(1, 42).message(2, thread)
        val signal = Proto().int(1, 11).string(2, "SIGSEGV").int(3, 1).string(4, "SEGV_MAPERR").int(8, 1).int(9, 0x10)
        val log = Proto().int(4, 4).string(5, "PepperjackEngine").string(6, "Initializing ngspice")
        val buffer = Proto().string(1, "main").message(2, log)
        val tombstone = Proto().int(6, 42).message(10, signal).string(14, "boom").message(16, entry).message(18, buffer)

        val text = Tombstone.format(tombstone.out.toByteArray())
        assertTrue(text, "signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x10" in text)
        assertTrue(text, "Abort message: boom" in text)
        assertTrue(text, "#00 pc 0000000000001234  /data/app/lib/arm64/libngspice.so (ngSpice_Init+40)" in text)
        assertTrue(text, "I PepperjackEngine: Initializing ngspice" in text)
    }
}
