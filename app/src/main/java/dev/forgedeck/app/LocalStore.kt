package dev.forgedeck.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

class LocalStore(context: Context) {
    private val vault = context.getSharedPreferences("vault", Context.MODE_PRIVATE)
    private val prefs = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
    private val cache = context.getSharedPreferences("cache", Context.MODE_PRIVATE)
    private val keyAlias = "forgedeck.token.v1"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (ks.getKey(keyAlias, null) as? SecretKey)
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                .apply {
                    init(
                        KeyGenParameterSpec.Builder(
                                keyAlias,
                                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                            )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .build()
                    )
                }
                .generateKey()
    }

    @Synchronized
    fun token(): String {
        return runCatching {
                val packed = vault.getString("token", null) ?: return ""
                val parts = packed.split(':')
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(
                    Cipher.DECRYPT_MODE,
                    key(),
                    GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])),
                )
                String(c.doFinal(Base64.getDecoder().decode(parts[1])), Charsets.UTF_8)
            }
            .getOrElse {
                expire()
                ""
            }
    }

    @Synchronized
    fun saveToken(token: String, login: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val encrypted =
            Base64.getEncoder().encodeToString(c.iv) +
                ":" +
                Base64.getEncoder().encodeToString(c.doFinal(token.toByteArray()))
        check(vault.edit().putString("token", encrypted).putString("login", login).commit()) {
            "認証情報を保存できませんでした"
        }
    }

    val login
        get() = vault.getString("login", "") ?: ""

    fun cachePut(key: String, body: String) {
        synchronized(cache) {
            if (body.length > 600_000) {
                // A new validator must never be attached to an older response body.
                cache.edit().remove("$login|$key").commit()
                return
            }
            if (cache.all.values.sumOf { (it as? String)?.length ?: 0 } > 2_000_000)
                cache.edit().clear().commit()
            cache
                .edit()
                .putString(
                    "$login|$key",
                    JSONObject()
                        .put("time", System.currentTimeMillis())
                        .put("body", body)
                        .toString(),
                )
                .commit()
        }
    }

    fun cacheGet(key: String): Pair<String, Long>? =
        cache.getString("$login|$key", null)?.let {
            runCatching { JSONObject(it).let { o -> o.getString("body") to o.getLong("time") } }
                .getOrNull()
        }

    fun validatorGet(key: String): Pair<String, String>? =
        cache.getString("$login|$key", null)?.let {
            runCatching {
                    val o = JSONObject(it)
                    o.str("etag") to o.str("modified")
                }
                .getOrNull()
        }

    fun validatorPut(key: String, etag: String, modified: String) {
        synchronized(cache) {
            val raw = cache.getString("$login|$key", null) ?: return
            val o = JSONObject(raw).put("etag", etag).put("modified", modified)
            cache.edit().putString("$login|$key", o.toString()).commit()
        }
    }

    private fun set(name: String) =
        prefs.getStringSet("$login|$name", emptySet())?.toSet() ?: emptySet()

    fun pins() = set("pins")

    fun waiting() = set("waiting")

    fun toggle(name: String, value: String) {
        val values = set(name).toMutableSet()
        if (!values.add(value)) values.remove(value)
        prefs.edit().putStringSet("$login|$name", values).commit()
    }

    fun recent(): List<String> =
        prefs.getString("$login|recent", "")!!.split('|').filter { it.isNotBlank() }

    fun visit(repo: String) {
        prefs
            .edit()
            .putString(
                "$login|recent",
                (listOf(repo) + recent().filter { it != repo }).take(10).joinToString("|"),
            )
            .commit()
    }

    fun draft(editor: Editor) {
        prefs
            .edit()
            .putString(
                "$login|draft:${editor.key}",
                JSONObject()
                    .put("sha", editor.originalSha)
                    .put("original", editor.original)
                    .put("text", editor.text)
                    .put("path", editor.newPath)
                    .put("title", editor.title)
                    .put("target", editor.target)
                    .toString(),
            )
            .commit()
    }

    fun restore(editor: Editor): Editor =
        prefs.getString("$login|draft:${editor.key}", null)?.let {
            runCatching {
                    val o = JSONObject(it)
                    editor.copy(
                        originalSha = o.str("sha"),
                        original = if (o.has("original")) o.str("original") else editor.original,
                        text = o.str("text"),
                        newPath = o.str("path"),
                        title = o.str("title"),
                        target = o.str("target"),
                    )
                }
                .getOrNull()
        } ?: editor

    fun discard(editor: Editor) {
        prefs.edit().remove("$login|draft:${editor.key}").commit()
    }

    fun pending(value: PendingPr?) {
        if (value == null) prefs.edit().remove("$login|pending").commit()
        else
            prefs
                .edit()
                .putString(
                    "$login|pending",
                    JSONObject()
                        .put("repo", value.repo)
                        .put("base", value.base)
                        .put("head", value.head)
                        .put("title", value.title)
                        .put("commitSha", value.commitSha)
                        .put("body", value.body)
                        .toString(),
                )
                .commit()
    }

    fun pending(): PendingPr? =
        prefs.getString("$login|pending", null)?.let {
            runCatching {
                    val o = JSONObject(it)
                    PendingPr(
                        o.getString("repo"),
                        o.getString("base"),
                        o.getString("head"),
                        o.getString("title"),
                        o.str("commitSha"),
                        o.str("body"),
                    )
                }
                .getOrNull()
        }

    var theme: String
        get() = prefs.getString("theme", "system") ?: "system"
        set(value) {
            prefs.edit().putString("theme", value).commit()
        }

    fun expire() {
        vault.edit().remove("token").commit()
        cache.edit().clear().commit()
    }

    fun logout() {
        val profile = login
        vault.edit().clear().commit()
        cache.edit().clear().commit()
        prefs
            .edit()
            .apply { prefs.all.keys.filter { it.startsWith("$profile|") }.forEach { remove(it) } }
            .commit()
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            deleteEntry(keyAlias)
        }
    }
}
