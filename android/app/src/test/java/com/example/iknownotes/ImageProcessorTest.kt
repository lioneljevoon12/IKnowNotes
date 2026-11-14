package com.example.iknownotes

import com.example.iknownotes.util.ImageProcessor
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageProcessorTest {

    @Test
    fun sampleSize_keepsBothSidesAtLeastTheTarget() {
        assertEquals(1, ImageProcessor.sampleSizeFor(1200, 800, 1600))
        assertEquals(1, ImageProcessor.sampleSizeFor(3000, 2000, 1600)) // 2x would leave 1000px
        assertEquals(2, ImageProcessor.sampleSizeFor(4000, 3200, 1600))
        assertEquals(4, ImageProcessor.sampleSizeFor(8000, 6400, 1600))
    }

    @Test
    fun sampleSize_isBoundedByTheShorterSide() {
        // A panorama must not be shrunk so far that its short side drops below the target.
        assertEquals(1, ImageProcessor.sampleSizeFor(12000, 2000, 1600))
    }

    @Test
    fun scale_bringsTheLongerSideDownButNeverUp() {
        assertEquals(0.5f, ImageProcessor.scaleFor(3200, 2000, 1600), 0.0001f)
        assertEquals(0.5f, ImageProcessor.scaleFor(2000, 3200, 1600), 0.0001f)
        assertEquals(1f, ImageProcessor.scaleFor(800, 600, 1600), 0.0001f)
    }
}
