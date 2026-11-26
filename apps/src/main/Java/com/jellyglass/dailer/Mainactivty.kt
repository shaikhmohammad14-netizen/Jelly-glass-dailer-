package com.jellyglass.dialer

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.CallScreeningService
import android.telecom.InCallService
import android.telecom.VideoProfile
import androidx.compose.runtime.*
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.compose.ui.graphics.toArgb

/** Shared call state used by the service, notifications and the call screens. */
object CallManager {
    var current by mutableStateOf<Call?>(null)
    var tick by mutableIntStateOf(0)
    var muted by mutableStateOf(false)
    var speaker by mutableStateOf(false)
    var service: InCallService? = null

    fun number(call: Call): String = call.details.handle?.schemeSpecificPart ?: ""

    fun displayName(ctx: Context, call: Call): String {
        val n = number(call)
        val cached = call.details.callerDisplayName
        if (!cached.isNullOrBlank()) return cached
        return lookup(ctx, n) ?: n.ifBlank { "Unknown" }
    }

    fun lookup(ctx: Context, number: String): String? {
        if (number.isBlank() || !has(ctx, Manifest.permission.READ_CONTACTS)) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun answer() { current?.answer(VideoProfile.STATE_AUDIO_ONLY) }
    fun decline() { current?.reject(false, null) }
    fun end() { current?.disconnect() }
    fun toggleHold() {
        val c = current ?: return
        if (c.state == Call.STATE_HOLDING) c.unhold() else c.hold()
    }
    fun toggleMute() { service?.setMuted(!muted) }
    fun toggleSpeaker() {
        service?.setAudioRoute(if (speaker) CallAudioState.ROUTE_EARPIECE else CallAudioState.ROUTE_SPEAKER)
    }
}

object CallNotifier {
    const val CH_IN = "jg_incoming"
    const val CH_ON = "jg_ongoing"
    const val ID = 4242
    const val ANSWER = "com.jellyglass.dialer.ANSWER"
    const val DECLINE = "com.jellyglass.dialer.DECLINE"
    const val HANGUP = "com.jellyglass.dialer.HANGUP"

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val a = NotificationChannel(CH_IN, "Incoming calls", NotificationManager.IMPORTANCE_HIGH)
        a.setSound(null, null)
        a.enableVibration(false)
        a.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(a)
        val b = NotificationChannel(CH_ON, "Ongoing calls", NotificationManager.IMPORTANCE_LOW)
        b.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(b)
    }

    fun build(ctx: Context, call: Call): Notification {
        val name = CallManager.displayName(ctx, call)
        val person = Person.Builder().setName(name).setImportant(true).build()
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        fun act(a: String) = PendingIntent.getBroadcast(
            ctx, a.hashCode(), Intent(ctx, CallActionReceiver::class.java).setAction(a),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val ringing = call.state == Call.STATE_RINGING
        val style = if (ringing) NotificationCompat.CallStyle.forIncomingCall(person, act(DECLINE), act(ANSWER))
        else NotificationCompat.CallStyle.forOngoingCall(person, act(HANGUP))
        val b = NotificationCompat.Builder(ctx, if (ringing) CH_IN else CH_ON)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(name)
            .setContentText(if (ringing) "Incoming call" else "Ongoing call")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setStyle(style)
            .setColor(Accents[Store.accent.coerceIn(0, Accents.lastIndex)].toArgb2())
        if (ringing) {
            b.setPriority(NotificationCompat.PRIORITY_HIGH).setFullScreenIntent(open, true)
        } else {
            val t = call.details.connectTimeMillis
            if (t > 0) b.setWhen(t).setShowWhen(true).setUsesChronometer(true)
        }
        return b.build()
    }

    private fun androidx.compose.ui.graphics.Color.toArgb2(): Int = this.toArgb()
}

class CallService : InCallService() {
    private val cb = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            CallManager.tick++
            update(call)
            if (state == Call.STATE_ACTIVE) launchUi()
        }
        override fun onDetailsChanged(call: Call, details: Call.Details) { CallManager.tick++ }
    }

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        CallNotifier.channels(this)
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        CallManager.service = this
        call.registerCallback(cb)
        CallManager.current = call
        CallManager.tick++
        update(call)
        if (call.state != Call.STATE_RINGING) launchUi()
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(cb)
        val next = calls.lastOrNull { it !== call }
        CallManager.current = next
        CallManager.tick++
        if (next == null) {
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
            getSystemService(NotificationManager::class.java).cancel(CallNotifier.ID)
        } else {
            update(next)
        }
    }

    @Suppress("DEPRECATION")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        CallManager.muted = audioState?.isMuted ?: false
        CallManager.speaker = audioState?.route == CallAudioState.ROUTE_SPEAKER
    }

    override fun onDestroy() {
        CallManager.service = null
        CallManager.current = null
        super.onDestroy()
    }

    private fun update(call: Call) {
        val n = CallNotifier.build(this, call)
        try {
            startForeground(CallNotifier.ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
        } catch (e: Exception) {
            try { getSystemService(NotificationManager::class.java).notify(CallNotifier.ID, n) } catch (_: Exception) {}
        }
    }

    private fun launchUi() {
        try {
            startActivity(
                Intent(this, InCallActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        } catch (_: Exception) {
        }
    }
}

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            CallNotifier.ANSWER -> CallManager.answer()
            CallNotifier.DECLINE -> CallManager.decline()
            CallNotifier.HANGUP -> CallManager.end()
        }
    }
}

/** Blocks likely spam from numbers that are not in your contacts (needs the Call screening role). */
class ScreeningService : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        Store.init(this)
        val number = details.handle?.schemeSpecificPart ?: ""
        val incoming = details.callDirection == Call.Details.DIRECTION_INCOMING
        val block = incoming && Store.autoBlock && number.isNotBlank() &&
            Spam.check(number).score >= 0.7f && CallManager.lookup(this, number) == null
        val r = CallResponse.Builder()
        if (block) r.setDisallowCall(true).setRejectCall(true).setSkipNotification(false)
        respondToCall(details, r.build())
    }
}

package com.jellyglass.dialer

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class LogEntry(val number: String, val name: String?, val type: Int, val date: Long, val duration: Long)
data class Contact(val name: String, val number: String, val starred: Boolean)

fun has(ctx: Context, perm: String) =
    ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

fun loadCallLog(ctx: Context): List<LogEntry> {
    if (!has(ctx, Manifest.permission.READ_CALL_LOG)) return emptyList()
    val out = ArrayList<LogEntry>()
    try {
        val args = Bundle().apply {
            putInt(ContentResolver.QUERY_ARG_LIMIT, 300)
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(CallLog.Calls.DATE))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
        }
        val proj = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION)
        ctx.contentResolver.query(CallLog.Calls.CONTENT_URI, proj, args, null)?.use { c ->
            while (c.moveToNext()) {
                out.add(LogEntry(c.getString(0) ?: "", c.getString(1), c.getInt(2), c.getLong(3), c.getLong(4)))
            }
        }
    } catch (_: Exception) {
    }
    return out
}

