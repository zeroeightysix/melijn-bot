package me.melijn.bot.services

import dev.minn.jda.ktx.coroutines.await
import dev.minn.jda.ktx.messages.InlineEmbed
import dev.minn.jda.ktx.messages.InlineMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.toJavaInstant
import me.melijn.ap.injector.Inject
import me.melijn.bot.commands.AttendanceExtension
import me.melijn.bot.database.manager.AttendanceManager
import me.melijn.bot.database.manager.AttendeesManager
import me.melijn.bot.database.model.AttendanceState
import me.melijn.bot.utils.ExceptionUtil.unreachable
import me.melijn.bot.utils.JDAUtil.awaitOrNull
import me.melijn.bot.utils.KoinUtil.inject
import me.melijn.bot.utils.Log
import me.melijn.gen.AttendanceData
import me.melijn.kordkommons.async.TaskScope
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.sharding.ShardManager
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder
import net.dv8tion.jda.api.utils.messages.MessageEditData
import java.time.ZoneId
import kotlin.time.Duration

class UnHandleableAttendanceException : Throwable() {

}

@Inject
class AttendanceService {

    private val attendanceManager by inject<AttendanceManager>()
    private val attendeesManager by inject<AttendeesManager>()
    private val shardManager by inject<ShardManager>()
    private val logger by Log
    private val mentionRegex = Message.MentionType.USER.pattern.toRegex()

    /** it's the job of [AttendanceExtension] to cancel [waitingJob] */
    var waitingJob = TaskScope.launch { }

    init {
        TaskScope.launch {
            while (true) {
                val attendanceEntry = attendanceManager.getNextChangingEntry()
                if (attendanceEntry == null) {
                    waitingJob = launch { delay(Duration.INFINITE) }
                    waitingJob.join()
                    continue
                }

                val now = Clock.System.now()

                if (attendanceEntry.nextStateChangeMoment <= now) {
                    try {
                        doUpdates(attendanceEntry)
                        attendanceManager.store(attendanceEntry)
                    } catch (t: UnHandleableAttendanceException) {
                        attendanceEntry.apply {
                            this.state = AttendanceState.DISABLED
                        }
                        attendanceManager.store(attendanceEntry)
                    } catch (t: Exception) {
                        logger.error(t) { "error while processing attendanceId: ${attendanceEntry.attendanceId}" }
                    }
                }

                waitingJob = launch {
                    if (attendanceEntry.nextStateChangeMoment > now) {
                        val duration = attendanceEntry.nextStateChangeMoment - now
                        logger.info { "Next attendance state change in: $duration" }
                        delay(duration)
                    }
                }
                waitingJob.join()
            }
        }
    }

    private suspend fun doUpdates(entry: AttendanceData) {
        val guild = shardManager.getGuildById(entry.guildId)
        val textChannel = guild
            ?.getTextChannelById(entry.channelId)
            ?.takeIf {
                it.guild.selfMember.hasPermission(
                    it,
                    Permission.MESSAGE_SEND,
                    Permission.MESSAGE_EMBED_LINKS
                )
            }
        val message = textChannel
            ?.retrieveMessageById(entry.messageId)
            ?.awaitOrNull() ?: throw UnHandleableAttendanceException()

        var nextAvailableState = entry.nextAvailableState()
        if (nextAvailableState == null) {
            if (entry.state == AttendanceState.FINISHED) {

                reopenAttendance(entry, textChannel)
            } else {
                logger.warn { "We're trying to update an attendance $entry that has no next state" }
            }
            return
        }

        val builder = MessageEditBuilder().applyMessage(message)
        val messageEditor = InlineMessage<MessageEditData>(builder)
        val messageEmbed = message.embeds.first()
        val embedEditor = InlineEmbed(messageEmbed)


        while (nextAvailableState != null) {
            val nextState = nextAvailableState
            requireNotNull(nextState)
            entry.apply {
                this.state = nextState
            }

            when (nextState) {
                AttendanceState.DISABLED -> unreachable()
                AttendanceState.LISTENING -> unreachable()
                AttendanceState.CLOSED -> {
                    embedEditor.apply {
                        this.title = "[Closed] ${entry.topic}"
                    }

                    entry.nextStateChangeMoment = entry.getNextStateChangeMoment()
                }

                AttendanceState.NOTIFIED -> {
                    val role = entry.notifyRoleId?.let { guild.getRoleById(it) }
                    if (role != null) {
                        val msg = textChannel
                            .sendMessage("Reminder: ${role.asMention}")
                            .mentionRoles(role.id)
                            .setMessageReference(message.idLong)
                            .awaitOrNull() ?: throw UnHandleableAttendanceException()
                        entry.notifyMessageId = msg.idLong
                    } else {
                        entry.notifyOffset = null
                    }

                    entry.nextStateChangeMoment = entry.getNextStateChangeMoment()
                }

                AttendanceState.FINISHED -> {
                    with(messageEditor) {
                        with(embedEditor) {
                            val attendees = messageEmbed.description?.lines()?.filter {
                                it.contains(mentionRegex)
                            }?.joinToString("\n") ?: ""
                            AttendanceExtension.applyFinishedMessage(
                                entry.topic,
                                entry.description,
                                attendees,
                                entry.nextMoment.toJavaInstant()
                            )
                        }
                    }
                    entry.nextStateChangeMoment = entry.getNextStateChangeMoment()
                }
            }

            nextAvailableState = entry.nextAvailableState()
        }

        messageEditor.builder.setEmbeds(embedEditor.build())
        message.editMessage(messageEditor.build()).queue()
    }

