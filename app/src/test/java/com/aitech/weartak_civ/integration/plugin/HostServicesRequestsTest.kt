package com.aitech.weartak_civ.integration.plugin

import android.os.Bundle
import com.weartak.plugin.sdk.host.v1.HostServicesContract as C
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class HostServicesRequestsTest {
    private fun http(path: String = "${C.REST_PREFIX}tak.server.plugins.TakCadServerPlugin/submit") =
        Bundle().apply { putString(C.KEY_PATH, path) }

    @Test fun canonicalTakCadRequestAndFuturePostAreAccepted() {
        val get = PluginHttpRequest.parse(http().apply { putString(C.KEY_QUERY, "fn=getIncidents") })
        assertEquals("GET", get.method)
        assertEquals("fn=getIncidents", get.query)
        val post = PluginHttpRequest.parse(http().apply {
            putString(C.KEY_METHOD, "POST")
            putString(C.KEY_BODY, "{\"note\":\"test\"}")
        })
        assertEquals("POST", post.method)
        assertEquals("application/json", post.contentType)
    }

    @Test fun rejectsUrlAndPathConfinementEscapes() {
        listOf(
            "https://evil.invalid${C.REST_PREFIX}test",
            "//evil.invalid${C.REST_PREFIX}test",
            "/Marti/api/missions",
            "${C.REST_PREFIX}../missions",
            "${C.REST_PREFIX}x/../../missions",
            "${C.REST_PREFIX}%2e%2e/missions",
            "${C.REST_PREFIX}%252e%252e/missions",
            "${C.REST_PREFIX}x%2fy",
            "${C.REST_PREFIX}x\\..\\missions",
            "${C.REST_PREFIX}x//y",
            "${C.REST_PREFIX}x?fn=test",
            "${C.REST_PREFIX}x#fragment",
            C.REST_PREFIX,
        ).forEach { path ->
            assertThrows("Accepted $path", IllegalArgumentException::class.java) {
                PluginHttpRequest.parse(http(path))
            }
        }
    }

    @Test fun rejectsUnknownFieldsWrongTypesMethodsAndOversizedUtf8() {
        listOf(
            http().apply { putString("url", "https://evil.invalid") },
            http().apply { putString("Authorization", "secret") },
            http().apply { putInt(C.KEY_PATH, 1) },
            http().apply { putString(C.KEY_METHOD, "CONNECT") },
            http().apply { putString(C.KEY_BODY, "GET body") },
            http().apply { putString(C.KEY_QUERY, "fn=x\r\nHost: evil.invalid") },
            http().apply { putString(C.KEY_QUERY, "?fn=getIncidents") },
            http().apply { putString(C.KEY_CONTENT_TYPE, "text/plain\r\nHost: evil.invalid") },
            http().apply {
                putString(C.KEY_METHOD, "POST")
                putString(C.KEY_BODY, "\u00e9".repeat(C.MAX_REQUEST_BYTES / 2 + 1))
            },
        ).forEach { request ->
            assertThrows(IllegalArgumentException::class.java) { PluginHttpRequest.parse(request) }
        }
    }

    @Test fun acceptsExactUtf8LimitAndValidLocationOrUid() {
        PluginHttpRequest.parse(http().apply {
            putString(C.KEY_METHOD, "POST")
            putString(C.KEY_BODY, "\u00e9".repeat(C.MAX_REQUEST_BYTES / 2))
        })
        assertEquals("incident", PluginMapRequest.parse(Bundle().apply {
            putString(C.KEY_ACTION, C.ACTION_BLOODHOUND)
            putString(C.KEY_UID, "incident")
        }).uid)
        assertEquals(90.0, PluginMapRequest.parse(Bundle().apply {
            putString(C.KEY_ACTION, C.ACTION_TARGET)
            putDouble(C.KEY_LATITUDE, 90.0)
            putDouble(C.KEY_LONGITUDE, -180.0)
        }).latitude!!, 0.0)
    }

    @Test fun rejectsInvalidMapActionAndCoordinates() {
        val valid = Bundle().apply { putString(C.KEY_ACTION, C.ACTION_TARGET) }
        listOf(
            Bundle(valid),
            Bundle(valid).apply { putString(C.KEY_UID, "") },
            Bundle(valid).apply { putDouble(C.KEY_LATITUDE, 1.0) },
            Bundle(valid).apply {
                putDouble(C.KEY_LATITUDE, Double.NaN)
                putDouble(C.KEY_LONGITUDE, 1.0)
            },
            Bundle(valid).apply {
                putDouble(C.KEY_LATITUDE, 91.0)
                putDouble(C.KEY_LONGITUDE, 1.0)
            },
            Bundle(valid).apply {
                putString(C.KEY_LATITUDE, "1")
                putDouble(C.KEY_LONGITUDE, 1.0)
            },
            Bundle(valid).apply {
                putString(C.KEY_ACTION, "delete")
                putString(C.KEY_UID, "incident")
            },
        ).forEach { request ->
            assertThrows(IllegalArgumentException::class.java) { PluginMapRequest.parse(request) }
        }
    }
}