fun loadContacts(ctx: Context): List<Contact> {
    if (!has(ctx, Manifest.permission.READ_CONTACTS)) return emptyList()
    val out = ArrayList<Contact>()
    try {
        val proj = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.STARRED
        )
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj, null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                out.add(Contact(c.getString(0) ?: "", c.getString(1) ?: "", c.getInt(2) == 1))
            }
        }
    } catch (_: Exception) {
    }
    return out
}

fun placeCall(ctx: Context, number: String) {
    if (number.isBlank()) return
    try {
        ctx.getSystemService(TelecomManager::class.java)
            .placeCall(Uri.fromParts("tel", number, null), Bundle())
    } catch (e: SecurityException) {
        Toast.makeText(ctx, "Allow the Phone permission first", Toast.LENGTH_SHORT).show()
    }
}

/** On-device heuristics. Fast, private, offline. */
object Spam {
    data class Result(val score: Float, val label: String, val reason: String)

    fun check(number: String): Result {
        val clean = number.replace(" ", "").replace("-", "")
        val d = clean.filter { it.isDigit() }
        if (d.isEmpty()) return Result(.35f, "Suspicious", "Hidden or private number")
        var s = 0f
        val why = ArrayList<String>()
        if (Regex("^(\\+?91)?140\\d{6,}").containsMatchIn(clean)) { s += .75f; why.add("telemarketing prefix 140") }
        if (Regex("(\\d)\\1{6,}").containsMatchIn(d)) { s += .35f; why.add("repeated digits") }
        if (d.length >= 14) { s += .3f; why.add("unusually long") }
        if (d.length in 3..5) { s += .15f; why.add("short code") }
        if (clean.startsWith("+") && Regex("^\\+(1900|1976|224|225|233|234|375)").containsMatchIn(clean)) { s += .5f; why.add("known scam-call region") }
        val label = when {
            s >= .7f -> "Likely spam"
            s >= .35f -> "Suspicious"
            else -> "Looks normal"
        }
        return Result(s.coerceAtMost(1f), label, if (why.isEmpty()) "No spam patterns found" else why.joinToString(", "))
    }
}

/** Replies and drafts through the online model when allowed, with an offline fallback. */
object AiEngine {
    suspend fun complete(prompt: String, fallback: String): String = withContext(Dispatchers.IO) {
        val engine = Store.aiEngine
        if (engine != 0) {
            if (Store.apiKey.isBlank()) {
                if (engine == 1) return@withContext "Add your API key in Settings to use online AI."
            } else {
                try {
                    return@withContext callApi(prompt)
                } catch (e: Exception) {
                    if (engine == 1) return@withContext "Online AI failed: ${e.message}"
                }
            }
        }
        fallback
    }

    private fun callApi(prompt: String): String {
        val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.doOutput = true
        conn.setRequestProperty("content-type", "application/json")
        conn.setRequestProperty("x-api-key", Store.apiKey)
        conn.setRequestProperty("anthropic-version", "2023-06-01")
        val body = JSONObject()
            .put("model", Store.model)
            .put("max_tokens", 400)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val txt = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) throw RuntimeException("HTTP $code")
        return JSONObject(txt).getJSONArray("content").getJSONObject(0).getString("text")
    }
}

object QuickReply {
    val defaults = listOf(
        "Can't talk right now. I'll call you back soon.",
        "I'm in a meeting. Please message me.",
        "Driving at the moment. I'll call you later."
    )

    fun send(ctx: Context, number: String, text: String): Boolean {
        if (number.isBlank() || !has(ctx, Manifest.permission.SEND_SMS)) {
            Toast.makeText(ctx, "Allow SMS permission to send quick replies", Toast.LENGTH_SHORT).show()
            return false
        }
        return try {
            val sms = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java)
            else @Suppress("DEPRECATION") SmsManager.getDefault()
            sms.sendTextMessage(number, null, text, null, null)
            true
        } catch (e: Exception) {
            false
        }
    }
}

package com.jellyglass.dialer

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

val LocalDark = compositionLocalOf { false }

val Accents = listOf(0xFF2F6BFF, 0xFFFF3B7F, 0xFF22C55E, 0xFFFF9500, 0xFF8B5CF6, 0xFF00C2D1).map { Color(it) }
val WallNames = listOf("Aurora", "Sunset", "Ocean", "Mint", "Mono", "Custom")

private fun cl(vararg v: Long) = v.map { Color(it) }

private val LIGHT = listOf(
    cl(0xFFFF9AC1, 0xFF7FB2FF, 0xFFFFD37A, 0xFFEFE9FF),
    cl(0xFFFFB36B, 0xFFFF6F91, 0xFFFFD6A5, 0xFFFFF0E6),
    cl(0xFF5EE7DF, 0xFF4FACFE, 0xFFB8F1FF, 0xFFE6F7FF),
    cl(0xFFA8F0C6, 0xFF7DD3FC, 0xFFFEF08A, 0xFFEFFFF6),
    cl(0xFFD4D4D8, 0xFFA1A1AA, 0xFFF4F4F5, 0xFFFAFAFA)
)
private val DARK = listOf(
    cl(0xFF0EA5A4, 0xFF5B3DF5, 0xFFD02BD0, 0xFF050612),
    cl(0xFFFF6A3D, 0xFFC0145F, 0xFF6D28D9, 0xFF0D0509),
    cl(0xFF06B6D4, 0xFF2563EB, 0xFF14B8A6, 0xFF020813),
    cl(0xFF10B981, 0xFF0891B2, 0xFF84CC16, 0xFF020B07),
    cl(0xFF6B7280, 0xFF374151, 0xFF9CA3AF, 0xFF060606)
)

/** Returns [blob1, blob2, blob3, base] colors. */
fun palette(dark: Boolean, idx: Int = Store.wallpaper): List<Color> {
    if (idx >= 5) {
        val h = Store.customHue
        fun hh(o: Float) = (h + o + 360f) % 360f
        return if (dark) listOf(
            Color.hsv(hh(0f), .9f, .7f), Color.hsv(hh(50f), .85f, .75f),
            Color.hsv(hh(-50f), .8f, .7f), Color.hsv(hh(0f), .6f, .06f)
        ) else listOf(
            Color.hsv(hh(0f), .45f, 1f), Color.hsv(hh(50f), .45f, 1f),
            Color.hsv(hh(-50f), .4f, 1f), Color.hsv(hh(0f), .08f, .98f)
        )
    }
    return (if (dark) DARK else LIGHT)[idx]
}

