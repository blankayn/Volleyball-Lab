package com.volleyballlab.player.ui.component

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.volleyballlab.player.domain.model.Skill
import java.io.IOException
import java.util.Locale
import org.json.JSONObject

/**
 * The clips the scene knows about, as a type rather than a string.
 *
 * A typo in `playAnimation("recieve")` is a silent no-op that logs to a console
 * nobody is watching; a typo in [AthleteAnimation.Receive] does not compile.
 *
 * ## Not all of these are in the shipped model
 *
 * `athlete.glb` currently carries **only [Wave]**, which is what the splash uses.
 * The skill clips exist in two other places - the procedural fallback figure
 * defines them, and the Blender script that authored them is kept with the
 * session notes - so they are a re-export away rather than gone.
 *
 * Asking for one that is absent is not a crash: [AthleteSceneController.play]
 * forwards it, the scene logs which clips it does have, and nothing moves. Check
 * the list handed to `onReady` before wiring a screen to one of these.
 */
enum class AthleteAnimation(val jsName: String) {
    Idle("idle"),
    /** Loops, unlike the skill clips. The one clip the shipped model contains. */
    Wave("wave"),
    Serve("serve"),
    Receive("receive"),
    Set("set"),
    Spike("spike");

    companion object {
        /**
         * The animation that demonstrates [skill].
         *
         * Blocking has no clip of its own yet and maps to [Idle]; the scene makes
         * the same substitution, and both places say so rather than failing
         * quietly.
         */
        fun forSkill(skill: Skill): AthleteAnimation = when (skill) {
            Skill.Serving -> Serve
            Skill.Receiving -> Receive
            Skill.Setting -> Set
            Skill.Spiking -> Spike
            Skill.Blocking -> Idle
        }
    }
}

/** Framings the scene knows by name. */
enum class AthleteCamera(val jsName: String) {
    Full("full"),
    CloseUp("closeup"),
    Action("action"),
    Front("front"),
    Side("side"),
}

/**
 * The kit colours, as the scene names them.
 *
 * Null means "leave whatever the scene already has", so changing one colour does
 * not require restating the other eight.
 */
data class AthleteColors(
    val shirt: Color? = null,
    val stripe: Color? = null,
    val shorts: Color? = null,
    val skin: Color? = null,
    val hair: Color? = null,
    val shoeBody: Color? = null,
    val shoeAccent: Color? = null,
) {
    internal fun toJson(): JSONObject = JSONObject().apply {
        shirt?.let { put("shirt", it.toCssHex()) }
        stripe?.let { put("stripe", it.toCssHex()) }
        shorts?.let { put("shorts", it.toCssHex()) }
        skin?.let { put("skin", it.toCssHex()) }
        hair?.let { put("hair", it.toCssHex()) }
        shoeBody?.let { put("shoeBody", it.toCssHex()) }
        shoeAccent?.let { put("shoeAccent", it.toCssHex()) }
    }
}

/**
 * Drives the 3D athlete from Kotlin.
 *
 * ## Why calls are queued
 *
 * The WebView takes a moment to boot the page, compile the module and build the
 * figure. A `playAnimation` issued in that window would land on a `window` that
 * has no such function yet and vanish. Commands sent before the scene reports
 * ready are held and replayed in order, so callers never have to think about
 * whether the page happens to be up.
 */
@androidx.compose.runtime.Stable
class AthleteSceneController internal constructor() {

    private var webView: WebView? = null
    private var ready = false
    private val pending = ArrayDeque<String>()

    internal fun attach(view: WebView) {
        webView = view
    }

    internal fun detach() {
        webView = null
        ready = false
        pending.clear()
    }

    internal fun onSceneReady() {
        ready = true
        while (pending.isNotEmpty()) evaluate(pending.removeFirst())
    }

    fun play(animation: AthleteAnimation, speed: Float = 1f) {
        val options = JSONObject().put("speed", speed.toDouble())
        call("window.playAnimation(${animation.jsName.quoted()}, $options)")
    }

    fun play(skill: Skill, speed: Float = 1f) = play(AthleteAnimation.forSkill(skill), speed)

    /** Stops everything, re-centres the camera and returns to idle. */
    fun reset() = call("window.resetAnimation()")

    fun setColors(colors: AthleteColors) = call("window.setPlayerColor(${colors.toJson()})")

    fun setCamera(camera: AthleteCamera) = call("window.setCamera(${camera.jsName.quoted()})")

    /**
     * Free-camera variant.
     *
     * @param theta yaw in degrees, 0 being straight on.
     * @param phi pitch in degrees, 90 being level with the target.
     * @param distance metres from the target.
     * @param targetHeight height of the point the camera looks at, in metres.
     */
    fun setCamera(theta: Float, phi: Float, distance: Float, targetHeight: Float = 1.05f) {
        val payload = JSONObject()
            .put("theta", theta.toDouble())
            .put("phi", phi.toDouble())
            .put("distance", distance.toDouble())
            .put("target", targetHeight.toDouble())
        call("window.setCamera($payload)")
    }

    /** Pass null for a transparent background, which is the default. */
    fun setBackground(color: Color?) {
        val value = color?.toCssHex() ?: "transparent"
        call("window.setBackground(${value.quoted()})")
    }

    /**
     * Starts and stops the render loop. Lifecycle plumbing the composable owns,
     * so it is internal rather than something a screen decides.
     *
     * Not queued: if the scene is not up yet there is no loop to stop, and a
     * queued "stop" would fire the moment it booted.
     */
    internal fun setRendering(enabled: Boolean) {
        if (ready) evaluate("window.setRendering($enabled)")
    }

