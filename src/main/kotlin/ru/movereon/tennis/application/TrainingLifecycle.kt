package ru.movereon.tennis.application

import ru.movereon.tennis.core.*

/** The same transition graph drives visible buttons and transactional command validation. */
internal object TrainingLifecycle {
    fun targets(phase:TrainingPhase):List<TrainingPhase> = when(phase) {
        TrainingPhase.OPEN -> listOf(TrainingPhase.CANCELLED,TrainingPhase.CLOSED)
        TrainingPhase.CLOSED,TrainingPhase.CANCELLED -> listOf(TrainingPhase.OPEN)
    }

    fun requireTransition(from:TrainingPhase,to:TrainingPhase) =
        checkAccounting(to in targets(from),ErrorCode.INVALID_STATE,"Этот переход недоступен. Открой меню статуса заново")

    fun requireOpen(training:TrainingRecord) = checkAccounting(training.phase==TrainingPhase.OPEN,ErrorCode.INVALID_STATE,
        if(training.phase==TrainingPhase.CANCELLED) "Тренировка отменена" else "Тренировка завершена")

    fun command(training:TrainingRecord,target:TrainingPhase,version:Long):SettlementCommand {
        requireTransition(training.phase,target)
        return when(target) {
            TrainingPhase.CLOSED -> SettlementCommand.FinishTraining(training.id,version)
            TrainingPhase.CANCELLED -> SettlementCommand.CancelTraining(training.id,version)
            TrainingPhase.OPEN -> if(training.phase==TrainingPhase.CLOSED) SettlementCommand.ReopenTraining(training.id,version)
                else SettlementCommand.RestoreTraining(training.id,version)
        }
    }
}
