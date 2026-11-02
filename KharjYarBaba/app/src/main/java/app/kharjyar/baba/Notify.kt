package app.kharjyar.baba

import android.app.*
import android.content.*
import android.graphics.drawable.Icon
import android.provider.Telephony
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.*

object Notifier {
    const val CHANNEL = "ask"
    val CATS = listOf("سوپرمارکت", "بنزین و ماشین", "قبض", "دارو و درمان", "رستوران", "قسط", "سایر")

    fun num(n: Long): String = NumberFormat.getInstance(Locale("fa")).format(n)
    fun toman(rial: Long) = num(rial / 10) + " تومان"

    /** Notification with 3 one-tap answer buttons; the rest of the categories are in the app. */
    fun ask(ctx: Context, id: Long, rial: Long, merchant: String?) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "سؤال از خرج‌ها", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(ctx, id.toInt(), Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("${toman(rial)} برداشت شد")
            .setContentText(merchant ?: "این چی بود؟ یک دکمه بزن")
            .setContentIntent(open).setAutoCancel(true)
        CATS.take(3).forEachIndexed { i, c ->
            val intent = Intent(ctx, CategoryReceiver::class.java).putExtra("id", id).putExtra("cat", c)
            val pi = PendingIntent.getBroadcast(ctx, (id * 10 + i).toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.addAction(Notification.Action.Builder(null as Icon?, c, pi).build())
        }
        nm.notify(id.toInt(), b.build())
    }
}

object Ingest {
    suspend fun handle(ctx: Context, body: String, time: Long, notify: Boolean) {
        val r = Parser.parse(body) ?: return
        val id = Db.get(ctx).dao().insert(
            Tx(fingerprint = "$time|${body.hashCode()}", isDebit = r.debit, amountRial = r.rial,
                balanceRial = r.balance, merchant = r.merchant, category = null, time = time, body = body)
        )
        if (id > 0 && notify && r.debit) Notifier.ask(ctx, id, r.rial, r.merchant)
    }
}

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(i)
        if (parts.isNullOrEmpty()) return
        val body = parts.joinToString("") { it.messageBody ?: "" }
        val time = parts.first().timestampMillis
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch { try { Ingest.handle(ctx, body, time, true) } finally { pending.finish() } }
    }
}

class CategoryReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val id = i.getLongExtra("id", -1)
        val cat = i.getStringExtra("cat") ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch { try { Db.get(ctx).dao().setCategory(id, cat) } finally { pending.finish() } }
        ctx.getSystemService(NotificationManager::class.java).cancel(id.toInt())
    }
}
