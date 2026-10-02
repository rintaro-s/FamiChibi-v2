package com.daijobu.famichibi

import android.annotation.SuppressLint
import android.content.Context
import com.daijobu.famichibi.chibi.AvatarCache
import com.daijobu.famichibi.chibi.Speech
import com.daijobu.famichibi.data.HostAccount
import com.daijobu.famichibi.data.Prefs
import com.daijobu.famichibi.net.Family
import com.daijobu.famichibi.net.HostSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/** A family together with the host session it lives on. */
data class FamRef(val session: HostSession, val family: Family)

/** Application-wide singletons (manual DI). */
@SuppressLint("StaticFieldLeak")
object Graph {
    lateinit var app: Context
        private set
    lateinit var prefs: Prefs
        private set
    lateinit var avatars: AvatarCache
        private set
    lateinit var speech: Speech
        private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _sessions = MutableStateFlow<List<HostSession>>(emptyList())
    val sessions: StateFlow<List<HostSession>> = _sessions.asStateFlow()

    /** Which family screen is visible in the app right now (to avoid double deliveries by the overlay). */
    val foregroundFamily = MutableStateFlow<String?>(null)
    val appInForeground = MutableStateFlow(false)

    @OptIn(ExperimentalCoroutinesApi::class)
    val families: StateFlow<List<FamRef>> by lazy {
        _sessions.flatMapLatest { list ->
            if (list.isEmpty()) flowOf(emptyList())
            else combine(list.map { s -> s.families.combineWith(s) }) { arr -> arr.flatMap { it } }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    }

    private fun StateFlow<List<Family>>.combineWith(s: HostSession): Flow<List<FamRef>> =
        combine(this, flowOf(s)) { fams, sess -> fams.map { FamRef(sess, it) } }

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        prefs = Prefs(app)
        avatars = AvatarCache(app)
        speech = Speech(app)
        com.daijobu.famichibi.data.Silent.start()
        syncSessions()
    }

    /** Create/destroy sessions to match the saved accounts. */
    fun syncSessions() {
        val accounts = prefs.accounts.value
        val current = _sessions.value
        // the token identifies the account; the URL may change (failover), so it is not part of the identity
        val keep = current.filter { s -> accounts.any { it.token == s.account.token } }
        current.filterNot { it in keep }.forEach { it.stop() }
        val added = accounts.filter { a -> keep.none { it.account.token == a.token } }
            .map { HostSession(it, scope).also { s -> s.start() } }
        _sessions.value = keep + added
    }

    fun addAccount(a: HostAccount): HostSession {
        prefs.upsertAccount(a)
        syncSessions()
        return _sessions.value.first { it.account.url == a.url }
    }

    fun session(url: String?): HostSession? = _sessions.value.firstOrNull { it.account.url == url } ?: _sessions.value.firstOrNull()

    fun famRef(familyId: String?): FamRef? = families.value.firstOrNull { it.family.id == familyId }
}
