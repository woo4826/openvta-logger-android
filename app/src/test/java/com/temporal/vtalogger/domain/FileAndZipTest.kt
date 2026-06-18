package com.temporal.vtalogger.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.TimeZone
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory

class FileAndZipTest {
    @Test
    fun driverIdIsSanitizedForFileNames() {
        assertEquals("Driver_01_Test", FileNames.sanitizeDriverId(" Driver/01 Test "))
        assertEquals("UNKNOWN", FileNames.sanitizeDriverId(" / "))
    }

    @Test
    fun sessionBaseNameMatchesLegacyPrefixDateTimeDriverShape() {
        val name = FileNames.sessionBaseName(
            startedAtMillis = 1_577_836_800_000L,
            driverId = "CC00",
            timeZone = TimeZone.getTimeZone("UTC"),
        )

        assertEquals("VTA01012020_000000_CC00", name)
    }

    @Test
    fun zipSingleFileContainsOriginalVtaEntryName() {
        val dir = createTempDirectory(prefix = "vta-test").toFile()
        val source = File(dir, "VTA01012020_000000_CC00.Vta")
        val zip = File(dir, "VTA01012020_000000_CC00.Zip")
        source.writeText("sample")

        ZipFiles.zipSingleFile(source, zip)

        assertTrue(zip.isFile)
        ZipFile(zip).use { archive ->
            val entry = archive.getEntry(source.name)
            assertEquals(source.name, entry.name)
            assertEquals("sample", archive.getInputStream(entry).reader().readText())
        }
    }
}
