package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("OmniFile", appName)
  }

  @Test
  fun `format bytes correctly`() {
    val formatted = com.example.model.FileDetails.formatBytes(1024L * 1024L)
    assertEquals("1.00 MB", formatted)
  }

  @Test
  fun `batch job summary computes savings correctly`() {
    val r1 = com.example.model.ConversionJobResult(
      isSuccess = true,
      inputName = "photo1.jpg",
      inputSizeBytes = 2000L,
      outputName = "photo1.webp",
      outputSizeBytes = 1000L,
      outputPath = "/dummy/photo1.webp",
      durationMs = 50L,
      format = "WEBP",
      message = "Success"
    )
    val r2 = com.example.model.ConversionJobResult(
      isSuccess = true,
      inputName = "photo2.jpg",
      inputSizeBytes = 4000L,
      outputName = "photo2.webp",
      outputSizeBytes = 2000L,
      outputPath = "/dummy/photo2.webp",
      durationMs = 80L,
      format = "WEBP",
      message = "Success"
    )
    val summary = com.example.model.BatchJobSummary(
      results = listOf(r1, r2),
      totalDurationMs = 130L,
      mode = "CONVERT"
    )
    assertEquals(6000L, summary.totalInputBytes)
    assertEquals(3000L, summary.totalOutputBytes)
    assertEquals(3000L, summary.totalSavedBytes)
    assertEquals(50, summary.overallSavingsPercent)
    assertEquals(2, summary.successCount)
  }
}