    private fun call(script: String) {
        if (ready) evaluate(script) else pending.addLast(script)
    }

    private fun evaluate(script: String) {
        val view = webView ?: return
        // evaluateJavascript must be called on the view's own thread, and the
        // ready callback arrives from the WebView's JS thread rather than main.
        view.post { view.evaluateJavascript(script, null) }
    }
}

@Composable
fun rememberAthleteSceneController(): AthleteSceneController =
    remember { AthleteSceneController() }

/**
 * A Three.js volleyball athlete, hosted in a WebView.
 *
 * ## Why the page is served over https rather than file://
 *
 * The scene is an ES module, and a module loaded from a `file://` document is
 * treated as cross-origin by Chromium and refused - the WebView shows a blank
 * page and the reason only appears in the JS console. Rather than opening
 * `allowUniversalAccessFromFileURLs`, which relaxes that rule for every URL the
 * WebView ever loads, assets are served through [AssetOriginClient] on a
 * reserved hostname. The page then has a normal secure origin, modules load,
 * and no file-access flags are touched.
 *
 * @param onReady the animation names that actually loaded, once the scene is up.
 * @param onAnimationFinished fired when a one-shot clip ends, before it returns
 *   to idle. Useful for chaining a caption to a demonstration.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AthleteScene(
    controller: AthleteSceneController,
    modifier: Modifier = Modifier,
    transparent: Boolean = true,
    onReady: (List<String>) -> Unit = {},
    onAnimationFinished: (String) -> Unit = {},
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // The page is ours and needs no file:// reach of its own; the
                // asset origin below is what serves it.
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mediaPlaybackRequiresUserGesture = false

                setBackgroundColor(
                    if (transparent) AndroidColor.TRANSPARENT else AndroidColor.WHITE
                )
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = WebView.OVER_SCROLL_NEVER

                webViewClient = AssetOriginClient(context.applicationContext)
                addJavascriptInterface(
                    AthleteBridge(controller, onReady, onAnimationFinished),
                    BRIDGE_NAME,
                )

                controller.attach(this)
                loadUrl("$ASSET_ORIGIN/$SCENE_PATH")
            }
        },
        onRelease = { view ->
            controller.detach()
            view.removeJavascriptInterface(BRIDGE_NAME)
            view.destroy()
        },
    )

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            // Stops the render loop with the screen. A WebGL loop left running
            // behind a paused activity is a battery drain the user cannot see
            // and would have no way to explain.
            when (event) {
                Lifecycle.Event.ON_PAUSE -> controller.setRendering(false)
                Lifecycle.Event.ON_RESUME -> controller.setRendering(true)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

/**
 * Serves the `assets/athlete` folder on a reserved hostname.
 *
 * `appassets.androidplatform.net` is the name Google reserved for exactly this
 * and never resolves publicly, so a request that somehow escaped the intercept
 * fails closed rather than reaching a real server.
 */
private class AssetOriginClient(private val context: Context) : WebViewClient() {

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val url = request.url
        if (!url.host.equals(ASSET_HOST, ignoreCase = true)) return null

        val path = url.path?.trimStart('/').orEmpty()
        if (path.isEmpty() || path.contains("..")) return notFound()

        return try {
            WebResourceResponse(
                mimeTypeOf(path),
                null,
                context.assets.open(path),
            )
        } catch (missing: IOException) {
            // A 404 rather than a null return: null lets the WebView try to
            // fetch the URL for real, and a silent network attempt is a worse
            // failure than an honest missing-file response.
            notFound()
        }
    }

    private fun notFound() = WebResourceResponse(
        "text/plain",
        "utf-8",
        404,
        "Not Found",
        emptyMap(),
        null,
    )

    /**
     * ES modules are refused unless served with a JavaScript MIME type, so the
     * `.js` entry here is load-bearing rather than tidiness.
     */
    private fun mimeTypeOf(path: String): String =
        when (path.substringAfterLast('.', "").lowercase(Locale.US)) {
            "html" -> "text/html"
            "js", "mjs" -> "text/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "glb" -> "model/gltf-binary"
            "gltf" -> "model/gltf+json"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "bin" -> "application/octet-stream"
            else -> "application/octet-stream"
        }
}

/**
 * What the page can call back into.
 *
 * Every method is annotated and every one hops to the main thread: methods on a
 * JavaScript interface arrive on a WebView-owned thread, and touching Compose
 * state from there is a crash waiting for a slow day.
 */
private class AthleteBridge(
    private val controller: AthleteSceneController,
    private val onReady: (List<String>) -> Unit,
    private val onAnimationFinished: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onReady(payload: String) {
        val animations = runCatching {
            val array = JSONObject(payload).getJSONArray("animations")
            List(array.length()) { array.getString(it) }
        }.getOrDefault(emptyList())

        main.post {
            controller.onSceneReady()
            onReady(animations)
        }
    }

    @JavascriptInterface
    fun onAnimationFinished(name: String) {
        main.post { onAnimationFinished(name) }
    }

    @JavascriptInterface
    fun onError(message: String) {
        main.post { android.util.Log.w("AthleteScene", "Scene failed to boot: $message") }
    }
}

private const val ASSET_HOST = "appassets.androidplatform.net"
private const val ASSET_ORIGIN = "https://$ASSET_HOST"
private const val SCENE_PATH = "athlete/index.html"
private const val BRIDGE_NAME = "AndroidAthlete"

private fun Color.toCssHex(): String =
    String.format(Locale.US, "#%06X", toArgb() and 0xFFFFFF)

private fun String.quoted(): String = JSONObject.quote(this)
