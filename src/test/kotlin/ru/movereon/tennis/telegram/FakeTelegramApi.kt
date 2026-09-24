package ru.movereon.tennis.telegram

class FakeTelegramApi : TelegramApi {
    val bot = TgUser(900,true,"Расчёты","tennis_test_bot")
    val members = mutableMapOf<Pair<Long,Long>,TgMember>()
    val messages = linkedMapOf<Pair<Long,Long>,TgMessage>()
    val prompts = mutableSetOf<Pair<Long,Long>>()
    val sent = mutableListOf<TgMessage>()
    val deleted=mutableListOf<Pair<Long,Long>>()
    var deleteFailure:TelegramFailure?=null
    override fun delete(chatId:Long,messageId:Long) { deleteFailure?.let { throw it };deleted+=chatId to messageId;messages.remove(chatId to messageId);richMessages.remove(chatId to messageId) }
    val pinned=mutableListOf<Pair<Long,Long>>()
    val silentPins=mutableListOf<Pair<Long,Long>>()
    val richMessages=mutableMapOf<Pair<Long,Long>,String>()
    val richPhotos=mutableMapOf<Pair<Long,Long>,String?>()
    var pinFailure:TelegramFailure?=null
    var pinAttempts=0
    val unpinned=mutableListOf<Pair<Long,Long>>()
    val unpinAttempts=mutableListOf<Pair<Long,Long>>()
    var unpinFailure:TelegramFailure?=null
    override fun unpin(chatId:Long,messageId:Long) { unpinAttempts+=chatId to messageId;unpinFailure?.let { throw it };unpinned+=chatId to messageId }

    override fun pin(chatId:Long,messageId:Long,silent:Boolean) { pinAttempts++;pinFailure?.let { throw it };pinned+=chatId to messageId;if(silent) silentPins+=chatId to messageId }
    override fun sendRich(chatId:Long,text:String,html:String,keyboard:TgKeyboard,photoId:String?):TgMessage = send(chatId,text,keyboard).also { richMessages[chatId to it.id]=html;richPhotos[chatId to it.id]=photoId }
    override fun editRich(chatId:Long,messageId:Long,text:String,html:String,keyboard:TgKeyboard,photoId:String?) { edit(chatId,messageId,text,keyboard);richMessages[chatId to messageId]=html;richPhotos[chatId to messageId]=photoId }

    val privateRedirects=mutableListOf<Pair<String,String>>()
    override fun openPrivate(callbackId:String,url:String) { privateRedirects+=callbackId to url }
    val membershipCalls = mutableListOf<Pair<Long,Long>>()
    var memberFailure = false
    var memberRejected = false
    var failChat: Long? = null
    var acceptThenFail: ((Long,String)->Boolean)? = null
    private var nextMessage = 1L
    val pollOptions=mutableMapOf<String,List<String>>()
    val pollPhotos=mutableMapOf<String,String?>()
    var pollFailure:TelegramFailure?=null
    var stopFailure:TelegramFailure?=null
    override fun sendPoll(chatId:Long,question:String,options:List<String>,keyboard:TgKeyboard,photoId:String?):TgMessage {
        val m=send(chatId,question,keyboard).let { it.copy(poll=TgPoll("poll-${it.id}")) }
        messages[chatId to m.id]=m;pollOptions[m.poll!!.id]=options;pollPhotos[m.poll.id]=photoId
        pollFailure?.let { throw it }
        return m
    }
    override fun stopPoll(chatId:Long,messageId:Long) {
        stopFailure?.let { throw it }
        val m=messages.getValue(chatId to messageId)
        messages[chatId to messageId]=m.copy(poll=m.poll!!.copy(isClosed=true))
    }
    override fun editKeyboard(chatId:Long,messageId:Long,keyboard:TgKeyboard) {
        val m=messages.getValue(chatId to messageId);messages[chatId to messageId]=m.copy(keyboard=keyboard)
    }
    override fun me() = bot
    override fun administrators(chatId:Long) = members.filter { it.key.first==chatId && it.value.admin }
        .map { (key,member) -> member.copy(user=member.user ?: TgUser(key.second,firstName="User ${key.second}")) }
    override fun updates(offset: Long?,timeout: Int) = emptyList<TgUpdate>()
    override fun member(chatId: Long,userId: Long): TgMember {
        membershipCalls += chatId to userId
        if(memberFailure) throw TelegramFailure(FailureKind.UNCERTAIN)
        if(memberRejected) throw TelegramFailure(FailureKind.REJECTED,400)
        return members[chatId to userId] ?: TgMember("left")
    }
    override fun send(chatId: Long,text: String,keyboard: TgKeyboard?,forceReply: Boolean): TgMessage {
        if(failChat == chatId) throw TelegramFailure(FailureKind.REJECTED,403)
        val result = TgMessage(nextMessage++,TgChat(chatId,if(chatId < 0) "supergroup" else "private"),bot,text,keyboard=keyboard)
        messages[chatId to result.id] = result
        sent += result
        if(forceReply) prompts += chatId to result.id
        if(acceptThenFail?.invoke(chatId,text) == true) { acceptThenFail = null; throw TelegramFailure(FailureKind.UNCERTAIN) }
        return result
    }
    override fun edit(chatId: Long,messageId: Long,text: String,keyboard: TgKeyboard?) {
        val old=messages[chatId to messageId] ?: throw TelegramFailure(FailureKind.MESSAGE_MISSING,400)
        messages[chatId to messageId] = old.copy(text=text,keyboard=keyboard)
    }
    override fun answer(callbackId: String,text: String?,alert: Boolean) = Unit
}
