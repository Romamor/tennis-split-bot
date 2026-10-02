package ru.movereon.tennis.selfservice

import org.junit.jupiter.api.Test
import ru.movereon.tennis.application.*
import kotlin.test.assertTrue

class TrainingCardTest {
    private fun card(players:List<Attendance>,trackTime:Boolean=true,phase:TrainingPhase=TrainingPhase.OPEN)=
        TrainingCard.render(TrainingRecord(-1,"example","Теннис","2026-10-02","18:30",
            phase,1,0,1,players,TrainingRules(trackTime=trackTime))) { Account(it,"Игрок $it") }

    private fun expect(card:TrainingCard.Content,summary:String) {
        assertTrue(card.text.endsWith(summary),card.text)
        assertTrue(card.html.endsWith("<p>$summary</p>"),card.html)
    }

    @Test fun `average uses total person hours including each guest in every phase`() {
        // 750 rubles / (1 + 2 + 0.5 + 0.5) hours = 187.5, rounded to 188.
        val players=listOf(Attendance(1,true,60,30,350,guestCount=2),Attendance(2,true,120,paid=400),
            Attendance(3,false,600))
        for(phase in TrainingPhase.entries) expect(card(players,phase=phase),"Час: 188 ₽")
    }

    @Test fun `average rounds fractions down below half a ruble`() {
        expect(card(listOf(Attendance(1,true,90,paid=100))),"Час: 67 ₽")
        expect(card(listOf(Attendance(1,true,90,paid=101))),"Час: 67 ₽")
    }

    @Test fun `missing duration is unknown but zero payment with duration is zero`() {
        expect(card(emptyList()),"Час: —")
        expect(card(listOf(Attendance(1,true,paid=300))),"Час: —")
        expect(card(listOf(Attendance(1,true,60))),"Час: 0 ₽")
    }

    @Test fun `disabled time uses equal headcount including guests regardless of saved minutes`() {
        val players=listOf(Attendance(1,true,120,30,750,guestCount=2),Attendance(2,true,0),Attendance(3,false,600))
        expect(card(players,false),"На человека: 188 ₽")
        expect(card(emptyList(),false),"На человека: —")
        expect(card(listOf(Attendance(1,true)),false),"На человека: 0 ₽")
        // Returning to tracked time uses the preserved durations: 750 / 3 hours.
        expect(card(players),"Час: 250 ₽")
    }

    @Test fun `maximum valid payment does not overflow hourly multiplication`() {
        expect(card(listOf(Attendance(1,true,30,paid=Long.MAX_VALUE))),
            "Час: 18446744073709551614 ₽")
    }

    @Test fun `stored payment from nonplaying account remains part of total cost`() {
        expect(card(listOf(Attendance(1,true,60),Attendance(2,false,paid=300))),
            "Час: 300 ₽")
    }
}
