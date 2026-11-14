package com.example.iknownotes

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.iknownotes.data.remote.RetrofitClient
import com.example.iknownotes.data.repo.NotesRepository
import com.example.iknownotes.ui.splash.SplashActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Only meaningful for a build made with -PdevLogin=true (skipped otherwise) and a reachable backend:
 * opening the app logged out must end up signed in, without touching the login screen.
 */
@RunWith(AndroidJUnit4::class)
class AutoLoginInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before
    fun loggedOut() {
        assumeTrue("needs a -PdevLogin=true build", BuildConfig.DEV_AUTO_LOGIN)
        NotesRepository.get(context).clearLocalData()
        RetrofitClient.getSessionManager(context).clear()
    }

    @Test
    fun launchingLoggedOut_signsInTheTestAccountAndOpensTheNotes() {
        val session = RetrofitClient.getSessionManager(context)
        assertTrue(!session.isLoggedIn)

        ActivityScenario.launch(SplashActivity::class.java).use {
            val end = System.currentTimeMillis() + 90_000
            while (!session.isLoggedIn && System.currentTimeMillis() < end) Thread.sleep(300)
            if (!session.isLoggedIn) fail("auto-login never signed in")

            assertEquals(BuildConfig.DEV_USERNAME, session.username)
            assertTrue(!session.accessToken.isNullOrBlank())
        }
    }

    @Test
    fun secondLaunch_reusesTheExistingAccount() {
        val session = RetrofitClient.getSessionManager(context)
        // First run creates the account (or logs in if it exists)...
        ActivityScenario.launch(SplashActivity::class.java).use {
            val end = System.currentTimeMillis() + 90_000
            while (!session.isLoggedIn && System.currentTimeMillis() < end) Thread.sleep(300)
            assertTrue(session.isLoggedIn)
        }
        // ...and after logging out, the same account is signed in again (login path, not register).
        session.clear()
        ActivityScenario.launch(SplashActivity::class.java).use {
            val end = System.currentTimeMillis() + 90_000
            while (!session.isLoggedIn && System.currentTimeMillis() < end) Thread.sleep(300)
            assertTrue(session.isLoggedIn)
            assertEquals(BuildConfig.DEV_USERNAME, session.username)
        }
    }
}
