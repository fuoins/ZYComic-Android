package com.zycomic.app.net

import org.junit.Assert.assertThrows
import org.junit.Test

class PicacgConfigValidationTest {
    @Test
    fun acceptsFullHttpApiUrlsAndBareImageHostsAlongsideLegacyPools() {
        RemoteConfigFetcher.Validator.validate(config())
    }

    @Test
    fun acceptsConfiguredHttpApiRoute() {
        RemoteConfigFetcher.Validator.validate(config(apiUrl = "http://api.pica.example/v1"))
    }

    @Test
    fun rejectsBareApiHostAndImageUrlForPicacgPools() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteConfigFetcher.Validator.validate(config(apiUrl = "api.pica.example/v1"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RemoteConfigFetcher.Validator.validate(config(imageHost = "https://cdn.pica.example"))
        }
    }

    @Test
    fun rejectsOnlyOnePicacgPoolBeingPresent() {
        val body = config().replace(",\"sources3\":[\"cdn.pica.example\"]", "")
        assertThrows(IllegalArgumentException::class.java) { RemoteConfigFetcher.Validator.validate(body) }
    }

    private fun config(apiUrl: String = "https://api.pica.example/v1", imageHost: String = "cdn.pica.example") =
        """{"version":1,"port":443,"lines":["https://manwa.example/api"],"sources":["manwa-images.example"],"lines2":[],"sources2":[],"lines3":["$apiUrl"],"sources3":["$imageHost"],"rule":{"manwa.example":["8.8.8.8"],"manwa-images.example":["8.8.4.4"],"api.pica.example":["1.1.1.1"],"cdn.pica.example":["9.9.9.9"]}}"""
}
