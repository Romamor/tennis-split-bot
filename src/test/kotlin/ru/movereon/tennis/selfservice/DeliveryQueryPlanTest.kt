package ru.movereon.tennis.selfservice

import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.storage.*
import kotlin.test.*

class DeliveryQueryPlanTest {
    @TempDir lateinit var dir:Path
    private val statements=mutableListOf<String>()
    private fun database(name:String)=Database(dir.resolve(name),trace=statements::add).also { db ->
        db.write { c ->
            sqlUpdate(c,"INSERT INTO users(id,first_name) VALUES(1,'Synthetic participant'),(2,'Second participant')")
            sqlUpdate(c,"INSERT INTO groups(id,title,time_zone) VALUES(-1,'Synthetic group','Europe/Moscow'),(-2,'Second group','Europe/Moscow')")
            sqlUpdate(c,"INSERT INTO group_users(group_id,user_id,present) VALUES(-1,1,1),(-1,2,1),(-2,1,1)")
        }
    }
    private fun plan(db:Database,sql:String)=db.read { c ->
        c.prepareStatement("EXPLAIN QUERY PLAN $sql").use { s ->
            repeat(s.parameterMetaData.parameterCount) { s.setLong(it+1,0) }
            s.executeQuery().use { r -> buildList { while(r.next()) add(r.getString("detail")) } }
        }
    }

    @Test fun `empty and populated action queues use the partial pending index independently of history statistics`() {
        val db=database("actions.sqlite");val store=InteractionStore(db)
        db.write { c ->
            sqlUpdate(c,"INSERT INTO trainings(group_id,id,title,played_on,starts_at,status,version,created_by,created_at) VALUES(-1,'training','Synthetic training','2026-11-06','18:30','OPEN',1,1,'now')")
            sqlUpdate(c,"INSERT INTO transfers(group_id,id,from_user,to_user,amount,occurred_on,status,version,created_by,created_at) VALUES(-1,'transfer',1,2,150,'2026-11-06','ACTIVE',1,1,'now')")
            repeat(1500) { index ->
                sqlUpdate(c,"INSERT INTO actions(group_id,request_id,actor_id,kind,training_id,payload_json,after_json,result_version,occurred_at,needs_delivery,delivered_at) VALUES(-1,?,1,'CreateTraining','training','{}','{}',1,'now',1,'delivered')","history:$index")
            }
        }
        repeat(2) { analyzed ->
            if(analyzed==1) db.write { c -> c.createStatement().use { it.execute("ANALYZE") } }
            statements.clear()
            assertTrue(store.pendingCards().isEmpty());assertTrue(store.pendingPaymentNotices().isEmpty())
            val queries=statements.toList()
            assertEquals(2,queries.size)
            queries.forEach { sql ->
                val details=plan(db,sql)
                assertTrue(details.any { it.contains("USING INDEX pending_actions") },details.joinToString("\n"))
                assertFalse(details.any { it.startsWith("SCAN ") && !it.contains("pending_actions") },details.joinToString("\n"))
            }
        }
        db.write { c ->
            for((request,training,transfer,kind) in listOf(
                listOf("pending-1","training",null,"CreateTraining"),listOf("pending-2","training",null,"EditTraining"),
                listOf("pending-3",null,"transfer","SendPayment"),listOf("pending-4",null,"transfer","EditPayment")))
                sqlUpdate(c,"INSERT INTO actions(group_id,request_id,actor_id,kind,training_id,transfer_id,payload_json,after_json,result_version,occurred_at,needs_delivery) VALUES(-1,?,1,?,?,?,'{}','{}',1,'now',1)",request,kind,training,transfer)
        }
        assertEquals(listOf(Triple(-1L,"training",1502L)),store.pendingCards())
        assertEquals(listOf(PaymentNoticeWork(-1,"transfer",1504,true)),store.pendingPaymentNotices())
        assertTrue(store.pendingCards(setOf("training:-1:training")).isEmpty())
        val history=plan(db,"SELECT id FROM actions WHERE group_id=-1 ORDER BY id DESC LIMIT 20")
        assertTrue(history.any { it.contains("sqlite_autoindex_actions_") },history.joinToString("\n"))
        assertFalse(history.any { it.contains("TEMP B-TREE") },history.joinToString("\n"))
    }

    @Test fun `pin reconciliation scans trainings once per transition and preserves every pin state`() {
        val db=database("pins.sqlite");val store=InteractionStore(db)
        val expected=linkedMapOf<String,String>()
        val states=listOf("NONE","PENDING","SENDING","SENT","FAILED","UNKNOWN","UNPIN_PENDING","UNPIN_SENDING","UNPIN_FAILED","UNPINNED")
        db.write { c ->
            for(trainingStatus in listOf("OPEN","CLOSED","CANCELLED")) for(pin in states) for(message in listOf<Long?>(42,null)) {
                val id="$trainingStatus-$pin-${message ?: "none"}";val key="training:-1:$id"
                sqlUpdate(c,"INSERT INTO trainings(group_id,id,title,played_on,starts_at,status,version,created_by,created_at) VALUES(-1,?,'Synthetic training','2026-11-06','18:30',?,1,1,'now')",id,trainingStatus)
                sqlUpdate(c,"INSERT INTO bot_deliveries(delivery_key,group_id,chat_id,message_id,status,pin_status) VALUES(?,-1,-1,?,'SENT',?)",key,message,pin)
                expected[key]=when {
                    trainingStatus=="OPEN" && pin.startsWith("UNPIN") -> "NONE"
                    trainingStatus!="OPEN" && message!=null && !pin.startsWith("UNPIN") -> "UNPIN_PENDING"
                    else -> pin
                }
            }
            repeat(1500) { index ->
                val id="archive:$index";val key="training:-2:$id"
                sqlUpdate(c,"INSERT INTO trainings(group_id,id,title,played_on,starts_at,status,version,created_by,created_at) VALUES(-2,?,'Archived training','2026-11-06','18:30','CLOSED',1,1,'now')",id)
                sqlUpdate(c,"INSERT INTO bot_deliveries(delivery_key,group_id,chat_id,message_id,status,pin_status) VALUES(?,-2,-2,42,'SENT','UNPINNED')",key)
                expected[key]="UNPINNED"
            }
            sqlUpdate(c,"INSERT INTO bot_deliveries(delivery_key,group_id,chat_id,message_id,status,pin_status) VALUES('group-menu:-1',-1,-1,42,'SENT','NONE'),('poll:-1:old',-1,-1,43,'SENT','UNPINNED')")
            expected["group-menu:-1"]="PENDING";expected["poll:-1:old"]="UNPINNED"
        }
        statements.clear();store.reconcilePins()
        val queries=statements.toList()
        assertEquals(3,queries.size)
        queries.filter { it.contains("FROM trainings") }.forEach { sql ->
            val details=plan(db,sql)
            assertFalse(details.any { it.contains("CORRELATED") },details.joinToString("\n"))
            assertEquals(1,details.count { it=="SCAN t" },details.joinToString("\n"))
        }
        repeat(2) {
            db.read { c -> assertEquals(expected,sqlQuery(c,"SELECT delivery_key,pin_status FROM bot_deliveries") { it.getString(1) to it.getString(2) }.toMap()) }
            store.reconcilePins()
        }
    }
}
