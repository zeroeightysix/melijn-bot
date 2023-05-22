package me.melijn.bot.commands

import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinition
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import com.kotlindiscord.kord.extensions.commands.Arguments
import com.kotlindiscord.kord.extensions.commands.converters.impl.*
import com.kotlindiscord.kord.extensions.extensions.Extension
import com.kotlindiscord.kord.extensions.i18n.TranslationsProvider
import com.kotlindiscord.kord.extensions.types.respond
import com.kotlindiscord.kord.extensions.utils.toDuration
import com.kotlindiscord.kord.extensions.utils.waitFor
import dev.minn.jda.ktx.coroutines.await
import dev.minn.jda.ktx.interactions.components.replyModal
import dev.minn.jda.ktx.messages.InlineEmbed
import dev.minn.jda.ktx.messages.InlineMessage
import dev.minn.jda.ktx.messages.MessageCreate
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import me.melijn.apkordex.command.KordExtension
import me.melijn.bot.database.manager.AttendanceManager
import me.melijn.bot.database.manager.AttendeesManager
import me.melijn.bot.database.model.AttendanceState
import me.melijn.bot.services.AttendanceService
import me.melijn.bot.utils.KoinUtil.inject
import me.melijn.bot.utils.KordExUtils.bail
import me.melijn.bot.utils.KordExUtils.publicGuildSlashCommand
import me.melijn.bot.utils.KordExUtils.publicGuildSubCommand
import me.melijn.bot.utils.KordExUtils.tr
import me.melijn.bot.utils.embedWithColor
import me.melijn.kordkommons.async.TaskScope
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.utils.TimeFormat
import net.dv8tion.jda.api.utils.messages.MessageEditData
import org.koin.core.component.inject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.*
import java.util.concurrent.CancellationException
import kotlin.jvm.optionals.getOrNull
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.minutes
import me.melijn.bot.events.buttons.AttendanceButtonHandler.Companion.ATTENDANCE_BTN_ATTEND as BTN_ATTEND_SUFFIX
import me.melijn.bot.events.buttons.AttendanceButtonHandler.Companion.ATTENDANCE_BTN_PREFIX as BTN_PREFIX
import me.melijn.bot.events.buttons.AttendanceButtonHandler.Companion.ATTENDANCE_BTN_REVOKE as BTN_REVOKE_SUFFIX

@KordExtension
class AttendanceExtension : Extension() {

    override val name: String = "attendance"

    val attendanceManager by inject<AttendanceManager>()
    val attendeesManager by inject<AttendeesManager>()

