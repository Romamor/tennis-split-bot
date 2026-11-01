package ru.movereon.tennis.telegram

/** Opt-in metadata only: no accounts, chat IDs, tokens, messages or financial values. */
class UiDiagnostics(val enabled:Boolean=false,private val sink:(String)->Unit={ System.err.println(it) }) {
    fun update(id:Long,stage:String,action:String?=null,detail:String?=null) {
        if(!enabled) return
        emit("UI update=$id stage=${code(stage)}"+
            (action?.let { " action=${code(it)}" } ?: "")+
            (detail?.let { " detail=${code(it)}" } ?: ""))
    }
    fun api(method:String,outcome:String) {
        if(enabled && method!="getUpdates") emit("UI api=${code(method)} outcome=${code(outcome)}")
    }
    private fun code(value:String)=value.take(80).replace(Regex("[^A-Za-z0-9_:-]"),"_")
    private fun emit(line:String) { runCatching { sink(line) } }
}
