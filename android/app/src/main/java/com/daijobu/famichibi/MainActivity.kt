package com.daijobu.famichibi

import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.daijobu.famichibi.net.Family
import com.daijobu.famichibi.service.ChibiService
import com.daijobu.famichibi.ui.FamiTheme
import com.daijobu.famichibi.ui.FamilyScreen
import com.daijobu.famichibi.ui.HomeScreen
import com.daijobu.famichibi.ui.JoinRequest
import com.daijobu.famichibi.ui.Onboarding
import com.daijobu.famichibi.ui.SettingsScreen
import com.daijobu.famichibi.ui.VoiceScreen
import com.daijobu.famichibi.ui.errText
import com.daijobu.famichibi.ui.toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class MainActivity : ComponentActivity() {
    private val join = MutableStateFlow<JoinRequest?>(null)
    private val openTarget = MutableStateFlow<Pair<String, String?>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Graph.init(this)
        enableEdgeToEdge()
        handleIntent(intent)
        if (Graph.prefs.accounts.value.isNotEmpty() && Graph.prefs.settings.value.onboarded) ChibiService.start(this)
        setContent { FamiTheme { AppNav() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        i ?: return
        var uri: Uri? = i.data
        if (i.action == NfcAdapter.ACTION_NDEF_DISCOVERED && uri == null) {
            @Suppress("DEPRECATION")
            val msgs = i.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
            uri = (msgs?.firstOrNull() as? NdefMessage)?.records?.firstNotNullOfOrNull { it.toUri() }
        }
        if (uri != null && uri.scheme == "famichibi") {
            val host = uri.getQueryParameter("host")
            if (host != null) join.value = JoinRequest(host, uri.getQueryParameter("code"), uri.getQueryParameter("invite"))
        }
        i.getStringExtra("family")?.let { openTarget.value = it to i.getStringExtra("channel") }
    }

    @Composable
    private fun AppNav() {
        val nav = rememberNavController()
        val ctx = LocalContext.current
        val accounts by Graph.prefs.accounts.collectAsState()
        val settings by Graph.prefs.settings.collectAsState()
        val pendingJoin by join.collectAsState()
        val target by openTarget.collectAsState()
        val start = androidx.compose.runtime.remember { if (accounts.isEmpty() || !settings.onboarded) "onboarding" else "home" }

        // Deep link / NFC while already signed in: join directly on that host.
        LaunchedEffect(pendingJoin, accounts.size) {
            val j = pendingJoin ?: return@LaunchedEffect
            if (accounts.isEmpty() || !settings.onboarded) return@LaunchedEffect
            val base = com.daijobu.famichibi.net.Api.normalizeBase(j.host)
            val s = Graph.sessions.value.firstOrNull { it.api.base == base }
            if (s == null) { nav.navigate("addhost"); return@LaunchedEffect }
            runCatching {
                val r = if (j.invite != null) s.api.post("/api/invites/${j.invite}/claim", buildJsonObject { })
                else s.api.post("/api/families/join", buildJsonObject { put("code", j.code.orEmpty()) })
                val f: Family = s.api.decode(r["family"])
                s.upsertFamily(f)
                nav.navigate("family/${f.id}")
            }.onFailure { toast(ctx, errText(it)) }
            join.value = null
        }
        LaunchedEffect(target, accounts.size) {
            val (fid, cid) = target ?: return@LaunchedEffect
            if (accounts.isEmpty()) return@LaunchedEffect
            nav.navigate("family/$fid" + (cid?.let { "?channel=$it" } ?: ""))
            openTarget.value = null
        }

        NavHost(nav, startDestination = start) {
            composable("onboarding") {
                Onboarding(pendingJoin) { fid ->
                    join.value = null
                    nav.navigate(if (fid != null) "family/$fid" else "home") { popUpTo("onboarding") { inclusive = true } }
                    if (fid != null) Graph.prefs.lastFamilyId = fid
                }
            }
            composable("addhost") {
                Onboarding(pendingJoin, startAt = 1) { fid ->
                    join.value = null
                    nav.navigate(if (fid != null) "family/$fid" else "home") { popUpTo("home") }
                }
            }
            composable("home") {
                HomeScreen(openFamily = { nav.navigate("family/$it") }, openSettings = { nav.navigate("settings") }, addHost = { nav.navigate("addhost") })
            }
            composable("family/{fid}?channel={cid}", arguments = listOf(
                navArgument("fid") { type = NavType.StringType },
                navArgument("cid") { type = NavType.StringType; nullable = true; defaultValue = null },
            )) { e ->
                val fid = e.arguments?.getString("fid")!!
                FamilyScreen(fid, e.arguments?.getString("cid"), back = { if (!nav.popBackStack()) nav.navigate("home") }, openVoice = { cid -> nav.navigate("voice/$fid/$cid") })
            }
            composable("voice/{fid}/{cid}") { e ->
                VoiceScreen(e.arguments?.getString("fid")!!, e.arguments?.getString("cid")!!, back = { nav.popBackStack() })
            }
            composable("settings") { SettingsScreen(back = { nav.popBackStack() }, addHost = { nav.navigate("addhost") }) }
        }
    }
}
