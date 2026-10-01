package ru.movereon.tennis.selfservice

import ru.movereon.tennis.telegram.TgMember
import java.time.Clock

/** Presentation only. Mutation authorization must query Telegram independently. */
internal class NavigationMembershipCache(private val clock:Clock) {
    private data class Entry(val member:TgMember,val expiresAt:Long)
    private val entries=object:LinkedHashMap<Pair<Long,Long>,Entry>(256,0.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<Pair<Long,Long>,Entry>?)=size>256
    }
    fun remember(group:Long,user:Long,member:TgMember) { entries[group to user]=Entry(member,clock.millis()+30_000) }
    fun get(group:Long,user:Long,fetch:()->TgMember):TgMember {
        entries[group to user]?.takeIf { it.expiresAt>clock.millis() }?.let { return it.member }
        return fetch().also { remember(group,user,it) }
    }
    fun invalidate(group:Long) { entries.keys.removeIf { it.first==group } }
}