    override suspend fun setup() {
        publicGuildSlashCommand {
            name = "attendance"
            description = "Manage attendance events"

            publicGuildSubCommand(::AttendanceCreateArgs) {
                name = "create"
                description = "Create a new attendance event"
                noDefer()

                action {
                    var topic = this.arguments.topic
                    var description = this.arguments.description
                    var schedule = this.arguments.schedule
                    var givenMoment = this.arguments.nextMoment

                    val zone = this.arguments.zoneId
                    val atZone = givenMoment.atZone(zone)
                    val ms = atZone.toEpochSecond() * 1000

                    val discordTimestamp = TimeFormat.DATE_TIME_LONG.format(givenMoment.atZone(zone))

                    this.event.replyModal("attendance-create-modal", "Create Attendance Event") {
                        this.short("topic", "Topic", true, topic, placeholder = "Title or topic for the event")
                        this.paragraph("description", "Description", true, description, placeholder = "What is the event about ?")
                        this.short("schedule", "Schedule [QUARTZ CRON format]", false, schedule, placeholder = "0 15 10 ? * 6L 2022-2025")
                        this.short(
                            "moment",
                            "Moment",
                            false,
                            arguments.moment?.toString(),
                            placeholder = "yyyy-MM-dd HH:mm"
                        )
                    }.await()

                    val modalInteractionEvent = shardManager.waitFor<ModalInteractionEvent>(100.minutes) {
                        this.user.idLong == user.idLong && this.modalId == "attendance-create-modal"
                    } ?: bail("modal timeout ${user.asMention}")

                    topic = modalInteractionEvent.getValue("topic")!!.asString
                    description = modalInteractionEvent.getValue("description")?.asString
                    schedule = modalInteractionEvent.getValue("schedule")?.asString

                    // Recalc given moment from modal reply
                    val moment =
                        modalInteractionEvent.getValue("moment")?.asString?.let { DateTimeConverter.parseFromString(it) }
                    givenMoment = nextMomentFromMomentOrSchedule(moment, schedule)

                    val textChannel = arguments.channel
                    val message = textChannel.sendMessage(
                        getAttendanceMessage(
                            topic,
                            description,
                            givenMoment,
                            zone
                        )
                    ).await()

                    val nextMoment = Instant.ofEpochMilli(ms).toKotlinInstant()

                    val closeOffset = arguments.closeOffset?.toDuration(TimeZone.UTC)
                    val notifyOffset = arguments.notifyOffset?.toDuration(TimeZone.UTC)

                    val maxOffset = maxOf(closeOffset ?: ZERO, notifyOffset ?: ZERO)

                    val data = attendanceManager.insertAndGetRow(
                        guild!!.idLong,
                        arguments.channel.idLong,
                        message.idLong,
                        arguments.attendeesRole?.idLong,
                        closeOffset,
                        arguments.notifyAttendees,
                        notifyOffset,
                        topic,
                        description,
                        arguments.repeating,
                        nextMoment,
                        AttendanceState.LISTENING,
                        nextMoment - maxOffset,
                        schedule,
                        zone.toString(),
                        arguments.scheduleTimeout?.toDuration(TimeZone.UTC)
                    )

                    val service by inject<AttendanceService>()
                    service.waitingJob.cancel(CancellationException("new attendance, recheck first"))

                    modalInteractionEvent.interaction.reply(MessageCreate {
                        content = "Next attendance is at: $atZone\n${discordTimestamp}\n${data}"
                    }).await()
                }
            }

            publicGuildSubCommand(::AttendanceRemoveArgs) {
                name = "remove"
                description = "Remove an attendance event"

                action {
                    val attendanceId = arguments.attendanceId
                    val removed = attendanceManager.delete(attendanceId, guild!!.idLong)

                    respond {
                        content = if (removed) {
                            "Removed attendance event with id: $attendanceId"
                        } else {
                            "Failed to remove attendance event with id: $attendanceId"
                        }
                    }
                }
            }

            publicGuildSubCommand {
                name = "list"
                description = "List the attendance events"

                action {
                    val attendanceEvents = attendanceManager.getByGuildKey(guild!!.idLong)

                    respond {
                        embedWithColor {
                            title = "Attendance Events"

                            description = "**id - next moment - topic**\n"
                            description += attendanceEvents.joinToString("\n") { attendance ->
                                val moment = attendance.nextMoment.toJavaInstant()
                                val discordTimeStamp = TimeFormat.DATE_TIME_LONG.format(moment.toEpochMilli())

                                "${attendance.attendanceId} - $discordTimeStamp - ${attendance.topic}"
                            }

                            if (attendanceEvents.isEmpty()) {
                                description = "There are no attendance events in this server"
                            }
                        }
                    }
                }
            }

            publicGuildSubCommand(::AttendanceInfoArgs) {
                name = "info"
                description = "Display all information of an attendance event"

                action {
                    val data = this.arguments.attendanceData.await()

                    respond {
                        content = data.toString()
                    }
                }
            }
        }
    }


    companion object {
        val cronDefinition: CronDefinition = CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ)
        val cronParser = CronParser(cronDefinition)

        fun getAttendanceMessage(
            topic: String,
            description: String?,
            givenMoment: LocalDateTime,
            timeZone: ZoneId
        ) = MessageCreate {
            val discordTimestamp = TimeFormat.DATE_TIME_LONG.format(givenMoment.atZone(timeZone))
            val discordReltime = TimeFormat.RELATIVE.format(givenMoment.atZone(timeZone))
            val translations: TranslationsProvider by inject()
            embed {
                title = topic
                this.description = translations.tr(
                    "attendance.messageLayout.active", Locale.getDefault(),
                    description,
                    discordTimestamp,
                    discordReltime,
                    ""
                )
            }
            actionRow(
                Button.success(BTN_PREFIX + BTN_ATTEND_SUFFIX, "Attend"),
                Button.danger(BTN_PREFIX + BTN_REVOKE_SUFFIX, "Revoke")
            )
        }

        context(InlineMessage<MessageEditData>, InlineEmbed)
        fun applyFinishedMessage(
            topic: String,
            description: String?,
            attendees: String,
            givenMoment: LocalDateTime,
            timeZone: ZoneId
        ) {
            val discordTimestamp = TimeFormat.DATE_TIME_LONG.format(givenMoment.atZone(timeZone))
            val translations: TranslationsProvider by inject()

            this@InlineEmbed.title = "[Finished] $topic"
            this@InlineEmbed.description = translations.tr(
                    "attendance.messageLayout.finished", Locale.getDefault(),
                    description,
                    discordTimestamp,
                    attendees
                )
            this@InlineMessage.builder.setComponents(emptySet())
        }

