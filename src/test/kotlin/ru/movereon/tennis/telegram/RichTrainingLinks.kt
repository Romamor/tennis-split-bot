package ru.movereon.tennis.telegram

/** Locate a training URL by its visible training row, without inventing keyboard buttons. */
fun FakeTelegramApi.trainingLinks(message:TgMessage):List<TgButton> =
    Regex("<tr>(.*?)</tr>",RegexOption.DOT_MATCHES_ALL).findAll(richMessages[message.chat.id to message.id].orEmpty()).mapNotNull { row ->
        val url=Regex("<a href=\"([^\"]+)\"").find(row.value)?.groupValues?.get(1) ?: return@mapNotNull null
        val label=row.groupValues[1].replace("<br>"," · ").replace("</td>"," · ").replace(Regex("<[^>]*>"),"")
            .replace("&lt;","<").replace("&gt;",">").replace("&quot;","\"").replace("&amp;","&")
        TgButton(label,url=url.replace("&amp;","&"))
    }.toList()
