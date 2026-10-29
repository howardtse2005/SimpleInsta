package app.simpleinsta.ig.policy

import android.content.Context
import org.json.JSONObject

/**
 * Assembles the injected script from asset files.
 *
 * Order matters: the runtime installs the fetch/XHR patches first, then the
 * processors register themselves. Each file is individually wrapped in
 * try/catch so one broken policy cannot take the whole bundle down — a partially
 * applied policy is strictly better than a blank page.
 */
class PolicyBundle(private val context: Context) {

    val version: String = VERSION

    private val body: String by lazy { assemble() }

    /**
     * @param config values the JS needs from native (watermark, caps, debug)
     * @param bridgeShim true when the modern WebMessageListener is unavailable
     *        and we fell back to addJavascriptInterface
     */
    fun script(config: JSONObject, bridgeShim: Boolean): String = buildString {
        append("(function(){try{window.__ENOUGH_CONFIG__=")
        append(config.toString())
        append(";}catch(e){}})();\n")
        if (bridgeShim) {
            append("(function(){try{window.simpleinstaBridge={postMessage:function(s){")
            append("window.SimpleInstaNative&&window.SimpleInstaNative.post(String(s));}};}catch(e){}})();\n")
        }
        append(body)
    }

    private fun assemble(): String = FILES.joinToString("\n;\n") { name ->
        val src = try {
            context.assets.open("policy/$name").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "/* missing $name: ${e.message} */"
        }
        "try{\n$src\n}catch(e){try{console.error('[simpleinsta] $name',e)}catch(_){}}"
    }

    private companion object {
        const val VERSION = "2"
        val FILES = listOf(
            "00_runtime.js",
            "10_feed.js",
            "20_dm.js",
            "30_dom.js",
            "90_health.js"
        )
    }
}
