package ru.movereon.tennis.selfservice

import java.nio.file.Files
import java.nio.file.Path
import java.time.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.application.*
import ru.movereon.tennis.storage.*
import ru.movereon.tennis.telegram.*
import kotlin.test.*

class PrivacyInboxTest {
    @TempDir lateinit var dir:Path
    private val clock=Clock.fixed(Instant.parse("2026-11-06T12:00:00Z"),ZoneOffset.UTC)
    private val username="synthetic_example_bot"
    private val sender=TgUser(1,firstName="Synthetic participant")
    private val group=TgChat(-1,"supergroup","Synthetic group")
    private fun raw(db:Database,id:Long)=db.read { c -> sqlQuery(c,"SELECT update_json FROM bot_events WHERE update_id=?",id) { it.getString(1) }.single() }
    private fun decorated(id:Long,chat:TgChat,text:String)=TgMessage(id,chat,sender,text,
        replyTo=TgMessage(900,chat,sender,"SECRET_QUOTED_CONTENT_7492"),
        photo=listOf(TgPhotoSize("SECRET_PHOTO_REFERENCE_7492",100,100)),
        keyboard=TgKeyboard(listOf(listOf(TgButton("SECRET_KEYBOARD_LABEL_7492","SECRET_CALLBACK_COPY_7492")))))

    @Test fun `ordinary group bodies and attachments never reach SQLite WAL or its backup while membership survives`() {
        val db=Database(dir.resolve("privacy.sqlite"));val inbox=UpdateInbox(db,clock,username)
        val joined=TgUser(2,firstName="Joined participant");val left=TgUser(3,firstName="Departed participant")
        val message=decorated(10,group,"SECRET_ORDINARY_GROUP_BODY_7492").copy(newMembers=listOf(joined),leftMember=left,migrateTo=-2)
        inbox.accept(listOf(TgUpdate(100,message)))
        val stored=assertNotNull(inbox.pending().single().update?.message)
        assertNull(stored.text);assertNull(stored.replyTo);assertNull(stored.keyboard);assertTrue(stored.photo.isEmpty())
        assertEquals(sender,stored.from);assertEquals(group,stored.chat)
        assertEquals(listOf(joined),stored.newMembers);assertEquals(left,stored.leftMember);assertEquals(-2L,stored.migrateTo)
        assertEquals(clock.millis(),inbox.acceptedAt(100))
        assertEquals(clock.millis(),Json.parseToJsonElement(raw(db,100)).jsonObject.getValue("accepted_at").jsonPrimitive.long)
        val backup=db.backup(dir.resolve("backup.sqlite"))
        for(file in listOf(db.path,Path.of("${db.path}-wal"),Path.of("${db.path}-shm"),backup).filter(Files::exists)) {
            val bytes=Files.readAllBytes(file).toString(Charsets.ISO_8859_1)
            assertFalse(bytes.contains("SECRET_"),"Unneeded message content leaked to ${file.fileName}")
        }
        val restored=UpdateInbox(Database(backup,readOnly=true),clock,username)
        assertEquals(stored,restored.pending().single().update?.message)
    }

    @Test fun `only start and menu commands addressed to this bot retain their command token`() {
        val db=Database(dir.resolve("commands.sqlite"));val inbox=UpdateInbox(db,clock,username)
        val commands=listOf(
            "/start" to "/start","/menu ignored argument" to "/menu",
            "/start@synthetic_example_bot ignored argument" to "/start","/menu@SYNTHETIC_EXAMPLE_BOT" to "/menu",
            "/menu@another_example_bot" to null,"/start@another_example_bot" to null,
            "/startsomething" to null,"/other" to null,"ordinary message" to null)
        commands.forEachIndexed { index,(input,expected) ->
            val id=index+1L;inbox.accept(listOf(TgUpdate(id,decorated(id,group,input).copy(from=sender.copy(id=id)))))
            assertEquals(expected,Json.parseToJsonElement(raw(db,id)).jsonObject.getValue("message").jsonObject["text"]?.jsonPrimitive?.contentOrNull)
        }
        val unknown=UpdateInbox(Database(dir.resolve("unknown-name.sqlite")),clock)
        unknown.accept(listOf(TgUpdate(1,decorated(1,group,"/menu@another_example_bot"))))
        assertNull(unknown.pending().single().update?.message?.text,"Without a known bot username no addressed command can be authenticated")
    }

