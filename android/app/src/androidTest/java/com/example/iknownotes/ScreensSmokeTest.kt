package com.example.iknownotes

import android.app.Activity
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.iknownotes.data.remote.RetrofitClient
import com.example.iknownotes.ui.notedetail.NoteDetailActivity
import com.example.iknownotes.ui.noteedit.NoteEditActivity
import com.example.iknownotes.ui.notes.NotesListActivity
import com.example.iknownotes.ui.trash.TrashActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/**
 * End-to-end smoke test on an emulator: logs in through the real API (the Django
 * dev server must be reachable at 10.0.2.2:8000 with user tester/password123),
 * then opens the real screens. Catches crashes that unit tests can't (layout
 * inflation, view binding, runtime wiring).
 */
@RunWith(AndroidJUnit4::class)
class ScreensSmokeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val base = "http://10.0.2.2:8000"
    private val box = "☐"
    private val done = "☑"

    private lateinit var access: String
    private var checklistNoteId = -1

    @Before
    fun setUp() {
        val tokens = request("POST", "/api/token/", JSONObject().put("username", "tester").put("password", "password123"), null)
        access = JSONObject(tokens).getString("access")
        val refresh = JSONObject(tokens).getString("refresh")
        RetrofitClient.getSessionManager(context).saveSession(access, refresh, "tester")

        // Start from a clean server-side state for this user.
        val existing = JSONObject(request("GET", "/api/notes/?page_size=100", null, access)).getJSONArray("results")
        for (i in 0 until existing.length()) {
            val id = existing.getJSONObject(i).getInt("id")
            request("DELETE", "/api/notes/$id/", null, access)
            request("DELETE", "/api/notes/$id/permanent/", null, access)
        }

        post("Belanja", "<p dir=\"ltr\">$box susu<br>$done telur<br>$box roti</p>", "yellow")
            .also { checklistNoteId = it }
        post("Catatan biasa", "<p dir=\"ltr\">isi biasa saja</p>", "blue")
        post("Rapat tim", "<p dir=\"ltr\">agenda rapat</p>", "green")
    }

    @After
    fun tearDown() {
        // Don't leave the offline cache or token behind for the next test.
        com.example.iknownotes.data.repo.NotesRepository.get(context).clearLocalData()
    }

    @Test
    fun notesList_showsNotes_andOpensTheFilterSheet() {
        ActivityScenario.launch(NotesListActivity::class.java).use { scenario ->
            waitUntil("list shows 3 notes") { itemCount(scenario) == 3 }

            click(scenario, R.id.btn_filter)
            waitUntil("filter sheet is shown") { onScreen(context.getString(R.string.action_apply)) }
            assertTrue(onScreen(context.getString(R.string.sort_title_asc)))
            assertTrue(onScreen(context.getString(R.string.date_7_days)))
        }
    }

    @Test
    fun searchBox_filtersOnTheDevice() {
        ActivityScenario.launch(NotesListActivity::class.java).use { scenario ->
            waitUntil("list shows 3 notes") { itemCount(scenario) == 3 }

            instrumentation.runOnMainSync {
                scenario.onActivity { it.findViewById<android.widget.EditText>(R.id.et_search).setText("rapat") }
            }
            waitUntil("search narrows to 1 note") { itemCount(scenario) == 1 }
        }
    }

    @Test
    fun viewModeToggle_switchesBetweenGridAndList() {
        ActivityScenario.launch(NotesListActivity::class.java).use { scenario ->
            waitUntil("list shows 3 notes") { itemCount(scenario) == 3 }
            fun spanCount(): Int {
                var span = 0
                instrumentation.runOnMainSync {
                    scenario.onActivity {
                        val rv = it.findViewById<RecyclerView>(R.id.rv_notes)
                        span = (rv.layoutManager as androidx.recyclerview.widget.StaggeredGridLayoutManager).spanCount
                    }
                }
                return span
            }
            val before = spanCount()
            click(scenario, R.id.btn_view_mode)
            assertTrue("span count should change", spanCount() != before)
            click(scenario, R.id.btn_view_mode)
            assertEquals(before, spanCount())
        }
    }

    @Test
    fun detailScreen_rendersChecklist_ticksItem_andShowsShareMenu() {
        val intent = android.content.Intent(context, NoteDetailActivity::class.java)
            .putExtra(NoteDetailActivity.EXTRA_NOTE_ID, checklistNoteId)
        ActivityScenario.launch<NoteDetailActivity>(intent).use { scenario ->
            waitUntil("checklist text is shown") { contentText(scenario).contains("$box susu") }
            assertTrue(contentText(scenario).contains("$done telur"))

            // Tap the first box through its ClickableSpan (a real touch would hit the same span).
            instrumentation.runOnMainSync {
                scenario.onActivity {
                    val tv = it.findViewById<TextView>(R.id.tv_content)
                    val spans = (tv.text as Spanned).getSpans(0, tv.text.length, ClickableSpan::class.java)
                    assertTrue("expected one tappable box per item", spans.size == 3)
                    spans.minByOrNull { span -> (tv.text as Spanned).getSpanStart(span) }!!.onClick(tv)
                }
            }
            waitUntil("first box is ticked") { contentText(scenario).contains("$done susu") }

            // ...and the change reached the server.
            waitUntil("server stored the ticked box") {
                JSONObject(request("GET", "/api/notes/$checklistNoteId/", null, access))
                    .getString("content").contains("&#9745; susu")
            }

            click(scenario, R.id.btn_share)
            waitUntil("share menu is shown") { onScreen(context.getString(R.string.export_pdf)) }
            assertTrue(onScreen(context.getString(R.string.export_txt)))
            assertTrue(onScreen(context.getString(R.string.copy_text)))
        }
    }

    @Test
    fun editScreen_opensWithChecklistToolbar_andLoadsTheNote() {
        val intent = android.content.Intent(context, NoteEditActivity::class.java)
            .putExtra(NoteEditActivity.EXTRA_NOTE_ID, checklistNoteId)
        ActivityScenario.launch<NoteEditActivity>(intent).use { scenario ->
            waitUntil("note loaded into the editor") {
                var text = ""
                instrumentation.runOnMainSync {
                    scenario.onActivity { text = it.findViewById<android.widget.EditText>(R.id.et_content).text.toString() }
                }
                text.contains("$box susu")
            }
            var toolbarShown = false
            instrumentation.runOnMainSync {
                scenario.onActivity { toolbarShown = it.findViewById<View>(R.id.btn_checklist).isShown }
            }
            assertTrue("checklist button should be visible", toolbarShown)
        }
    }

    @Test
    fun trashScreen_opens() {
        ActivityScenario.launch(TrashActivity::class.java).use { scenario ->
            var shown = false
            instrumentation.runOnMainSync {
                scenario.onActivity { shown = it.findViewById<View>(R.id.btn_back).isShown }
            }
            assertTrue(shown)
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun <A : Activity> click(scenario: ActivityScenario<A>, viewId: Int) {
        instrumentation.runOnMainSync {
            scenario.onActivity { it.findViewById<View>(viewId).performClick() }
        }
    }

    /** True when [text] is visible in the foreground window (works for dialogs and sheets). */
    private fun onScreen(text: String): Boolean {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        return !root.findAccessibilityNodeInfosByText(text).isNullOrEmpty()
    }

    private fun itemCount(scenario: ActivityScenario<NotesListActivity>): Int {
        var count = -1
        instrumentation.runOnMainSync {
            scenario.onActivity { count = it.findViewById<RecyclerView>(R.id.rv_notes).adapter?.itemCount ?: -1 }
        }
        return count
    }

    private fun contentText(scenario: ActivityScenario<NoteDetailActivity>): String {
        var text = ""
        instrumentation.runOnMainSync {
            scenario.onActivity { text = it.findViewById<TextView>(R.id.tv_content).text.toString() }
        }
        return text
    }

    private fun waitUntil(what: String, timeoutMs: Long = 90_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (runCatching(condition).getOrDefault(false)) return
            Thread.sleep(300)
        }
        fail("Timed out waiting for: $what")
    }

    private fun post(title: String, content: String, color: String): Int {
        val body = JSONObject().put("title", title).put("content", content).put("color", color)
        return JSONObject(request("POST", "/api/notes/", body, access)).getInt("id")
    }

    private fun request(method: String, path: String, body: JSONObject?, token: String?): String {
        val connection = URL(base + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Content-Type", "application/json")
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            // 404 on cleanup deletes is expected (note not in trash yet), everything else must succeed.
            if (code !in 200..299 && !(method == "DELETE" && code == 404)) {
                fail("$method $path -> $code: $text")
            }
            return text
        } finally {
            connection.disconnect()
        }
    }
}