        fun nextMomentFromCronSchedule(schedule: String): LocalDateTime? {
            val cron = cronParser.parse(schedule)
            val execTimes = ExecutionTime.forCron(cron)
            val now = ZonedDateTime.now(ZoneId.of("UTC"))
            val next = execTimes.nextExecution(now)
            return next.getOrNull()?.toLocalDateTime()
        }
    }

    val jumpUrlRegex = Message.JUMP_URL_PATTERN.toRegex()

    inner class AttendanceInfoArgs : Arguments() {
        private var isId = false
        private lateinit var locale: Locale

        private val attendanceId by string {
            name = "attendance-id"
            description = "The id of the attendance event OR message-reference-url"

            this.validate {
                locale = this.context.resolvedLocale.await()
                val id = this.value.toLongOrNull()
                isId = id != null
                if (isId) {
                    this.pass()
                } else if (jumpUrlRegex.matches(value)) {
                    this.pass()
                } else {
                    this.fail(tr("attendance.suppliedInvalidIdOrMsgUrl"))
                }
            }
        }

        val attendanceData = TaskScope.async(
            TaskScope.dispatcher, start = CoroutineStart.LAZY
        ) {
            val translations: TranslationsProvider by inject()

            return@async if (isId)
                attendanceManager.getByAttendanceKey(attendanceId.toLong())
            else {
                fun regexGroupBail(): Nothing = bail("broken jda regex: Message.JUMP_URL_PATTERN")

                val res = jumpUrlRegex.find(attendanceId) ?: bail("you broke regex!")

                val guildId = res.groups["guild"]?.value?.toLong() ?: regexGroupBail()
                val channelId = res.groups["channel"]?.value?.toLong() ?: regexGroupBail()
                val messageId = res.groups["message"]?.value?.toLong() ?: regexGroupBail()

                attendanceManager.getById(guildId, channelId, messageId)
            } ?: bail(translations.tr("attendance.suppliedInvalidIdOrMsgUrl", locale))
        }
    }

    inner class AttendanceRemoveArgs : Arguments() {
        val attendanceId by long {
            name = "attendance-id"
            description = "The id of the attendance event"
        }
        val boop by optionalBoolean {
            name = "boop"
            description = "boop"
        }
    }

    inner class AttendanceCreateArgs : Arguments() {
        val topic by string {
            name = "topic"
            description = "The topic of the attendance event"
        }
        val channel by channel<TextChannel> {
            name = "channel"
            description = "The channel of the attendance event"
            requireChannelType(ChannelType.TEXT)
            requirePermissions(Permission.MESSAGE_EMBED_LINKS, Permission.MESSAGE_SEND)
        }
        val moment by optionalDateTime {
            name = "moment"
            description = "The moment of the attendance event, you can provide this or schedule"
        }
        val zoneId by defaultingZoneId {
            name = "time-zone"
            description = "Your timezone zoneId (e.g. Europe/Amsterdam, Europe/Brussels, UTC)"
            defaultValue = ZoneId.of("UTC")
        }
        val schedule by optionalString {
            name = "schedule"
            description = "The schedule of the attendance events"
        }
        val description by optionalString {
            name = "description"
            description = "The description of the attendance event"
        }
        val repeating by defaultingBoolean {
            name = "repeating"
            description = "Whether to plan the next attendance based on the schedule after the last one + closeOffset"
            defaultValue = false
        }
        val closeOffset by optionalDuration {
            name = "close-offset"
            description = "The close offset of the attendance event"
        }
        val notifyOffset by optionalDuration {
            name = "notify-offset"
            description = "The notify offset of the attendance event"
        }
        val scheduleTimeout by optionalDuration {
            name = "schedule-timeout"
            description = "The schedule timeout of the attendance event"
        }
        val notifyAttendees by defaultingBoolean {
            name = "notify-attendees"
            description = "Whether or not to notify attendees"
            defaultValue = true
        }
        val attendeesRole by optionalRole {
            name = "attendees-role"
            description = "The role given to attendees"
        }

        val nextMoment: LocalDateTime by lazy {
            nextMomentFromMomentOrSchedule(moment, schedule)
        }
    }

    private fun nextMomentFromMomentOrSchedule(moment: LocalDateTime?, schedule: String?): LocalDateTime =
        if (moment == null && schedule == null) {
            bail("You must provide either a moment or a schedule")
        } else if (moment != null) {
            moment
        } else {
            requireNotNull(schedule) // compiler can't infer this is true sadly
            fun invalidCron(): Nothing =
                bail("The schedule you provided is invalid, it must be a quartz-cron job format")
            try {
                val next = nextMomentFromCronSchedule(schedule)
                next ?: invalidCron()
            } catch (e: Exception) {
                invalidCron()
            }
        }
}