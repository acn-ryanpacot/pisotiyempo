package com.pisotiyempo.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.pisotiyempo.R
import com.pisotiyempo.sync.LinkCode
import com.pisotiyempo.ui.common.MutedText
import com.pisotiyempo.ui.common.SectionCard
import com.pisotiyempo.ui.common.appContainer
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private enum class SyncDialog { ENTER, SHOW, CONFIRM_DELETE }

/** Link this phone with the desktop app: scan its QR code, or exchange the link code as text. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SyncCard() {
    val c = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by c.devices.status.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<SyncDialog?>(null) }
    var shownCode by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val badCode = stringResource(R.string.sync_bad_code)
    val linkedMsg = stringResource(R.string.sync_linked_message)
    val scanPrompt = stringResource(R.string.sync_scan_prompt)

    fun join(text: String) {
        val code = LinkCode.parse(text)
        if (code == null) {
            message = badCode
            return
        }
        scope.launch {
            c.devices.join(code)
            message = linkedMsg
        }
    }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result -> result.contents?.let { join(it) } }

    SectionCard(stringResource(R.string.sync_title)) {
        MutedText(stringResource(R.string.sync_description))
        if (!status.linked) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scanner.launch(
                        ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt(scanPrompt).setBeepEnabled(false).setOrientationLocked(false),
                    )
                }) { Text(stringResource(R.string.sync_scan)) }
                OutlinedButton(onClick = { dialog = SyncDialog.ENTER }) { Text(stringResource(R.string.sync_enter_code)) }
            }
            TextButton(onClick = {
                scope.launch {
                    shownCode = c.devices.createGroup().encode()
                    dialog = SyncDialog.SHOW
                }
            }) { Text(stringResource(R.string.sync_create_here)) }
        } else {
            val s = status.state
            MutedText(
                if (s.lastSyncAt == 0L) stringResource(R.string.sync_never)
                else stringResource(R.string.sync_last, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(s.lastSyncAt))),
            )
            s.lastError?.let { MutedText(stringResource(R.string.sync_error, it)) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !status.running, onClick = { scope.launch { c.devices.syncNow() } }) {
                    Text(stringResource(if (status.running) R.string.sync_running else R.string.sync_now))
                }
                OutlinedButton(onClick = {
                    scope.launch {
                        shownCode = c.devices.link()?.encode()
                        dialog = SyncDialog.SHOW
                    }
                }) { Text(stringResource(R.string.sync_show_code)) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { scope.launch { c.devices.unlink() } }) { Text(stringResource(R.string.sync_unlink)) }
                TextButton(onClick = { dialog = SyncDialog.CONFIRM_DELETE }) { Text(stringResource(R.string.sync_delete_server)) }
            }
        }
        message?.let { MutedText(it) }
    }

    when (dialog) {
        SyncDialog.ENTER -> {
            var text by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(stringResource(R.string.sync_enter_code)) },
                text = {
                    OutlinedTextField(text, { text = it }, label = { Text(stringResource(R.string.sync_code_label)) }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                },
                confirmButton = { TextButton(onClick = { dialog = null; join(text) }) { Text(stringResource(R.string.sync_link_button)) } },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.common_cancel)) } },
            )
        }
        SyncDialog.SHOW -> {
            val code = shownCode.orEmpty()
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(stringResource(R.string.sync_code_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MutedText(stringResource(R.string.sync_code_help))
                        Text(code, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        MutedText(stringResource(R.string.sync_code_warning))
                    }
                },
                confirmButton = { TextButton(onClick = { copy(context, code) }) { Text(stringResource(R.string.sync_copy)) } },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.sync_done)) } },
            )
        }
        SyncDialog.CONFIRM_DELETE -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.sync_delete_server)) },
            text = { Text(stringResource(R.string.sync_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    scope.launch { runCatching { c.devices.deleteFromServer() } }
                }) { Text(stringResource(R.string.sync_delete_button)) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
        null -> Unit
    }
}

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("Piso Tiyempo link code", text))
}
