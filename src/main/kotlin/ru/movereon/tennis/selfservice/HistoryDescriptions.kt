package ru.movereon.tennis.selfservice

import ru.movereon.tennis.application.*
import ru.movereon.tennis.core.*
import ru.movereon.tennis.selfservice.Screens.Companion.clean
import ru.movereon.tennis.selfservice.Screens.Companion.date

import kotlinx.serialization.json.*
import ru.movereon.tennis.selfservice.Screens.Companion.hours

/** Formats immutable audit snapshots, including retired training states. */
internal class HistoryDescriptions(private val json:Json,private val shortName:(Long)->String) {
    // Historical JSON is immutable; normalize only the retired phase when reading old snapshots.
    private fun historyTraining(text:String):TrainingRecord {
        val value=json.parseToJsonElement(text).jsonObject
        val compatible=if(value["phase"]?.jsonPrimitive?.content=="REVIEW") JsonObject(value+("phase" to JsonPrimitive("OPEN"))) else value
        return json.decodeFromJsonElement(TrainingRecord.serializer(),compatible)
    }
    fun describe(a: AuditAction): String = when (a.kind) {
        "SetDefaultStartTime" -> "Изменил начало по умолчанию · ${json.decodeFromString<String>(a.after)}"
        "CreateTraining" -> "Опубликовал тренировку"
        "EditTraining" -> {
            val after=historyTraining(a.after)
            val before=historyTraining(requireNotNull(a.before))
            listOfNotNull(if(before.title!=after.title) "Название: ${clean(before.title,100)} → ${clean(after.title,100)}" else null,
                if(before.date!=after.date) "Дата: ${date(before.date)} → ${date(after.date)}" else null,
                if(before.startTime!=after.startTime) "Начало: ${before.startTime} → ${after.startTime}" else null).joinToString("\n")
        }
        "EditTransferAmount" -> {
            val before=json.decodeFromString<MoneyTransfer>(requireNotNull(a.before))
            val after=json.decodeFromString<MoneyTransfer>(a.after)
            "Исправил сумму перевода: ${before.amount} ₽ → ${after.amount} ₽"
        }
        "SetGroupTrainingRule" -> "Изменил настройки гостей или времени группы"
        "UpdateTrainingRules", "SynchronizeTrainingRules" -> {
            val before=historyTraining(requireNotNull(a.before)).rules
            val after=historyTraining(a.after).rules
            (if(a.kind=="SynchronizeTrainingRules") "Автоматически применены настройки группы: " else "Применены настройки группы: ")+
                listOfNotNull(
                    if(before.trackTime!=after.trackTime) if(after.trackTime) "учёт времени включён, сохранённые длительности восстановлены" else "учёт времени выключен, равные доли" else null,
                    if(before.guestsEnabled!=after.guestsEnabled) if(after.guestsEnabled) "добавление гостей разрешено" else "добавление гостей запрещено, записанные сохранены" else null
                ).joinToString("; ")
        }
        "CreateTrainingFromPoll" -> "Создал тренировку из опроса"
        "FinishTraining" -> "Учёл тренировку · ${historyTraining(a.after).players.sumOf { it.paid }} ₽"
        "ReopenTraining" -> if(json.parseToJsonElement(a.after).jsonObject["phase"]?.jsonPrimitive?.content=="REVIEW")
            "Открыл исправление по прежним правилам; расчёт оставался учтённым" else "Открыл тренировку заново; прежний расчёт отменён"
        "MigrateTrainingState" -> "При обновлении бота тренировку открыли заново; прежний расчёт отменён"
        "RemovePlayer" -> "Исключил игрока"
        "CancelTraining" -> "Отменил тренировку и снял её расчёт"
        "RestoreTraining" -> "Восстановил тренировку; расчёт ещё не учтён"
        "SendPayment", "SendOtherPayment" -> "Отметил отправку платежа"
        "RecordAdminPayment" -> "Администратор записал платёж"
        "ReceivePayment" -> "Подтвердил получение платежа"
        "RecordTransfer" -> "Записал перевод"
        "ChangeTransfer" -> "Изменил состояние перевода"
        "SetAdministrator" -> if (a.after == "true") "Назначил администратора бота" else "Снял назначение администратора"
        "AddPlayers" -> {
            val after=historyTraining(a.after)
            val before=a.before?.let { historyTraining(it) }
            val added=after.players.filter { p -> p.playing && before?.players?.any { it.userId==p.userId && it.playing }!=true }
            "Добавил игроков: ${added.size} · "+added.take(3).joinToString(", ") { shortName(it.userId) }+if(added.size>3) ", ещё ${added.size-3}" else ""
        }
        "ChangeAttendance", "SaveAttendance" -> {
            val after = historyTraining(a.after)
            val before = a.before?.let { historyTraining(it) }
            val changed = after.players.filter { p -> before?.players?.find { it.userId == p.userId } != p }
            changed.joinToString("\n") { p ->
                val old=before?.players?.find { it.userId==p.userId }
                "${shortName(p.userId)}:\n"+listOfNotNull(
                    if(old?.playing!=p.playing) "Участие: ${if(old?.playing==true) "играл" else "не отмечено"} → ${if(p.playing) "играл" else "не играл"}" else null,
                    if(after.rules.trackTime && (old==null || old.minutes!=p.minutes)) "Время: ${old?.let { hours(it.minutes) } ?: "—"} → ${hours(p.minutes)}" else null,
                    if((old?.guestCount ?: 0)!=p.guestCount) "Гостей: ${old?.guestCount ?: 0} → ${p.guestCount}" else null,
                    if(after.rules.trackTime && old?.guestMinutes!=p.guestMinutes && (p.guestCount>0 || (old?.guestCount ?: 0)>0)) "Время гостей: ${hours(old?.guestMinutes ?: 0)} → ${hours(p.guestMinutes)}" else null,
                    if(old==null || old.paid!=p.paid) "Оплата: ${old?.paid ?: 0} ₽ → ${p.paid} ₽" else null).joinToString("\n")
            }.ifEmpty { "Подтвердил прежние время и оплату" }
        }
        else -> "Изменение записи"
    }
}