@Composable fun tx(): Color = if (LocalDark.current) Color.White else Color(0xFF15141C)
@Composable fun mu(): Color = tx().copy(alpha = .6f)
@Composable fun accent(): Color = Accents[Store.accent.coerceIn(0, Accents.lastIndex)]

@Composable
fun Wallpaper(dark: Boolean, content: @Composable BoxScope.() -> Unit) {
    val p = palette(dark)
    val t = rememberInfiniteTransition(label = "wall")
    val a by t.animateFloat(0f, 1f, infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    Box(
        Modifier.fillMaxSize().background(p[3]).drawBehind {
            val w = size.width
            val h = size.height
            fun blob(c: Color, cx: Float, cy: Float, r: Float) {
                drawCircle(
                    brush = Brush.radialGradient(listOf(c.copy(alpha = if (dark) .75f else .9f), Color.Transparent), center = Offset(cx, cy), radius = r),
                    radius = r, center = Offset(cx, cy)
                )
            }
            blob(p[0], w * (.1f + .15f * a), h * (.1f + .1f * a), w * .95f)
            blob(p[1], w * (.95f - .15f * a), h * (.95f - .1f * a), w * 1.05f)
            blob(p[2], w * (.35f + .2f * a), h * (.5f + .1f * a), w * .65f)
        },
        content = content
    )
}

@Composable
fun Modifier.glass(shape: Shape = CircleShape): Modifier {
    val dark = LocalDark.current
    val a = (Store.glass * (if (dark) 0.8f else 1.8f)).coerceIn(0f, 1f)
    return this
        .clip(shape)
        .background(Color.White.copy(alpha = a))
        .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = .26f), Color.Transparent)))
        .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) .4f else .9f), Color.White.copy(alpha = .1f))), shape)
}

/** Squash-and-spring press effect. Keep it first in the modifier chain. */
@Composable
fun Modifier.jellyClick(onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val sx by animateFloatAsState(if (pressed) 1f - .14f * Store.jelly else 1f, spring(dampingRatio = .35f, stiffness = 450f), label = "sx")
    val sy by animateFloatAsState(if (pressed) 1f - .09f * Store.jelly else 1f, spring(dampingRatio = .35f, stiffness = 450f), label = "sy")
    return this
        .graphicsLayer { scaleX = sx; scaleY = sy }
        .clickable(interactionSource = src, indication = null, onClick = onClick)
}

@Composable
fun T(s: String, size: Int = 15, color: Color = tx(), weight: FontWeight? = null, modifier: Modifier = Modifier, align: TextAlign? = null) {
    Text(s, modifier = modifier, color = color, fontSize = size.sp, fontWeight = weight, textAlign = align)
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().glass(RoundedCornerShape(36.dp)).padding(16.dp), content = content)
}

@Composable
fun PillBtn(text: String, color: Color, modifier: Modifier = Modifier, h: Int = 56, onClick: () -> Unit) {
    Box(modifier.jellyClick(onClick).height(h.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        T(text, 16, Color.White, FontWeight.SemiBold)
    }
}

@Composable
fun GlassPill(text: String, modifier: Modifier = Modifier, h: Int = 52, onClick: () -> Unit) {
    Box(modifier.jellyClick(onClick).height(h.dp).glass(), contentAlignment = Alignment.Center) {
        T(text, 15, Color.White)
    }
}

@Composable
fun Avatar(name: String, size: Int = 46) {
    val hue = ((name.hashCode() and 0x7fffffff) % 360).toFloat()
    Box(Modifier.size(size.dp).clip(CircleShape).background(Color.hsv(hue, .55f, .85f)), contentAlignment = Alignment.Center) {
        T(name.trim().take(1).uppercase().ifEmpty { "?" }, (size * 0.4).toInt(), Color.White, FontWeight.Bold)
    }
}

@Composable
fun Seg(options: List<String>, sel: Int, onSel: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().glass().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEachIndexed { i, o ->
            val on = i == sel
            val sc by animateFloatAsState(if (on) 1.04f else 1f, spring(dampingRatio = .45f, stiffness = 500f), label = "seg")
            Box(
                Modifier.weight(1f).graphicsLayer { scaleX = sc; scaleY = sc }
                    .clip(CircleShape)
                    .background(if (on) Color.White.copy(alpha = .42f) else Color.Transparent)
                    .clickable { onSel(i) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) { T(o, 13, if (on) tx() else mu()) }
        }
    }
}

@Composable
fun GlassSwitch(on: Boolean, onChange: (Boolean) -> Unit) {
    val x by animateDpAsState(if (on) 20.dp else 0.dp, spring(dampingRatio = .4f, stiffness = 450f), label = "swx")
    val bg by animateColorAsState(if (on) Color(0xFF22C55E) else Color.Gray.copy(alpha = .4f), label = "swc")
    Box(Modifier.size(52.dp, 32.dp).clip(CircleShape).background(bg).clickable { onChange(!on) }.padding(3.dp)) {
        Box(Modifier.offset(x = x).size(26.dp).clip(CircleShape).background(Color.White))
    }
}

@Composable
fun ToggleRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        T(label, 15, modifier = Modifier.weight(1f))
        GlassSwitch(on, onChange)
    }
}

/** Liquid glass slider: the thumb stretches while you drag. Value is 0..1. */
@Composable
fun GlassSlider(value: Float, onChange: (Float) -> Unit) {
    var w by remember { mutableFloatStateOf(1f) }
    var drag by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (drag) 1.4f else 1f, spring(dampingRatio = .4f, stiffness = 400f), label = "sl")
    val half = with(LocalDensity.current) { 23.dp.toPx() }
    Box(
        Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 23.dp)
            .onSizeChanged { w = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) { detectTapGestures { onChange((it.x / w).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { drag = true; onChange((it.x / w).coerceIn(0f, 1f)) },
                    onDragEnd = { drag = false },
                    onDragCancel = { drag = false }
                ) { change, _ -> change.consume(); onChange((change.position.x / w).coerceIn(0f, 1f)) }
            }
    ) {
        Box(Modifier.align(Alignment.Center).fillMaxWidth().height(10.dp).clip(CircleShape).background(Color.Gray.copy(alpha = .35f))) {
            Box(Modifier.fillMaxWidth(value.coerceIn(0f, 1f)).fillMaxHeight().background(accent()))
        }
        Box(
            Modifier.align(Alignment.CenterStart)
                .offset { IntOffset((value.coerceIn(0f, 1f) * w - half).roundToInt(), 0) }
                .graphicsLayer { scaleX = sc; scaleY = 1f + (sc - 1f) * .4f }
                .size(46.dp, 32.dp).clip(CircleShape)
                .background(Color.White.copy(alpha = .8f))
                .border(1.dp, Color.White, CircleShape)
        )
    }
}

