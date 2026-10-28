package ru.movereon.tennis.selfservice

import ru.movereon.tennis.application.*
import ru.movereon.tennis.selfservice.FinanceScreens.Companion.signed

/** Explicit private read-only surface. Never renders a group roster, transfer card or mutation. */
internal class FormerMemberScreens(private val service:SettlementService) {
    fun render(layout:ScreenLayout,a:Access):ScreenContent=with(layout) {
        when(action.kind) {
            "finance" -> {
                val balance=service.ownBalance(a)
                val description=when {
                    balance>0 -> "Оплачено тобой за других участников группы."
                    balance<0 -> "Оплачено за тебя другими участниками группы."
                    else -> "Нулевой баланс."
                }
                val hint="Ты больше не участник группы. Доступна только своя история, без изменения данных."
                val amount=inline(signed(balance),ScreenAction("finance_balance_history",a.groupId),if(balance>0) "success" else if(balance<0) "danger" else "link")
                row("📜 История баланса",ScreenAction("finance_balance_history",a.groupId))
                row("🏓 Мои тренировки",ScreenAction("own_group_trainings",a.groupId))
                row("⬅️ Назад",ScreenAction("groups_back",0,option="finance"))
                ScreenContent("Мои финансы:\n${signed(balance)}\n$description\n$hint",
                    "<h3>Мои финансы</h3><p>$amount<br>$description</p><p>$hint</p>")
            }
            "finance_balance_history" -> renderBalanceHistory(this,a,service.balanceHistory(a,action.page),
                service.group(a.groupId).timeZone,{ "" },restricted=true)
            "own_group_trainings" -> {
                val p=service.trainings(a,action.page,mine=true)
                val content=TrainingList.render("Мои тренировки · ${p.total}",p.items,personalUser=a.userId) {
                    button(Screens.clean(it.title,34),ScreenAction("my_training",a.groupId,it.id,back=action.copy(page=p.index)))
                }
                pages(p.index,p.pages)
                row("⬅️ Назад",ScreenAction("finance",a.groupId))
                content
            }
            "my_training" -> {
                val content=TrainingCard.render(service.ownTraining(a,action.id),service::account,onlyUser=a.userId)
                // Old cards may carry an editor or transfer as their return address. Keep this surface closed.
                val parent=action.back?.takeIf { it.kind=="my_trainings" && it.group==0L ||
                    it.kind in setOf("finance_balance_history","own_group_trainings") && it.group==a.groupId }
                    ?: ScreenAction("own_group_trainings",a.groupId)
                row("⬅️ Назад",parent)
                ScreenContent(content.text,content.html)
            }
            else -> error("Not an own-history screen: ${action.kind}")
        }
    }
    companion object { val kinds=setOf("finance","finance_balance_history","my_training","own_group_trainings") }
}
