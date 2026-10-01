package ru.movereon.tennis.selfservice

import org.junit.jupiter.api.Test
import ru.movereon.tennis.telegram.TgMember
import java.time.*
import kotlin.test.*

class NavigationMembershipCacheTest {
    @Test fun `navigation cache expires invalidates one group and remains bounded`() {
        var now=Instant.parse("2026-10-02T12:00:00Z")
        val clock=object:Clock() {
            override fun getZone()=ZoneOffset.UTC
            override fun withZone(zone:ZoneId)=fixed(now,zone)
            override fun instant()=now
        }
        val cache=NavigationMembershipCache(clock);var calls=0
        fun fetch()=TgMember("member").also { calls++ }
        cache.get(-1,1,::fetch);cache.get(-2,1,::fetch)
        cache.get(-1,1,::fetch);assertEquals(2,calls)
        cache.invalidate(-1);cache.get(-1,1,::fetch);cache.get(-2,1,::fetch);assertEquals(3,calls)
        now=now.plusSeconds(31);cache.get(-2,1,::fetch);assertEquals(4,calls)
        for(user in 10L..266L) cache.remember(-3,user,TgMember("member"))
        cache.get(-2,1,::fetch);assertEquals(5,calls,"Old entries must be evicted instead of accumulating forever")
    }
}
