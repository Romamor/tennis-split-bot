package ru.movereon.tennis.telegram

/** Locate an inline training callback by its visible training row, without inventing keyboard buttons. */
fun FakeTelegramApi.trainingLinks(message:TgMessage):List<TgButton> =
    Regex("<tr>(.*?)</tr>",RegexOption.DOT_MATCHES_ALL).findAll(richMessages[message.chat.id to message.id].orEmpty()).mapNotNull { row ->
        val token=Regex("<tg-button[^>]*data=\"([^\"]+)\"").find(row.value)?.groupValues?.get(1) ?: return@mapNotNull null
        val label=row.groupValues[1].replace("<br>"," · ").replace("</td>"," · ").replace(Regex("<[^>]*>"),"")
            .replace("&lt;","<").replace("&gt;",">").replace("&quot;","\"").replace("&amp;","&")
        TgButton(label,callbackData=token)
    }.toList()
