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
    private val trainingScreens=TrainingScreens(service,state)
    private val financeScreens=FinanceScreens(service)
    data class Output(val text: String, val keyboard: TgKeyboard, val tokens: Set<String>, val richHtml:String?=null)
    private fun name(id: Long): String = service.account(id).let { u ->
        clean(u.name, 36) + (u.username?.let { " · @${clean(it, 32)}" } ?: "")
    }
    private fun shortName(id: Long) = clean(service.account(id).name, 36)
    fun render(action: ScreenAction, access: Access?, scope: String, user: Long?, form: InputForm? = null, notice: String? = null, inGroup: Boolean = false, telegramAdmins:Set<Long> = emptySet(), groupOptions:List<GroupOption> = emptyList()): Output = state.buttonBatch {
        renderInside(action,access,scope,user,form,notice,inGroup,telegramAdmins,groupOptions)
    }
    private fun renderInside(action: ScreenAction, access: Access?, scope: String, user: Long?, form: InputForm?, notice: String?, inGroup: Boolean, telegramAdmins:Set<Long>, groupOptions:List<GroupOption>):Output {
        return with(ScreenLayout(action,state,botName,scope,user,inGroup)) {
        val a = access
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
            "groups" -> {
                val choices=groupOptions.filter { when(action.option) { "manage"->it.admin;"administrators"->it.superAdmin;else->true } }
                val index=action.page.coerceIn(0,maxOf(0,(choices.size-1)/8))
                choices.drop(index*8).take(8).forEach { row(clean(it.group.title,60),ScreenAction("select_group",0,value=it.group.id,option=action.option)) }
                pages(index,maxOf(1,(choices.size+7)/8),action.copy(group=0))
                row("Назад",ScreenAction(if(action.option=="administrators") "settings" else "menu",0))
                "Выбери группу."+if(choices.isEmpty()) "\nНет доступных групп." else ""
            }
            "menu" -> {
                requireNotNull(user)
                row("🏓 Мои тренировки",ScreenAction("my_trainings",0))
                row("💰 Мои финансы",ScreenAction("groups",0,option="finance"))
                row("➕ Создать тренировку",ScreenAction("new",0))
                row("⚙️ Настройки",ScreenAction("settings",0))
                if(groupOptions.any { it.admin }) row("📋 Управление тренировками",ScreenAction("groups",0,option="manage"))
                "Что хочешь сделать?"
            }
            "settings" -> {
                row("Тренировка",ScreenAction("training_settings",0))
                if(groupOptions.any { it.superAdmin }) row("Администраторы групп",ScreenAction("groups",0,option="administrators"))
                menu()
                "Настройки"
            }
            "training_settings" -> {
                row("Название",ScreenAction("default_title",0))
                row("Время",ScreenAction("default_time",0))
                rows+=listOf(button("Назад",ScreenAction("settings",0)),button("Меню",ScreenAction("menu",0)))
                "Укажите параметры тренировки по умолчанию"
            }
            "my_trainings" -> {
                val data=service.myTrainings(requireNotNull(user),action.page);val p=data.page
                p.items.forEach { row("${date(it.date)} · ${clean(it.title,34)} · ${phase(it.phase)}",ScreenAction("my_training",it.groupId,it.id,back=ScreenAction("my_trainings",0,page=p.index))) }
                pages(p.index,p.pages,ScreenAction("my_trainings",0,page=p.index))
                row("Назад",ScreenAction("menu",0))
                "Тренировок: ${p.total}\nВремя: ${hours(data.minutes)}\nПотрачено денег: ${data.paid} ₽"
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
                richHtml=content.html
                content.text
            }
            "player" -> {
                val t = service.training(requireNotNull(a), action.id)
                val target = action.user.takeIf { it > 0 } ?: a.userId
                checkAccounting(target == a.userId || service.canEdit(a,t), ErrorCode.FORBIDDEN, "Можно менять только свои данные")
                val stored = t.players.firstOrNull { it.userId == target }
                val draft = state.attendanceDraft(a.userId,a.groupId)?.takeIf { it.training==t.id && it.user==target }
                val player = draft?.value ?: stored
                if (t.phase == TrainingPhase.OPEN) {
                    fun change(label: String, type: AttendanceChange, value: Long = 0) = button(label, next("change", target = target, value = value, option = type.name))
                    if (player?.playing != true) rows += listOf(change("Присоединиться · 0 ч", AttendanceChange.JOIN))
                    else {
                        rows += listOf(change("−0,5 ч", AttendanceChange.ADJUST_MINUTES, -30), change("+0,5 ч", AttendanceChange.ADJUST_MINUTES, 30))
                        rows += listOf(change("+1 гость",AttendanceChange.ADJUST_GUESTS,1))
                        if(player.guestCount>0) rows += listOf(change("−1 гость",AttendanceChange.ADJUST_GUESTS,-1))
                    }
                    if (player!=null && (player.playing || player.paid>0)) {
                        if (player.paid == 0L) rows += listOf(change("Платил · 300 ₽", AttendanceChange.MARK_PAID))
                        else rows += listOf(change("−50 ₽", AttendanceChange.ADJUST_PAID, -50), change("+50 ₽", AttendanceChange.ADJUST_PAID, 50))
                        if (!inGroup) row("Другая сумма", next("ask_paid", target = target))
                        if(player.playing) rows += listOf(change("Не играл · оплату сохранить", AttendanceChange.LEAVE))
                    }
                    if (draft != null) {
                        if (draft.expected==stored) row("Всё правильно",next("save_attendance",target=target,option=state.draftSignature(draft)))
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
                    (if (player?.playing == true) "Играл ${hours(player.minutes)}" else "Не играл") +
                    (if ((player?.guestCount ?: 0) > 0) " · гостей: ${player!!.guestCount}, по ${hours(player.guestMinutes)}" else "") +
                    "\nОплатил стол: ${player?.paid ?: 0} ₽" +
                    (if (draft!=null && draft.expected!=stored) "\nСохранённые данные уже изменились. Обнови форму перед подтверждением." else if (draft!=null) "\nСохраним после «Всё правильно»." else "") +
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
                    personRow(p.userId,"${label(p.userId)} · ${if(p.playing) hours(p.minutes) else "Не играл"}${if(p.guestCount>0) " · гостей: ${p.guestCount}" else ""} · ${p.paid} ₽",
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
            "transfer_people" -> {
                val p=service.roster(requireNotNull(a),action.page,exclude=setOf(a.userId))
                p.items.forEach { person ->
                    personRow(person.account.id,label(person.account.id),next("transfer_direction",target=person.account.id).copy(back=action.copy(page=p.index)))
                }
                pages(p.index,p.pages); back(ScreenAction("debts",action.group)); "С кем рассчитываемся?"
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
                    row("⬅️ Назад",ScreenAction("settings",0))
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
            "balances", "settled" -> {
                val p = service.roster(requireNotNull(a), action.page, balanceOnly = true, settled = action.kind == "settled")
                pages(p.index, p.pages)
                if(action.kind=="settled") row("👥 Баланс группы",action.back?.takeIf { it.kind=="balances" } ?: ScreenAction("balances",action.group,back=ScreenAction("debts",action.group)))
                else row("⚖️ Нулевой баланс",next("settled").copy(back=action))
                back(ScreenAction("debts",action.group))
                (if(action.kind=="settled") "⚖️ Нулевой баланс" else "👥 Баланс группы")+" · ${p.total}\n\n"+
                    p.items.joinToString("\n") { "${clean(it.account.name,48)} · баланс: ${signed(it.balance)} ₽" }
            }
            "debts" -> {
                requireNotNull(a)
                val balances = service.balances(a)
                val transfers = suggestTransfers(balances.mapKeys { ParticipantId(it.key.toString()) }).filter { it.from.value == a.userId.toString() || it.to.value == a.userId.toString() }
                val index = action.page.coerceIn(0, maxOf(0, (transfers.size - 1) / 8))
                transfers.drop(index * 8).take(8).forEach {
                    val from = it.from.value.toLong(); val to = it.to.value.toLong()
                    row("${if (from == a.userId) "Я → ${shortName(to)}" else "${shortName(from)} → я"}: ${it.amount} ₽",
                        next("suggested_transfer", target = if (from == a.userId) to else from, value = it.amount, option = if (from == a.userId) "out" else "in").copy(back=action))
                }
                pages(index, maxOf(1, (transfers.size + 7) / 8))
                row("💸 Записать перевод", next("transfer_people").copy(back=action))
                row("🧾 История переводов",next("transfers").copy(back=action))
                row("👥 Баланс группы",next("balances").copy(back=action))
                menu()
                "Мой баланс: ${signed(balances[a.userId] ?: 0)} ₽\n" + if (transfers.isEmpty()) "Сейчас рассчитываться не с кем." else "Предлагаемые переводы для взаиморасчётов. Отмечай перевод после передачи денег."
            }
            "transfers" -> {
                val p = service.transfers(requireNotNull(a), action.page)
                p.items.forEach { row("${date(it.date)} · ${it.amount} ₽ · ${if (it.from == a.userId) "${shortName(it.to)} ←" else "${shortName(it.from)} →"}", next("transfer", it.id).copy(back=action.copy(page=p.index))) }
                pages(p.index, p.pages); back(ScreenAction("debts",action.group))
                "Мои переводы · ${p.total}"
            }
            "transfer" -> {
                val t = service.transfer(requireNotNull(a), action.id)
                checkAccounting(a.userId in setOf(t.from, t.to) || service.isAdmin(a), ErrorCode.FORBIDDEN, "Перевод доступен его сторонам")
                if (a.userId in setOf(t.from, t.to)) {
                    if(t.status!=PaymentStatus.CANCELLED) row("✏️ Исправить сумму",next("edit_transfer_amount",version=t.version).copy(back=action))
                    if (t.status == PaymentStatus.ACTIVE) row("Уточнить перевод", next("review_transfer", version = t.version))
                    if (t.status == PaymentStatus.REVIEW && t.reviewer == a.userId) {
                        row("Всё верно", next("confirm_transfer", version = t.version))
                        row("Отменить запись", next("cancel_transfer", version = t.version))
                    }
                }
                row("История изменений",next("transfer_history").copy(back=action))
                back(ScreenAction("transfers",action.group,back=ScreenAction("debts",action.group)))
                "${name(t.from)} → ${name(t.to)}\n${t.amount} ₽ · ${date(t.date)}\n" + when (t.status) {
                    PaymentStatus.ACTIVE -> "Учтён"
                    PaymentStatus.REVIEW -> "Уточняем · пока не влияет на баланс"
                    PaymentStatus.CANCELLED -> "Отменён"
                } + if (t.note.isNotBlank()) "\n${clean(t.note, 300)}" else ""
            }
            "transfer_direction" -> {
                row("Я отправил → ${shortName(action.user)}", next("transfer_amount", option = "out").copy(back=action))
                row("Я получил ← ${shortName(action.user)}", next("transfer_amount", option = "in").copy(back=action))
                back(next("transfer_people")); "Кто кому передал деньги?"
            }
            "form" -> {
                val f = requireNotNull(form)
                fun formAction(kind: String) = next(kind, option = state.formSignature(f))
                fun similarText() = if(f.similar.isEmpty()) "" else "\nРанее записано:\n"+f.similar.take(2).joinToString("\n") {
                    val t=service.transfer(requireNotNull(a),it)
                    "${shortName(t.from)} → ${shortName(t.to)} · ${t.amount} ₽ · ${date(t.date)}"
                }
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
                        val choices=groupOptions.filter { it.canPublish }
                        val index=f.page.coerceIn(0,maxOf(0,(choices.size-1)/8))
                        choices.drop(index*8).take(8).forEach { row(clean(it.group.title,60),formAction("form_group_select").copy(value=it.group.id)) }
                        pages(index,maxOf(1,(choices.size+7)/8),formAction("form_group_page"))
                        "Выбери группу для тренировки."+if(choices.isEmpty()) "\nНужна группа, в которой состоишь ты и бот с правами администратора." else ""
                    }
                    "default_title" -> {
                        row("Сохранить название",formAction("save_default_title"))
                        "Название по умолчанию: ${clean(f.title,100)}\nНапиши название тренировки."
                    }
                    "ready" -> {
                        row(if (f.training.isEmpty()) "Опубликовать" else "Сохранить изменения", formAction("save_training"))
                        if(f.training.isNotEmpty()) row("Изменить название / время", formAction("form_restart"))
                        "${clean(f.title, 100)}\n${date(f.date)} · ${f.time}\n" + if (f.training.isEmpty()) "Карточка появится в группе и будет закреплена с уведомлением участников. Для закрепления боту нужно соответствующее право." else "Данные изменятся в существующей тренировке."
                    }
                    "paid" -> "${name(f.user)}\nНапиши общую сумму оплаты стола в рублях. Можно 0."
                    "edit_transfer_amount" -> "Текущая сумма: ${f.amount} ₽\nНапиши исправленную сумму в рублях."
                    "edit_transfer_ready", "edit_transfer_duplicate" -> {
                        row("✅ Сохранить сумму",formAction("save_transfer_amount"))
                        (if(f.kind=="edit_transfer_duplicate") "Похожий перевод за последние 24 часа уже есть. Подтвердить исправление?\n" else "Проверь исправленную сумму:\n")+"${f.amount} ₽"+similarText()
                    }
                    "transfer_amount" -> "${if (f.direction == "out") "Я → ${name(f.user)}" else "${name(f.user)} → я"}\nНапиши сумму уже переданных денег в целых рублях."
                    "transfer_ready", "transfer_duplicate" -> {
                        row(if (f.kind == "transfer_duplicate") "Это ещё один перевод — записать" else "Деньги переданы — записать", formAction("save_transfer"))
                        if (f.kind == "transfer_ready") row("Изменить дату", formAction("transfer_date"))
                        if (f.kind == "transfer_ready") row("Комментарий", formAction("transfer_note"))
                        (if (f.kind == "transfer_duplicate") "Похожий перевод уже записан за последние 24 часа. Это ещё один перевод?\n\n" else "") +
                            "${if (f.direction == "out") "Я → ${name(f.user)}" else "${name(f.user)} → я"}\n${f.amount} ₽ · ${date(f.date)}" + if (f.note.isNotBlank()) "\n${clean(f.note, 300)}"+similarText() else similarText()
                    }
                    "transfer_date" -> "Напиши дату перевода: ДД.ММ.ГГГГ."
                    "transfer_note" -> "Напиши комментарий к переводу, не длиннее 300 символов."
                    "pick_account", "pick_players", "pick_add_player" -> "Выбери реальный аккаунт кнопкой под строкой ввода. Он появится в составе этой группы."
                    else -> error("Unknown input form")
                }.also {
                    if(f.kind in setOf("default_title","default_time")) {
                        rows+=listOf(button("Назад",ScreenAction("training_settings",0)),button("Меню",ScreenAction("menu",0)))
                    } else if(f.training.isEmpty() && f.kind in setOf("title","date","time","group","ready")) {
                        rows.add(buildList<TgButton> {
                            if(f.kind!="title") add(button("Назад",formAction("form_back")))
                            add(button("Отмена",formAction("form_cancel")))
                        })
                    } else if(f.kind=="pick_add_player") row("Назад",requireNotNull(f.origin))
                    else if(f.kind=="pick_players") row("Назад к выбору",next("add_players",id=f.training,page=f.page,option=state.formSignature(f)))
                    else row("Отмена",f.origin ?: next(if (f.training.isNotEmpty()) "training" else "debts", id = f.training))
                }
            }
            "history", "transfer_history" -> {
                val p = service.history(requireNotNull(a), action.id.takeIf { it.isNotEmpty() && action.kind=="history" }, action.page,action.id.takeIf { action.kind=="transfer_history" })
                val historyZone=ZoneId.of(service.group(action.group).timeZone)
                pages(p.index, p.pages)
                back(next(if(action.kind=="transfer_history") "transfer" else if (action.id.isEmpty()) "menu" else "training"))
                "История изменений · ${p.total}\n\n" + p.items.joinToString("\n\n") { "${Instant.parse(it.occurredAt).atZone(historyZone).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))} · ${if(it.kind=="MigrateTrainingState") "Обновление бота" else shortName(it.actorId)}\n${describe(it).take(330)}" }
            }
            else -> error("Unknown screen: ${action.kind}")
        }
        if (inGroup && action.kind !in setOf("public","player","exit_confirm","participation","participation_time","participation_payment")) row("Закрыть", next("close_panel"))
        val header = ""
        // No arbitrary user content can grow a Telegram message beyond the documented limit.
        val result = (notice?.let { "${clean(it, 220)}\n\n" } ?: "") + header + text
        check(result.length <= if(richHtml==null) 4096 else 32768) { "Экран превышает допустимую длину" }
        Output(result, TgKeyboard(rows), tokens,richHtml?.let { (notice?.let { n -> "<p>${TrainingCard.escape(clean(n,220))}</p>" } ?: "")+it })
        }
    }
    private fun playerLine(p: Attendance) = "${name(p.userId)}\n  ${if (p.playing) hours(p.minutes) else "Не играл"}" +
        (if (p.guestCount > 0) " · гостей: ${p.guestCount}, по ${hours(p.guestMinutes)}" else "") + " · оплатил ${p.paid} ₽"
    // Historical JSON is immutable; normalize only the retired phase when reading old snapshots.
    private fun historyTraining(text:String):TrainingRecord {
        val value=state.json.parseToJsonElement(text).jsonObject
        val compatible=if(value["phase"]?.jsonPrimitive?.content=="REVIEW") JsonObject(value+("phase" to JsonPrimitive("OPEN"))) else value
        return state.json.decodeFromJsonElement(TrainingRecord.serializer(),compatible)
    }
    private fun describe(a: AuditAction): String = when (a.kind) {
        "SetDefaultStartTime" -> "Изменил начало по умолчанию · ${state.json.decodeFromString<String>(a.after)}"
        "CreateTraining" -> "Опубликовал тренировку"
        "EditTraining" -> {
            val after=historyTraining(a.after)
            val before=historyTraining(requireNotNull(a.before))
            listOfNotNull(if(before.title!=after.title) "Название: ${clean(before.title,100)} → ${clean(after.title,100)}" else null,
                if(before.date!=after.date) "Дата: ${date(before.date)} → ${date(after.date)}" else null,
                if(before.startTime!=after.startTime) "Начало: ${before.startTime} → ${after.startTime}" else null).joinToString("\n")
        }
        "EditTransferAmount" -> {
            val before=state.json.decodeFromString<MoneyTransfer>(requireNotNull(a.before))
            val after=state.json.decodeFromString<MoneyTransfer>(a.after)
            "Исправил сумму перевода: ${before.amount} ₽ → ${after.amount} ₽"
        }
        "FinishTraining" -> "Учёл тренировку · ${historyTraining(a.after).players.sumOf { it.paid }} ₽"
        "ReopenTraining" -> if(state.json.parseToJsonElement(a.after).jsonObject["phase"]?.jsonPrimitive?.content=="REVIEW")
            "Открыл исправление по прежним правилам; расчёт оставался учтённым" else "Открыл тренировку заново; прежний расчёт отменён"
        "MigrateTrainingState" -> "При обновлении бота тренировку открыли заново; прежний расчёт отменён"
        "RemovePlayer" -> "Исключил игрока"
        "CancelTraining" -> "Отменил тренировку и снял её расчёт"
        "RestoreTraining" -> "Восстановил тренировку; расчёт ещё не учтён"
        "SendPayment" -> "Отметил отправку платежа"
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
                    if(old==null || old.minutes!=p.minutes) "Время: ${old?.let { hours(it.minutes) } ?: "—"} → ${hours(p.minutes)}" else null,
                    if((old?.guestCount ?: 0)!=p.guestCount) "Гостей: ${old?.guestCount ?: 0} → ${p.guestCount}" else null,
                    if(old?.guestMinutes!=p.guestMinutes && (p.guestCount>0 || (old?.guestCount ?: 0)>0)) "Время гостей: ${hours(old?.guestMinutes ?: 0)} → ${hours(p.guestMinutes)}" else null,
                    if(old==null || old.paid!=p.paid) "Оплата: ${old?.paid ?: 0} ₽ → ${p.paid} ₽" else null).joinToString("\n")
            }.ifEmpty { "Подтвердил прежние время и оплату" }
        }
        else -> "Изменение записи"
    }
    companion object {
        val privateActions = FinanceScreens.kinds + setOf("finance_send_save","finance_receive_save","training_status","set_training_status","add_player_list","exclude_player_list","manage_players","add_player","remove_player","pick_add_player","my_trainings","my_training","training_settings","default_title","save_default_title","settings","default_time","save_default_time","menu", "groups", "trainings", "debts", "balances", "settled", "transfers", "transfer", "transfer_people", "transfer_direction", "transfer_amount", "new", "edit_details", "profile_preview", "ask_paid", "edit_transfer_amount", "save_transfer_amount", "pick_account", "pick_players", "add_players", "toggle_player", "save_players", "roster", "administrators", "admin_candidates", "admin_person", "set_admin", "history", "transfer_history")
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