package com.jellyglass.dialer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telecom.Call
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class InCallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        enableEdgeToEdge()
        val preview = intent.getBooleanExtra("preview", false)
        setContent { CompositionLocalProvider(LocalDark provides true) { CallRoot(preview) { finishAndRemoveTask() } } }
    }
}

class CallActions(val answer: () -> Unit, val decline: () -> Unit, val end: () -> Unit, val keypad: () -> Unit)
class Ctl(val glyph: String, val label: String, val on: Boolean, val click: () -> Unit)

fun controls(ctx: Context, st: Int, a: CallActions) = listOf(
    Ctl("\uD83C\uDFA4", "Mute", CallManager.muted) { CallManager.toggleMute() },
    Ctl("\u2317", "Keypad", false) { a.keypad() },
    Ctl("\uD83D\uDD0A", "Speaker", CallManager.speaker) { CallManager.toggleSpeaker() },
    Ctl("\uFF0B", "Add call", false) { ctx.startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
    Ctl("\u23F8", "Hold", st == Call.STATE_HOLDING) { CallManager.toggleHold() },
    Ctl("\u263A", "Contacts", false) { ctx.startActivity(Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
)

@Composable
fun statusText(st: Int): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val start = CallManager.current?.details?.connectTimeMillis ?: 0L
    return when (st) {
        Call.STATE_ACTIVE -> if (start > 0) { val s = ((now - start) / 1000).coerceAtLeast(0); "%02d:%02d".format(s / 60, s % 60) } else "00:00"
        Call.STATE_HOLDING -> "On hold"
        Call.STATE_DISCONNECTED -> "Call ended"
        Call.STATE_RINGING -> "Incoming call"
        else -> "Calling\u2026"
    }
}

@Composable
fun CallRoot(preview: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val tick = CallManager.tick
    val call = CallManager.current
    val st = if (call != null) call.state else if (preview) Call.STATE_RINGING else Call.STATE_DISCONNECTED
    val number = if (call != null) CallManager.number(call) else "+91 98765 43210"
    val name = if (call != null) CallManager.displayName(ctx, call) else "Riya"
    var keys by remember { mutableStateOf(false) }
    LaunchedEffect(call == null, tick) { if (call == null && !preview) { delay(700); onClose() } }
    val act = CallActions(
        answer = { if (preview) onClose() else CallManager.answer() },
        decline = { if (preview) onClose() else CallManager.decline() },
        end = { if (preview) onClose() else CallManager.end() },
        keypad = { keys = true }
    )
    val ringing = st == Call.STATE_RINGING
    val style = Store.callStyle
    CallBackdrop(style) {
        if (ringing) when (style) {
            0 -> IosIncoming(name, number, act)
            1 -> OppoIncoming(name, number, act)
            else -> OneIncoming(name, number, act)
        } else when (style) {
            0 -> IosCall(name, number, st, act)
            1 -> OppoCall(name, number, st, act)
            else -> OneCall(name, number, st, act)
        }
        if (keys) DtmfPad { keys = false }
        if (Store.debug) T("state=$st number=$number", 11, Color.Yellow, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp))
    }
}

@Composable
fun CallBackdrop(style: Int, content: @Composable BoxScope.() -> Unit) {
    when (style) {
        0 -> Wallpaper(true) { Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .35f)), content = content) }
        1 -> {
            val p = palette(true)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(p[1], p[0], p[3]))), content = content)
        }
        else -> Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF14151C), Color(0xFF0B0B10)))), content = content)
    }
}

@Composable
fun SpamBadge(number: String) {
    val ctx = LocalContext.current
    if (!Store.spamBadge) return
    val r = remember(number) { Spam.check(number) }
    val known = remember(number) { CallManager.lookup(ctx, number) != null }
    if (known || r.score < .35f) return
    Box(Modifier.padding(top = 10.dp).clip(CircleShape).background(Color(0xFFFF453A).copy(alpha = .85f)).padding(horizontal = 14.dp, vertical = 6.dp)) {
        T("\u26A0 " + r.label + " \u00B7 " + r.reason, 12, Color.White)
    }
}

@Composable
fun QuickReplies(number: String, onDone: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        QuickReply.defaults.forEach { t ->
            GlassPill(t, Modifier.fillMaxWidth(), 46) { QuickReply.send(ctx, number, t); onDone() }
        }
    }
}

@Composable
fun Pulse(content: @Composable () -> Unit) {
    val t = rememberInfiniteTransition(label = "pulse")
    val s by t.animateFloat(1f, 2.3f, infiniteRepeatable(tween(2400, easing = LinearOutSlowInEasing)), label = "s")
    Box(contentAlignment = Alignment.Center) {
        Box(Modifier.size(110.dp).graphicsLayer { scaleX = s; scaleY = s; alpha = ((2.3f - s) / 1.3f).coerceIn(0f, 1f) }.border(2.dp, Color.White, CircleShape))
        content()
    }
}

@Composable
fun Chevron() {
    val t = rememberInfiniteTransition(label = "chev")
    val a by t.animateFloat(.2f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    T("\u2303", 24, Color.White.copy(alpha = a))
}

@Composable
fun SlideToAnswer(onDone: () -> Unit) {
    val dens = LocalDensity.current
    var w by remember { mutableFloatStateOf(0f) }
    val off = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val maxX = (w - with(dens) { 68.dp.toPx() }).coerceAtLeast(1f)
    Box(Modifier.fillMaxWidth().height(68.dp).glass().onSizeChanged { w = it.width.toFloat() }) {
        T("Slide to answer", 15, Color.White.copy(alpha = .9f), modifier = Modifier.align(Alignment.Center))
        Box(
            Modifier.padding(6.dp).offset { IntOffset(off.value.roundToInt(), 0) }.size(56.dp).clip(CircleShape)
                .background(Color(0xFF22C55E))
                .pointerInput(maxX) {
                    detectHorizontalDragGestures(
                        onDragEnd = { scope.launch { if (off.value > maxX * .75f) onDone() else off.animateTo(0f, spring(dampingRatio = .4f, stiffness = 300f)) } },
                        onDragCancel = { scope.launch { off.animateTo(0f) } }
                    ) { change, dx -> change.consume(); scope.launch { off.snapTo((off.value + dx).coerceIn(0f, maxX)) } }
                },
            contentAlignment = Alignment.Center
        ) { T("\u279C", 22, Color.White) }
    }
}

// ---------- Incoming: iOS ----------
@Composable
fun IosIncoming(name: String, number: String, a: CallActions) {
    var replies by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(40.dp))
        T(name, 42, Color.White, FontWeight.Light)
        T("mobile", 16, Color.White.copy(alpha = .7f), modifier = Modifier.padding(top = 4.dp))
        SpamBadge(number)
        Spacer(Modifier.weight(1f))
        if (replies) { QuickReplies(number) { a.decline() }; Spacer(Modifier.height(12.dp)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassPill("\u23F0 Remind me", Modifier.weight(1f)) { a.decline() }
            GlassPill("\u2709 Message", Modifier.weight(1f)) { replies = !replies }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillBtn("Decline", Color(0xFFFF453A), Modifier.weight(1f), 62) { a.decline() }
            PillBtn("Accept", Color(0xFF22C55E), Modifier.weight(1f), 62) { a.answer() }
        }
    }
}

