package ru.movereon.tennis.selfservice

import ru.movereon.tennis.application.*
import ru.movereon.tennis.core.*
import ru.movereon.tennis.telegram.*
import kotlinx.serialization.json.*
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Navigation lists are paged; a training card shows its complete roster. */
class Screens(private val service: SettlementService, private val state: InteractionStore, private val botName: String) {
    private val polls=TrainingPolls(service)
    private val navigationScreens=NavigationScreens(service,polls)
    private val pollScreens=PollScreens(service,polls)
    private val trainingScreens=TrainingScreens(service,state)
    private val financeScreens=FinanceScreens(service)
    data class Output(val text: String, val keyboard: TgKeyboard, val tokens: Set<String>, val richHtml:String?=null,val photoId:String?=null)
    private fun name(id: Long): String = service.account(id).let { u ->
        clean(u.name, 36) + (u.username?.let { " · @${clean(it, 32)}" } ?: "")
    }
    private val historyDescriptions=HistoryDescriptions(state.json,::shortName)
    private fun shortName(id: Long) = clean(service.account(id).name, 36)
    fun render(action: ScreenAction, access: Access?, scope: String, user: Long?, form: InputForm? = null, notice: String? = null, inGroup: Boolean = false, telegramAdmins:Set<Long> = emptySet(), groupOptions:List<GroupOption> = emptyList()): Output = state.buttonBatch {
        renderInside(action,access,scope,user,form,notice,inGroup,telegramAdmins,groupOptions)
    }
    private fun renderInside(action: ScreenAction, access: Access?, scope: String, user: Long?, form: InputForm?, notice: String?, inGroup: Boolean, telegramAdmins:Set<Long>, groupOptions:List<GroupOption>):Output {
        return with(ScreenLayout(action,state,botName,scope,user,inGroup)) {
        val a = access
        if(action.kind in NavigationScreens.kinds || action.kind in PollScreens.kinds) a?.let(service::requireKnownGroupMember)
        if(action.kind in NavigationScreens.kinds) return@with finishScreen(this,navigationScreens.render(this,a,groupOptions),null,notice,inGroup)
        if(action.kind in PollScreens.kinds) return@with finishScreen(this,pollScreens.render(this,a),null,notice,inGroup)
        val accounts=if(a!=null) service.groupAccounts(a) else emptyList()
        val accountCache=accounts.associateBy { it.id }.toMutableMap()
        fun account(id:Long)=accountCache.getOrPut(id) { service.account(id) }
        val collisions=accounts.groupBy { clean(it.name,36).lowercase() }.filterValues { it.size>1 }
        val labels=accounts.associate { account ->
            account.id to (clean(account.name,36)+if(clean(account.name,36).lowercase() in collisions && !account.username.isNullOrBlank()) " · @${clean(account.username,32)}" else "")
        }
        val ambiguous=labels.entries.groupBy { it.value.lowercase() }.filterValues { it.size>1 }.values.flatten().map { it.key }.toSet()
        fun label(id:Long)=labels[id] ?: shortName(id)
        fun personRow(id:Long,text:String,target:ScreenAction) {
            val current=if(action.kind=="add_players" && form!=null) action.copy(option=state.formSignature(form)) else action
            val destination=if(id in ambiguous) ScreenAction("profile_preview",action.group,action.id,page=action.page,user=id,back=current,resume=target) else target
            row(text+(if(id in ambiguous) " · #${accounts.filter { labels[it.id]==labels[id] }.sortedBy { it.id }.indexOfFirst { it.id==id }+1}" else ""),destination)
        }
        var richHtml:String?=null
        var photoId:String?=null
        val text = when (action.kind) {
            "profile_preview" -> {
                rows+=listOf(TgButton("Открыть профиль Telegram",url="tg://user?id=${action.user}"))
                row("Выбрать этого участника",requireNotNull(action.resume))
                back(next("roster",option="players"))
                "${name(action.user)}\nВ списке есть одинаковые имена. Проверь профиль перед выбором."
            }
            "exit_confirm" -> {
                row("Продолжить ввод",next("exit_continue"))
                row("Сбросить и выйти",next("exit_discard"))
                "Есть несохранённые изменения. Сбросить их и выйти?"
            }
            "my_trainings" -> {
                val data=service.myTrainings(requireNotNull(user),action.page);val p=data.page
                p.items.forEach { row("${date(it.date)} · ${clean(it.title,34)} · ${phase(it.phase)}",ScreenAction("my_training",it.groupId,it.id,back=ScreenAction("my_trainings",0,page=p.index))) }
                pages(p.index,p.pages,ScreenAction("my_trainings",0,page=p.index))
                row("Назад",ScreenAction("menu",0))
                "Тренировок: ${data.completedCount}\nВремя: ${hours(data.minutes)}\nПотрачено денег: ${data.paid} ₽"
            }
            "trainings" -> {
                val p = service.trainings(requireNotNull(a), action.page, mine = action.option == "mine", unfinished = action.option == "open")
                p.items.forEach { row("${date(it.date)} · ${clean(it.title, 28)} · ${phase(it.phase)}", next("training", it.id, option = "").copy(back=action.copy(page=p.index))) }
                pages(p.index, p.pages)
                menu()
                (if (action.option == "mine") "Мои тренировки" else "Тренировки группы") + " · ${p.total}" + if (p.total == 0) "\nЗаписей пока нет." else ""
            }
            in FinanceScreens.kinds -> {
                val content=financeScreens.render(this,a,::account,::personRow)
                richHtml=content.html;content.text
            }
            in TrainingScreens.kinds -> {
                val content=trainingScreens.render(this,a,::personRow,::label,::account)
                richHtml=content.html;photoId=content.photoId
                content.text
            }
            "player" -> {
                val t = service.training(requireNotNull(a), action.id)
                val target = action.user.takeIf { it > 0 } ?: a.userId
                checkAccounting(target == a.userId || service.canEdit(a,t), ErrorCode.FORBIDDEN, "Можно менять только свои данные")
                val stored = t.players.firstOrNull { it.userId == target }
                val draft = state.attendanceDraft(a.userId,a.groupId)?.takeIf { it.training==t.id && it.user==target }
                val compatible=draft==null || runCatching { t.rules.validate(draft.value,stored) }.isSuccess
                val player = draft?.value ?: stored
                if (t.phase == TrainingPhase.OPEN) {
                    fun change(label: String, type: AttendanceChange, value: Long = 0) = button(label, next("change", target = target, value = value, option = type.name))
                    if (player?.playing != true) rows += listOf(change(if(t.rules.trackTime) "Присоединиться · 0 ч" else "Присоединиться", AttendanceChange.JOIN))
                    else {
                        if(t.rules.trackTime) rows += listOf(change("−0,5 ч", AttendanceChange.ADJUST_MINUTES, -30), change("+0,5 ч", AttendanceChange.ADJUST_MINUTES, 30))
                        if(t.rules.guestsEnabled) rows += listOf(change("+1 гость",AttendanceChange.ADJUST_GUESTS,1))
                        if(player.guestCount>0) rows += listOf(change("−1 гость",AttendanceChange.ADJUST_GUESTS,-1))
                    }
                    if (player!=null && (player.playing || player.paid>0)) {
                        if (player.paid == 0L) rows += listOf(change("Платил · 300 ₽", AttendanceChange.MARK_PAID))
                        else rows += listOf(change("−50 ₽", AttendanceChange.ADJUST_PAID, -50), change("+50 ₽", AttendanceChange.ADJUST_PAID, 50))
                        if (!inGroup) row("Другая сумма", next("ask_paid", target = target))
                        if(player.playing) rows += listOf(change("Не участвую", AttendanceChange.LEAVE))
                    }
                    if (draft != null) {
                        if (draft.expected==stored && compatible) row("Всё правильно",next("save_attendance",target=target,option=state.draftSignature(draft)))
                        else {
                            row("Загрузить сохранённые данные",next("reload_attendance",target=target))
                            row("Убрать несохранённый ввод",next("discard_attendance",target=target,option=state.draftSignature(draft)))
                        }
                    }
                } else if (draft!=null) {
                    row("Убрать несохранённый ввод",next("discard_attendance",target=target,option=state.draftSignature(draft)))
                } else if (inGroup) row("Закрыть",next("close_panel"))
                if(draft?.returnPage!=null) row("К составу",draft.origin ?: next("roster",page=draft.returnPage,target=0,option="players"))
                else row("Карточка тренировки", draft?.origin ?: action.back ?: next("training", target = 0, option = ""))
                "${clean(t.title, 60)} · ${date(t.date)}\n${name(target)}\n" +
                    (if (player?.playing == true) if(t.rules.trackTime) "Играл ${hours(player.minutes)}" else "Играл" else "Не играл") +
                    (if ((player?.guestCount ?: 0) > 0) " · гостей: ${player!!.guestCount}"+(if(t.rules.trackTime) ", по ${hours(player.guestMinutes)}" else "") else "") +
                    "\nОплатил стол: ${player?.paid ?: 0} ₽" +
                    (if (draft!=null && (draft.expected!=stored || !compatible)) "\nСохранённые данные уже изменились. Обнови форму перед подтверждением." else if (draft!=null) "\nСохраним после «Всё правильно»." else "") +
                    if (t.phase == TrainingPhase.CLOSED) "\nТренировка учтена. Создатель или администратор может выбрать «Открыть заново»: прежний расчёт будет отменён." else ""
            }
            "leave_confirm" -> {
                val t = service.training(requireNotNull(a), action.id)
                val target = action.user.takeIf { it > 0 } ?: a.userId
                checkAccounting(target == a.userId || service.canEdit(a,t), ErrorCode.FORBIDDEN, "Можно менять только свои данные")
                val pending=state.attendanceDraft(a.userId,a.groupId)?.takeIf { it.training==t.id && it.user==target }
                val p = requireNotNull(pending?.value ?: t.players.firstOrNull { it.userId == target }) { "Участник не найден в тренировке" }
                row(if (p.paid > 0) "Не играл — убрать ${p.paid} ₽" else "Подтвердить: не играл",
                    next("change", target = target, value = p.paid,
                        option = if (p.paid > 0) AttendanceChange.LEAVE_AND_CLEAR_PAYMENT.name else AttendanceChange.LEAVE.name))
                row("Назад", next("player", target = target))
                "${name(target)}\nНе играл в этой тренировке?\n" +
                    if (p.paid > 0) "Участие${if (p.guestMinutes > 0) " и гость +1" else ""} будут убраны вместе с оплатой стола ${p.paid} ₽. Переводы между людьми сохранятся."
                    else "Участие будет убрано."
            }
            "roster" -> {
                val t=service.training(requireNotNull(a),action.id)
                checkAccounting(service.canEdit(a,t),ErrorCode.FORBIDDEN,"Редактировать тренировку может её создатель или администратор этой группы")
                val players=t.players.filter { it.playing || it.paid>0 }.sortedBy { it.ordinal }
                val index=action.page.coerceIn(0,maxOf(0,(players.size-1)/8))
                players.drop(index*8).take(8).forEach { p ->
                    personRow(p.userId,"${label(p.userId)} · ${if(p.playing) (if(t.rules.trackTime) hours(p.minutes) else "Играл") else "Не играл"}${if(p.guestCount>0) " · гостей: ${p.guestCount}" else ""} · ${p.paid} ₽",
                        next("player",page=index,target=p.userId,option="roster").copy(back=action.copy(page=index)))
                }
                pages(index,maxOf(1,(players.size+7)/8))
                row("Участники группы",next("add_players",option="").copy(back=action.copy(page=index)))
                back(next("training",option=""))
                "${clean(t.title,60)} · ${date(t.date)}\nВ составе: ${players.count { it.playing }} · гостей: ${players.sumOf { it.guestCount }}\nОплачено: ${players.sumOf { it.paid }} ₽\nВыбери игрока для правки или добавь людей через «Участники группы»."
            }
            "add_players" -> {
                val t=service.training(requireNotNull(a),action.id)
                checkAccounting(service.canEdit(a,t),ErrorCode.FORBIDDEN,"Редактировать тренировку может её создатель или администратор этой группы")
                val f=requireNotNull(form)
                val playing=t.players.filter { it.playing }.map { it.userId }.toSet()
                val order=f.order ?: service.rosterIds(a).filter { it !in playing }
                val index=action.page.coerceIn(0,maxOf(0,(order.size-1)/8))
                val p=Page(order.drop(index*8).take(8).map { service.groupAccount(a,it) },order.size,index)
                val selected=f.selectedUsers.filter { it !in playing }.toSet()
                val signature=state.formSignature(f)
                p.items.forEach { person ->
                    personRow(person.id,"${if(person.id in playing) "✓ Уже в составе ·" else if(person.id in selected) "✅" else "▫️"} ${label(person.id)}",
                        next("toggle_player",page=p.index,target=person.id,option=signature))
                }
                pages(p.index,p.pages,action.copy(option=signature))
                if(selected.isNotEmpty()) row("Добавить · ${selected.size}",next("save_players",page=p.index,version=t.version,option=signature))
                row("Добавить человека",next("pick_players",page=p.index,option=signature))
                row("Отмена",f.origin ?: next("roster",option="players"))
                "${clean(t.title,60)} · ${date(t.date)}\nУчастники группы · выбрано: ${selected.size}\nОтметь нескольких людей на любых страницах. Добавим по 1 ч и 0 ₽ после подтверждения."
            }
            "administrators", "admin_candidates" -> {
                val p=service.administrators(requireNotNull(a),telegramAdmins,action.kind=="admin_candidates",action.page)
                p.items.forEach { entry ->
                    val role=when(entry.role) { GroupRole.SUPERADMIN -> "Суперадмин · Telegram";GroupRole.ADMIN -> "Админ бота";GroupRole.MEMBER -> "Участник" }
                    personRow(entry.account.id,"${label(entry.account.id)} · $role",next("admin_person",target=entry.account.id).copy(back=action.copy(page=p.index)))
                }
                pages(p.index,p.pages)
                if (action.kind=="administrators") {
                    row("Назначить администратора",next("admin_candidates"))
                    row("⬅️ Назад",ScreenAction("poll_settings",action.group))
                } else back(next("administrators"))
                if (action.kind=="administrators") "Администраторы группы · ${p.total}\nСуперадмины получают права из Telegram. Назначенные админы бота управляют тренировками, но не назначают других."
                else "Кого назначить?\nВ этом списке только участники без административной роли."
            }
            "admin_person" -> {
                requireNotNull(a)
                checkAccounting(a.telegramAdmin, ErrorCode.FORBIDDEN, "Назначать могут администраторы Telegram-группы")
                val appointed = service.isAdmin(Access(a.groupId, action.user))
                val superior=action.user in telegramAdmins
                if (appointed) row(if(superior) "Снять дополнительное назначение" else "Снять назначение",next("set_admin",value=0))
                else if (!superior) row("Назначить администратором",next("set_admin",value=1))
                row("Назад", next("administrators"))
                "${name(action.user)}\n" + when {
                    superior -> "Суперадминистратор · администратор Telegram-группы.\nСтаршие права меняются в настройках группы." + if (appointed) "\nТакже есть отдельное назначение администратором бота." else ""
                    appointed -> "Администратор бота · назначен в этой группе. Можно снять назначение."
                    else -> "Участник · административных прав нет."
                }
            }
            "preview_finish" -> {
                val t = service.training(requireNotNull(a), action.id)
                checkAccounting(service.canFinish(a,t), ErrorCode.FORBIDDEN, "Учесть тренировку может её создатель или администратор этой группы")
                val input=t.calculation()
                require(input.players.isNotEmpty()) { "Укажи наигранное время хотя бы одного участника" }
                require(input.payments.isNotEmpty()) { "Укажи оплату стола" }
                val calc = calculateTraining(input)
                val p = calc.entries
                val index = action.page.coerceIn(0, maxOf(0, (p.size - 1) / 8))
                pages(index, maxOf(1, (p.size + 7) / 8))
                row("✅ Подтвердить учёт", next("finish", version = t.version))
                back(next("training"))
                "Проверка расчёта · ${date(t.date)}\nВсего: ${calc.total} ₽\n\n" + p.drop(index * 8).take(8).joinToString("\n") {
                    "${shortName(it.participant.value.toLong())}: ${signed(it.amount)} ₽"
                } + "\n\nЭто результат этой тренировки. При применении правок он заменит прежний."
            }
            "cancel_confirm" -> {
                row("Да, отменить тренировку", next("cancel"))
                back(next("training"))
                "Отменить тренировку? Её влияние на балансы будет снято. Реальные переводы сохранятся."
            }
            "recover_confirm" -> {
                row("Опубликовать карточку заново", next("recover_card"))
                back(next("training"))
                "Если карточки в группе нет, её можно опубликовать заново. При неизвестном результате прежней отправки в группе могла остаться старая копия. Данные тренировки не дублируются."
            }
            "form" -> {
                val f = requireNotNull(form)
                if(PaymentInput.isForm(f)) {
                    val content=financeScreens.renderForm(this,requireNotNull(a),f,state.formSignature(f),::account,::personRow)
                    richHtml=content.html
                    content.text
                } else {
                fun formAction(kind: String) = next(kind, option = state.formSignature(f))
                when (f.kind) {
                    "title" -> { row("Оставить «${clean(f.title, 30)}»", formAction("form_next")); "Напиши название тренировки." }
                    "date" -> {
                        val d=LocalDate.parse(f.date)
                        fun adjust(part:Long,delta:Long)=formAction("form_date_adjust").copy(user=part,value=delta)
                        rows+=listOf(button("▲ День",adjust(0,1)),button("▲ Месяц",adjust(1,1)),button("▲ Год",adjust(2,1)))
                        rows+=listOf(button("%02d".format(d.dayOfMonth),adjust(0,0)),button("%02d".format(d.monthValue),adjust(1,0)),button(d.year.toString(),adjust(2,0)))
                        rows+=listOf(button("▼ День",adjust(0,-1)),button("▼ Месяц",adjust(1,-1)),button("▼ Год",adjust(2,-1)))
                        row("Продолжить · ${date(f.date)}",formAction("form_next"))
                        "Дата тренировки\nВыбери дату стрелками или напиши ДД.ММ.ГГГГ."
                    }
                    "time", "default_time" -> {
                        fun adjust(delta:Long)=formAction("form_time_adjust").copy(value=delta)
                        val (hours,minutes)=f.time.split(":")
                        rows+=listOf(button("▲ Часы",adjust(60)),button("▲ Минуты",adjust(30)))
                        rows+=listOf(button(hours,adjust(0)),button(minutes,adjust(0)))
                        rows+=listOf(button("▼ Часы",adjust(-60)),button("▼ Минуты",adjust(-30)))
                        row(if(f.kind=="default_time") "✅ Сохранить время" else "Продолжить · ${f.time}",formAction(if(f.kind=="default_time") "save_default_time" else "form_next"))
                        "${if(f.kind=="default_time") "Начало по умолчанию" else "Начало тренировки"}: ${f.time}\nМожно написать время в формате ЧЧ:ММ."
                    }
                    "group" -> {
                        val choices=groupOptions.filter { it.canPublish && (f.pollId.isEmpty() || polls.enabled(it.group.id)) }
                        val index=f.page.coerceIn(0,maxOf(0,(choices.size-1)/8))
                        choices.drop(index*8).take(8).forEach { row(clean(it.group.title,60),formAction("form_group_select").copy(value=it.group.id)) }
                        pages(index,maxOf(1,(choices.size+7)/8),formAction("form_group_page"))
                        "Выбери группу для тренировки."+if(choices.isEmpty()) "\nНужна группа, в которой состоишь ты и бот с правами администратора." else ""
                    }
                    "default_title" -> {
                        row("Сохранить название",formAction("save_default_title"))
                        "Название по умолчанию: ${clean(f.title,100)}\nНапиши название тренировки."
                    }
                    "poll_decline" -> {
                        row("Оставить «${clean(f.declineLabel,35)}»",formAction("form_next"))
                        "Напиши четвёртый вариант опроса или оставь «Не приду». Можно свой несмешной вариант.\nЭтот ответ всегда означает отказ от участия; бот его не сохраняет."
                    }
                    "poll_photo" -> "Пришли одно фото в этот чат. Оно появится в самом опросе над вариантами ответа."
                    "ready" -> {
                        row(if (f.training.isEmpty()) "Опубликовать" else "Сохранить изменения", formAction(if(f.pollId.isNotEmpty()) "save_poll" else "save_training"))
                        if(f.pollId.isNotEmpty()) {
                            row(if(f.pollPhotoId==null) "🖼 Добавить фото" else "🖼 Заменить фото",formAction("form_photo"))
                            if(f.pollPhotoId!=null) row("Убрать фото",formAction("form_photo_remove"))
                        }
                        if(f.training.isNotEmpty()) row("Изменить название / время", formAction("form_restart"))
                        "${clean(f.title, 100)}\n${date(f.date)} · ${f.time}\n" + if(f.pollId.isNotEmpty()) "Группа: ${clean(service.group(requireNotNull(f.publishGroup)).title,60)}\n\n"+
                            TrainingPoll(requireNotNull(f.publishGroup),f.pollId,f.title,f.date,f.time,f.declineLabel,requireNotNull(user),null,null,"PENDING",false,null,null).options().joinToString("\n")+
                            (if(f.pollPhotoId!=null) "\n\nФото: добавлено." else "")+
                            "\n\nБудет опубликован и закреплён неанонимный опрос. Тренировка появится после завершения сбора." else if (f.training.isEmpty()) "Карточка появится в группе и будет закреплена с уведомлением участников. Для закрепления боту нужно соответствующее право." else "Данные изменятся в существующей тренировке."
                    }
                    "paid" -> "${name(f.user)}\nНапиши общую сумму оплаты стола в рублях. Можно 0."
                    "pick_account", "pick_players", "pick_add_player" -> "Выбери реальный аккаунт кнопкой под строкой ввода. Он появится в составе этой группы."
                    else -> error("Unknown input form")
                }.also {
                    if(f.kind in setOf("default_title","default_time")) {
                        rows+=listOf(button("Назад",ScreenAction("training_settings",0)),button("Меню",ScreenAction("menu",0)))
                    } else if(f.training.isEmpty() && f.kind in setOf("title","date","time","poll_decline","poll_photo","group","ready")) {
                        rows.add(buildList<TgButton> {
                            if(f.kind!="title") add(button("Назад",formAction("form_back")))
                            add(button("Отмена",formAction("form_cancel")))
                        })
                    } else if(f.kind=="pick_add_player") row("Назад",requireNotNull(f.origin))
                    else if(f.kind=="pick_players") row("Назад к выбору",next("add_players",id=f.training,page=f.page,option=state.formSignature(f)))
                    else row("Отмена",f.origin ?: next(if (f.training.isNotEmpty()) "training" else "menu", id = f.training))
                }
                }
            }
            "history" -> {
                val p = service.history(requireNotNull(a), action.id.takeIf { it.isNotEmpty() }, action.page)
                val historyZone=ZoneId.of(service.group(action.group).timeZone)
                pages(p.index, p.pages)
                back(next(if (action.id.isEmpty()) "menu" else "training"))
                "История изменений · ${p.total}\n\n" + p.items.joinToString("\n\n") { "${Instant.parse(it.occurredAt).atZone(historyZone).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))} · ${if(it.kind=="MigrateTrainingState") "Обновление бота" else shortName(it.actorId)}\n${historyDescriptions.describe(it).take(330)}" }
            }
            else -> error("Unknown screen: ${action.kind}")
        }
        finishScreen(this,text,richHtml,notice,inGroup,photoId)
        }
    }
    private fun finishScreen(layout:ScreenLayout,text:String,richHtml:String?,notice:String?,inGroup:Boolean,photoId:String?=null):Output=with(layout) {
        if (inGroup && action.kind !in setOf("public","player","exit_confirm","participation","participation_time","participation_payment")) row("Закрыть", next("close_panel"))
        // No arbitrary user content can grow a Telegram message beyond the documented limit.
        val result = (notice?.let { "${clean(it, 220)}\n\n" } ?: "") + text
        check(result.length <= if(richHtml==null) 4096 else 32768) { "Экран превышает допустимую длину" }
        Output(result, keyboard(), tokens,richHtml?.let { (notice?.let { n -> "<p>${TrainingCard.escape(clean(n,220))}</p>" } ?: "")+it },photoId)
    }
    companion object {
        val privateActions = FinanceScreens.kinds + PaymentInput.actions + setOf("group_rule_save","new_poll","poll_list","poll_settings","poll_setting_save","poll_retry","finance_send_save","finance_receive_save","training_status","set_training_status","add_player_list","exclude_player_list","manage_players","add_player","remove_player","pick_add_player","my_trainings","my_training","training_settings","default_title","save_default_title","settings","default_time","save_default_time","menu", "groups", "trainings", "new", "edit_details", "profile_preview", "ask_paid", "pick_account", "pick_players", "add_players", "toggle_player", "save_players", "roster", "administrators", "admin_candidates", "admin_person", "set_admin", "history")
        fun clean(text: String, length: Int) = text.replace(Regex("[\\r\\n\\t]"), " ").take(length)
        fun hours(minutes: Long) = "${minutes / 60}${if (minutes % 60 == 30L) ",5" else ""} ч"
        fun date(value: String) = LocalDate.parse(value).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
        fun phase(value: TrainingPhase) = when (value) {
            TrainingPhase.OPEN -> "Открыта"
            TrainingPhase.CLOSED -> "Учтена"
            TrainingPhase.CANCELLED -> "Отменена"
        }
        fun signed(value: Long) = if (value > 0) "+$value" else value.toString()
    }
}
