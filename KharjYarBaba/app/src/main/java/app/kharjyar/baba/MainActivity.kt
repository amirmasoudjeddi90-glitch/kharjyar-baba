package app.kharjyar.baba

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.icu.text.SimpleDateFormat
import android.icu.util.Calendar
import android.icu.util.PersianCalendar
import android.icu.util.ULocale
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private val Teal = Color(0xFF0F766E)

/** Start of the current Persian (Jalali) month. */
fun monthStart(): Long {
    val c = PersianCalendar()
    c.set(Calendar.DAY_OF_MONTH, 1); c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

class MainVM(app: Application) : AndroidViewModel(app) {
    private val dao = Db.get(app).dao()
    val list = dao.all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val spent = dao.spent(monthStart()).map { it ?: 0L }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    fun label(id: Long, cat: String?) { viewModelScope.launch { dao.setCategory(id, cat) } }

    /** One-time catch-up: reads the last 90 days of the inbox (duplicates are ignored by the DB). */
    fun importHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            val since = System.currentTimeMillis() - 90L * 24 * 3600 * 1000
            getApplication<Application>().contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI, arrayOf("body", "date"), "date > ?", arrayOf(since.toString()), "date ASC"
            )?.use { c -> while (c.moveToNext()) Ingest.handle(getApplication(), c.getString(0) ?: "", c.getLong(1), false) }
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Teal)) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { Root() }
            }
        }
    }
}

@Composable
fun Root(vm: MainVM = viewModel()) {
    val ctx = LocalContext.current
    val perms = remember {
        buildList {
            add(Manifest.permission.RECEIVE_SMS); add(Manifest.permission.READ_SMS)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }
    var granted by remember {
        mutableStateOf(listOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS).all {
            ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
        })
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        granted = r[Manifest.permission.READ_SMS] == true && r[Manifest.permission.RECEIVE_SMS] == true
    }
    LaunchedEffect(granted) { if (granted) vm.importHistory() }

    if (!granted) {
        Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Text("خرج‌یار", fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text("برای اینکه خودش پیامک‌های بانک را بخواند و بپرسد «این چی بود؟»، اجازه‌ی پیامک و اعلان لازم است. پیامک‌ها فقط روی همین گوشی می‌مانند.",
                fontSize = 18.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Button(onClick = { launcher.launch(perms) }) { Text("اجازه می‌دهم", fontSize = 20.sp) }
        }
        return
    }

    val txs by vm.list.collectAsStateWithLifecycle()
    val spent by vm.spent.collectAsStateWithLifecycle()
    val waiting = txs.count { it.isDebit && it.category == null }
    LazyColumn(
        Modifier.fillMaxSize().background(Color(0xFFF6F7F5)),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Summary(spent, waiting) }
        items(txs, key = { it.id }) { t -> TxCard(t) { c -> vm.label(t.id, c) } }
    }
}

@Composable
fun Summary(spent: Long, waiting: Int) {
    Card(colors = CardDefaults.cardColors(containerColor = Teal)) {
        Column(Modifier.padding(20.dp).fillMaxWidth()) {
            Text("خرج این ماه", color = Color.White.copy(alpha = .8f), fontSize = 18.sp)
            Text(Notifier.toman(spent), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            if (waiting > 0) Text("${Notifier.num(waiting.toLong())} مورد منتظر جواب «این چی بود؟» است", color = Color(0xFFFDE68A), fontSize = 17.sp)
        }
    }
}

private val dateFmt = SimpleDateFormat("EEEE d MMMM، HH:mm", ULocale("fa_IR@calendar=persian"))

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TxCard(t: Tx, onLabel: (String?) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                (if (t.isDebit) "− " else "+ ") + Notifier.toman(t.amountRial),
                fontSize = 24.sp, fontWeight = FontWeight.Bold,
                color = if (t.isDebit) Color(0xFFB91C1C) else Color(0xFF15803D)
            )
            Text(dateFmt.format(Date(t.time)) + (t.merchant?.let { " · $it" } ?: ""), fontSize = 16.sp, color = Color.Gray)
            if (t.isDebit) {
                if (t.category == null) {
                    Text("این چی بود؟", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Notifier.CATS.forEach { c ->
                            FilledTonalButton(onClick = { onLabel(c) }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                                Text(c, fontSize = 17.sp)
                            }
                        }
                    }
                } else {
                    TextButton(onClick = { onLabel(null) }) { Text("${t.category}  ✎ تغییر", fontSize = 18.sp) }
                }
            }
        }
    }
}
