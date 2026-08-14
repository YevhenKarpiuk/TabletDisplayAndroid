package com.tabletdisplay.protocol

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WireProtocolTest {
    private data class VectorCase(
        val name: String,
        val file: String,
        val direction: String,
        val expectation: String,
        val type: Int,
        val flags: Int,
        val payloadLength: Int,
        val sequence: Long
    )

    @Test
    fun goldenVectorsDecodeEncodeAndRejectAsSpecified() {
        val directory = goldenDirectory()
        val cases = loadManifest(directory)
        assertEquals(14, cases.size)

        cases.forEach { vectorCase ->
            if (vectorCase.expectation == "VALID") {
                checkValidVector(directory, vectorCase)
            } else {
                checkRejectedVector(directory, vectorCase)
            }
        }

        checkDirectionStream(directory, cases, "W2A")
        checkDirectionStream(directory, cases, "A2W")
    }

    private fun checkValidVector(directory: File, vectorCase: VectorCase) {
        val bytes = loadHex(File(directory, vectorCase.file))

        for (split in 0..bytes.size) {
            val decoder = IncrementalMessageDecoder(enforceSequence = false)
            val messages = buildList {
                addAll(decoder.feed(bytes, 0, split))
                addAll(decoder.feed(bytes, split, bytes.size - split))
            }
            assertEquals("${vectorCase.name}: split $split", 1, messages.size)
        }

        val messages = feedInChunks(
            IncrementalMessageDecoder(enforceSequence = false),
            bytes
        )
        assertEquals("${vectorCase.name}: chunk count", 1, messages.size)
        val message = messages.single()
        assertEquals(vectorCase.type, message.header.messageType)
        assertEquals(vectorCase.flags, message.header.flags)
        assertEquals(vectorCase.payloadLength, message.header.payloadLength)
        assertEquals(vectorCase.sequence, message.header.sequence)
        assertArrayEquals(
            "${vectorCase.name}: decode/encode",
            bytes,
            WireProtocol.encodeMessage(message.header, message.payload)
        )
    }

    private fun checkRejectedVector(directory: File, vectorCase: VectorCase) {
        val bytes = loadHex(File(directory, vectorCase.file))
        val decoder = IncrementalMessageDecoder(
            enforceSequence = vectorCase.expectation == "SEQUENCE_GAP"
        )

        try {
            feedInChunks(decoder, bytes)
            fail("${vectorCase.name}: rejection expected")
        } catch (exception: ProtocolException) {
            assertEquals(vectorCase.expectation, exception.kind.name)
        }
    }

    private fun checkDirectionStream(
        directory: File,
        cases: List<VectorCase>,
        direction: String
    ) {
        val decoder = IncrementalMessageDecoder()
        val selected = cases.filter {
            it.expectation == "VALID" && it.direction == direction
        }
        var count = 0
        selected.forEach { vectorCase ->
            count += feedInChunks(
                decoder,
                loadHex(File(directory, vectorCase.file)),
                vectorCase.sequence.toInt()
            ).size
        }
        assertEquals(direction, selected.size, count)
    }

    private fun feedInChunks(
        decoder: IncrementalMessageDecoder,
        bytes: ByteArray,
        firstChunk: Int = 1
    ): List<WireMessage> {
        val messages = mutableListOf<WireMessage>()
        var offset = 0
        var chunk = firstChunk
        while (offset < bytes.size) {
            val count = minOf(chunk, bytes.size - offset)
            messages += decoder.feed(bytes, offset, count)
            offset += count
            chunk = chunk % 11 + 1
        }
        return messages
    }

    private fun goldenDirectory(): File {
        val userDirectory = System.getProperty("user.dir")
            ?: error("user.dir is not defined")
        val start = File(userDirectory).absoluteFile
        return generateSequence(start) { it.parentFile }
            .map { File(it, "protocol/golden") }
            .firstOrNull { File(it, "manifest.tsv").isFile }
            ?: error("protocol/golden was not found from $start")
    }

    private fun loadManifest(directory: File): List<VectorCase> =
        File(directory, "manifest.tsv").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val fields = line.split('\t')
                assertEquals("manifest columns", 8, fields.size)
                VectorCase(
                    name = fields[0],
                    file = fields[1],
                    direction = fields[2],
                    expectation = fields[3],
                    type = fields[4].removePrefix("0x").toInt(16),
                    flags = fields[5].removePrefix("0x").toInt(16),
                    payloadLength = fields[6].toInt(),
                    sequence = fields[7].toLong()
                )
            }

    private fun loadHex(file: File): ByteArray {
        val digits = file.readText().filterNot(Char::isWhitespace)
        assertTrue("${file.name}: even hex digits", digits.length % 2 == 0)
        return ByteArray(digits.length / 2) { index ->
            digits.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