// ---------- Incoming: ColorOS ----------
@Composable
fun OppoIncoming(name: String, number: String, a: CallActions) {
    var replies by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(48.dp))
        Pulse { Avatar(name, 110) }
        Spacer(Modifier.height(36.dp))
        T(name, 32, Color.White, FontWeight.Medium)
        T("Incoming call \u00B7 Mobile", 15, Color.White.copy(alpha = .75f), modifier = Modifier.padding(top = 6.dp))
        SpamBadge(number)
        Spacer(Modifier.weight(1f))
        if (replies) { QuickReplies(number) { a.decline() }; Spacer(Modifier.height(12.dp)) }
        GlassPill("\u2709 Reply with message", Modifier.fillMaxWidth()) { replies = !replies }
        Spacer(Modifier.height(14.dp))
        SlideToAnswer { a.answer() }
        Spacer(Modifier.height(14.dp))
        PillBtn("Decline", Color(0xFFFF453A), Modifier.fillMaxWidth(), 56) { a.decline() }
    }
}

// ---------- Incoming: One UI ----------
@Composable
fun OneIncoming(name: String, number: String, a: CallActions) {
    var replies by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(40.dp))
        T(name, 34, Color.White, FontWeight.Medium)
        T(number.ifBlank { "Unknown" } + " \u00B7 Mobile", 14, Color.White.copy(alpha = .7f), modifier = Modifier.padding(top = 6.dp))
        SpamBadge(number)
        Spacer(Modifier.height(28.dp))
        Avatar(name, 140)
        Spacer(Modifier.weight(1f))
        if (replies) { QuickReplies(number) { a.decline() }; Spacer(Modifier.height(12.dp)) }
        GlassPill("\u2709 Send message", Modifier.padding(bottom = 26.dp)) { replies = !replies }
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Chevron()
                Box(Modifier.jellyClick { a.answer() }.size(84.dp).clip(CircleShape).background(Color(0xFF22C55E)), contentAlignment = Alignment.Center) { T("\u260E", 34, Color.White) }
                T("Answer", 13, Color.White, modifier = Modifier.padding(top = 8.dp))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Chevron()
                Box(Modifier.jellyClick { a.decline() }.size(84.dp).clip(CircleShape).background(Color(0xFFFF453A)), contentAlignment = Alignment.Center) {
                    T("\u260E", 34, Color.White, modifier = Modifier.graphicsLayer { rotationZ = 135f })
                }
                T("Decline", 13, Color.White, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

// ---------- In call: iOS ----------
@Composable
fun IosCall(name: String, number: String, st: Int, a: CallActions) {
    val ctx = LocalContext.current
    val c = controls(ctx, st, a)
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(32.dp))
        T(name, 36, Color.White, FontWeight.Light)
        T(statusText(st), 17, Color.White.copy(alpha = .75f), modifier = Modifier.padding(top = 4.dp))
        SpamBadge(number)
        Spacer(Modifier.weight(1f))
        c.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { b ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.jellyClick(b.click).size(76.dp).clip(CircleShape)
                                .background(if (b.on) Color.White else Color.White.copy(alpha = .2f)),
                            contentAlignment = Alignment.Center
                        ) { T(b.glyph, 26, if (b.on) Color.Black else Color.White) }
                        T(b.label, 12, Color.White, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Box(Modifier.jellyClick { a.end() }.size(76.dp).clip(CircleShape).background(Color(0xFFFF453A)), contentAlignment = Alignment.Center) {
            T("\u260E", 30, Color.White, modifier = Modifier.graphicsLayer { rotationZ = 135f })
        }
    }
}

// ---------- In call: ColorOS ----------
@Composable
fun OppoCall(name: String, number: String, st: Int, a: CallActions) {
    val ctx = LocalContext.current
    val c = controls(ctx, st, a)
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().glass().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Avatar(name, 52)
            Column(Modifier.weight(1f)) {
                T(name, 18, Color.White, FontWeight.SemiBold)
                T(statusText(st), 13, Color.White.copy(alpha = .75f))
            }
        }
        SpamBadge(number)
        Spacer(Modifier.weight(1f))
        c.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { b ->
                    Box(
                        Modifier.weight(1f).jellyClick(b.click).height(56.dp).clip(CircleShape)
                            .background(if (b.on) Color.White else Color.White.copy(alpha = .2f)),
                        contentAlignment = Alignment.Center
                    ) { T(b.glyph + "  " + b.label, 15, if (b.on) Color.Black else Color.White) }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        PillBtn("End call", Color(0xFFFF453A), Modifier.fillMaxWidth(), 60) { a.end() }
    }
}

// ---------- In call: One UI ----------
@Composable
fun OneCall(name: String, number: String, st: Int, a: CallActions) {
    val ctx = LocalContext.current
    val c = controls(ctx, st, a)
    val five = listOf(c[3], c[4], c[2], c[0], c[1])
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(36.dp))
        T(name, 34, Color.White, FontWeight.Medium)
        T(statusText(st), 16, Color.White.copy(alpha = .7f), modifier = Modifier.padding(top = 6.dp))
        SpamBadge(number)
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth().glass().padding(10.dp), horizontalArrangement = Arrangement.SpaceAround) {
            five.forEach { b ->
                Box(
                    Modifier.jellyClick(b.click).size(52.dp).clip(CircleShape).background(if (b.on) Color.White else Color.Transparent),
                    contentAlignment = Alignment.Center
                ) { T(b.glyph, 22, if (b.on) Color.Black else Color.White) }
            }
        }
        Spacer(Modifier.height(18.dp))
        PillBtn("End call", Color(0xFFFF453A), Modifier.fillMaxWidth(), 60) { a.end() }
    }
}

@Composable
fun DtmfPad(onClose: () -> Unit) {
    val handler = remember { Handler(Looper.getMainLooper()) }
    val keys = listOf(listOf('1', '2', '3'), listOf('4', '5', '6'), listOf('7', '8', '9'), listOf('*', '0', '#'))
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .6f)).clickable { onClose() }, contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).glass(RoundedCornerShape(40.dp)).padding(16.dp)) {
            keys.forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { k ->
                        Box(
                            Modifier.weight(1f).jellyClick {
                                val call = CallManager.current
                                call?.playDtmfTone(k)
                                handler.postDelayed({ call?.stopDtmfTone() }, 150)
                            }.height(60.dp).glass(),
                            contentAlignment = Alignment.Center
                        ) { T(k.toString(), 24, Color.White) }
                    }
                }
            }
            PillBtn("Hide", Color.White.copy(alpha = .2f), Modifier.fillMaxWidth().padding(top = 6.dp), 48) { onClose() }
        }
    }
}

