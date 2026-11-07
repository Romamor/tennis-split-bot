package ru.movereon.tennis.telegram

/** Telegram's explicit delay is a lower bound, never a value to cap. */
internal object RetryPolicy {
    fun delayMillis(failure:TelegramFailure,defaultSeconds:Int=5):Long =
        (failure.retryAfter ?: defaultSeconds).coerceAtLeast(1).toLong()*1000L
    fun deadline(now:Long,failure:TelegramFailure,defaultSeconds:Int=5):Long =
        Math.addExact(now,delayMillis(failure,defaultSeconds))
}
