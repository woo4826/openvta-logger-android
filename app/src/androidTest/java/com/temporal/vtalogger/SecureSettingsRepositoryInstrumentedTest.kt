package com.temporal.vtalogger

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.temporal.vtalogger.data.SecureSettingsRepository
import com.temporal.vtalogger.domain.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecureSettingsRepositoryInstrumentedTest {
    @Test
    fun ftpPasswordIsNotStoredAsPlaintextInSharedPreferences() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = SecureSettingsRepository(context)
        val settings = AppSettings(
            driverId = "CC00",
            ftpHost = "ftp.example.test",
            ftpUser = "RoadData",
            ftpPassword = "secret-password",
        )

        repository.save(settings)

        val rawPreferences = context
            .getSharedPreferences("secure_settings", Context.MODE_PRIVATE)
            .all
            .values
            .joinToString(separator = "\n")
        assertFalse(rawPreferences.contains("secret-password"))
        assertEquals(settings, repository.load())
    }
}