package com.jellyglass.dialer

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        enableEdgeToEdge()
        handle(intent)
        setContent { App() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        val d = i?.data
        if (d != null && d.scheme == "tel") Launch.number = d.schemeSpecificPart
    }
}

@Composable
fun App() {
    val dark = Store.themeMode == 2 || (Store.themeMode == 0 && isSystemInDarkTheme())
    CompositionLocalProvider(LocalDark provides dark) {
        Wallpaper(dark) { Root() }
    }
}

private val TITLES = listOf("Favorites", "Recents", "Keypad", "AI", "Settings")
private val GLYPHS = listOf("\u2605", "\u21BA", "\u2317", "\u2726", "\u2699")

@Composable
fun Root() {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val perms = remember(refresh) { has(ctx, Manifest.permission.READ_CONTACTS) && has(ctx, Manifest.permission.READ_CALL_LOG) }
    val log by produceState(emptyList<LogEntry>(), refresh) { value = withContext(Dispatchers.IO) { loadCallLog(ctx) } }
    val contacts by produceState(emptyList<Contact>(), refresh) { value = withContext(Dispatchers.IO) { loadContacts(ctx) } }
    LaunchedEffect(Launch.number) { if (Launch.number != null) tab = 2 }
    val ask = {
        val list = mutableListOf(
            Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG, Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE, Manifest.permission.SEND_SMS
        )
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        permLauncher.launch(list.toTypedArray())
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
            T(TITLES[tab], 32, weight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp, top = 12.dp, bottom = 10.dp))
            if (!perms && tab <= 2) {
                GlassCard(Modifier.padding(bottom = 10.dp)) {
                    T("Allow access", 16, weight = FontWeight.SemiBold)
                    T("Contacts, call log, phone and SMS permissions let the dialer show recents, place calls and send quick replies.", 13, mu(), modifier = Modifier.padding(vertical = 6.dp))
                    PillBtn("Grant access", accent(), Modifier.fillMaxWidth(), 48) { ask() }
                }
            }
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> FavoritesScreen(contacts)
                    1 -> RecentsScreen(log)
                    2 -> KeypadScreen(contacts)
                    3 -> AiScreen(log)
                    else -> SettingsScreen(refresh)
                }
            }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp)
                .fillMaxWidth().glass().padding(6.dp)
        ) {
            TITLES.forEachIndexed { i, t ->
                val on = i == tab
                Column(
                    Modifier.weight(1f).jellyClick { tab = i }.clip(CircleShape)
                        .background(if (on) Color.White.copy(alpha = .42f) else Color.Transparent)
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    T(GLYPHS[i], 19, if (on) accent() else mu())
                    T(t, 10, if (on) accent() else mu())
                }
            }
        }
    }
}

