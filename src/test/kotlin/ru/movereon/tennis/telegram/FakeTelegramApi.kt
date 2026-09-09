package ru.movereon.tennis.telegram

class FakeTelegramApi : TelegramApi {
    val bot = TgUser(900,true,"Расчёты","tennis_test_bot")
    val members = mutableMapOf<Pair<Long,Long>,TgMember>()
    val messages = linkedMapOf<Pair<Long,Long>,TgMessage>()
    val prompts = mutableSetOf<Pair<Long,Long>>()
    val sent = mutableListOf<TgMessage>()
    val membershipCalls = mutableListOf<Pair<Long,Long>>()
    var memberFailure = false
    var memberRejected = false
    var failChat: Long? = null
    var acceptThenFail: ((Long,String)->Boolean)? = null
    private var nextMessage = 1L
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
