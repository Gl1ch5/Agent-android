package dev.agent.app

import android.net.Uri
import android.provider.DocumentsContract

/**
 * Turns a folder picked with the system document picker into a real filesystem path.
 * Works for folders on shared storage ("primary" volume and SD cards). Returns null for
 * providers that do not expose a plain path, such as cloud drives.
 */
fun treeUriToPath(uri: Uri): String? {
    if (uri.authority != "com.android.externalstorage.documents") return null
    val docId = DocumentsContract.getTreeDocumentId(uri)
    val volume = docId.substringBefore(':')
    val relative = docId.substringAfter(':', "")
    val base = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
    return if (relative.isEmpty()) base else "$base/$relative"
}
