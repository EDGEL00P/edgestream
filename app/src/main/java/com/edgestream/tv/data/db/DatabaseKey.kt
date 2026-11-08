package com.edgestream.tv.data.db

import android.content.Context
import com.edgestream.tv.data.Secrets
import java.io.File
import java.security.SecureRandom

/**
 * The database's encryption key: 256 random bits, used raw (SQLCipher's x'…' form, so no slow
 * key stretching on every launch), stored only sealed by the Android Keystore.
 */
internal object DatabaseKey {
    private const val PREF = "dbKey"

    /**
     * The key for [name]. A fresh one whenever the database is still plain (first launch, or a
     * conversion that never finished), so no key is ever reused from an attempt that failed.
     */
    fun passphrase(context: Context, name: String): String {
        System.loadLibrary("sqlcipher")
        val prefs = context.getSharedPreferences("edgestream", Context.MODE_PRIVATE)
        val db = context.getDatabasePath(name)
        val stillPlain = (db.exists() && isPlain(db)) || (!db.exists() && File(db.path + ".plain").exists())
        if (!stillPlain) prefs.getString(PREF, null)?.let(Secrets::openOrBlank)?.takeIf { it.length == 64 }?.let { return "x'$it'" }
        val hex = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        // commit, not apply: the key must be on disk before anything is encrypted with it.
        check(prefs.edit().putString(PREF, Secrets.seal(hex)).commit()) { "Couldn't save the database key" }
        return "x'$hex'"
    }

    /**
     * Converts a database written before encryption, in place and crash-safe: export to a new
     * encrypted file, swap names, then delete the plain copy. A kill at any point leaves either
     * the plain or the encrypted file intact, and the next launch finishes the job.
     */
    fun encryptExisting(context: Context, name: String, passphrase: String) {
        val db = context.getDatabasePath(name)
        val plainBackup = File(db.path + ".plain")
        val encrypted = File(db.path + ".enc")
        if (!db.exists() && plainBackup.exists()) plainBackup.renameTo(db) // killed mid-swap
        if (db.exists() && isPlain(db)) {
            encrypted.delete()
            val plain = net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(
                db.path, "", null,
                // CREATE too: ATTACH inherits these flags, and it has to create the encrypted file.
                net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READWRITE or net.zetetic.database.sqlcipher.SQLiteDatabase.CREATE_IF_NECESSARY,
                null, null,
            )
            plain.use {
                val version = it.version
                // Bound, never spliced into the SQL: a failing statement is logged, and the key must not be.
                it.execSQL("ATTACH DATABASE ? AS encrypted KEY ?", arrayOf<Any>(encrypted.path, passphrase))
                it.rawExecSQL("SELECT sqlcipher_export('encrypted')")
                it.rawExecSQL("PRAGMA encrypted.user_version = $version")
                it.rawExecSQL("DETACH DATABASE encrypted")
            }
            // Closing the last connection folded the journal into the plain file; its leftovers hold plain pages.
            listOf("-wal", "-shm", "-journal").forEach { File(db.path + it).delete() }
            check(db.renameTo(plainBackup) && encrypted.renameTo(db)) { "Couldn't swap in the encrypted database" }
        }
        if (db.exists() && !isPlain(db)) plainBackup.delete()
    }

    private val HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    private fun isPlain(file: File): Boolean = runCatching {
        file.inputStream().use { input -> ByteArray(HEADER.size).also { input.read(it) }.contentEquals(HEADER) }
    }.getOrDefault(false)

}
