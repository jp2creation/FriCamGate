package com.nvr.camnory

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Read a single JPEG from a multipart stream; bound both junk and frame sizes. */
internal fun readStreamJpeg(input: InputStream, maxBytes: Int = 4 * 1024 * 1024): ByteArray? {
    var previous = -1
    var skipped = 0
    var frame: ByteArrayOutputStream? = null
    while (true) {
        val byte = input.read()
        if (byte == -1) {
            require(frame == null) { "Incomplete image" }
            return null
        }
        val current = frame
        if (current == null) {
            require(++skipped <= 65536) { "Invalid image stream" }
            if (previous == 0xff && byte == 0xd8) {
                frame = ByteArrayOutputStream()
                frame.write(0xff); frame.write(0xd8)
            }
        } else {
            require(current.size() < maxBytes) { "Image too large" }
            current.write(byte)
            if (previous == 0xff && byte == 0xd9) return current.toByteArray()
        }
        previous = byte
    }
}
