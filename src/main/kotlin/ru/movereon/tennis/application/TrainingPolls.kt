package ru.movereon.tennis.application

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.movereon.tennis.core.*
import ru.movereon.tennis.storage.*
import java.sql.Connection
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class TrainingPoll(val group:Long,val id:String,val title:String,val date:String,val time:String,val decline:String,
    val creator:Long,val telegramId:String?,val message:Long?,val status:String,val stopped:Boolean,val closer:Long?,val training:String?) {
    fun options():List<String> {
        val start=LocalDateTime.of(LocalDate.parse(date),LocalTime.parse(time))
        return listOf(-30L,0L,30L).map { offset ->
            val at=start.plusMinutes(offset)
            (if(offset==0L) "К началу — в " else "В ")+at.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))+
                if(at.toLocalDate()!=start.toLocalDate()) " (${at.format(DateTimeFormatter.ofPattern("dd.MM"))})" else ""
        }+decline
    }
}

/** Poll intent never enters the financial ledger. Only positive, current answers are stored. */
class TrainingPolls(private val service:SettlementService,private val clock:Clock=Clock.systemUTC()) {
    private val db=service.database
    fun enabled(group:Long)=db.read { c -> sqlQuery(c,"SELECT polls_enabled FROM groups WHERE id=?",group) { it.getBoolean(1) }.single() }
    fun setEnabled(a:Access,value:Boolean) {
        checkAccounting(service.isAdmin(a),ErrorCode.FORBIDDEN,"Настройка доступна администратору этой группы")
        db.write { c -> member(c,a);sqlUpdate(c,"UPDATE groups SET polls_enabled=? WHERE id=?",value,a.groupId) }
    }
    private fun member(c:Connection,a:Access) = checkAccounting(sqlQuery(c,"SELECT present FROM group_users WHERE group_id=? AND user_id=?",a.groupId,a.userId) { it.getBoolean(1) }.singleOrNull()==true,ErrorCode.FORBIDDEN,"Доступ только участникам этой группы")
    private fun get(c:Connection,group:Long,id:String)=readPoll(c,group,id)
    fun get(group:Long,id:String)=db.read { get(it,group,id) }
    fun byTelegram(id:String)=db.read { c -> sqlQuery(c,"SELECT * FROM training_polls WHERE telegram_id=?",id,map=::readPollRow).singleOrNull() }
    fun canManage(a:Access,p:TrainingPoll)=a.groupId==p.group && (a.userId==p.creator || service.isAdmin(a))
    fun requireManager(a:Access,p:TrainingPoll) {
        db.read { member(it,a) }
        checkAccounting(canManage(a,p),ErrorCode.FORBIDDEN,"Завершить сбор может создатель опроса или администратор этой группы")
    }
    fun active(group:Long?=null)=db.read { c -> sqlQuery(c,"SELECT * FROM training_polls WHERE status<>'CLOSED' AND (? IS NULL OR group_id=?) ORDER BY created_at DESC,id",group,group,map=::readPollRow) }
    fun managedPage(a:Access,page:Int)=db.read { c -> member(c,a);readManagedPolls(c,a,isGroupAdmin(c,a),page) }
    fun hasActive(group:Long,user:Long,admin:Boolean)=db.read { c ->
        sqlQuery(c,"SELECT EXISTS(SELECT 1 FROM training_polls WHERE group_id=? AND status<>'CLOSED' AND (? OR created_by=?))",group,admin,user) { it.getBoolean(1) }.single()
    }
    fun maintenancePolls()=db.read { c -> sqlQuery(c,"SELECT * FROM training_polls WHERE status IN ('OPEN','CLOSING') ORDER BY created_at DESC,id",map=::readPollRow) }
    fun closingPolls()=db.read { c -> sqlQuery(c,"SELECT * FROM training_polls WHERE status='CLOSING' AND stopped=1 ORDER BY created_at DESC,id",map=::readPollRow) }
    fun hasClosingPolls()=db.read { c -> sqlQuery(c,"SELECT EXISTS(SELECT 1 FROM training_polls WHERE status='CLOSING' AND stopped=1)") { it.getBoolean(1) }.single() }
    fun create(a:Access,id:String,title:String,date:String,time:String,decline:String):TrainingPoll = db.write { c ->
        member(c,a)
        val old=sqlQuery(c,"SELECT * FROM training_polls WHERE group_id=? AND id=?",a.groupId,id,map=::readPollRow).singleOrNull()
        if(old!=null) {
            checkAccounting(old.creator==a.userId && old.title==title && old.date==date && old.time==time && old.decline==decline,ErrorCode.COMMAND_CONFLICT,"Опрос уже создан с другими данными")
            return@write old
        }
        checkAccounting(sqlQuery(c,"SELECT polls_enabled FROM groups WHERE id=?",a.groupId) { it.getBoolean(1) }.single(),ErrorCode.FORBIDDEN,"Сбор через опрос выключен в этой группе")
        require(title==title.trim() && title.length in 1..100 && decline==decline.trim() && decline.length in 1..100) { "Название и последний ответ: от 1 до 100 символов" }
        LocalDate.parse(date);LocalTime.parse(time)
        val p=TrainingPoll(a.groupId,id,title,date,time,decline,a.userId,null,null,"PENDING",false,null,null)
        require(p.options().distinct().size==4) { "Последний ответ не должен совпадать со временем прихода" }
        sqlUpdate(c,"INSERT INTO training_polls(group_id,id,title,played_on,starts_at,decline_label,created_by,created_at,status) VALUES(?,?,?,?,?,?,?,?,'PENDING')",a.groupId,id,title,date,time,decline,a.userId,clock.instant().toString())
        p
    }
    fun status(p:TrainingPoll,status:String)=db.write { c -> sqlUpdate(c,"UPDATE training_polls SET status=? WHERE group_id=? AND id=?",status,p.group,p.id) }
    fun interrupted()=db.write { c -> sqlUpdate(c,"UPDATE training_polls SET status='UNKNOWN' WHERE status='SENDING'") }
    fun attach(p:TrainingPoll,telegram:String,message:Long)=db.write { c ->
        val current=get(c,p.group,p.id)
        checkAccounting(current.telegramId==null || current.telegramId==telegram && current.message==message,ErrorCode.COMMAND_CONFLICT,"Этот опрос уже связан с другим сообщением")
        sqlUpdate(c,"UPDATE training_polls SET telegram_id=?,message_id=?,status=CASE WHEN status IN ('SENDING','PENDING') THEN 'OPEN' ELSE status END WHERE group_id=? AND id=?",telegram,message,p.group,p.id)
    }
    /** No raw answer, negative choice or voter identity is retained in bot_events. */
    fun vote(event:Long,p:TrainingPoll,user:Long,option:Int?)=db.write { c ->
        if(sqlQuery(c,"SELECT completed FROM bot_events WHERE update_id=?",event) { it.getBoolean(1) }.singleOrNull()==true) return@write
        if(get(c,p.group,p.id).status in setOf("OPEN","CLOSING")) {
            if(option in 0..2) sqlUpdate(c,"INSERT INTO poll_signups(group_id,poll_id,user_id) VALUES(?,?,?) ON CONFLICT(group_id,poll_id,user_id) DO NOTHING",p.group,p.id,user)
            else sqlUpdate(c,"DELETE FROM poll_signups WHERE group_id=? AND poll_id=? AND user_id=?",p.group,p.id,user)
        }
        sqlUpdate(c,"INSERT INTO bot_events(update_id,completed) VALUES(?,1) ON CONFLICT(update_id) DO UPDATE SET completed=1,plan_json=NULL,user_id=NULL,group_id=NULL",event)
    }
    fun count(p:TrainingPoll)=db.read { c -> sqlQuery(c,"SELECT COUNT(*) FROM poll_signups WHERE group_id=? AND poll_id=?",p.group,p.id) { it.getInt(1) }.single() }
    fun beginClose(a:Access,id:String) {
        val p=get(a.groupId,id);requireManager(a,p)
        checkAccounting(p.message!=null,ErrorCode.INVALID_STATE,"Опрос ещё не опубликован")
        db.write { c -> sqlUpdate(c,"UPDATE training_polls SET status='CLOSING',closed_by=? WHERE group_id=? AND id=? AND status='OPEN'",a.userId,a.groupId,id) }
    }
    fun discard(a:Access,id:String) {
        val p=get(a.groupId,id);requireManager(a,p)
        checkAccounting(p.status in setOf("UNKNOWN","FAILED","PENDING"),ErrorCode.INVALID_STATE,"Опубликованный опрос нужно завершить")
        db.write { c ->
            sqlUpdate(c,"DELETE FROM training_polls WHERE group_id=? AND id=?",a.groupId,id)
            sqlUpdate(c,"DELETE FROM bot_buttons WHERE scope=?","poll:${a.groupId}:$id")
        }
    }
    fun stopped(p:TrainingPoll)=db.write { c -> sqlUpdate(c,"UPDATE training_polls SET stopped=1 WHERE group_id=? AND id=? AND status='CLOSING'",p.group,p.id) }
    /** Called only after Telegram's update queue is drained following stopPoll. One transaction, one training. */
    fun finish(p:TrainingPoll)=db.write { c ->
        val current=get(c,p.group,p.id)
        if(current.status=="CLOSED") return@write
        checkAccounting(current.status=="CLOSING" && current.stopped,ErrorCode.INVALID_STATE,"Сначала останови голосование")
        val actor=requireNotNull(current.closer)
        val rules=insertTraining(c,p.group,p.id,p.title,p.date,p.time,p.creator,clock.instant().toString())
        val players=sqlQuery(c,"SELECT user_id FROM poll_signups WHERE group_id=? AND poll_id=? ORDER BY user_id",p.group,p.id) { it.getLong(1) }
        players.forEachIndexed { index,user -> sqlUpdate(c,"INSERT INTO training_players(group_id,training_id,user_id,playing,minutes,paid,ordinal) VALUES(?,?,?,1,?,0,?)",p.group,p.id,user,rules.initialMinutes,index) }
        refreshAttendance(c,p.group)
        val after=Json.encodeToString(readTraining(c,p.group,p.id))
        sqlUpdate(c,"INSERT INTO actions(group_id,request_id,actor_id,kind,training_id,payload_json,after_json,result_version,occurred_at,needs_delivery) VALUES(?,?,?,'CreateTrainingFromPoll',?,'{}',?,1,?,1)",p.group,"poll:"+p.id,actor,p.id,after,clock.instant().toString())
        sqlUpdate(c,"UPDATE training_polls SET status='CLOSED',training_id=? WHERE group_id=? AND id=?",p.id,p.group,p.id)
        // Arrival plans have served their purpose. They are neither played duration nor financial history.
        sqlUpdate(c,"DELETE FROM poll_signups WHERE group_id=? AND poll_id=?",p.group,p.id)
        sqlUpdate(c,"DELETE FROM bot_buttons WHERE scope=?","poll:${p.group}:${p.id}")
        sqlUpdate(c,"UPDATE bot_deliveries SET pin_status='UNPIN_PENDING' WHERE delivery_key=?","poll:${p.group}:${p.id}")
    }
}
