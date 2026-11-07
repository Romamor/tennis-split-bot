package ru.movereon.tennis.telegram

/** Only group commands addressed to this bot can carry text into the durable inbox. */
internal object IncomingContent {
    fun groupCommand(text:String?,username:String?):String? {
        val token=text?.trim()?.substringBefore(' ') ?: return null
        val command=token.substringBefore('@')
        if(command !in setOf("/start","/menu")) return null
        if('@' in token && (username==null || !token.substringAfter('@').equals(username,true))) return null
        return command
    }
    fun minimize(update:TgUpdate,username:String?):TgUpdate {
        fun minimal(message:TgMessage,callback:Boolean=false):TgMessage {
            val group=message.chat.id<0
            return message.copy(text=when { callback -> null;group -> groupCommand(message.text,username);else -> message.text },
                replyTo=null,keyboard=null,photo=if(group || callback) emptyList() else message.photo)
        }
        return update.copy(message=update.message?.let { minimal(it) },
            callback=update.callback?.let { it.copy(message=it.message?.let { message -> minimal(message,true) }) })
    }
}
