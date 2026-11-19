package io.github.valerioisufi.pageboard

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.valerioisufi.pageboard.platform.PageboardAndroid

class MainActivity : ComponentActivity() {
    private var activeDocumentId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Context used by the database, the document resources and the TeX fonts
        PageboardAndroid.init(applicationContext)

        activeDocumentId = documentIdOf(intent)

        setContent {
            val docId = activeDocumentId
            if (docId != null) {
                SampleDocumentWindow(
                    documentId = docId,
                    onClose = { finish() }
                )
            } else {
                App(
                    onOpenDocumentInNewWindow = { id -> openDocumentInNewTask(id) }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        activeDocumentId = documentIdOf(intent)
    }

    private fun openDocumentInNewTask(documentId: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("drawview://document/$documentId"), this, MainActivity::class.java).apply {
            putExtra(EXTRA_DOCUMENT_ID, documentId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
            addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        }
        startActivity(intent)
    }

    private fun documentIdOf(intent: Intent?): String? {
        if (intent == null) return null
        intent.getStringExtra(EXTRA_DOCUMENT_ID)?.let { return it }
        val data = intent.data ?: return null
        if (data.scheme == "drawview" && data.host == "document") {
            return data.pathSegments.firstOrNull() ?: data.lastPathSegment
        }
        return null
    }

    companion object {
        private const val EXTRA_DOCUMENT_ID = "io.github.valerioisufi.pageboard.DOCUMENT_ID"
    }
}

