package com.aitech.weartak_civ.service

import java.io.OutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object DiagnosticsPackage {
    suspend fun bytes(positionLog: PositionLog, diagnostics: AppDiagnostics): ByteArray {
        val output = LimitedPackageBuffer()
        write(output, positionLog, diagnostics)
        return output.toByteArray()
    }

    suspend fun write(
        output: OutputStream,
        positionLog: PositionLog,
        diagnostics: AppDiagnostics
    ) {
        val events = diagnostics.exportJson()
        val packageId = UUID.randomUUID().toString()
        val manifest = """
            <MissionPackageManifest version="2">
              <Configuration>
                <Parameter name="uid" value="$packageId"/>
                <Parameter name="name" value="WearTAK diagnostics"/>
                <Parameter name="onReceiveImport" value="false"/>
                <Parameter name="onReceiveDelete" value="false"/>
              </Configuration>
              <Contents>
                <Content zipEntry="FILES/positions.csv" ignore="false"><Parameter name="isCOT" value="false"/></Content>
                <Content zipEntry="FILES/diagnostics.json" ignore="false"><Parameter name="isCOT" value="false"/></Content>
                <Content zipEntry="FILES/README.txt" ignore="false"><Parameter name="isCOT" value="false"/></Content>
              </Contents>
            </MissionPackageManifest>
        """.trimIndent()
        ZipOutputStream(output).use { zip ->
            fun writeEntry(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            writeEntry("MANIFEST/manifest.xml", manifest)
            zip.putNextEntry(ZipEntry("FILES/positions.csv"))
            val writer = zip.writer(Charsets.UTF_8)
            positionLog.export(writer)
            zip.closeEntry()
            writeEntry("FILES/diagnostics.json", events)
            writeEntry("FILES/README.txt", """
                Sensitive location history. Limit distribution.
                Accuracy is Android Location.accuracy: estimated 68% horizontal radius in metres.
                Empty accuracy means unavailable. Bearer and relay may be unknown.
                A transport handoff is not confirmed receipt.
                Position log incomplete: ${positionLog.storageFailed.value}.
                Position logging is opt-in and retention is bounded.
            """.trimIndent())
        }
    }

    private class LimitedPackageBuffer : ByteArrayOutputStream() {
        override fun write(value: Int) {
            checkCapacity(1)
            super.write(value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            checkCapacity(length)
            super.write(bytes, offset, length)
        }

        private fun checkCapacity(length: Int) {
            if (length > com.aitech.weartak_civ.data.takserver.MAX_DATA_PACKAGE_BYTES - size())
                throw IOException("Diagnostics package exceeds upload size limit")
        }
    }
}
