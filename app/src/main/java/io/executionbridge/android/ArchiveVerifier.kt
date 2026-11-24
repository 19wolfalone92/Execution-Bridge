package io.executionbridge.android
import java.io.File
import java.util.zip.ZipFile
import org.json.JSONObject

object ArchiveVerifier {
    private fun exactLong(value:Any?):Long=when(value) {
        is Int -> value.toLong()
        is Long -> value
        else -> throw IllegalArgumentException("metadata integer required")
    }
    fun inspect(file:File,hash:String):JSONObject {
        require(file.isFile && file.length() in 1..128L*1024*1024 && hash.matches(Regex("[0-9a-f]{64}")))
        val required=setOf("archive_metadata.json","diagnostic_report.json","test_results.json",
            "diagnostic_chain_audit.json","diagnostic_chain_comparison.json",
            "futures_fill_stop_timing.json","signed_position_stop_join.json",
            "runtime_resource_diagnostics.json","futures_positions_signed_snapshot.json",
            "futures_exchange_stop_proofs.json","futures_intent_stop_lifecycle.json",
            "futures_exit_reconciliation.json","signal_decision_history.json",
            "observer_paper_full_history.ndjson","observer_paper_full_history_metadata.json",
            "stop_trail_decisions_full.json")
        val found=mutableSetOf<String>()
        var total=0L
        var lines=0L
        var meta:JSONObject?=null
        var paper:JSONObject?=null
        ZipFile(file).use {z ->
            require(z.size()<=256)
            val entries=z.entries()
            val buf=ByteArray(65536)
            while(entries.hasMoreElements()) {
                val e=entries.nextElement()
                require(!e.isDirectory && e.name.matches(Regex("[A-Za-z0-9_.-]{1,128}")) && e.name!="." && e.name!=".." && found.add(e.name))
                require(e.size in 0..(512L*1024*1024-total))
                val isMeta=e.name=="archive_metadata.json" || e.name=="observer_paper_full_history_metadata.json"
                if(isMeta) require(e.size in 1..65536)
                val small=if(isMeta) java.io.ByteArrayOutputStream() else null
                val crc=java.util.zip.CRC32()
                var count=0L
                z.getInputStream(e).use {input ->
                    while(true) {
                        check(!Thread.currentThread().isInterrupted)
                        val n=input.read(buf);if(n<0) break
                        count+=n;total+=n
                        require(count<=e.size && total<=512L*1024*1024)
                        crc.update(buf,0,n)
                        small?.write(buf,0,n)
                        if(e.name=="observer_paper_full_history.ndjson") for(i in 0 until n) if(buf[i]==10.toByte()) lines++
                    }
                }
                require(count==e.size && crc.value==e.crc)
                if(small!=null) {
                    val data=JSONObject(small.toString("UTF-8"))
                    if(e.name=="archive_metadata.json") meta=data else paper=data
                }
            }
        }
        require(found.containsAll(required))
        val m=requireNotNull(meta);val p=requireNotNull(paper)
        require(m.opt("schema")=="WILLIAMS_ARCHIVE_META_V1")
        val stamp=exactLong(m.opt("report_at_ms"));val contract=exactLong(m.opt("diagnostic_contract_version"))
        require(stamp>0 && contract>0)
        require(p.opt("export_complete")==true && exactLong(p.opt("record_count"))==lines)
        return JSONObject().put("integrity","VALIDATED").put("sha256",hash).put("entry_count",found.size)
            .put("report_at_ms",stamp).put("paper_record_count",lines)
            .put("origin","NOT_ATTESTED").put("live_exchange_state","NOT_VERIFIED")
    }

}
