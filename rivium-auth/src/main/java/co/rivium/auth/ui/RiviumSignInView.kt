package co.rivium.auth.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.BidiFormatter
import android.text.Editable
import android.text.InputType
import android.text.TextDirectionHeuristics
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import co.rivium.auth.RiviumAuth
import co.rivium.auth.AuthResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Told the user once they signed in. */
fun interface RiviumSignInListener {
    fun onSignedIn(result: AuthResult)
}

/**
 * The ready-made sign-in form, to put in any layout of your app. It shows only the methods turned on in the Console
 * and calls [onSignedIn] once a user is signed in. [RiviumSignIn.show] opens it as a dialog.
 *
 * Built from Android framework views only. Colours come from your theme (`colorAccent`, `colorPrimary`,
 * `colorBackground`, `textColorPrimary`). A right-to-left language is laid out right to left; that needs
 * `android:supportsRtl="true"` in your manifest (the default for new projects).
 */
@SuppressLint("ViewConstructor")
class RiviumSignInView @JvmOverloads constructor(
    context: Context,
    auth: RiviumAuth = RiviumAuth.shared,
    val options: RiviumSignInOptions = RiviumSignInOptions(),
    onSignedIn: RiviumSignInListener? = null,
) : FrameLayout(context) {

    /** The screen's state. */
    val controller: RiviumSignInController = RiviumSignInController(
        auth = auth,
        options = options,
        strings = RiviumSignInStrings.of(options.languageCode ?: appLanguage(context)),
        onSignedIn = { result -> onSignedIn?.onSignedIn(result) },
    )

    /** Shows a close button (disabled while a sign-in runs). [RiviumSignIn.show] sets it. */
    var onClose: (() -> Unit)? = null
        set(value) {
            field = value
            closeButton.visibility = if (value == null) View.GONE else View.VISIBLE
            refresh()
        }

    /** Told whenever [RiviumSignInController.busy] may have changed. */
    internal var onBusyChanged: ((Boolean) -> Unit)? = null

    private val c = controller
    private val s = c.strings
    private val palette = Palette.of(context)
    private var scope: CoroutineScope? = null
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(context)
    private val closeButton: Button
    private val updaters = mutableListOf<() -> Unit>()
    private var builtKey: Any? = null

    init {
        layoutDirection = if (s.rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        textDirection = if (s.rtl) View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_LTR
        setBackgroundColor(palette.background)
        val column = FrameLayout(context).apply {
            setPadding(dp(24), dp(40), dp(24), dp(24))
            addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        // At most 400dp wide, centred.
        val limit = object : FrameLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val max = dp(448)
                val w = MeasureSpec.getSize(widthMeasureSpec)
                val spec = if (w > max) MeasureSpec.makeMeasureSpec(max, MeasureSpec.EXACTLY) else widthMeasureSpec
                super.onMeasure(spec, heightMeasureSpec)
            }
        }
        limit.addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        scroll.isFillViewport = true
        scroll.addView(FrameLayout(context).apply {
            addView(limit, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        })
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        closeButton = flatButton("✕", "rivium-close") { if (!c.busy) onClose?.invoke() }.apply {
            contentDescription = s["close"]
            textSize = 18f
            visibility = View.GONE
        }
        addView(closeButton, LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
        c.onChange = { refresh() }
        refresh()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (scope != null) return
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = sc
        builtKey = null
        sc.launch { c.load() }
    }

    override fun onDetachedFromWindow() {
        scope?.cancel()
        scope = null
        c.close()
        super.onDetachedFromWindow()
    }

    /** Runs a controller action on the main thread; it starts at once, so [RiviumSignInController.busy] is set. */
    private fun launch(action: suspend RiviumSignInController.() -> Unit) {
        scope?.launch { c.action() }
    }

    // ------------------------------------------------------------------ Rendering

    private fun refresh() {
        val key = Triple(c.step, c.safeBotUrl, c.sent)
        if (key != builtKey) {
            builtKey = key
            build()
        }
        updaters.forEach { it() }
        closeButton.isEnabled = !c.busy
        closeButton.alpha = if (c.busy) 0.4f else 1f
        onBusyChanged?.invoke(c.busy)
    }

    private fun build() {
        content.removeAllViews()
        updaters.clear()
        when (c.step) {
            RiviumSignInStep.LOADING -> content.addView(
                ProgressBar(context).apply { isIndeterminate = true },
                LinearLayout.LayoutParams(dp(48), dp(48)).apply { gravity = Gravity.CENTER_HORIZONTAL; setMargins(0, dp(48), 0, dp(48)) },
            )
            RiviumSignInStep.HOME -> buildHome()
            RiviumSignInStep.SIGN_UP -> buildSignUp()
            RiviumSignInStep.FORGOT -> buildEmailOnly(s["forgotTitle"], s["sendReset"], "rivium-forgot") { sendReset() }
            RiviumSignInStep.MAGIC_LINK -> buildEmailOnly(s["magicTitle"], s["sendLink"], "rivium-magic-link") { sendMagicLink() }
            RiviumSignInStep.TELEGRAM_PHONE, RiviumSignInStep.TELEGRAM_CODE -> buildTelegram()
            RiviumSignInStep.SENT -> buildSent()
            RiviumSignInStep.UNAVAILABLE -> buildUnavailable()
        }
    }

    private fun buildHome() {
        header(c.title, back = null)
        errorBanner()
        if (c.showPassword) {
            add(field(s["email"], "rivium-email", { c.email }, { c.email = it }, { c.emailError }, Kind.EMAIL))
            gap(12)
            add(field(s["password"], "rivium-password", { c.password }, { c.password = it }, { c.passwordError }, Kind.PASSWORD) { launch { signIn() } })
            add(flatButton(s["forgot"], "rivium-forgot") { c.go(RiviumSignInStep.FORGOT) }.also { disableWhileBusy(it) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END })
            add(primaryButton(s["signIn"], "rivium-sign-in") { launch { signIn() } })
            add(flatButton(s["noAccount"], "rivium-to-sign-up") { c.go(RiviumSignInStep.SIGN_UP) }.also { disableWhileBusy(it) })
        }
        if (c.showPassword && c.hasOtherMethods) orDivider()
        if (c.hasOtherMethods) otherMethods()
    }

    private fun otherMethods() {
        if (c.showGoogle) methodButton("G", s["withGoogle"], "rivium-google") { launch { continueWithGoogle() } }
        if (c.showApple) methodButton(null, s["withApple"], "rivium-apple") { launch { continueWithApple() } }
        if (c.showMagicLink) methodButton("✉", s["withEmailLink"], "rivium-magic-link") { c.go(RiviumSignInStep.MAGIC_LINK) }
        if (c.showTelegram) methodButton("➤", s["withTelegram"], "rivium-telegram") { c.go(RiviumSignInStep.TELEGRAM_PHONE) }
        if (c.showGuest) add(flatButton(s["guest"], "rivium-guest") { launch { continueAsGuest() } }.also { disableWhileBusy(it) })
    }

    private fun buildSignUp() {
        header(s["signUpTitle"], back = { c.back() })
        errorBanner()
        add(field(s["email"], "rivium-sign-up-email", { c.email }, { c.email = it }, { c.emailError }, Kind.EMAIL))
        gap(12)
        add(field(s["password"], "rivium-sign-up-password", { c.password }, { c.password = it }, { c.passwordError }, Kind.NEW_PASSWORD) { launch { signUp() } })
        gap(16)
        add(primaryButton(s["signUp"], "rivium-sign-up") { launch { signUp() } })
        add(flatButton(s["haveAccount"], "rivium-to-sign-in") { c.back() }.also { disableWhileBusy(it) })
    }

    private fun buildEmailOnly(title: String, action: String, tag: String, send: suspend RiviumSignInController.() -> Unit) {
        header(title, back = { c.back() })
        errorBanner()
        add(field(s["email"], "$tag-email", { c.email }, { c.email = it }, { c.emailError }, Kind.EMAIL) { launch(send) })
        gap(16)
        add(primaryButton(action, "$tag-send") { launch(send) })
    }

    private fun buildTelegram() {
        val codeStep = c.step == RiviumSignInStep.TELEGRAM_CODE
        header(s["telegramTitle"], back = { if (codeStep) c.go(RiviumSignInStep.TELEGRAM_PHONE) else c.back() })
        errorBanner()
        if (!codeStep) {
            add(field(s["phone"], "rivium-phone", { c.phone }, { c.phone = it }, { null }, Kind.PHONE, hint = s["phoneHint"]) { launch { sendTelegramCode() } })
            val bot = c.safeBotUrl
            if (bot != null) {
                gap(12)
                add(label(s["botFirst"].replace("{bot}", botName(bot))))
                gap(4)
                val link = label(bot).apply {
                    tag = "rivium-bot-link"
                    layoutDirection = View.LAYOUT_DIRECTION_LTR
                    textDirection = View.TEXT_DIRECTION_LTR
                    setTextColor(palette.accent)
                    setTextIsSelectable(options.openUrl == null)
                    if (options.openUrl != null) setOnClickListener { if (!c.busy) c.openBot() }
                }
                add(link)
            }
            gap(16)
            add(primaryButton(if (bot != null) s["retry"] else s["sendCode"], "rivium-send-code") { launch { sendTelegramCode() } })
        } else {
            add(label(s["codeSent"]))
            gap(12)
            add(field(s["code"], "rivium-code", { c.code }, { c.code = it }, { null }, Kind.CODE) { launch { verifyTelegramCode() } })
            gap(16)
            add(primaryButton(s["verify"], "rivium-verify-code") { launch { verifyTelegramCode() } })
        }
    }

    private fun buildSent() {
        header(s["checkEmail"], back = null)
        val key = when (c.sent) {
            RiviumSentKind.RESET -> "resetSent"
            RiviumSentKind.MAGIC_LINK -> "linkSent"
            else -> "verifySent"
        }
        add(label("✉").apply { textSize = 40f; gravity = Gravity.CENTER; setTextColor(palette.accent) })
        gap(8)
        add(label(s[key].replace("{email}", BidiFormatter.getInstance(s.rtl).unicodeWrap(c.email.trim(), TextDirectionHeuristics.LTR))).apply { tag = "rivium-sent"; gravity = Gravity.CENTER })
        gap(16)
        add(outlinedButton(s["back"], "rivium-back") { c.back() })
    }

    private fun buildUnavailable() {
        header(c.title, back = null)
        val text = label("").apply { tag = "rivium-unavailable"; gravity = Gravity.CENTER }
        updaters += { text.text = c.errorText ?: s["noMethods"] }
        add(text)
        gap(16)
        add(outlinedButton(s["retry"], "rivium-retry") { launch { load() } })
    }

    // ------------------------------------------------------------------ Pieces

    private fun header(title: String, back: (() -> Unit)?) {
        options.logo?.let { logo ->
            add(ImageView(context).apply {
                setImageDrawable(logo)
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(64), dp(64)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12) })
        }
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (back != null) {
            val b = flatButton(if (s.rtl) "→" else "←", "rivium-header-back") { back() }.apply {
                contentDescription = s["back"]
                textSize = 20f
            }
            disableWhileBusy(b)
            row.addView(b, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        row.addView(TextView(context).apply {
            text = title
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.text)
            gravity = if (back == null) Gravity.CENTER else Gravity.CENTER_VERTICAL or Gravity.START
            textAlignment = if (back == null) View.TEXT_ALIGNMENT_CENTER else View.TEXT_ALIGNMENT_VIEW_START
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (back != null) row.addView(View(context), LinearLayout.LayoutParams(dp(48), dp(1)))
        add(row)
        gap(20)
    }

    private fun errorBanner() {
        val banner = TextView(context).apply {
            tag = "rivium-sign-in-error"
            setTextColor(palette.error)
            background = rounded(palette.errorBackground, 0, 8)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            textSize = 14f
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        add(banner, params().apply { bottomMargin = dp(12) })
        updaters += {
            val t = c.errorText
            banner.text = t ?: ""
            banner.visibility = if (t == null) View.GONE else View.VISIBLE
        }
    }

    private enum class Kind { EMAIL, PASSWORD, NEW_PASSWORD, PHONE, CODE }

    private fun field(
        label: String,
        tag: String,
        value: () -> String,
        onChanged: (String) -> Unit,
        error: () -> String?,
        kind: Kind,
        hint: String? = null,
        onSubmit: (() -> Unit)? = null,
    ): View {
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(context).apply {
            text = label
            textSize = 13f
            setTextColor(palette.muted)
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }, params().apply { bottomMargin = dp(4) })
        val ltr = kind == Kind.EMAIL || kind == Kind.PHONE || kind == Kind.CODE
        val edit = EditText(context).apply {
            this.tag = tag
            contentDescription = label
            this.hint = hint
            setText(value())
            isSingleLine = true
            textSize = 16f
            setTextColor(palette.text)
            setHintTextColor(palette.muted)
            background = rounded(Color.TRANSPARENT, palette.outline, 8)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            inputType = when (kind) {
                Kind.EMAIL -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                Kind.PASSWORD, Kind.NEW_PASSWORD -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                Kind.PHONE -> InputType.TYPE_CLASS_PHONE
                Kind.CODE -> InputType.TYPE_CLASS_NUMBER
            }
            if (ltr) {
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                textDirection = View.TEXT_DIRECTION_LTR
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            }
            if (Build.VERSION.SDK_INT >= 26) {
                setAutofillHints(
                    when (kind) {
                        Kind.EMAIL -> View.AUTOFILL_HINT_EMAIL_ADDRESS
                        Kind.PASSWORD -> View.AUTOFILL_HINT_PASSWORD
                        Kind.NEW_PASSWORD -> "newPassword"
                        Kind.PHONE -> View.AUTOFILL_HINT_PHONE
                        Kind.CODE -> "smsOTPCode"
                    },
                )
            }
            imeOptions = if (onSubmit == null) EditorInfo.IME_ACTION_NEXT else EditorInfo.IME_ACTION_DONE
            if (onSubmit != null) {
                setOnEditorActionListener { _, id, _ ->
                    if (id == EditorInfo.IME_ACTION_DONE) { onSubmit(); true } else false
                }
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = onChanged(s?.toString().orEmpty())
            })
        }
        box.addView(edit, params())
        val errorView = TextView(context).apply {
            textSize = 12f
            setTextColor(palette.error)
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }
        box.addView(errorView, params().apply { topMargin = dp(4) })
        updaters += {
            // The controller cleared it (for example the password after a sign-in).
            if (edit.text.toString() != value()) edit.setText(value())
            val e = error()
            errorView.text = e ?: ""
            errorView.visibility = if (e == null) View.GONE else View.VISIBLE
            edit.isEnabled = !c.busy
        }
        return box
    }

    private fun primaryButton(label: String, tag: String, onClick: () -> Unit): View {
        val frame = FrameLayout(context)
        val button = Button(context).apply {
            this.tag = tag
            text = label
            isAllCaps = false
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.onAccent)
            background = rounded(palette.accent, 0, 8)
            stateListAnimator = null
            minHeight = dp(48)
            setOnClickListener { if (!c.busy) onClick() }
        }
        val progress = ProgressBar(context).apply {
            isIndeterminate = true
            visibility = View.GONE
            indeterminateTintList = android.content.res.ColorStateList.valueOf(palette.onAccent)
        }
        frame.addView(button, LayoutParams(LayoutParams.MATCH_PARENT, dp(48)))
        frame.addView(progress, LayoutParams(dp(24), dp(24), Gravity.CENTER))
        updaters += {
            button.isEnabled = !c.busy
            button.text = if (c.busy) "" else label
            progress.visibility = if (c.busy) View.VISIBLE else View.GONE
        }
        return frame
    }

    private fun methodButton(icon: String?, label: String, tag: String, onClick: () -> Unit) {
        val b = outlinedButton(if (icon == null) label else "$icon   $label", tag, onClick)
        add(b, params().apply { bottomMargin = dp(10) })
    }

    private fun outlinedButton(label: String, tag: String, onClick: () -> Unit): Button = Button(context).apply {
        this.tag = tag
        text = label
        isAllCaps = false
        textSize = 15f
        setTextColor(palette.text)
        background = rounded(Color.TRANSPARENT, palette.outline, 8)
        stateListAnimator = null
        minHeight = dp(48)
        setOnClickListener { if (!c.busy) onClick() }
    }.also { disableWhileBusy(it) }

    private fun flatButton(label: String, tag: String, onClick: () -> Unit): Button = Button(context).apply {
        this.tag = tag
        text = label
        isAllCaps = false
        textSize = 14f
        setTextColor(palette.accent)
        setBackgroundColor(Color.TRANSPARENT)
        stateListAnimator = null
        minHeight = dp(40)
        minimumHeight = dp(40)
        minWidth = 0
        minimumWidth = 0
        setOnClickListener { if (!c.busy) onClick() }
    }

    private fun disableWhileBusy(v: View) {
        updaters += {
            v.isEnabled = !c.busy
            v.alpha = if (c.busy) 0.5f else 1f
        }
    }

    private fun orDivider() {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun line() = View(context).apply { setBackgroundColor(palette.outline) }
        row.addView(line(), LinearLayout.LayoutParams(0, dp(1), 1f))
        row.addView(TextView(context).apply {
            text = s["or"]
            setTextColor(palette.muted)
            setPadding(dp(12), 0, dp(12), 0)
        })
        row.addView(line(), LinearLayout.LayoutParams(0, dp(1), 1f))
        add(row, params().apply { setMargins(0, dp(12), 0, dp(12)) })
    }

    private fun label(text: String) = TextView(context).apply {
        this.text = text
        textSize = 15f
        setTextColor(palette.text)
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    }

    private fun add(v: View, lp: LinearLayout.LayoutParams = params()) = content.addView(v, lp)

    private fun gap(heightDp: Int) = content.addView(View(context), LinearLayout.LayoutParams(1, dp(heightDp)))

    private fun params() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun rounded(fill: Int, stroke: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    internal companion object {
        /** The app's language: the first locale of the context's configuration. */
        fun appLanguage(context: Context): String? {
            val config = context.resources.configuration
            val locale = if (Build.VERSION.SDK_INT >= 24) config.locales.takeIf { !it.isEmpty }?.get(0) else @Suppress("DEPRECATION") config.locale
            return locale?.language
        }

        fun botName(url: String): String {
            val name = try {
                java.net.URI(url).path.orEmpty().split('/').firstOrNull { it.isNotEmpty() }
            } catch (e: Exception) {
                null
            }
            return if (name == null) "Telegram" else "@$name"
        }
    }
}

/** Colours from the app's theme, with fallbacks that work in light and dark mode. */
internal class Palette(
    val accent: Int,
    val onAccent: Int,
    val text: Int,
    val muted: Int,
    val background: Int,
    val outline: Int,
    val error: Int,
    val errorBackground: Int,
    /** A dark background. */
    val dark: Boolean,
) {
    companion object {
        fun of(context: Context): Palette {
            val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val background = color(context, android.R.attr.colorBackground) ?: if (night) 0xFF121212.toInt() else Color.WHITE
            val dark = luminance(background) < 0.2
            val accent = color(context, android.R.attr.colorAccent)
                ?: color(context, android.R.attr.colorPrimary)
                ?: if (dark) 0xFF8AB4F8.toInt() else 0xFF1A73E8.toInt()
            val text = color(context, android.R.attr.textColorPrimary)?.takeIf { contrast(it, background) >= 3.0 }
                ?: if (dark) 0xFFE8EAED.toInt() else 0xFF202124.toInt()
            val muted = color(context, android.R.attr.textColorSecondary)?.takeIf { contrast(it, background) >= 3.0 }
                ?: if (dark) 0xFFBDC1C6.toInt() else 0xFF5F6368.toInt()
            return Palette(
                accent = accent,
                onAccent = if (luminance(accent) > 0.4) 0xFF202124.toInt() else Color.WHITE,
                text = text,
                muted = muted,
                background = background,
                outline = if (dark) 0x40FFFFFF else 0x33000000,
                error = if (dark) 0xFFF2B8B5.toInt() else 0xFFB3261E.toInt(),
                errorBackground = if (dark) 0x33F2B8B5 else 0x14B3261E,
                dark = dark,
            )
        }

        private fun color(context: Context, attr: Int): Int? {
            val a = context.obtainStyledAttributes(intArrayOf(attr))
            return try {
                if (a.hasValue(0)) a.getColor(0, 0) else null
            } catch (e: Exception) {
                null
            } finally {
                a.recycle()
            }
        }

        private fun luminance(c: Int): Double {
            fun ch(v: Int): Double = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
            return 0.2126 * ch(Color.red(c)) + 0.7152 * ch(Color.green(c)) + 0.0722 * ch(Color.blue(c))
        }

        private fun contrast(a: Int, b: Int): Double {
            val la = luminance(a) + 0.05
            val lb = luminance(b) + 0.05
            return if (la > lb) la / lb else lb / la
        }
    }
}