    private suspend fun reopenAttendance(
        entry: AttendanceData,
        textChannel: TextChannel
    ) {
        /** Reopen attendance logic */
        val nextMoment = entry.schedule?.let {
            try {
                AttendanceExtension.nextMomentFromCronSchedule(it)
            } catch (t: Throwable) {
                logger.error(t) { "A corrupt cron schedule is trying to get processed" }
                throw UnHandleableAttendanceException()
            }
        } ?: throw UnHandleableAttendanceException()

        val timeZone = entry.zoneId?.let { ZoneId.of(it) } ?: ZoneId.of("UTC")
        val nextZoned = nextMoment.atZone(timeZone)
        val ms = nextZoned.toEpochSecond() * 1000
        val messageData = AttendanceExtension.getAttendanceMessage(
            entry.topic, entry.description, nextZoned
        )

        val nextInstant = Instant.fromEpochMilliseconds(ms)
        entry.apply {
            this.messageId = textChannel.sendMessage(messageData).await().idLong
            this.state = AttendanceState.LISTENING
            this.nextMoment = nextInstant
            this.nextStateChangeMoment =
                nextInstant - maxOf(this.closeOffset ?: Duration.ZERO, this.notifyOffset ?: Duration.ZERO)
        }

        // recreates the notify role, so it's removed from attendees, saves the new id to the entry state
        entry.notifyRoleTemplateId?.let {
            val templateRole = textChannel.guild.getRoleById(it) ?: return@let
            val oldNotifyRole = entry.notifyRoleId?.let { it1 -> textChannel.guild.getRoleById(it1) }
            val newNotifyRole =
                textChannel.guild
                    .createCopyOfRole(templateRole)
                    .setName(templateRole.name + "*")
                    .reason("(attendance) create notify role").awaitOrNull()
            entry.notifyRoleId = newNotifyRole?.idLong
            oldNotifyRole?.delete()?.reason("(attendance) delete old notify role")?.queue()

            // Make the now outdated notify message appear still valid by swapping out
            // the mention for the template role mention
            entry.notifyMessageId?.let { it1 ->
                textChannel.retrieveMessageById(it1)
                    .awaitOrNull()
                    ?.editMessage("Reminder: ${templateRole.asMention}")
                    ?.queue()
                entry.notifyMessageId = null
            }

        }

        // deregister all attendees
        attendeesManager.deleteByAttendenceKey(entry.attendanceId)
    }

    private fun AttendanceData.getNextStateChangeMoment(): Instant {
        if (this.state == AttendanceState.DISABLED) throw IllegalArgumentException()
        if (this.state == AttendanceState.FINISHED) return this.nextMoment + (this.scheduleTimeout ?: Duration.ZERO)
        val nextState = nextAvailableState(Instant.DISTANT_FUTURE) ?: throw UnHandleableAttendanceException()
        return when (nextState) {
            AttendanceState.DISABLED -> unreachable()
            AttendanceState.LISTENING -> this.nextMoment + (this.scheduleTimeout ?: Duration.ZERO)
            AttendanceState.CLOSED -> this.nextMoment - this.closeOffset!!
            AttendanceState.NOTIFIED -> this.nextMoment - this.notifyOffset!!
            AttendanceState.FINISHED -> this.nextMoment
        }
    }

    private fun AttendanceData.nextAvailableState(now: Instant = Clock.System.now()): AttendanceState? {
        val closeOffset = this.closeOffset
        val notifyOffset = this.notifyOffset

        when (this.state) {
            AttendanceState.LISTENING -> {
                return if (closeOffset == null && notifyOffset == null) {
                    if (this.nextMoment < now) {
                        AttendanceState.FINISHED
                    } else null
                } else if (closeOffset != null && notifyOffset != null) {
                    if (this.nextMoment - closeOffset < now) {
                        if (notifyOffset > closeOffset) AttendanceState.NOTIFIED
                        else AttendanceState.CLOSED
                    } else if (this.nextMoment - notifyOffset < now) {
                        if (closeOffset > notifyOffset) AttendanceState.CLOSED
                        else AttendanceState.NOTIFIED
                    } else null
                } else if (closeOffset != null) {
                    if (this.nextMoment - closeOffset < now) {
                        AttendanceState.CLOSED
                    } else null
                } else if (notifyOffset != null) {
                    if (this.nextMoment - notifyOffset < now) {
                        AttendanceState.NOTIFIED
                    } else null
                } else {
                    unreachable()
                }
            }

            AttendanceState.CLOSED -> {
                return if (notifyOffset == null || this.notifyRoleId == null || notifyOffset > closeOffset!!) {
                    if (this.nextMoment < now) {
                        AttendanceState.FINISHED
                    } else null
                } else {
                    if (this.nextMoment - notifyOffset < now && notifyOffset < closeOffset) {
                        AttendanceState.NOTIFIED
                    } else null
                }
            }

            AttendanceState.NOTIFIED -> {
                return if (closeOffset == null || closeOffset > notifyOffset!!) {
                    if (this.nextMoment < now) {
                        AttendanceState.FINISHED
                    } else null
                } else {
                    if (this.nextMoment - closeOffset < now && closeOffset < notifyOffset) {
                        AttendanceState.CLOSED
                    } else null
                }
            }

            AttendanceState.FINISHED -> return null
            AttendanceState.DISABLED -> return null
        }
    }
}