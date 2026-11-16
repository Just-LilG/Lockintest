package com.godbless.lockin.home

import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.godbless.lockin.data.Plan
import com.godbless.lockin.data.PlanRepository
import com.godbless.lockin.push.PlanReminders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Your daily goal and your partner's, in minutes. Every screen that shows progress reads these. */
internal val LocalGoalMinutes = compositionLocalOf { PlanState.DefaultGoal }
internal val LocalPartnerGoalMinutes = compositionLocalOf { PlanState.DefaultGoal }

/**
 * Daily goals and the sessions you and your partner have planned. Goals are kept on the phone too, so
 * the mountain is right from the first frame, even before the first answer from the server.
 */
internal class PlanState(
    private val context: Context,
    private val repo: PlanRepository,
    private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("goals", Context.MODE_PRIVATE)

    var goalMinutes by mutableStateOf(prefs.getInt("mine", DefaultGoal))
        private set
    var partnerGoalMinutes by mutableStateOf(prefs.getInt("theirs", DefaultGoal))
        private set

    /** Days you rest on purpose (bit 0 is Monday). Kept on this phone: they only decide what counts toward your streak. */
    @set:JvmName("assignRestMask")
    var restMask by mutableStateOf(prefs.getInt("rest", 0))
        private set
    val restDays: Set<java.time.DayOfWeek>
        get() = java.time.DayOfWeek.entries.filter { restMask and (1 shl (it.value - 1)) != 0 }.toSet()

    fun setRestMask(mask: Int) {
        restMask = mask and 0x7F
        prefs.edit().putInt("rest", restMask).apply()
    }

    /** Upcoming sessions, soonest first. */
    val plans = mutableStateListOf<Plan>()

    @set:JvmName("assignReminders")
    var reminders by mutableStateOf(PlanReminders.enabled(context))
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    private var saveGoal: Job? = null

    suspend fun refresh(paired: Boolean) {
        repo.goals().onSuccess { g ->
            // A goal still waiting to be saved wins over what the server last heard.
            if (saveGoal?.isActive != true) {
                goalMinutes = g.mine
                prefs.edit().putInt("mine", g.mine).apply()
            }
            g.theirs?.let {
                partnerGoalMinutes = it
                prefs.edit().putInt("theirs", it).apply()
            }
        }
        if (!paired) {
            if (plans.isNotEmpty()) plans.clear()
            PlanReminders.sync(context, emptyList())
            return
        }
        repo.plans().onSuccess { list ->
            plans.clear()
            plans.addAll(list)
            PlanReminders.sync(context, list)
        }
    }

    /** Changing the goal shows at once and is saved once the buttons have been left alone for a moment. */
    fun setGoal(minutes: Int) {
        goalMinutes = minutes
        prefs.edit().putInt("mine", minutes).apply()
        saveGoal?.cancel()
        saveGoal = scope.launch {
            delay(700)
            repo.setGoal(minutes).onFailure { error = it.message }
        }
    }

    fun setReminders(on: Boolean) {
        reminders = on
        PlanReminders.setEnabled(context, on, plans.toList())
    }

    /** Plans a session. [done] runs when it was saved. */
    fun plan(title: String, startsAt: Long, minutes: Int, done: () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            repo.plan(title, startsAt, minutes)
                .onSuccess {
                    refresh(paired = true)
                    done()
                }
                .onFailure { error = it.message }
            busy = false
        }
    }

    fun cancel(plan: Plan) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            repo.cancel(plan.id)
                .onSuccess { plans.removeAll { it.id == plan.id }; PlanReminders.sync(context, plans.toList()) }
                .onFailure { error = it.message }
            busy = false
        }
    }

    /** The next session that has not finished yet. */
    fun next(now: Long): Plan? = plans.firstOrNull { it.startsAt + it.minutes * 60_000L > now }

    companion object {
        const val DefaultGoal = 120
    }
}