@Composable
fun FavoritesScreen(contacts: List<Contact>) {
    val ctx = LocalContext.current
    val favs = contacts.filter { it.starred }.distinctBy { it.name }
    LazyColumn(contentPadding = PaddingValues(bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        if (favs.isNotEmpty()) {
            items(favs.chunked(3)) { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { c ->
                        Column(
                            Modifier.weight(1f).jellyClick { placeCall(ctx, c.number) }.glass().padding(vertical = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Avatar(c.name, 56)
                            T(c.name, 13, modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp), align = TextAlign.Center)
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        } else {
            item { GlassCard { T("Star a contact in your Contacts app and it shows up here.", 14, mu()) } }
        }
        item { T("All contacts", 14, mu(), modifier = Modifier.padding(start = 8.dp, top = 6.dp)) }
        items(contacts.distinctBy { it.name + it.number }) { c ->
            ListRow(c.name, c.number, c.name) { placeCall(ctx, c.number) }
        }
    }
}

@Composable
fun ListRow(title: String, sub: String, avatarName: String, titleColor: Color = tx(), onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().jellyClick(onClick).glass().padding(start = 9.dp, end = 20.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Avatar(avatarName)
        Column(Modifier.weight(1f)) {
            T(title, 16, titleColor, FontWeight.SemiBold)
            T(sub, 13, mu())
        }
    }
}

@Composable
fun RecentsScreen(log: List<LogEntry>) {
    val ctx = LocalContext.current
    var f by remember { mutableIntStateOf(0) }
    val shown = when (f) {
        1 -> log.filter { it.type == CallLog.Calls.MISSED_TYPE }
        2 -> log.filter { Spam.check(it.number).score >= .35f && it.name == null }
        else -> log
    }
    Column {
        Seg(listOf("All", "Missed", "Spam"), f, { f = it }, Modifier.padding(bottom = 10.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            if (shown.isEmpty()) item { GlassCard { T("Nothing here yet.", 14, mu()) } }
            items(shown) { e ->
                val kind = when (e.type) {
                    CallLog.Calls.INCOMING_TYPE -> "Incoming"
                    CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                    CallLog.Calls.MISSED_TYPE -> "Missed"
                    CallLog.Calls.REJECTED_TYPE -> "Declined"
                    CallLog.Calls.BLOCKED_TYPE -> "Blocked"
                    CallLog.Calls.VOICEMAIL_TYPE -> "Voicemail"
                    else -> "Call"
                }
                val whenTxt = DateUtils.getRelativeTimeSpanString(e.date, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
                val title = e.name?.takeIf { it.isNotBlank() } ?: e.number.ifBlank { "Unknown" }
                val spam = if (Store.spamBadge && e.name == null) Spam.check(e.number) else null
                val sub = kind + " \u00B7 " + whenTxt + (if (spam != null && spam.score >= .35f) " \u00B7 \u26A0 " + spam.label else "")
                ListRow(title, sub, title, if (e.type == CallLog.Calls.MISSED_TYPE) Color(0xFFFF453A) else tx()) { placeCall(ctx, e.number) }
            }
        }
    }
}

@Composable
fun KeypadScreen(contacts: List<Contact>) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var digits by remember { mutableStateOf("") }
    LaunchedEffect(Launch.number) { Launch.number?.let { digits = it; Launch.number = null } }
    val d = digits.filter { it.isDigit() }
    val match = if (d.length >= 2) contacts.firstOrNull { it.number.filter { c -> c.isDigit() }.contains(d) } else null
    val keys = listOf(
        listOf("1" to "", "2" to "ABC", "3" to "DEF"), listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
        listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"), listOf("*" to "", "0" to "+", "#" to "")
    )
    Column(Modifier.fillMaxSize().padding(bottom = 100.dp), verticalArrangement = Arrangement.Bottom) {
        T(digits.ifEmpty { " " }, 38, weight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth(), align = TextAlign.Center)
        T(if (match != null) "Call " + match.name else " ", 14, accent(), FontWeight.SemiBold, Modifier.fillMaxWidth().padding(vertical = 6.dp), TextAlign.Center)
        keys.forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (k, sub) ->
                    Row(
                        Modifier.weight(1f).jellyClick {
                            digits += k
                            if (Store.haptics) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }.height(64.dp).glass(),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
                    ) {
                        T(k, 26)
                        if (sub.isNotEmpty()) T(sub, 9, mu(), modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.weight(1f).jellyClick { placeCall(ctx, match?.number ?: digits) }.height(64.dp)
                    .clip(CircleShape).background(Color(0xFF22C55E)),
                contentAlignment = Alignment.Center
            ) { T("\u260E", 26, Color.White) }
            Box(Modifier.weight(1f).jellyClick { digits = digits.dropLast(1) }.height(64.dp), contentAlignment = Alignment.Center) {
                T("\u232B", 24, mu())
            }
        }
    }
}

@Composable
fun AiScreen(log: List<LogEntry>) {
    val scope = rememberCoroutineScope()
    var number by remember { mutableStateOf("") }
    var spamOut by remember { mutableStateOf("") }
    var replies by remember { mutableStateOf("") }
    var insight by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassCard {
            T("AI engine", 16, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Seg(listOf("On-device", "Online", "Hybrid"), Store.aiEngine, { Store.aiEngine = it; Store.save() })
            T(
                when (Store.aiEngine) {
                    0 -> "Private and offline. Uses built-in spam rules only."
                    1 -> "Uses the online model. Add your API key in Settings."
                    else -> "Online model when a key is set, built-in rules otherwise."
                }, 13, mu()
            )
        }
        GlassCard {
            T("Spam checker", 16, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            GlassField(number, { number = it }, "Enter a phone number")
            Spacer(Modifier.height(8.dp))
            PillBtn(if (busy) "Checking\u2026" else "Check number", accent(), Modifier.fillMaxWidth(), 48) {
                if (number.isBlank() || busy) return@PillBtn
                val r = Spam.check(number)
                val base = r.label + ": " + r.reason
                busy = true
                scope.launch {
                    val ai = AiEngine.complete(
                        "In two short sentences, say whether the phone number $number looks like a spam or scam call. Local rule result: $base.",
                        base
                    )
                    spamOut = ai
                    busy = false
                }
            }
            if (spamOut.isNotEmpty()) T(spamOut, 14, modifier = Modifier.padding(top = 10.dp))
        }
        GlassCard {
            T("Smart replies", 16, weight = FontWeight.SemiBold)
            T("Draft polite texts to send when you decline a call.", 13, mu(), modifier = Modifier.padding(vertical = 6.dp))
            PillBtn("Write replies", accent(), Modifier.fillMaxWidth(), 48) {
                if (busy) return@PillBtn
                busy = true
                scope.launch {
                    replies = AiEngine.complete(
                        "Write 3 short, polite text messages (under 12 words each) to send when declining a phone call. One per line, no numbering.",
                        QuickReply.defaults.joinToString("\n")
                    )
                    busy = false
                }
            }
            if (replies.isNotEmpty()) T(replies, 14, modifier = Modifier.padding(top = 10.dp))
        }
        GlassCard {
            T("Recents insights", 16, weight = FontWeight.SemiBold)
            T("Summarise your recent calls and what needs a reply.", 13, mu(), modifier = Modifier.padding(vertical = 6.dp))
            PillBtn("Summarise", accent(), Modifier.fillMaxWidth(), 48) {
                if (busy) return@PillBtn
                val recent = log.take(20)
                val missed = recent.count { it.type == CallLog.Calls.MISSED_TYPE }
                val fb = "${recent.size} recent calls, $missed missed."
                busy = true
                scope.launch {
                    val lines = recent.joinToString("\n") { (it.name ?: it.number) + " type=" + it.type + " duration=" + it.duration + "s" }
                    insight = AiEngine.complete("Summarise these recent calls in 3 short bullet points and say who to call back:\n$lines", fb)
                    busy = false
                }
            }
            if (insight.isNotEmpty()) T(insight, 14, modifier = Modifier.padding(top = 10.dp))
        }
        T(
            "Note: Android does not let third-party apps record or listen to call audio, so live transcripts are not possible. AI here works on numbers, recents and text.",
            12, mu(), modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
}

@Composable
fun GlassField(value: String, onChange: (String) -> Unit, hint: String, password: Boolean = false) {
    BasicTextField(
        value = value, onValueChange = onChange, singleLine = true,
        textStyle = TextStyle(color = tx(), fontSize = 15.sp), cursorBrush = SolidColor(accent()),
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().glass().padding(horizontal = 18.dp, vertical = 13.dp),
        decorationBox = { inner ->
            if (value.isEmpty()) T(hint, 15, mu())
            inner()
        }
    )
}

private fun open(ctx: Context, i: Intent) {
    try {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(ctx, "This screen is not available on your phone", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun SettingsScreen(refresh: Int) {
    val ctx = LocalContext.current
    val pkg = ctx.packageName
    val rm = ctx.getSystemService(RoleManager::class.java)
    var tick by remember { mutableIntStateOf(0) }
    val dialerHeld = remember(refresh, tick) { rm.isRoleHeld(RoleManager.ROLE_DIALER) }
    val screenHeld = remember(refresh, tick) { rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }
    fun change(f: () -> Unit) { f(); Store.save() }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassCard {
            T("Phone", 16, weight = FontWeight.SemiBold)
            T(if (dialerHeld) "Jelly Dialer is your default phone app." else "Make Jelly Dialer your default phone app so it can show incoming and in-call screens.", 13, mu(), modifier = Modifier.padding(vertical = 6.dp))
            if (!dialerHeld) PillBtn("Set as default dialer", accent(), Modifier.fillMaxWidth(), 48) {
                if (rm.isRoleAvailable(RoleManager.ROLE_DIALER)) roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER))
            }
            Spacer(Modifier.height(8.dp))
            if (!screenHeld) PillBtn("Enable spam call screening", Color(0xFF8B5CF6), Modifier.fillMaxWidth(), 48) {
                if (rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
            } else T("Spam call screening is on.", 13, mu())
            ToggleRow("Auto-block likely spam", Store.autoBlock) { change { Store.autoBlock = it } }
            ToggleRow("Show spam badges", Store.spamBadge) { change { Store.spamBadge = it } }
            ToggleRow("Haptic feedback", Store.haptics) { change { Store.haptics = it } }
        }
        GlassCard {
            T("Appearance", 16, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Seg(listOf("Light", "Auto", "Dark"), when (Store.themeMode) { 1 -> 0; 2 -> 2; else -> 1 }, { change { Store.themeMode = when (it) { 0 -> 1; 2 -> 2; else -> 0 } } })
            T("Theme color", 13, mu(), modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Accents.forEachIndexed { i, c ->
                    Box(
                        Modifier.size(38.dp).jellyClick { change { Store.accent = i } }.clip(CircleShape).background(c)
                            .border(if (Store.accent == i) 3.dp else 1.dp, Color.White, CircleShape)
                    )
                }
            }
            T("Background", 13, mu(), modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
            Seg(WallNames.take(3), Store.wallpaper.takeIf { it < 3 } ?: -1, { change { Store.wallpaper = it } })
            Spacer(Modifier.height(6.dp))
            Seg(WallNames.drop(3), (Store.wallpaper - 3).takeIf { it >= 0 } ?: -1, { change { Store.wallpaper = it + 3 } })
            if (Store.wallpaper == 5) {
                T("Custom color", 13, mu(), modifier = Modifier.padding(top = 10.dp))
                GlassSlider(Store.customHue / 360f, { change { Store.customHue = it * 360f } })
            }
        }
        GlassCard {
            T("Call screen style", 16, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Seg(listOf("iOS", "ColorOS", "One UI"), Store.callStyle, { change { Store.callStyle = it } })
            Spacer(Modifier.height(10.dp))
            PillBtn("Preview incoming call", accent(), Modifier.fillMaxWidth(), 48) {
                ctx.startActivity(Intent(ctx, InCallActivity::class.java).putExtra("preview", true))
            }
            T("Incoming calls also appear in your phone's own call banner or island, because Jelly Dialer posts a standard call notification.", 12, mu(), modifier = Modifier.padding(top = 8.dp))
        }
        GlassCard {
            T("Liquid glass", 16, weight = FontWeight.SemiBold)
            T("Transparency", 13, mu(), modifier = Modifier.padding(top = 8.dp))
            GlassSlider(Store.glass / .4f, { change { Store.glass = it * .4f } })
            T("Jelly bounce", 13, mu())
            GlassSlider(Store.jelly / 2f, { change { Store.jelly = it * 2f } })
        }
        GlassCard {
            T("AI", 16, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Seg(listOf("On-device", "Online", "Hybrid"), Store.aiEngine, { change { Store.aiEngine = it } })
            Spacer(Modifier.height(8.dp))
            GlassField(Store.apiKey, { change { Store.apiKey = it } }, "Anthropic API key", password = true)
            Spacer(Modifier.height(8.dp))
            GlassField(Store.model, { change { Store.model = it } }, "Model name")
        }
        GlassCard {
            T("System settings", 16, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            val links = listOf<Pair<String, () -> Intent>>(
                "Sound and vibration" to { Intent(Settings.ACTION_SOUND_SETTINGS) },
                "Ringtone" to { Intent(RingtoneManager.ACTION_RINGTONE_PICKER).putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE) },
                "Call notifications" to { Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg) },
                "Full-screen call alerts" to {
                    if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$pkg"))
                    else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                },
                "Blocked numbers" to { ctx.getSystemService(TelecomManager::class.java).createManageBlockedNumbersIntent() },
                "SIM and calling accounts" to { Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS) },
                "Voicemail" to { Intent(TelephonyManager.ACTION_CONFIGURE_VOICEMAIL) },
                "Default apps" to { Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS) },
                "Network and Wi-Fi calling" to { Intent(Settings.ACTION_WIRELESS_SETTINGS) },
                "Display" to { Intent(Settings.ACTION_DISPLAY_SETTINGS) },
                "Battery optimisation" to { Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) },
                "App info" to { Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")) }
            )
            links.forEach { (label, mk) ->
                Row(Modifier.fillMaxWidth().jellyClick { try { open(ctx, mk()) } catch (_: Exception) {} }.padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    T(label, 15, modifier = Modifier.weight(1f))
                    T("\u203A", 20, mu())
                }
            }
        }
        GlassCard {
            T("Developer", 16, weight = FontWeight.SemiBold)
            ToggleRow("Show call debug info", Store.debug) { change { Store.debug = it } }
        }
    }
}

package com.jellyglass.dialer

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*

/** App settings, backed by SharedPreferences and observable by Compose. */
object Store {
    private var sp: SharedPreferences? = null

    var themeMode by mutableIntStateOf(0)      // 0 auto, 1 light, 2 dark
    var accent by mutableIntStateOf(0)
    var wallpaper by mutableIntStateOf(0)      // 0..4 presets, 5 custom
    var customHue by mutableFloatStateOf(20f)
    var callStyle by mutableIntStateOf(0)      // 0 iOS, 1 ColorOS, 2 One UI
    var glass by mutableFloatStateOf(0.18f)
    var jelly by mutableFloatStateOf(1f)
    var aiEngine by mutableIntStateOf(2)       // 0 on-device, 1 online, 2 hybrid
    var apiKey by mutableStateOf("")
    var model by mutableStateOf("claude-haiku-5-5")
    var autoBlock by mutableStateOf(true)
    var spamBadge by mutableStateOf(true)
    var haptics by mutableStateOf(true)
    var debug by mutableStateOf(false)

    fun init(c: Context) {
        if (sp != null) return
        val p = c.applicationContext.getSharedPreferences("jg", Context.MODE_PRIVATE)
        sp = p
        themeMode = p.getInt("themeMode", 0)
        accent = p.getInt("accent", 0)
        wallpaper = p.getInt("wallpaper", 0)
        customHue = p.getFloat("customHue", 20f)
        callStyle = p.getInt("callStyle", 0)
        glass = p.getFloat("glass", 0.18f)
        jelly = p.getFloat("jelly", 1f)
        aiEngine = p.getInt("aiEngine", 2)
        apiKey = p.getString("apiKey", "") ?: ""
        model = p.getString("model", "claude-haiku-5-5") ?: "claude-haiku-5-5"
        autoBlock = p.getBoolean("autoBlock", true)
        spamBadge = p.getBoolean("spamBadge", true)
        haptics = p.getBoolean("haptics", true)
        debug = p.getBoolean("debug", false)
    }

    fun save() {
        sp?.edit()
            ?.putInt("themeMode", themeMode)
            ?.putInt("accent", accent)
            ?.putInt("wallpaper", wallpaper)
            ?.putFloat("customHue", customHue)
            ?.putInt("callStyle", callStyle)
            ?.putFloat("glass", glass)
            ?.putFloat("jelly", jelly)
            ?.putInt("aiEngine", aiEngine)
            ?.putString("apiKey", apiKey)
            ?.putString("model", model)
            ?.putBoolean("autoBlock", autoBlock)
            ?.putBoolean("spamBadge", spamBadge)
            ?.putBoolean("haptics", haptics)
            ?.putBoolean("debug", debug)
            ?.apply()
    }
}

/** Number passed in from a tel: link or ACTION_DIAL. */
object Launch {
    var number by mutableStateOf<String?>(null)
}
