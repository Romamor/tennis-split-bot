package ru.movereon.tennis.telegram

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock

/** Bounded, aggregate-only telemetry. No event, account, chat or button tokens are exported. */
class UiMetrics(private val enabled:Boolean=false, private val clock:Clock=Clock.systemUTC(),
    private val nanoTime:()->Long=System::nanoTime) {
    private class Histogram {
        val counts=LongArray(bounds.size+1)
        var sum=0.0
        fun observe(seconds:Double) {
            counts[bounds.indexOfFirst { seconds<=it }.takeIf { it>=0 } ?: bounds.size]++
            sum+=seconds
        }
    }
    private data class Interaction(val received:Long, var category:String="other", var responseRecorded:Boolean=false)
    private data class Attempt(val interaction:Interaction, val started:Long, var outcome:String="ignored", var responded:Boolean=false)
    // Entries are only needed across retries in this process, not a second durable inbox.
    private val unfinished=linkedMapOf<Long,Interaction>()
    private var active:Attempt?=null
    private val histograms=linkedMapOf<Pair<String,String>,Histogram>()
    private val counters=linkedMapOf<Pair<String,String>,Long>()
    private val startedAt=clock.millis()/1000.0
    private var lastPublish:Long?=null
    init {
        if(enabled) {
            // Publish a zero baseline before the first click; rate() can then see its increment.
            for(category in categories) {
                histograms["response" to category]=Histogram()
                for(outcome in listOf("success","retry","uncertain","rejected","error","ignored")) counters[outcome to category]=0
            }
            histograms["queue" to "all"]=Histogram()
            histograms["processing" to "all"]=Histogram()
            for(method in methods+"other") histograms["api" to method]=Histogram()
        }
    }

    fun <T> interaction(id:Long,receivedAt:Long?,block:()->T):T {
        if(!enabled) return block()
        val previous=active
        val first=id !in unfinished
        val interaction=unfinished.getOrPut(id) { Interaction(receivedAt ?: clock.millis()) }
        if(unfinished.size>512) { unfinished.remove(unfinished.keys.first());increment("evicted","other") }
        val attempt=Attempt(interaction,nanoTime())
        active=attempt
        if(first) duration("queue", "all", (clock.millis()-interaction.received)/1000.0)
        try { return block() }
        catch(f:TelegramFailure) {
            failure(f.kind)
            if(f.kind in setOf(FailureKind.RETRY_LATER,FailureKind.UNCERTAIN)) attempt.outcome="retry"
            throw f
        }
        catch(f:RuntimeException) { failure();throw f }
        finally {
            duration("processing","all",(nanoTime()-attempt.started)/1e9)
            increment(attempt.outcome,interaction.category)
            if(attempt.outcome!="retry") unfinished.remove(id)
            active=previous
        }
    }

    fun action(kind:String?) {
        active?.interaction?.category=when {
            kind==null -> "other"
            kind.startsWith("participation") || kind.startsWith("attendance") -> "participation"
            kind.startsWith("payment") || kind.startsWith("transfer") -> "transfer"
            kind.startsWith("finance") || kind.startsWith("balance") -> "finance"
            kind.startsWith("poll") -> "poll"
            kind.startsWith("form") -> "input"
            kind.contains("training") || kind.contains("player") -> "training"
            kind.contains("admin") || kind.contains("setting") || kind.startsWith("default") -> "settings"
            kind in setOf("menu","groups","groups_back","close_panel","private_link") -> "menu"
            else -> "other"
        }
    }

    /** Mark only an accepted panel/redirect/deletion, not a spinner acknowledgement. */
    fun responded() {
        val attempt=active ?: return
        if(attempt.responded || attempt.outcome !in setOf("ignored","success")) return
        attempt.responded=true
        attempt.outcome="success"
        if(!attempt.interaction.responseRecorded) {
            duration("response",attempt.interaction.category,(clock.millis()-attempt.interaction.received)/1000.0)
            attempt.interaction.responseRecorded=true
        }
    }

    fun failure(kind:FailureKind?=null) {
        active?.outcome=when(kind) {
            FailureKind.RETRY_LATER -> "retry"
            FailureKind.UNCERTAIN -> "uncertain"
            null -> "error"
            else -> "rejected"
        }
    }

    fun <T> api(method:String,block:()->T):T {
        if(!enabled || method=="getUpdates") return block()
        val start=nanoTime()
        // A closed label set prevents accidental request data or new method names becoming labels.
        val label=method.takeIf { it in methods } ?: "other"
        try { return block() }
        catch(f:TelegramFailure) {
            increment("api_${f.kind.name.lowercase()}","all")
            throw f
        } finally { duration("api",label,(nanoTime()-start)/1e9) }
    }

    private fun increment(outcome:String,category:String) {
        val key=outcome to category
        counters[key]=(counters[key] ?: 0)+1
    }
    private fun duration(phase:String,label:String,seconds:Double) {
        if(seconds.isFinite() && seconds>=0) histograms.getOrPut(phase to label) { Histogram() }.observe(seconds)
    }

    internal fun exposition():String=buildString {
        append("# TYPE tennis_ui_sample_timestamp_seconds gauge\ntennis_ui_sample_timestamp_seconds ${clock.millis()/1000.0}\n")
        append("# TYPE tennis_ui_started_timestamp_seconds gauge\ntennis_ui_started_timestamp_seconds $startedAt\n")
        append("# TYPE tennis_ui_attempts_total counter\n")
        counters.forEach { (key,value) -> append("tennis_ui_attempts_total{outcome=\"${key.first}\",category=\"${key.second}\"} $value\n") }
        for(phase in listOf("response","queue","processing","api")) {
            val name="tennis_ui_${phase}_seconds"
            append("# TYPE $name histogram\n")
            histograms.filterKeys { it.first==phase }.forEach { (key,histogram) ->
                val label=if(phase=="api") "method" else "category"
                var count=0L
                histogram.counts.forEachIndexed { index,n ->
                    count+=n
                    val bound=if(index==bounds.size) "+Inf" else bounds[index].toString()
                    append("${name}_bucket{$label=\"${key.second}\",le=\"$bound\"} $count\n")
                }
                append("${name}_count{$label=\"${key.second}\"} $count\n")
                append("${name}_sum{$label=\"${key.second}\"} ${histogram.sum}\n")
            }
        }
    }

    /** Called on the bot loop: no HTTP listener, extra thread or write per click. */
    fun publish(path:Path,write:(Path,String)->Unit=::atomicWrite):Boolean {
        if(!enabled) return true
        val now=clock.millis()
        if(lastPublish?.let { now>=it && now-it<30_000 }==true) return true
        lastPublish=now // Rate-limit failed writes too; telemetry must never pin the event loop.
        return runCatching { write(path,exposition()) }.isSuccess
    }

    companion object {
        private val categories=setOf("other","participation","transfer","finance","poll","input","training","settings","menu")
        private val bounds=doubleArrayOf(.1,.25,.5,1.0,2.0,5.0,10.0,30.0,60.0,300.0,3600.0,86400.0)
        private val methods=setOf("getMe","getChatMember","getChatAdministrators","sendMessage","sendRichMessage","sendPoll",
            "stopPoll","editMessageText","editMessageReplyMarkup","editEphemeralMessageText","deleteEphemeralMessage",
            "deleteMessage","pinChatMessage","unpinChatMessage","answerCallbackQuery")
        private fun atomicWrite(path:Path,text:String) {
            val target=path.toAbsolutePath()
            val temporary=Files.createTempFile(target.parent,".ui-metrics-",".tmp")
            try {
                Files.writeString(temporary,text)
                Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temporary) }
        }
    }
}
