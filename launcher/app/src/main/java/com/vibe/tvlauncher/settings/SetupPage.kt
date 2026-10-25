package com.vibe.tvlauncher.settings

/** The phone/computer setup page served by [SetupServer]. Plain HTML, no scripts needed. */
object SetupPage {
    private const val CLEAR_PREFIX = "clear__"

    /** Turns a submitted form into changes: drops fields that weren't edited and maps "remove" boxes. */
    fun changes(form: Map<String, String>, current: List<Field>): Map<String, String> {
        val byKey = current.associateBy { it.key }
        val out = LinkedHashMap<String, String>()
        form.forEach { (k, v) ->
            if (k.startsWith(CLEAR_PREFIX)) {
                out[k.removePrefix(CLEAR_PREFIX)] = SettingsRegistry.CLEAR
                return@forEach
            }
            val f = byKey[k] ?: return@forEach
            if (f.type == FieldType.Secret) {
                if (v.isNotBlank()) out[k] = v
            } else if (v.trim() != f.value) {
                out[k] = v
            }
        }
        return out
    }

    fun render(fields: List<Field>, code: String, results: Map<String, String?>?): String = buildString {
        append(HEAD)
        append("<h1>Vibe TV settings</h1><p class=sub>Changes apply on the TV as soon as you save.</p>")
        if (results != null) {
            val errors = results.filterValues { it != null }
            append(
                if (results.isEmpty()) "<div class=note>Nothing changed.</div>"
                else if (errors.isEmpty()) "<div class='note ok'>Saved.</div>"
                else "<div class='note bad'>Some settings weren't saved, see below.</div>",
            )
        }
        append("<form method=post action='/'><input type=hidden name=c value='").append(code).append("'>")
        fields.groupBy { it.section }.forEach { (section, list) ->
            append("<section><h2>").append(esc(section)).append("</h2>")
            list.forEach { f -> field(f, results?.get(f.key), results?.containsKey(f.key) == true) }
            append("</section>")
        }
        append("<button type=submit>Save</button></form>")
        if (fields.none { it.key.startsWith(SettingsRegistry.NUVIO_PREFIX) }) {
            append("<p class=sub>Nuvio settings appear here when the Vibe Nuvio app is installed.</p>")
        }
        append("</main></body></html>")
    }

    private fun StringBuilder.field(f: Field, error: String?, touched: Boolean) {
        val id = esc(f.key)
        append("<label class=field><span class=label>").append(esc(f.label)).append("</span>")
        when (f.type) {
            FieldType.Choice, FieldType.Toggle -> {
                val choices = if (f.type == FieldType.Toggle) listOf("true" to "On", "false" to "Off") else f.choices
                append("<select name='").append(id).append("'>")
                choices.forEach { (v, l) ->
                    append("<option value='").append(esc(v)).append("'")
                    if (v == f.value) append(" selected")
                    append(">").append(esc(l)).append("</option>")
                }
                append("</select>")
            }
            FieldType.Secret -> {
                val set = f.value.isNotEmpty()
                append("<input type=password autocomplete=off name='").append(id).append("' placeholder='")
                append(if (set) "Saved. Paste a new one to replace it" else "Not set").append("'>")
                if (set) {
                    append("<span class=clear><input type=checkbox name='").append(CLEAR_PREFIX).append(id)
                    append("' value=1> Remove saved key</span>")
                }
            }
            FieldType.Text -> {
                append("<input type=text autocomplete=off spellcheck=false name='").append(id)
                append("' value='").append(esc(f.value)).append("'>")
            }
        }
        if (f.help.isNotEmpty()) append("<span class=help>").append(esc(f.help)).append("</span>")
        if (error != null) append("<span class=err>").append(esc(error)).append("</span>")
        else if (touched) append("<span class=okmsg>Saved</span>")
        append("</label>")
    }

    fun denied(): String = HEAD +
        "<h1>Open this page from the TV</h1><p class=sub>On the TV, go to Settings and scan the QR code. " +
        "The link only works while the settings screen is open there.</p></main></body></html>"

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("'", "&#39;").replace("\"", "&quot;")

    private val HEAD = """
        <!doctype html><html><head><meta charset=utf-8>
        <meta name=viewport content="width=device-width,initial-scale=1">
        <title>Vibe TV settings</title>
        <style>
        :root{color-scheme:dark}
        body{margin:0;background:#101114;color:#e8eaed;font:16px/1.45 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
        main{max-width:640px;margin:0 auto;padding:24px 16px 64px}
        h1{font-size:26px;font-weight:500;margin:8px 0 4px}
        h2{font-size:15px;font-weight:500;color:#8ab4f8;margin:0 0 12px;text-transform:uppercase;letter-spacing:.06em}
        .sub{color:#9aa0a6;margin:0 0 20px}
        section{background:#1e2025;border-radius:14px;padding:18px 16px 6px;margin:0 0 16px}
        .field{display:block;margin:0 0 16px}
        .label{display:block;margin:0 0 6px}
        input[type=text],input[type=password],select{box-sizing:border-box;width:100%;padding:11px 12px;border-radius:10px;
          border:1px solid #3c4043;background:#101114;color:#e8eaed;font:inherit}
        input:focus,select:focus{outline:2px solid #8ab4f8;border-color:transparent}
        .help{display:block;color:#9aa0a6;font-size:13px;margin-top:5px}
        .clear{display:block;color:#9aa0a6;font-size:13px;margin-top:6px}
        .err{display:block;color:#f28b82;font-size:13px;margin-top:5px}
        .okmsg{display:block;color:#81c995;font-size:13px;margin-top:5px}
        .note{border-radius:10px;padding:10px 12px;margin:0 0 16px;background:#1e2025}
        .note.ok{background:#1e3a2a;color:#81c995}.note.bad{background:#3c1f1f;color:#f28b82}
        button{width:100%;padding:13px;border:0;border-radius:24px;background:#e8eaed;color:#202124;font:inherit;font-weight:500}
        </style></head><body><main>
    """.trimIndent()
}
