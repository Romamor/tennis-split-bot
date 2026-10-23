package ru.movereon.tennis.selfservice

import ru.movereon.tennis.application.*
import ru.movereon.tennis.core.*
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Training creation, editing and personal defaults share the same versioned form steps. */
internal class TrainingInput(
    private val service:SettlementService,
    private val state:InteractionStore,
    private val polls:TrainingPolls,
    private val clock:Clock,
    private val zone:String,
    private val publicationChoices:(Long,InputForm)->List<GroupOption>,
    private val requirePublication:(Long,Long)->Unit
) {
    fun prepare(base:EventPlan,savedForm:InputForm?,a:Access?):EventPlan {
        val action=base.screen
        fun plan(screen:ScreenAction=action,command:SettlementCommand?=null,form:InputForm?=null)=
            base.copy(screen=screen,command=command,form=form)
        fun formPlan(form:InputForm)=plan(ScreenAction("form",form.group,form.training,back=form.origin ?: action.back),
            form=form.copy(origin=form.origin ?: action.back))
        return when(action.kind) {
            "default_time", "default_title" -> {
                val defaults=service.trainingDefaults(base.user)
                val f=InputForm(action.kind,0,title=defaults.title,time=defaults.time,
                    defaultValue=if(action.kind=="default_title") defaults.title else defaults.time,origin=ScreenAction("training_settings",0))
                formPlan(f.copy(baseline=f.baselineValues()))
            }
            "save_default_title" -> {
                val f=requireNotNull(savedForm)
                checkAccounting(f.kind=="default_title" && action.option==state.formSignature(f),ErrorCode.STALE_VERSION,"Настройка изменилась. Открой её заново.")
                plan(ScreenAction("training_settings",0)).copy(defaultUpdate=DefaultTrainingUpdate("title",requireNotNull(f.defaultValue),f.title))
            }
            "form_back", "form_cancel", "form_group_page", "form_group_select" -> {
                val f=requireNotNull(savedForm)
                checkAccounting(f.training.isEmpty() && f.group==0L && action.option==state.formSignature(f),ErrorCode.STALE_VERSION,"Форма изменилась. Используй текущие кнопки.")
                when(action.kind) {
                    "form_cancel" -> plan(ScreenAction("menu",0))
                    "form_back" -> {
                        val beforeGroup=if(f.pollId.isNotEmpty()) "poll_decline" else "time"
                        val previous=when(f.kind) {
                            "date"->"title";"time"->"date";"poll_decline"->"time";"poll_photo"->"ready"
                            "group"->beforeGroup
                            "ready"->if(publicationChoices(base.user,f).size<=1) beforeGroup else "group"
                            else->error("No previous step")
                        }
                        formPlan(f.copy(kind=previous))
                    }
                    "form_group_page" -> { require(f.kind=="group");formPlan(f.copy(page=action.page)) }
                    else -> {
                        require(f.kind=="group")
                        requirePublication(action.value,base.user)
                        if(f.pollId.isNotEmpty()) checkAccounting(polls.enabled(action.value),ErrorCode.FORBIDDEN,"Сбор через опрос выключен в этой группе")
                        formPlan(f.copy(kind="ready",publishGroup=action.value))
                    }
                }
            }
            "form_date_adjust", "form_time_adjust", "save_default_time" -> {
                val f=requireNotNull(savedForm) { "Открой форму заново" }
                checkAccounting(f.group==action.group && action.option==state.formSignature(f),ErrorCode.STALE_VERSION,"Форма изменилась. Используй кнопки текущего сообщения")
                when(action.kind) {
                    "form_date_adjust" -> {
                        require(f.kind=="date" && action.value in -1L..1L)
                        val date=LocalDate.parse(f.date)
                        val changed=when(action.user) { 0L -> date.plusDays(action.value);1L -> date.plusMonths(action.value);2L -> date.plusYears(action.value);else -> error("Unknown date part") }
                        require(changed.year in 1..9999) { "Укажи год от 1 до 9999" }
                        formPlan(f.copy(date=changed.toString()))
                    }
                    "form_time_adjust" -> {
                        require(f.kind in setOf("time","default_time") && action.value in setOf(-60L,-30L,0L,30L,60L))
                        formPlan(f.copy(time=LocalTime.parse(f.time).plusMinutes(action.value).format(DateTimeFormatter.ofPattern("HH:mm"))))
                    }
                    else -> {
                        require(f.kind=="default_time")
                        plan(ScreenAction("training_settings",0)).copy(defaultUpdate=DefaultTrainingUpdate("time",requireNotNull(f.defaultValue),f.time))
                    }
                }
            }
            "new", "new_poll" -> {
                val defaults=service.trainingDefaults(base.user)
                val f=InputForm("title",0,title=defaults.title,date=LocalDate.now(clock.withZone(ZoneId.of(zone))).toString(),time=defaults.time,origin=ScreenAction("menu",0),pollId=if(action.kind=="new_poll") UUID.randomUUID().toString() else "")
                formPlan(f.copy(baseline=f.baselineValues()))
            }
            "edit_details" -> {
                val auth=requireNotNull(a)
                val t = service.training(auth, action.id)
                checkAccounting(service.canEdit(auth,t), ErrorCode.FORBIDDEN, "Редактировать тренировку может её создатель или администратор этой группы")
                val f=InputForm("title", action.group, t.id, version = t.version, title = t.title, date = t.date, time = t.startTime,origin=action.back)
                formPlan(f.copy(baseline=f.baselineValues()))
            }
            "form_next", "form_restart", "form_photo", "form_photo_remove", "save_training", "save_poll" -> {
                val f = requireNotNull(savedForm) { "Открой форму заново" }
                checkAccounting(f.group == action.group, ErrorCode.FORBIDDEN, "Эта форма относится к другой группе")
                checkAccounting(action.option == state.formSignature(f), ErrorCode.STALE_VERSION, "Форма изменилась. Используй кнопки текущего сообщения")
                when (action.kind) {
                    "form_next" -> formPlan(advance(f))
                    "form_restart" -> formPlan(f.copy(kind = "title"))
                    "form_photo" -> { require(f.kind=="ready" && f.pollId.isNotEmpty());formPlan(f.copy(kind="poll_photo")) }
                    "form_photo_remove" -> { require(f.kind=="ready" && f.pollId.isNotEmpty());formPlan(f.copy(pollPhotoId=null)) }
                    "save_poll" -> {
                        require(f.kind=="ready" && f.pollId.isNotEmpty())
                        val group=requireNotNull(f.publishGroup)
                        requirePublication(group,base.user)
                        plan(ScreenAction("poll_publish",group,f.pollId),form=f)
                    }
                    "save_training" -> {
                        require(f.pollId.isEmpty())
                        require(f.kind == "ready") { "Сначала заполни тренировку" }
                        val id = f.training.ifEmpty { UUID.randomUUID().toString() }
                        val command = if (f.training.isEmpty()) SettlementCommand.CreateTraining(id, f.title, f.date, f.time)
                            else SettlementCommand.EditTraining(id, f.version, f.title, f.date, f.time)
                        if(f.training.isEmpty()) {
                            val group=requireNotNull(f.publishGroup) { "Выбери группу для тренировки" }
                            requirePublication(group,base.user)
                            plan(ScreenAction("my_training",group,id,back=ScreenAction("my_trainings",0)),command)
                        } else plan(f.origin?.takeIf { it.kind=="training" } ?: ScreenAction("training",f.group,id),command)
                    }
                    else -> error("Неизвестное действие формы")
                }
            }
            else -> error("Unknown training input action")
        }
    }
    companion object {
        val actions=setOf("default_time","default_title","save_default_title","form_back","form_cancel","form_group_page",
            "form_group_select","form_date_adjust","form_time_adjust","save_default_time","new","new_poll","edit_details",
            "form_next","form_restart","form_photo","form_photo_remove","save_training","save_poll")
        private fun advance(f:InputForm)=f.copy(kind=when(f.kind) {
            "title" -> "date"
            "date" -> "time"
            "time" -> if(f.pollId.isNotEmpty()) "poll_decline" else if(f.training.isEmpty()) "group" else "ready"
            "poll_decline" -> "group"
            else -> error("Форма уже заполнена")
        })
        fun text(user:Long,chat:Long,f:InputForm,text:String):EventPlan {
            fun form(updated:InputForm)=EventPlan(user,chat,ScreenAction("form",f.group,f.training),form=updated)
            fun title(message:String):String {
                require(text.length in 1..100) { message }
                return text
            }
            fun time()=LocalTime.parse(text).format(DateTimeFormatter.ofPattern("HH:mm"))
            fun date()=runCatching {
                LocalDate.parse(text,DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(java.time.format.ResolverStyle.STRICT))
            }.getOrElse { LocalDate.parse(text) }.toString()
            return when(f.kind) {
                "title" -> form(advance(f.copy(title=title("Название: от 1 до 100 символов"))))
                "date" -> form(advance(f.copy(date=date())))
                "time" -> form(advance(f.copy(time=time())))
                "poll_decline" -> form(advance(f.copy(declineLabel=title("Последний ответ: от 1 до 100 символов"))))
                "poll_photo" -> form(f).copy(notice="Пришли фото обычным сообщением или нажми «Назад».")
                "default_title" -> form(f.copy(title=title("Название: от 1 до 100 символов")))
                "default_time" -> form(f.copy(time=time()))
                else -> form(f)
            }
        }
    }
}
