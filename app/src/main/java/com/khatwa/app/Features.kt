package com.khatwa.app

import com.khatwa.app.quotes.QuoteRepository
import com.khatwa.app.settings.Settings
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Feature wiring that depends on the container (quotes, lock, ...). Kept separate so the
 * container stays a plain list of singletons.
 */
class Features(private val c: AppContainer) {
    val quotes = QuoteRepository(c.app, c.db.quotes())

    fun start() {
        c.scope.launch { quotes.seedIfEmpty() }
        // Recompute the goal whenever the goal settings change.
        c.scope.launch {
            c.settings.flow
                .map { GoalKey(it.tempGoal, it.finalGoal, it.weeklyIncrement, it.manualGoal, it.goalStartDate?.toString()) }
                .distinctUntilChanged()
                .collect { if (c.settings.current().onboardingDone) c.tracker.refreshGoalNow() }
        }
    }

    private data class GoalKey(val t: Int, val f: Int, val i: Int, val m: Int?, val s: String?)
}
