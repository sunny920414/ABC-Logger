package kaist.iclab.abclogger.collector.survey

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.format.DateUtils

import androidx.core.app.AlarmManagerCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.TaskStackBuilder
import kaist.iclab.abclogger.*
import kaist.iclab.abclogger.collector.*
import kaist.iclab.abclogger.collector.survey.setting.SurveySettingActivity
import kaist.iclab.abclogger.ui.question.SurveyResponseActivity
import kotlinx.coroutines.*
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class SurveyCollector(val context: Context) : BaseCollector {
    data class Status(override val hasStarted: Boolean? = null,
                      override val lastTime: Long? = null,
                      val startTime: Long? = null,
                      val settings: List<Setting>? = null) : BaseStatus() {
        override fun info(): String = ""

        data class Setting(
                val id: Int = 0,
                val uuid: String? = null,
                var url: String? = null,
                val json: String? = null,
                val lastTimeTriggered: Long? = null,
                val nextTimeTriggered: Long? = null
        )
    }

    private val receiver: BroadcastReceiver by lazy {
        object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != ACTION_SURVEY_TRIGGER) return

                GlobalScope.launch(Dispatchers.IO) {
                    handleTrigger(intent.getStringExtra(EXTRA_SURVEY_UUID))
                    cancelAll()
                    scheduleAll()
                }
            }
        }
    }

    private val filter = IntentFilter().apply { addAction(ACTION_SURVEY_TRIGGER) }

    private suspend fun handleTrigger(uuid: String?) {
        val setting = (getStatus() as? Status)?.settings?.find { setting -> setting.uuid == uuid } ?: return
        val survey = setting.json?.let { Survey.fromJson(it) } ?: return
        val curTime = System.currentTimeMillis()
        val id = SurveyEntity(
                title = survey.title,
                message = survey.message,
                timeoutPolicy = survey.timeoutPolicy,
                timeoutSec = survey.timeoutSec,
                deliveredTime = curTime,
                json = setting.json
        ).fill(timeMillis = curTime).let { entity ->
            ObjBox.put(entity)
        }

        if (id <= 0) return

        setStatus(Status(lastTime = curTime))

        val surveyIntent = Intent(context, SurveyResponseActivity::class.java).fillExtras(
                SurveyResponseActivity.EXTRA_ENTITY_ID to id,
                SurveyResponseActivity.EXTRA_SHOW_FROM_LIST to false,
                SurveyResponseActivity.EXTRA_SURVEY_TITLE to survey.title,
                SurveyResponseActivity.EXTRA_SURVEY_MESSAGE to survey.message,
                SurveyResponseActivity.EXTRA_SURVEY_DELIVERED_TIME to curTime
        )
        val pendingIntent = TaskStackBuilder.create(context)
                .addNextIntentWithParentStack(surveyIntent)
                .getPendingIntent(REQUEST_CODE_SURVEY_OPEN, PendingIntent.FLAG_UPDATE_CURRENT)

        val notification = Notifications.build(
                context = context,
                channelId = Notifications.CHANNEL_ID_SURVEY,
                title = survey.title,
                text = survey.message,
                subText = DateUtils.formatDateTime(
                        context, curTime,
                        DateUtils.FORMAT_NO_YEAR or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME
                ),
                intent = pendingIntent
        )

        NotificationManagerCompat.from(context).notify(Notifications.ID_SURVEY_DELIVERED, notification)
    }

    private suspend fun scheduleAll(event: ABCEvent? = null) {
        updateSettings(event)?.forEach { setting -> scheduleSurvey(setting) }
    }

    private suspend fun cancelAll() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        (getStatus() as? Status)?.settings?.forEach { setting ->
            alarmManager.cancel(getPendingIntent(id = setting.id, uuid = setting.uuid ?: ""))
        }
    }

    private fun getPendingIntent(id: Int, uuid: String) = PendingIntent.getBroadcast(
            context, id,
            Intent(ACTION_SURVEY_TRIGGER).putExtra(EXTRA_SURVEY_UUID, uuid),
            PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun scheduleSurvey(setting: Status.Setting) {
        val nextTimeTriggered = setting.nextTimeTriggered ?: 0
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = getPendingIntent(setting.id, setting.uuid ?: "")

        if (nextTimeTriggered > 0) {
            val curTime = System.currentTimeMillis()
            val triggerTime = if (nextTimeTriggered < curTime + TimeUnit.SECONDS.toMillis(10)) {
                curTime + TimeUnit.SECONDS.toMillis(10)
            } else {
                nextTimeTriggered
            }
            AlarmManagerCompat.setExactAndAllowWhileIdle(alarmManager, AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
        } else {
            alarmManager.cancel(pendingIntent)
        }
    }

    private suspend fun updateSettings(event: ABCEvent? = null) : List<Status.Setting>? {
        val startTime = (getStatus() as? Status)?.startTime ?: return null

        val settings = (getStatus() as? Status)?.settings?.mapNotNull { setting ->
            setting.json?.let { json -> Survey.fromJson(json) }?.let { survey ->
                when (survey) {
                    is IntervalBasedSurvey -> updateIntervalBasedSurvey(survey = survey, setting = setting, startTime = startTime)
                    is ScheduleBasedSurvey -> updateScheduleBasedSurvey(survey = survey, setting = setting)
                    is EventBasedSurvey -> updateEventBasedSurvey(survey = survey, setting = setting, event = event)
                    else -> null
                }
            }
        }
        setStatus(Status(settings = settings))
        return settings
    }

    private fun updateIntervalBasedSurvey(survey: IntervalBasedSurvey,
                                          setting: Status.Setting,
                                          startTime: Long): Status.Setting {
        val curTime = System.currentTimeMillis()

        val initDelayMs = if (survey.initialDelaySec > 0) {
            TimeUnit.SECONDS.toMillis(survey.initialDelaySec)
        } else {
            0
        }
        val intervalMs = if (survey.intervalSec > 0) {
            TimeUnit.SECONDS.toMillis(survey.intervalSec)
        } else {
            0
        }
        val flexMs = if (survey.flexIntervalSec > 0) {
            TimeUnit.SECONDS.toMillis(Random.nextLong(survey.flexIntervalSec))
        } else {
            0
        }

        val initialTriggerAt: Long = startTime + initDelayMs
        val lastTimeTriggered: Long = setting.lastTimeTriggered ?: 0
        val nextTimeTriggered: Long = setting.nextTimeTriggered ?: 0

        return when {
            curTime < initialTriggerAt -> setting.copy(nextTimeTriggered = initialTriggerAt)
            curTime in (initialTriggerAt..nextTimeTriggered) -> setting.copy()
            else -> setting.copy(nextTimeTriggered = lastTimeTriggered + intervalMs + flexMs)
        }
    }

    private fun updateEventBasedSurvey(survey: EventBasedSurvey,
                                       setting: Status.Setting,
                                       event: ABCEvent? = null): Status.Setting {
        val intervalMs = if (survey.delayAfterTriggerEventSec > 0) {
            TimeUnit.SECONDS.toMillis(survey.delayAfterTriggerEventSec)
        } else {
            0
        }
        val flexMs = if (survey.flexDelayAfterTriggerEventSec > 0) {
            TimeUnit.SECONDS.toMillis(Random.nextLong(survey.flexDelayAfterTriggerEventSec))
        } else {
            0
        }

        return when (event?.eventType) {
            in survey.triggerEvents -> setting.copy(
                    nextTimeTriggered = event?.timestamp?.plus(intervalMs + flexMs) ?: 0
            )
            in survey.cancelEvents -> setting.copy(
                    nextTimeTriggered = 0
            )
            else -> setting.copy()
        }
    }

    private fun updateScheduleBasedSurvey(survey: ScheduleBasedSurvey, setting: Status.Setting): Status.Setting {
        val curTime = System.currentTimeMillis()
        val calendar = GregorianCalendar.getInstance(TimeZone.getDefault()).apply {
            timeInMillis = curTime
        }
        val curDate = calendar.time

        val nextTimeTriggered = survey.schedules.mapNotNull { schedule ->
            (0..Int.MAX_VALUE step 7).firstNotNullResult { day ->
                val triggerCalendar = calendar.apply {
                    set(GregorianCalendar.DAY_OF_WEEK, schedule.dayOfWeek.id)
                    set(GregorianCalendar.HOUR_OF_DAY, schedule.hour)
                    set(GregorianCalendar.MINUTE, schedule.minute)
                    set(GregorianCalendar.SECOND, 0)
                    set(GregorianCalendar.MILLISECOND, 0)

                    add(GregorianCalendar.DAY_OF_YEAR, day)
                }
                val triggerDate = triggerCalendar.time

                return@firstNotNullResult if (triggerDate.after(curDate)) {
                    triggerDate.time
                } else {
                    null
                }
            }
        }.min() ?: 0

        return setting.copy(nextTimeTriggered = nextTimeTriggered)
    }

    override suspend fun onStart() {
        ABCEvent.register(this)

        setStatus(Status(startTime = System.currentTimeMillis()))

        cancelAll()
        scheduleAll()

        context.safeRegisterReceiver(receiver, filter)
    }

    override suspend fun onStop() {
        ABCEvent.unregister(this)
        cancelAll()

        context.safeUnregisterReceiver(receiver)
    }

    override suspend fun checkAvailability(): Boolean =
            !(getStatus() as? Status)?.settings.isNullOrEmpty() && context.checkPermission(requiredPermissions)

    override val requiredPermissions: List<String>
        get() = listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE)

    override val newIntentForSetUp: Intent?
        get() = Intent(context, SurveySettingActivity::class.java)

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    fun onEvent(event: ABCEvent) {
        GlobalScope.launch(Dispatchers.IO) { scheduleAll(event) }
    }

    companion object {
        private const val EXTRA_SURVEY_UUID = "${BuildConfig.APPLICATION_ID}.EXTRA_SURVEY_UUID"
        private const val ACTION_SURVEY_TRIGGER = "${BuildConfig.APPLICATION_ID}.ACTION_SURVEY_TRIGGER"
        private const val REQUEST_CODE_SURVEY_OPEN = 0xdd
    }
}