    @Test fun `callback context votes and membership events survive without quoted message content`() {
        val db=Database(dir.resolve("context.sqlite"));val inbox=UpdateInbox(db,clock,username)
        val callback=TgCallback("callback-id",sender,decorated(10,TgChat(1,"private"),"SECRET_OLD_PANEL_7492"),"n:current-token")
        val vote=TgPollAnswer("synthetic-poll",sender.copy(id=2),listOf(1))
        val member=TgMemberUpdate(group,sender.copy(id=3),TgMember("member",user=TgUser(4)))
        val privateInput=decorated(11,TgChat(5,"private"),"Synthetic private input").copy(from=sender.copy(id=5),
            usersShared=TgUsersShared(7,listOf(TgSharedUser(6,"Selected participant"))))
        inbox.accept(listOf(TgUpdate(1,callback=callback),TgUpdate(2,pollAnswer=vote),TgUpdate(3,memberUpdate=member),TgUpdate(4,message=privateInput)))
        val updates=inbox.pending().associate { it.id to assertNotNull(it.update) }
        val saved=assertNotNull(updates.getValue(1).callback)
        assertEquals(callback.id,saved.id);assertEquals(callback.from,saved.from);assertEquals(callback.data,saved.data)
        assertEquals(10L,saved.message?.id);assertEquals(TgChat(1,"private"),saved.message?.chat)
        assertNull(saved.message?.text);assertNull(saved.message?.replyTo);assertNull(saved.message?.keyboard);assertTrue(saved.message!!.photo.isEmpty())
        assertEquals(vote,updates.getValue(2).pollAnswer);assertEquals(member,updates.getValue(3).memberUpdate)
        val savedInput=assertNotNull(updates.getValue(4).message)
        assertEquals(privateInput.text,savedInput.text);assertEquals(privateInput.photo,savedInput.photo)
        assertEquals(privateInput.usersShared,savedInput.usersShared);assertNull(savedInput.replyTo);assertNull(savedInput.keyboard)
        assertTrue(inbox.hasPendingVotes("synthetic-poll"))
        assertFalse(raw(db,1).contains("SECRET_"))
    }

    @Test fun `queued private input retains future form steps across restart and completion removes raw content`() {
        val fake=FakeTelegramApi();val file=dir.resolve("form.sqlite")
        var bot=SelfServiceBot(fake,Database(file),fake.bot,clock)
        bot.service.remember(Account(1,"Synthetic participant"))
        bot.service.register(SettlementGroup(-1,"Synthetic group","Europe/Moscow"))
        bot.service.rememberMembership(-1,1,true);fake.members[-1L to 1L]=TgMember("member")
        bot.state.session(1,1,0,InputForm("title",0,date="2026-11-06",origin=ScreenAction("menu",0)))
        val messages=listOf("Synthetic session title","2026-11-07","19:30").mapIndexed { index,text ->
            TgUpdate(index+1L,TgMessage(index+10L,TgChat(1,"private"),sender,text))
        }
        bot.acceptUpdates(messages)
        messages.forEach { assertContains(raw(bot.service.database,it.id),it.message!!.text!!) }
        bot=SelfServiceBot(fake,Database(file),fake.bot,clock);bot.processPending(10)
        val form=assertNotNull(bot.state.form(1,1))
        assertEquals("Synthetic session title",form.title);assertEquals("2026-11-07",form.date);assertEquals("19:30",form.time)
        assertEquals("group",form.kind)
        messages.forEach { assertTrue(bot.state.completed(it.id));assertNull(raw(bot.service.database,it.id)) }
    }

    @Test fun `stripped callback and poll vote can still be processed after durable receipt`() {
        val fake=FakeTelegramApi();val bot=SelfServiceBot(fake,Database(dir.resolve("process.sqlite")),fake.bot,clock)
        bot.service.remember(Account(1,"Synthetic participant"));bot.service.remember(Account(2,"Synthetic voter"))
        bot.service.register(SettlementGroup(-1,"Synthetic group","Europe/Moscow"))
        for(user in 1L..2L) { bot.service.rememberMembership(-1,user,true);fake.members[-1L to user]=TgMember("member") }
        bot.state.session(1,1,0,null)
        val key="personal:1:1";val action=ScreenAction("settings",0)
        val token=bot.state.button(action,1,key);bot.state.protect(setOf(token))
        val panel=fake.send(1,"SECRET_OLD_PANEL_7492",TgKeyboard(listOf(listOf(TgButton("Settings","n:$token")))))
        bot.state.sending(key,0,1,1);bot.state.deliveryResult(key,"SENT",panel.id)
        bot.polls.setEnabled(Access(-1,1,true),true)
        val poll=bot.polls.create(Access(-1,1,true),"poll","Synthetic poll","2026-11-07","18:30","No")
        bot.polls.attach(poll,"synthetic-poll",100)
        bot.acceptUpdates(listOf(TgUpdate(1,callback=TgCallback("click",sender,panel,"n:$token")),
            TgUpdate(2,pollAnswer=TgPollAnswer("synthetic-poll",TgUser(2),listOf(0)))))
        assertFalse(raw(bot.service.database,1).contains("SECRET_"))
        bot.processPending(10)
        assertTrue(bot.state.completed(1));assertTrue(bot.state.completed(2))
        assertTrue(fake.messages.values.any { it.chat.id==1L && it.text?.contains("Настройки")==true })
        bot.service.database.read { c -> assertEquals(listOf(2L),sqlQuery(c,"SELECT user_id FROM poll_signups WHERE group_id=-1 AND poll_id='poll'") { it.getLong(1) }) }
    }
}
