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

import app.cash.paparazzi.internal.ImageUtils
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

class APNGReaderTest {

  @Test
  fun decodesAllFrames() {
    val file = javaClass.classLoader.getResource("simple_animation.png")
    val reader = APNGReader(File(file!!.toURI()))

    for (i in 0 until 3) {
      val expectedFile = javaClass.classLoader.getResource("simple_animation_$i.png")
      val expectedImage = ImageIO.read(expectedFile)
      val actualImage = reader.getNextFrame()!!
      ImageUtils.assertImageSimilar(expectedFile!!.path, expectedImage, actualImage, 0.0)
    }

    assertThat(reader.finished()).isTrue
    assertThat(reader.frameCount).isEqualTo(reader.frameNumber)
  }

  @Test(expected = IllegalStateException::class)
  fun enforcesAnimationChunkSequence() {
    val file = javaClass.classLoader.getResource("invalid_sequence.png")
    val reader = APNGReader(File(file!!.toURI()))

    while (!reader.finished()) { reader.getNextFrame() }
  }

  @Test(expected = IllegalStateException::class)
  fun enforcesCRC() {
    val file = javaClass.classLoader.getResource("invalid_crc.png")
    val reader = APNGReader(File(file!!.toURI()))

    while (!reader.finished()) { reader.getNextFrame() }
  }

  @Test(expected = IllegalStateException::class)
  fun failsOnMissingPNGHeader() {
    val file = File.createTempFile("image.png", null)
    file.writeBytes((0..10).map { it.toByte() }.toByteArray())
    APNGReader(file)
  }
}
