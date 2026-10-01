package ru.movereon.tennis.selfservice

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import ru.movereon.tennis.application.*
import ru.movereon.tennis.storage.Database
import ru.movereon.tennis.telegram.*
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

/** An opt-in synthetic audit. No production data, credentials or network requests. */
@Tag("performance-audit")
class PerformanceAuditTest {
    private val outputJson=Json { prettyPrint=true;encodeDefaults=true }
    @Serializable data class Sample(val scenario:String,val reads:Long,val writes:Long,val sql:Int,
        val telegram:Map<String,Int>,val elapsedMs:Double,val openedConnections:Long=0)
    private class Fixture(val path:Path,groups:Int=1) {
        val statements=mutableListOf<String>()
        val calls=mutableMapOf<String,Int>()
        val fake=FakeTelegramApi()
        val panels=mutableMapOf<Pair<Long,Long>,TgMessage>()
        val visiblePanels=mutableMapOf<Triple<Long,Long,Long>,TgMessage>()
        private var nextPanel=1L
        private var nextEvent=1L
        val db=Database(path, trace={ statements+=it })
        private val delegate=object:TelegramApi by fake {
            override fun ephemeralRich(chatId:Long,userId:Long,callbackId:String,text:String,html:String,keyboard:TgKeyboard):TgMessage =
                TgMessage(chat=TgChat(chatId,"supergroup"),from=fake.bot,text=text,keyboard=keyboard,
                    receiver=TgUser(userId),ephemeralId=nextPanel++).also { panels[chatId to userId]=it;visiblePanels[Triple(chatId,userId,requireNotNull(it.ephemeralId))]=it }
            override fun editEphemeralRich(chatId:Long,userId:Long,ephemeralId:Long,text:String,html:String,keyboard:TgKeyboard) {
                val previous=panels.getValue(chatId to userId)
                check(previous.ephemeralId==ephemeralId)
                val updated=previous.copy(text=text,keyboard=keyboard)
                panels[chatId to userId]=updated;visiblePanels[Triple(chatId,userId,ephemeralId)]=updated
            }
            override fun deleteEphemeral(chatId:Long,userId:Long,ephemeralId:Long) {
                visiblePanels.remove(Triple(chatId,userId,ephemeralId))
                if(panels[chatId to userId]?.ephemeralId==ephemeralId) panels.remove(chatId to userId)
            }
        }
        private val api=Proxy.newProxyInstance(TelegramApi::class.java.classLoader,arrayOf(TelegramApi::class.java)) { _,method,args ->
            calls.merge(method.name,1,Int::plus)
            try { method.invoke(delegate,*(args ?: emptyArray())) }
            catch(e:InvocationTargetException) { throw e.targetException }
        } as TelegramApi
        val bot=SelfServiceBot(api,db,fake.bot,Clock.fixed(Instant.parse("2026-10-02T12:00:00Z"),ZoneOffset.UTC))
        init {
            (1L..40L).forEach { bot.service.remember(Account(it,"Player $it")) }
            (1..groups).forEach { index ->
                val id=-index.toLong()
                bot.service.register(SettlementGroup(id,"Group $index","Europe/Moscow"))
                fake.members[id to fake.bot.id]=TgMember("administrator")
            }
            (1L..40L).forEach {
                bot.service.rememberMembership(-1,it,true)
                fake.members[-1L to it]=TgMember(if(it==1L) "administrator" else "member")
            }
            bot.polls.setEnabled(Access(-1,1,true),true)
        }
        fun start()=handle(TgUpdate(nextEvent++,TgMessage(id=nextEvent+1000,chat=TgChat(2,"private"),from=TgUser(2,firstName="Player 2"),text="/start")))
        fun press(user:Long,message:TgMessage,label:String) {
            val button=message.keyboard!!.rows.flatten().single { it.text==label || it.text.endsWith(" $label") || it.text.contains("$label ·") }
            handle(TgUpdate(nextEvent++,callback=TgCallback("c$nextEvent",TgUser(user,firstName="Player $user"),message,button.callbackData)))
        }
        private fun handle(event:TgUpdate) { bot.handle(event) }
        fun setupPanels(count:Int) {
            val a=Access(-1,1,true)
            bot.service.execute(a,"create",SettlementCommand.CreateTraining("training","Training","2026-10-02","18:30"))
            bot.service.execute(a,"players",SettlementCommand.AddPlayers("training",1,(2L..21L).toList()))
            bot.maintain()
            val card=fake.messages.getValue(-1L to bot.state.delivery("training:-1:training")!!.message!!)
            (2L until 2L+count).forEach { press(it,card,"Открыть") }
            press(2,panels.getValue(-1L to 2L),"Оплата")
        }
        fun pay()=press(2,panels.getValue(-1L to 2L),"+50 ₽")
        fun measure(name:String,block:()->Unit):Sample {
            statements.clear();calls.clear()
            val r=db.readTransactions.get();val w=db.writeTransactions.get();val opened=db.openedConnections.get();val t=System.nanoTime()
            block()
            return Sample(name,db.readTransactions.get()-r,db.writeTransactions.get()-w,statements.size,calls.toSortedMap(),(System.nanoTime()-t)/1e6,db.openedConnections.get()-opened)
        }
    }
    @Test fun `measure foreground background and connection lifecycle costs`() {
        val root=Path.of(System.getProperty("audit.dir"));Files.createDirectories(root)
        require(Files.list(root).use { !it.findAny().isPresent }) { "Use an empty audit output directory" }
        val samples=mutableListOf<Sample>()
        for(groups in listOf(1,5,20)) {
            val f=Fixture(root.resolve("menu-$groups.sqlite"),groups)
            f.db.withConnectionReuse {
                f.start()
                repeat(5) { samples+=f.measure("menu_groups_$groups",f::start) }
            }
        }
        for(panels in listOf(1,10,20)) {
            val f=Fixture(root.resolve("panels-$panels.sqlite"))
            f.db.withConnectionReuse {
                f.setupPanels(panels);f.pay();f.bot.maintain()
                while(f.bot.hasPendingPanelRefreshes()) f.bot.maintain()
                repeat(5) {
                    samples+=f.measure("payment_handle_panels_$panels",f::pay)
                    val first=f.measure("first_maintenance_batch_panels_$panels",f.bot::maintain)
                    val rest=f.measure("remaining_batches") { while(f.bot.hasPendingPanelRefreshes()) f.bot.maintain() }
                    samples+=first
                    val methods=(first.telegram.keys+rest.telegram.keys).associateWith { first.telegram.getOrDefault(it,0)+rest.telegram.getOrDefault(it,0) }
                    samples+=Sample("payment_maintenance_panels_$panels",first.reads+rest.reads,first.writes+rest.writes,
                        first.sql+rest.sql,methods,first.elapsedMs+rest.elapsedMs,first.openedConnections+rest.openedConnections)
                    samples+=f.measure("idle_maintenance_panels_$panels",f.bot::maintain)
                }
                assertEquals(300,f.bot.service.training(Access(-1,2),"training").players.single { it.userId==2L }.paid)
                assertEquals(panels,f.visiblePanels.size)
                f.db.verify()
            }
        }
        val f=Fixture(root.resolve("connection.sqlite"));f.setupPanels(1)
        // An idle connection prevents last-close WAL checkpoints, without holding a transaction.
        for(round in 0..3) for(keepOpen in if(round%2==0) listOf(false,true) else listOf(true,false)) {
            val anchor=if(keepOpen) DriverManager.getConnection("jdbc:sqlite:${f.path}") else null
            try {
                anchor?.createStatement()?.use { s -> s.executeQuery("PRAGMA journal_mode").use { r -> assertTrue(r.next());assertEquals("wal",r.getString(1)) } }
                repeat(3) { samples+=f.measure(if(keepOpen) "payment_with_idle_connection" else "payment_current_connections",f::pay);f.bot.maintain() }
            } finally { anchor?.close() }
        }
        f.db.withConnectionReuse {
            f.pay();f.bot.maintain()
            repeat(12) { samples+=f.measure("payment_reused_connections",f::pay);f.bot.maintain() }
        }
        f.db.verify()
        Files.writeString(root.resolve("measurements.json"),outputJson.encodeToString(samples))
        println("Performance samples: ${samples.size}; output: $root")
    }
}
