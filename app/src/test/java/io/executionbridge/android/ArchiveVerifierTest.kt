package io.executionbridge.android
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveVerifierTest {
    private val required=listOf("archive_metadata.json","diagnostic_report.json","test_results.json","diagnostic_chain_audit.json","diagnostic_chain_comparison.json","futures_fill_stop_timing.json","signed_position_stop_join.json","runtime_resource_diagnostics.json","futures_positions_signed_snapshot.json","futures_exchange_stop_proofs.json","futures_intent_stop_lifecycle.json","futures_exit_reconciliation.json","signal_decision_history.json","observer_paper_full_history.ndjson","observer_paper_full_history_metadata.json","stop_trail_decisions_full.json")
    private fun fixture(omit:String?=null, extra:String?=null, rows:String="1", complete:String="true", check:(File)->Unit) {
        val f=File.createTempFile("bridge-test-", ".zip")
        try {
            ZipOutputStream(f.outputStream()).use {z ->
                for(name in required.filter {it!=omit}+listOfNotNull(extra)) {
                    z.putNextEntry(ZipEntry(name))
                    val body=when(name) {
                        "archive_metadata.json" -> """{"schema":"WILLIAMS_ARCHIVE_META_V1","report_at_ms":1234,"diagnostic_contract_version":1}"""
                        "observer_paper_full_history_metadata.json" -> """{"export_complete":$complete,"record_count":$rows}"""
                        "observer_paper_full_history.ndjson" -> "{}\n"
                        else -> "{}"
                    }
                    z.write(body.toByteArray());z.closeEntry()
                }
            }
            check(f)
        } finally {f.delete()}
    }
    private fun inspect(f:File)=ArchiveVerifier.inspect(f,"a".repeat(64))
    @Test fun validArchiveNotLiveEvidence()=fixture {f ->
        val out=inspect(f)
        assertEquals("VALIDATED",out.getString("integrity"))
        assertEquals("NOT_VERIFIED",out.getString("live_exchange_state"))
        assertEquals(1L,out.getLong("paper_record_count"))
    }
    @Test fun missingEntryRejected()=fixture(omit="test_results.json") {f -> assertThrows(IllegalArgumentException::class.java){inspect(f)};Unit}
    @Test fun traversalRejected()=fixture(extra="../outside") {f -> assertThrows(IllegalArgumentException::class.java){inspect(f)};Unit}
    @Test fun incompleteHistoryRejected()=fixture(rows="2") {f -> assertThrows(IllegalArgumentException::class.java){inspect(f)};Unit}
    @Test fun stringCountRejected()=fixture(rows="\"1\"") {f -> assertThrows(IllegalArgumentException::class.java){inspect(f)};Unit}
    @Test fun stringBooleanRejected()=fixture(complete="\"true\"") {f -> assertThrows(IllegalArgumentException::class.java){inspect(f)};Unit}
}
