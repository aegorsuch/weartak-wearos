package com.aitech.weartak_civ.cot

import org.junit.Assert.*
import org.junit.Test

class CotArchiveDetailTest {
    private fun xml(detail: String) = """
        <event uid="pin" type="b-m-p-s-m" time="2026-10-24T12:00:00Z" start="2026-10-24T12:00:00Z" stale="2026-10-24T12:15:00Z">
          <point lat="41" lon="-87" hae="100" ce="10" le="10"/><detail>$detail</detail>
        </event>
    """.trimIndent()

    @Test fun archiveElementInDetailMarksTheMarkerArchived() {
        assertTrue(CotMessageParser.parseMarkerOrNull(xml("<archive/>"))!!.detail.archived)
        assertTrue(CotMessageParser.parseMarkerOrNull(xml("<contact callsign='Pin'/><archive></archive>"))!!.detail.archived)
    }

    @Test fun archiveTextOrNestedElementsDoNot() {
        for (detail in listOf("", "<remarks>archive</remarks>", "<remarks>&lt;archive/&gt;</remarks>",
            "<remarks><![CDATA[<archive/>]]></remarks>", "<extension><archive/></extension>")) {
            assertFalse(detail, CotMessageParser.parseMarkerOrNull(xml(detail))!!.detail.archived)
        }
    }
}
