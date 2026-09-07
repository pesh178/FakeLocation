package com.xposed.hook.storage

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.util.Xml
import com.xposed.hook.config.Constants
import kotlin.concurrent.thread
import org.xmlpull.v1.XmlSerializer

/** Read-only, per-calling-package bridge for Xposed target preferences. */
class SharedFileProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String {
        requireAuthorized(uri)
        return "application/xml"
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        requireAuthorized(uri)
        if (mode != "r") throw SecurityException("Preferences are read-only")
        val packageName = callingPackage ?: throw SecurityException("Caller package is missing")
        val pipe = ParcelFileDescriptor.createPipe()
        thread(name = "FakeLocationPrefs") {
            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                writePreferences(output, packageName)
            }
        }
        return pipe[0]
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? = throw UnsupportedOperationException("Metadata queries are not supported")

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        throw SecurityException("Preferences are read-only")
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int = throw SecurityException("Preferences are read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        throw SecurityException("Preferences are read-only")
    }

    private fun requireAuthorized(uri: Uri) {
        if (uri.path != "/shared_prefs/${Constants.PREF_FILE_NAME}.xml" || !isAuthorizedCaller()) {
            throw SecurityException("Caller is not authorized to read preferences")
        }
    }
    private fun isAuthorizedCaller(): Boolean {
        val providerContext = context ?: return false
        val packageName = callingPackage ?: return false
        val callingUid = Binder.getCallingUid()
        val uidPackages = providerContext.packageManager.getPackagesForUid(callingUid)
            ?: return false
        if (packageName !in uidPackages) return false
        return providerContext.getSharedPreferences(
            Constants.PREF_FILE_NAME,
            android.content.Context.MODE_PRIVATE
        ).getBoolean(packageName, false)
    }
    private fun writePreferences(output: java.io.OutputStream, packageName: String) {
        val values = context?.getSharedPreferences(
            Constants.PREF_FILE_NAME,
            android.content.Context.MODE_PRIVATE
        )?.all ?: emptyMap()
        val prefix = "${packageName}_"
        val serializer: XmlSerializer = Xml.newSerializer()
        serializer.setOutput(output, "utf-8")
        serializer.startDocument("utf-8", true)
        serializer.startTag(null, "map")
        for ((key, value) in values) {
            if (key != packageName && !key.startsWith(prefix)) continue
            writeValue(serializer, key, value)
        }
        serializer.endTag(null, "map")
        serializer.endDocument()
    }

    private fun writeValue(serializer: XmlSerializer, key: String, value: Any?) {
        when (value) {
            is String -> {
                serializer.startTag(null, "string")
                serializer.attribute(null, "name", key)
                serializer.text(value)
                serializer.endTag(null, "string")
            }
            is Boolean -> {
                serializer.startTag(null, "boolean")
                serializer.attribute(null, "name", key)
                serializer.attribute(null, "value", value.toString())
                serializer.endTag(null, "boolean")
            }
            is Int -> {
                serializer.startTag(null, "int")
                serializer.attribute(null, "name", key)
                serializer.attribute(null, "value", value.toString())
                serializer.endTag(null, "int")
            }
            is Long -> {
                serializer.startTag(null, "long")
                serializer.attribute(null, "name", key)
                serializer.attribute(null, "value", value.toString())
                serializer.endTag(null, "long")
            }
            is Float -> {
                serializer.startTag(null, "float")
                serializer.attribute(null, "name", key)
                serializer.attribute(null, "value", value.toString())
                serializer.endTag(null, "float")
            }
            is Set<*> -> {
                serializer.startTag(null, "set")
                serializer.attribute(null, "name", key)
                for (item in value) {
                    if (item is String) {
                        serializer.startTag(null, "string")
                        serializer.text(item)
                        serializer.endTag(null, "string")
                    }
                }
                serializer.endTag(null, "set")
            }
        }
    }
}