package app.simpleinsta.ig

import android.app.Application
import app.simpleinsta.ig.data.Prefs
import app.simpleinsta.ig.data.Store
import app.simpleinsta.ig.policy.PolicyBundle
import kotlinx.coroutines.flow.MutableStateFlow

class SimpleInstaApp : Application() {

    lateinit var store: Store
        private set

    lateinit var prefs: Prefs
        private set

    lateinit var policy: PolicyBundle
        private set

    /**
     * null = follow the system. Once Instagram reports its own theme we mirror
     * it, because the chrome sitting above and below the WebView should not
     * disagree with the page between them.
     */
    val darkTheme = MutableStateFlow<Boolean?>(null)

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        prefs = Prefs(this)
        policy = PolicyBundle(this)
        prefs.migrateIfNeeded()
    }
}
