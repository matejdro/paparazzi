/*
 * Copyright (C) 2023 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.cash.paparazzi.internal.apng

import app.cash.paparazzi.accessibility.RenderSettings.toColorInt
import app.cash.paparazzi.internal.apng.PngConsts.Header
import app.cash.paparazzi.internal.apng.PngConsts.Header.IDAT
import app.cash.paparazzi.internal.apng.PngConsts.Header.IHDR
import app.cash.paparazzi.internal.apng.PngConsts.Header.acTL
import app.cash.paparazzi.internal.apng.PngConsts.Header.fcTL
import app.cash.paparazzi.internal.apng.PngConsts.Header.fdAT
import app.cash.paparazzi.internal.apng.PngConsts.PNG_BITS_PER_PIXEL
import app.cash.paparazzi.internal.apng.PngConsts.PNG_COLOR_TYPE_RGBA
import app.cash.paparazzi.internal.apng.PngConsts.PNG_SIG
import okio.Buffer
import okio.BufferedSource
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.internal.commonToUtf8String
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.awt.Color
import java.awt.Point
import java.awt.image.BufferedImage
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Inflater

class APNGWriterTest {

  @Test
  fun writesAnimationMetadata() {
    val testFile = File.createTempFile("writesAnimationMetadata.png", null)
    APNGWriter(testFile, 3, 1).use { writer ->
      writer.writeImage(createImage(squareOffset = Point(5, 5)))
      writer.writeImage(createImage(squareOffset = Point(25, 25)))
      writer.writeImage(createImage(squareOffset = Point(45, 45)))
    }

    FileSystem.SYSTEM.openReadOnly(testFile.path.toPath()).use {
      it.source(0L).buffer().use {
        it.skip(PNG_SIG.size.toLong()) // Header
        val (ihdr, ihdrData) = it.assertNextChunk()
        assertThat(ihdr).isEqualTo(IHDR)
        assertThat(ihdrData.readInt()).isEqualTo(DEFAULT_SIZE)
        assertThat(ihdrData.readInt()).isEqualTo(DEFAULT_SIZE)
        assertThat(ihdrData.readByte()).isEqualTo(PNG_BITS_PER_PIXEL)
        assertThat(ihdrData.readByte()).isEqualTo(PNG_COLOR_TYPE_RGBA)
        assertThat(ihdrData.readByte()).isEqualTo(0)
        assertThat(ihdrData.readByte()).isEqualTo(0)
        assertThat(ihdrData.readByte()).isEqualTo(0)
        assertThat(ihdrData.exhausted()).isTrue

        val (actl, actlData) = it.assertNextChunk()
        assertThat(actl).isEqualTo(acTL)
        assertThat(actlData.readInt()).isEqualTo(3) // 3 Frames total
        assertThat(actlData.readInt()).isEqualTo(0) // Loops forever
        assertThat(actlData.exhausted()).isTrue
      }
    }
  }

  @Test
  fun writesSingleImageWithNoAnimationMetadata() {
    val testFile = File.createTempFile("writesSingleImageWithNoAnimationMetadata.png", null)
    APNGWriter(testFile, 1, 1).use { writer ->
      writer.writeImage(createImage(squareOffset = Point(5, 5)))
    }

    FileSystem.SYSTEM.openReadOnly(testFile.path.toPath()).use {
      it.source(0L).buffer().use {
        it.skip(PNG_SIG.size.toLong()) // Header
        assertThat(it.assertNextChunk().first).isEqualTo(IHDR)

        val (idat, idatData) = it.assertNextChunk()
        assertThat(idat).isEqualTo(IDAT)
        val imageData = idatData.decompress()
        assertThat(imageData.size).isEqualTo((DEFAULT_SIZE * DEFAULT_SIZE * 4L) + DEFAULT_SIZE)
      }
    }
  }

  @Test
  fun writesAnimationChunksSequentially() {
    val testFile = File.createTempFile("writesAnimationChunksSequentially.png", null)
    APNGWriter(testFile, 3, 1).use { writer ->
      writer.writeImage(createImage(squareOffset = Point(5, 5)))
      writer.writeImage(createImage(squareOffset = Point(15, 15)))
      writer.writeImage(createImage(squareOffset = Point(25, 25)))
    }

    FileSystem.SYSTEM.openReadOnly(testFile.path.toPath()).use {
      it.source(0L).buffer().use {
        it.skip(PNG_SIG.size.toLong()) // Header

        var sequence = 0
        while (!it.exhausted()) {
          val (header, data) = it.assertNextChunk()
          if (header == fcTL || header == fdAT) {
            assertThat(data.readInt()).isEqualTo(sequence++)
          }
        }
      }
    }
  }

  @Test
  fun writesAllFramesWithSameFrameRate() {
    val testFile = File.createTempFile("writesAllFramesWithSameFrameRate.png", null)
    APNGWriter(testFile, 3, 3).use { writer ->
      writer.writeImage(createImage(squareOffset = Point(5, 5)))
      writer.writeImage(createImage(squareOffset = Point(15, 15)))
      writer.writeImage(createImage(squareOffset = Point(25, 25)))
    }

    FileSystem.SYSTEM.openReadOnly(testFile.path.toPath()).use {
      it.source(0L).buffer().use {
        it.skip(PNG_SIG.size.toLong()) // Header

        while (!it.exhausted()) {
          val (header, data) = it.assertNextChunk()
          if (header == fcTL) {
            data.skip(20)
            assertThat(data.readShort()).isEqualTo(1)
            assertThat(data.readShort()).isEqualTo(3)
          }
        }
      }
    }
  }

  @Test
  fun writesFramesAsSmallestDiffRect() {
    val testFile = File.createTempFile("writesFramesAsSmallestDiffRect.png", null)
    APNGWriter(testFile, 2, 1).use { writer ->
      writer.writeImage(createImage(squareOffset = Point(5, 5)))
      writer.writeImage(createImage(squareOffset = Point(15, 15)))
    }

    FileSystem.SYSTEM.openReadOnly(testFile.path.toPath()).use {
      it.source(0L).buffer().use {
        it.skip(PNG_SIG.size.toLong()) // Header
        assertThat(it.assertNextChunk().first).isEqualTo(IHDR)
        assertThat(it.assertNextChunk().first).isEqualTo(acTL)
        assertThat(it.assertNextChunk().first).isEqualTo(fcTL)
        assertThat(it.assertNextChunk().first).isEqualTo(IDAT)

        val (header, data) = it.assertNextChunk()
        assertThat(header).isEqualTo(fcTL)
        assertThat(data.readInt()).isEqualTo(1)

        assertThat(data.readInt()).isEqualTo(60) // Width
        assertThat(data.readInt()).isEqualTo(60) // Height
        assertThat(data.readInt()).isEqualTo(5) // X Offset
        assertThat(data.readInt()).isEqualTo(5) // Y Offset
      }
    }
  }

  @Test
  fun writesEqualFramesAsSinglePixelFrameDiff() {
    val testFile = File.createTempFile("writesEqualFramesAsSinglePixelFrameDiff.png", null)
    APNGWriter(testFile, 2, 1).use { writer ->
      writer.writeImage(createImage(squareOffset = Point(5, 5)))
      writer.writeImage(createImage(squareOffset = Point(5, 5)))
    }

    FileSystem.SYSTEM.openReadOnly(testFile.path.toPath()).use {
      it.source(0L).buffer().use {
        it.skip(PNG_SIG.size.toLong()) // Header
        assertThat(it.assertNextChunk().first).isEqualTo(IHDR)
        assertThat(it.assertNextChunk().first).isEqualTo(acTL)
        assertThat(it.assertNextChunk().first).isEqualTo(fcTL)
        assertThat(it.assertNextChunk().first).isEqualTo(IDAT)

        val (header, data) = it.assertNextChunk()
        assertThat(header).isEqualTo(fcTL)
        assertThat(data.readInt()).isEqualTo(1)

        assertThat(data.readInt()).isEqualTo(1) // Width
        assertThat(data.readInt()).isEqualTo(1) // Height
        assertThat(data.readInt()).isEqualTo(0) // X Offset
        assertThat(data.readInt()).isEqualTo(0) // Y Offset

        val (fdatHeader, fdatData) = it.assertNextChunk()
        assertThat(fdatHeader).isEqualTo(fdAT)
        fdatData.skip(4L) // Sequence Number

        val imageData = fdatData.decompress()
        assertThat(imageData.readByte()).isEqualTo(0) // Row filter None
        assertThat(imageData.readInt()).isEqualTo(BACKGROUND_PIXEL_INT)
        assertThat(fdatData.exhausted()).isTrue
      }
    }
  }

  @Test
  fun rewritesFirstFrameWhenSmallerThanMaxFrame() {
    val testFile = File.createTempFile("rewritesFirstFrameWhenSmallerThanMaxFrame.png", null)
    APNGWriter(testFile, 2, 1).use { writer ->
      writer.writeImage(createImage(imageSize = DEFAULT_SIZE, squareOffset = Point(5, 5)))
      writer.writeImage(createImage(imageSize = MAX_SIZE, squareOffset = Point(15, 15)))
    }

    FileSystem.SYSTEM.openReadOnly(testFile.path.toPath()).use {
      it.source(0L).buffer().use {
        it.skip(PNG_SIG.size.toLong()) // Header
        val (ihdr, ihdrData) = it.assertNextChunk()
        assertThat(ihdr).isEqualTo(IHDR)
        assertThat(ihdrData.readInt()).isEqualTo(MAX_SIZE)
        assertThat(ihdrData.readInt()).isEqualTo(MAX_SIZE)

        assertThat(it.assertNextChunk().first).isEqualTo(acTL)
        assertThat(it.assertNextChunk().first).isEqualTo(fcTL)

        val (idat, idatData) = it.assertNextChunk()
        assertThat(idat).isEqualTo(IDAT)
        val decompress = idatData.decompress()
        assertThat(decompress.size).isEqualTo((MAX_SIZE * MAX_SIZE * 4L) + MAX_SIZE) // 4 Bytes Per Pixel + 1 Byte Per Row
      }
    }
  }

  @Test(expected = IllegalStateException::class)
  fun throwsExceptionWhenWrittenFramesNotEqualTotalFrames() {
    val testFile =
      File.createTempFile("throwsExceptionWhenWrittenFramesNotEqualTotalFrames.png", null)
    APNGWriter(testFile, 2, 1).use { writer ->
      writer.writeImage(createImage(squareOffset = Point(5, 5)))
    }
  }

  private fun BufferedSource.assertNextChunk(): Pair<Header, BufferedSource> {
    val crcEngine = CRC32()
    val dataLength = readInt()
    val chunkId = readByteArray(4L)
    val dataBuffer = Buffer().apply {
      write(this@assertNextChunk, dataLength.toLong())
    }

    val data = dataBuffer.peek().readByteArray()
    val crc = readInt()

    crcEngine.reset()
    crcEngine.update(chunkId, 0, 4)
    if (dataLength > 0) crcEngine.update(data, 0, dataLength)

    assertThat(crcEngine.value.toInt()).isEqualTo(crc)

    return Header.valueOf(chunkId.commonToUtf8String()) to dataBuffer
  }

  private fun createImage(imageSize: Int = DEFAULT_SIZE, squareOffset: Point) =
    BufferedImage(imageSize, imageSize, BufferedImage.TYPE_INT_ARGB).apply {
      val g = graphics
      g.color = BACKGROUND_COLOR
      g.fillRect(0, 0, width, height)

      g.color = Color.GREEN
      g.fillRect(squareOffset.x, squareOffset.y, SQUARE_SIZE, SQUARE_SIZE)

      g.dispose()
    }

  private fun BufferedSource.decompress(): Buffer {
    val inflater = Inflater().apply {
      val readByteArray = readByteArray()
      setInput(readByteArray)
    }

    val buffer = ByteArray(1_024_000)
    return Buffer().apply {
      do {
        val byteCount = inflater.inflate(buffer)
        write(buffer, 0, byteCount)
      } while (!inflater.finished() && byteCount != 0)
    }
  }

  companion object {
    private val BACKGROUND_COLOR = Color.BLUE

    // ColorInt is encoded as ARGB, PNG is encoded as RGBA rotating to move A to the end
    private val BACKGROUND_PIXEL_INT = BACKGROUND_COLOR.toColorInt().rotateLeft(8)

    private const val DEFAULT_SIZE = 100
    private const val MAX_SIZE = 200
    private const val SQUARE_SIZE = 50
  }
}
