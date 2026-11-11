package com.aitech.weartak_civ.data.takserver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class VoicePackageClassificationTest {
    @Test fun combinedMissionJsonIsAVoiceCandidate() {
        val mission = """{"missionId":{"uuid":"00000000-0000-4000-8000-000000000001"},"channels":[],"missionType":"COMBINED"}"""
        assertTrue(TakDataPackageImporter.voiceCandidate(voicePackage("mission", mission.toByteArray(), false)))
    }

    @Test fun binaryCandidateRequiresTheVoiceManifestAction() {
        // One protobuf length-delimited field is sufficient for host classification.
        // The receiver handles full semantic parsing.
        val mission = byteArrayOf(10, 2, 65, 66)
        assertTrue(TakDataPackageImporter.voiceCandidate(voicePackage("mission_proto", mission, true)))
        assertFalse(TakDataPackageImporter.voiceCandidate(voicePackage("mission_proto", mission, false)))
    }

    @Test fun manifestActionAloneDoesNotAuthorizeVoiceDelivery() {
        val unrelated = voicePackage("other.txt", "unrelated content".toByteArray(), true)
        assertFalse(TakDataPackageImporter.voiceCandidate(unrelated))
    }

    private fun voicePackage(path: String, content: ByteArray, voiceAction: Boolean): ByteArray {
        val action = if (voiceAction) {
            """<Parameter name="onReceiveAction" value="com.atakmap.android.gbr.multicastvoice.sharing.downloaded"/>"""
        } else {
            ""
        }
        val manifest = """
            <MissionPackageManifest version="2">
              <Configuration>$action</Configuration>
              <Contents><Content zipEntry="$path" ignore="false"/></Contents>
            </MissionPackageManifest>
        """.trimIndent()
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("MANIFEST/manifest.xml"))
            zip.write(manifest.toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(path))
            zip.write(content)
            zip.closeEntry()
        }
        return bytes.toByteArray()
    }
}
