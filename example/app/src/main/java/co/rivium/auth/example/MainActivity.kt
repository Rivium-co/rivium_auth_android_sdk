package co.rivium.auth.example

import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import co.rivium.auth.ListenerRegistration
import co.rivium.auth.RiviumAuth
import co.rivium.auth.RiviumAuthException
import kotlinx.coroutines.launch

// Rivium Auth — Android example (SDK 0.1.1)
//
// Paste your public project key (Rivium Console → your project → API keys) into the field below,
// or replace the placeholder. Never put a server secret in an app.
// In a real app call RiviumAuth.shared.configure(...) once, in Application.onCreate.

private const val API_KEY_PLACEHOLDER = "rv_live_YOUR_PUBLIC_KEY"

class MainActivity : AppCompatActivity() {

    private val auth = RiviumAuth.shared
    private var registration: ListenerRegistration? = null

    private lateinit var apiKey: EditText
    private lateinit var email: EditText
    private lateinit var password: EditText
    private lateinit var userLabel: TextView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(TextView(this).apply { text = "Rivium Auth"; textSize = 22f; typeface = Typeface.DEFAULT_BOLD })

        apiKey = field("Public API key (rv_live_...)", API_KEY_PLACEHOLDER)
        root.addView(apiKey)
        root.addView(button("Configure") { configure() })

        userLabel = TextView(this).apply { setPadding(0, dp(12), 0, dp(4)); textSize = 16f }
        root.addView(userLabel)
        status = TextView(this).apply { setPadding(0, 0, 0, dp(12)) }
        root.addView(status)

        root.addView(button("Sign in anonymously") { run { auth.signInAnonymously(); "Signed in anonymously." } })

        email = field("E-mail", "").apply { inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS or InputType.TYPE_CLASS_TEXT }
        password = field("Password", "").apply { inputType = InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_CLASS_TEXT }
        root.addView(email)
        root.addView(password)
        val emailRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        emailRow.addView(button("Sign up") {
            run {
                val result = auth.signUp(email.text.toString().trim(), password.text.toString())
                if (result.signedIn) "Signed up." else "Check your inbox: confirm the e-mail, then sign in."
            }
        })
        emailRow.addView(button("Sign in") {
            run { auth.signIn(email.text.toString().trim(), password.text.toString()); "Signed in with e-mail." }
        })
        root.addView(emailRow)

        val accountRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        accountRow.addView(button("Sign out") { run { auth.signOut(); "Signed out." } })
        accountRow.addView(button("Delete account") { run { auth.deleteAccount(); "Account deleted." } })
        root.addView(accountRow)

        setContentView(ScrollView(this).apply { addView(root) })
        configure()
    }

    override fun onDestroy() {
        registration?.remove()
        super.onDestroy()
    }

    private fun configure() {
        val key = apiKey.text.toString().trim()
        if (key.isEmpty() || key == API_KEY_PLACEHOLDER) {
            show("Paste your public API key, then tap Configure.")
            renderUser(null)
            return
        }
        registration?.remove()
        auth.configure(applicationContext, key)
        // Called on sign-in, sign-out and when a session ends; post to the main thread for views.
        registration = auth.addAuthStateListener { userId -> runOnUiThread { renderUser(userId) } }
        show("Configured.")
    }

    /** Runs one SDK call and shows its result or its error code. */
    private fun run(action: suspend () -> String) {
        show("Working…")
        lifecycleScope.launch {
            try {
                show(action())
            } catch (e: RiviumAuthException) {
                show("${e.code}: ${e.message}")
            } catch (e: Exception) {
                show(e.message ?: e.toString())
            }
        }
    }

    private fun renderUser(userId: String?) {
        userLabel.text = "User: ${userId ?: "signed out"}"
    }

    private fun show(message: String) {
        status.text = message
    }

    private fun field(hint: String, value: String) = EditText(this).apply {
        this.hint = hint
        setText(value)
        isSingleLine = true
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
