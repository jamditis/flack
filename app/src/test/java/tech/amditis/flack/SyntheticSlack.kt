package tech.amditis.flack

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import org.robolectric.Shadows.shadowOf
import tech.amditis.flack.core.SLACK_PACKAGE

/**
 * Builds Slack-shaped notifications for tests. Everything here is invented:
 * people, text and keys. Never paste real Slack data into a test.
 */
object SyntheticSlack {
    const val SLACK_UID = 10123
    const val REPLY_KEY = "slack_reply_text"

    /** Stand-in for Slack's reply action, sent to a broadcast the test can observe. */
    fun replyIntent(context: Context, conversation: String, creator: String = SLACK_PACKAGE, creatorUid: Int = SLACK_UID): PendingIntent {
        val intent = Intent("test.slack.REPLY").setPackage(context.packageName)
            .setData(Uri.Builder().scheme("test").authority("slack").appendPath(conversation).build())
            .putExtra("conversation", conversation)
        val pending = PendingIntent.getBroadcast(context, conversation.hashCode(), intent, PendingIntent.FLAG_MUTABLE)
        shadowOf(pending).setCreatorPackage(creator)
        shadowOf(pending).setCreatorUid(creatorUid)
        return pending
    }

    enum class Kind(val title: String?, val group: Boolean?) {
        DM(null, false),
        CHANNEL("#paper-kites", true),
        PRIVATE_CHANNEL("Private paper room", true),
        GROUP_DM("Riley Example, Sam Example", true),
        THREAD("#paper-kites · Paper launch thread", true),
        UNKNOWN("Paper room", null),
    }

    fun message(context: Context, kind: Kind, key: String = kind.name, reply: PendingIntent? = replyIntent(context, key)) =
        dm(context, key = key, isGroup = kind.group, title = kind.title, reply = reply)

    fun dm(
        context: Context,
        key: String = "0|com.Slack|42|null|$SLACK_UID",
        sender: String? = "Riley Example",
        text: String = "Lunch at noon?",
        isGroup: Boolean? = false,
        title: String? = null,
        reply: PendingIntent? = replyIntent(context, key),
        extraReply: PendingIntent? = null,
        freeForm: Boolean = true,
        packageName: String = SLACK_PACKAGE,
        user: UserHandle = Process.myUserHandle(),
        extraMessages: List<Pair<String?, String>> = emptyList(),
        replySemanticAction: Int = NotificationCompat.Action.SEMANTIC_ACTION_NONE,
        extraReplySemanticAction: Int = NotificationCompat.Action.SEMANTIC_ACTION_NONE,
    ): StatusBarNotification {
        val me = Person.Builder().setName("Me").build()
        val style = NotificationCompat.MessagingStyle(me).setConversationTitle(title)
        if (isGroup != null) style.setGroupConversation(isGroup)
        var time = 1_000L
        extraMessages.forEach { (who, body) ->
            style.addMessage(body, time++, who?.let { Person.Builder().setName(it).build() })
        }
        style.addMessage(text, time, sender?.let { Person.Builder().setName(it).build() })

        val builder = NotificationCompat.Builder(context, "slack")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setStyle(style)
        if (reply != null) {
            val input = RemoteInput.Builder(REPLY_KEY).setAllowFreeFormInput(freeForm)
            if (!freeForm) input.setChoices(arrayOf("Yes", "No"))
            builder.addAction(
                NotificationCompat.Action.Builder(0, "Reply", reply)
                    .addRemoteInput(input.build())
                    .setSemanticAction(replySemanticAction)
                    .setShowsUserInterface(false)
                    .build(),
            )
        }
        if (extraReply != null) {
            builder.addAction(
                NotificationCompat.Action.Builder(0, "Reply in thread", extraReply)
                    .addRemoteInput(RemoteInput.Builder("thread_text").build())
                    .setSemanticAction(extraReplySemanticAction)
                    .setShowsUserInterface(false)
                    .build(),
            )
        }
        val notification = builder.build()
        if (isGroup == null) notification.extras.remove(Notification.EXTRA_IS_GROUP_CONVERSATION)
        return statusBar(notification, key, packageName, user)
    }

    @Suppress("DEPRECATION")
    fun statusBar(notification: Notification, key: String, packageName: String = SLACK_PACKAGE, user: UserHandle = Process.myUserHandle()) =
        object : StatusBarNotification(packageName, packageName, key.hashCode(), null, SLACK_UID, 0, 0, notification, user, 0L) {
            override fun getKey() = key
        }
}
