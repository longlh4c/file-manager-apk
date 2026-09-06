package com.antigravity.filemanager.presentation.components

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale
import androidx.compose.ui.text.style.TextOverflow
import com.antigravity.filemanager.domain.model.CloudAccount
import com.antigravity.filemanager.domain.model.CloudProvider
import com.antigravity.filemanager.domain.model.CloudTransferProgress
import com.antigravity.filemanager.domain.model.FileItem
import com.antigravity.filemanager.presentation.cloud.CloudProviderIcon
import com.antigravity.filemanager.presentation.cloud.DropboxLogoIcon
import com.antigravity.filemanager.presentation.cloud.GoogleDriveLogoIcon
import com.antigravity.filemanager.presentation.theme.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TextInputDialog(
    title: String,
    initialValue: String = "",
    confirmButtonText: String = "OK",
    // true for renaming a file (not a folder, not a brand-new name with nothing to preserve) —
    // the field opens with just the name part selected/focused, extension excluded, matching how
    // Windows/most file managers handle Rename so overwriting the whole "name.ext" by accident
    // (or having to manually re-select just the name first) isn't the default.
    selectNameWithoutExtension: Boolean = false,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var textFieldValue by remember {
        val dotIndex = initialValue.lastIndexOf('.')
        val selectionEnd = if (selectNameWithoutExtension && dotIndex > 0) dotIndex else initialValue.length
        mutableStateOf(
            androidx.compose.ui.text.input.TextFieldValue(
                text = initialValue,
                selection = androidx.compose.ui.text.TextRange(0, selectionEnd)
            )
        )
    }
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp
            )
        },
        text = {
            OutlinedTextField(
                value = textFieldValue,
                onValueChange = { textFieldValue = it },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = TealPrimary,
                    unfocusedBorderColor = TextSecondary,
                    cursorColor = TealPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(textFieldValue.text.trim()) },
                colors = ButtonDefaults.buttonColors(containerColor = TealPrimary, contentColor = Color.Black)
            ) {
                Text(text = confirmButtonText, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "CANCEL", color = TextSecondary)
            }
        },
        containerColor = DarkCard
    )
}

private enum class ConflictResolution { OVERWRITE, SKIP, KEEP_BOTH }

@Composable
fun OverwriteConflictDialog(
    conflicts: List<com.antigravity.filemanager.domain.model.OverwriteConflict>,
    onConfirm: (overwriteNames: Set<String>, skipNames: Set<String>) -> Unit,
    onCancel: () -> Unit
) {
    // A single conflicting item has nothing to pick a resolution FOR before confirming — every
    // other item's choice defaults to "keep both" and applies immediately if there's a whole list
    // to weigh, but with only one file, "select Overwrite, then tap Confirm" was two taps for a
    // decision that's really just one. Below, the single-item branch fires onConfirm directly from
    // each action button instead of going through the select-then-confirm flow.
    if (conflicts.size == 1) {
        val conflict = conflicts.first()
        AlertDialog(
            onDismissRequest = onCancel,
            title = {
                Text(
                    text = "File already exists",
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column {
                    Text(text = conflict.name, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(
                        text = if (conflict.isDirectory) {
                            "A folder with this name already exists"
                        } else {
                            "Existing: ${FileItem.formatBytes(conflict.existingSize)}  →  New: ${FileItem.formatBytes(conflict.newSize)}"
                        },
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Overwrite replaces the existing item. Skip leaves this file out entirely. Keep both saves the new copy under a numbered name.",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { onConfirm(emptySet(), setOf(conflict.name)) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Text(text = "SKIP", color = TextSecondary, fontSize = 13.sp, maxLines = 1)
                    }
                    TextButton(
                        onClick = { onConfirm(emptySet(), emptySet()) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Text(text = "KEEP BOTH", color = TealPrimary, fontSize = 13.sp, maxLines = 1, softWrap = false)
                    }
                    Button(
                        onClick = { onConfirm(setOf(conflict.name), emptySet()) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350), contentColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(text = "OVERWRITE", fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, softWrap = false)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onCancel) {
                    Text(text = "CANCEL", color = TextSecondary)
                }
            },
            containerColor = DarkCard
        )
        return
    }

    // Per-file choice, defaulting to "keep both" — matches Overwrite/Skip/Keep Both from the
    // Windows "Destination File Already Exists" dialog. Keyed by name since conflicts are unique by name.
    val resolutionState = remember(conflicts) {
        mutableStateMapOf<String, ConflictResolution>().apply {
            conflicts.forEach { put(it.name, ConflictResolution.KEEP_BOTH) }
        }
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                text = "File already exists",
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp
            )
        },
        text = {
            Column {
                Text(
                    text = "The following item(s) already exist at the destination:",
                    color = TextSecondary,
                    fontSize = 15.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    TextButton(
                        onClick = { conflicts.forEach { resolutionState[it.name] = ConflictResolution.OVERWRITE } },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                    ) {
                        Text(text = "OVERWRITE ALL", color = Color(0xFFEF5350), fontSize = 12.sp, maxLines = 1, softWrap = false)
                    }
                    TextButton(
                        onClick = { conflicts.forEach { resolutionState[it.name] = ConflictResolution.SKIP } },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                    ) {
                        Text(text = "SKIP ALL", color = TextSecondary, fontSize = 12.sp, maxLines = 1, softWrap = false)
                    }
                    TextButton(
                        onClick = { conflicts.forEach { resolutionState[it.name] = ConflictResolution.KEEP_BOTH } },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                    ) {
                        Text(text = "KEEP ALL", color = TealPrimary, fontSize = 12.sp, maxLines = 1, softWrap = false)
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Column(
                    modifier = Modifier
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    conflicts.forEach { conflict ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                        ) {
                            Text(text = conflict.name, color = TextPrimary, fontSize = 14.sp)
                            Text(
                                text = if (conflict.isDirectory) {
                                    "A folder with this name already exists"
                                } else {
                                    "Existing: ${FileItem.formatBytes(conflict.existingSize)}  →  New: ${FileItem.formatBytes(conflict.newSize)}"
                                },
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            val current = resolutionState[conflict.name] ?: ConflictResolution.KEEP_BOTH
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                ConflictChoiceChip(
                                    label = "Overwrite",
                                    selected = current == ConflictResolution.OVERWRITE,
                                    selectedColor = Color(0xFFEF5350),
                                    onClick = { resolutionState[conflict.name] = ConflictResolution.OVERWRITE }
                                )
                                ConflictChoiceChip(
                                    label = "Skip",
                                    selected = current == ConflictResolution.SKIP,
                                    selectedColor = TextSecondary,
                                    onClick = { resolutionState[conflict.name] = ConflictResolution.SKIP }
                                )
                                ConflictChoiceChip(
                                    label = "Keep both",
                                    selected = current == ConflictResolution.KEEP_BOTH,
                                    selectedColor = TealPrimary,
                                    onClick = { resolutionState[conflict.name] = ConflictResolution.KEEP_BOTH }
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Overwrite replaces the existing item. Skip leaves this file out entirely. Keep both saves the new copy under a numbered name.",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val overwriteNames = resolutionState.filterValues { it == ConflictResolution.OVERWRITE }.keys.toSet()
                    val skipNames = resolutionState.filterValues { it == ConflictResolution.SKIP }.keys.toSet()
                    onConfirm(overwriteNames, skipNames)
                },
                colors = ButtonDefaults.buttonColors(containerColor = TealPrimary, contentColor = Color.White)
            ) {
                Text(text = "CONFIRM", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(text = "CANCEL", color = TextSecondary)
            }
        },
        containerColor = DarkCard
    )
}

@Composable
private fun ConflictChoiceChip(
    label: String,
    selected: Boolean,
    selectedColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) selectedColor.copy(alpha = 0.18f) else Color.Transparent)
            .border(1.dp, if (selected) selectedColor else TextSecondary.copy(alpha = 0.4f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            color = if (selected) selectedColor else TextSecondary,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
fun DeleteConfirmDialog(
    itemCount: Int,
    onConfirm: (moveToTrash: Boolean) -> Unit,
    onDismiss: () -> Unit,
    // When false, this is a "Delete Permanently" confirmation (e.g. already inside a trash/
    // rubbish-bin view) — there's no "move to trash" choice left to make, so the checkbox is
    // hidden and onConfirm always fires with false.
    showMoveToTrashOption: Boolean = true,
    title: String = "Delete",
    message: String = "Are you sure you want to delete $itemCount item(s)?"
) {
    var moveToTrash by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp
            )
        },
        text = {
            Column {
                Text(
                    text = message,
                    color = TextSecondary,
                    fontSize = 15.sp
                )
                if (showMoveToTrashOption) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { moveToTrash = !moveToTrash }
                    ) {
                        Checkbox(
                            checked = moveToTrash,
                            onCheckedChange = { moveToTrash = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = TealPrimary,
                                uncheckedColor = TextSecondary
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Move to Recycle Bin",
                            color = TextPrimary,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(if (showMoveToTrashOption) moveToTrash else false) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350), contentColor = Color.White)
            ) {
                Text(text = "DELETE", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "CANCEL", color = TextSecondary)
            }
        },
        containerColor = DarkCard
    )
}

@Composable
fun OverwriteFileConfirmDialog(
    fileName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Replace existing file?",
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp
            )
        },
        text = {
            Text(
                text = "\"$fileName\" already exists here. Replacing it will overwrite its current contents.",
                color = TextSecondary,
                fontSize = 15.sp
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350), contentColor = Color.White)
            ) {
                Text(text = "REPLACE", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "CANCEL", color = TextSecondary)
            }
        },
        containerColor = DarkCard
    )
}

@Composable
fun PropertiesDialog(
    file: FileItem,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Properties",
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                PropertyRow(label = "Name", value = file.name)
                PropertyRow(label = "Type", value = if (file.isDirectory) "Folder" else file.mimeType.ifEmpty { file.extension.uppercase() })
                PropertyRow(label = "Path", value = file.path)
                PropertyRow(label = "Size", value = if (file.isDirectory) "${file.itemCount} items" else file.formattedSize)
                if (file.formattedDate.isNotEmpty()) {
                    PropertyRow(label = "Modified", value = file.formattedDate)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = TealPrimary, contentColor = Color.Black)
            ) {
                Text(text = "OK", fontWeight = FontWeight.Bold)
            }
        },
        containerColor = DarkCard
    )
}

@Composable
private fun PropertyRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(text = label, color = TextSecondary, fontSize = 12.sp)
        Text(text = value, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Normal)
    }
}

/**
 * Properties for the Cloud tab's selection bar — one or many items (files and/or folders).
 * "Size" is always the real total byte count: files contribute their own size directly, folders
 * contribute their recursively-summed contents (computed in the background — see
 * CloudExplorerViewModel.showPropertiesForSelection). Single-item Cloud "Properties" now goes
 * through here too, so a folder shows its actual size instead of just an item count.
 */
@Composable
fun SelectionPropertiesDialog(
    items: List<com.antigravity.filemanager.domain.model.FileItem>,
    totalSize: Long,
    isComputing: Boolean,
    onDismiss: () -> Unit
) {
    if (items.isEmpty()) return
    val fileCount = items.count { !it.isDirectory }
    val folderCount = items.count { it.isDirectory }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Properties",
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (items.size == 1) {
                    val file = items.first()
                    PropertyRow(label = "Name", value = file.name)
                    PropertyRow(label = "Type", value = if (file.isDirectory) "Folder" else file.mimeType.ifEmpty { file.extension.uppercase() })
                    PropertyRow(label = "Path", value = file.path)
                    if (file.formattedDate.isNotEmpty()) {
                        PropertyRow(label = "Modified", value = file.formattedDate)
                    }
                } else {
                    val summary = buildList {
                        if (fileCount > 0) add("$fileCount file(s)")
                        if (folderCount > 0) add("$folderCount folder(s)")
                    }.joinToString(", ")
                    PropertyRow(label = "Selected", value = "${items.size} item(s) — $summary")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(text = "Size", color = TextSecondary, fontSize = 12.sp)
                        Text(
                            text = FileItem.formatBytes(totalSize) + if (isComputing) " (calculating…)" else "",
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Normal
                        )
                    }
                    if (isComputing) {
                        Spacer(modifier = Modifier.width(8.dp))
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = TealPrimary)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = TealPrimary, contentColor = Color.Black)
            ) {
                Text(text = "OK", fontWeight = FontWeight.Bold)
            }
        },
        containerColor = DarkCard
    )
}

@Composable
fun AddCloudDialog(
    onSelectProvider: (CloudProvider, String, String, String?, String?) -> Unit,
    onDismiss: () -> Unit,
    isAddingAccount: Boolean = false,
    addAccountError: String? = null,
    onClearAddAccountError: () -> Unit = {}
) {
    var selectedProvider by remember { mutableStateOf(CloudProvider.GOOGLE_DRIVE) }
    var accountName by remember { mutableStateOf("Google Drive") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var megaEmail by remember { mutableStateOf("") }
    var megaPassword by remember { mutableStateOf("") }
    var megaPasswordVisible by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    val googleSignInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { activityResult ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(activityResult.data)
        try {
            val googleAccount = task.getResult(ApiException::class.java)
            val email = googleAccount.email ?: ""
            onSelectProvider(CloudProvider.GOOGLE_DRIVE, accountName.ifBlank { "Google Drive" }, email, null, null)
        } catch (e: ApiException) {
            if (e.statusCode == 10 || e.statusCode == 12500) {
                errorMessage = "Google Sign-In lỗi Code 10 (DEVELOPER_ERROR): Chưa đăng ký SHA-1 và Package name (com.antigravity.filemanager) trong Google Cloud Console."
            } else {
                errorMessage = "Google sign-in failed (code ${e.statusCode})"
            }
        }
    }

    fun startGoogleSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(com.google.android.gms.common.api.Scope(com.google.api.services.drive.DriveScopes.DRIVE))
            .build()
        val client = GoogleSignIn.getClient(context, gso)
        // Google Play Services silently reuses the last-signed-in account for this app and skips
        // the account picker unless the client's cached session is cleared first — without this,
        // adding a second Google account is impossible; every sign-in silently returns the same
        // one already connected. signOut() only clears the local cached session (not the user's
        // Google login elsewhere), so it's safe to call every time regardless of whether an
        // account was already connected.
        client.signOut().addOnCompleteListener {
            googleSignInLauncher.launch(client.signInIntent)
        }
    }

    val providerColor = remember(selectedProvider) {
        when (selectedProvider) {
            CloudProvider.GOOGLE_DRIVE -> Color(0xFF4285F4)
            CloudProvider.DROPBOX -> Color(0xFF0061FF)
            CloudProvider.MEGA -> Color(0xFFD9272E)
        }
    }

    val providerDisplayName = remember(selectedProvider) {
        when (selectedProvider) {
            CloudProvider.GOOGLE_DRIVE -> "Google Drive"
            CloudProvider.DROPBOX -> "Dropbox"
            CloudProvider.MEGA -> "MEGA"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Cloud,
                    contentDescription = null,
                    tint = TealPrimary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Connect Cloud Storage", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            }
        },
        text = {
            Column {
                Text(text = "Select Cloud Service:", color = TextSecondary, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(10.dp))

                // 3 Supported Cloud Providers: Google Drive, Dropbox, Mega
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CloudProviderButton(
                        name = "Google Drive",
                        color = Color(0xFF4285F4),
                        isSelected = selectedProvider == CloudProvider.GOOGLE_DRIVE,
                        onClick = {
                            selectedProvider = CloudProvider.GOOGLE_DRIVE
                            accountName = "Google Drive"
                            errorMessage = null
                        },
                        modifier = Modifier.weight(1f)
                    )
                    CloudProviderButton(
                        name = "Dropbox",
                        color = Color(0xFF0061FF),
                        isSelected = selectedProvider == CloudProvider.DROPBOX,
                        onClick = {
                            selectedProvider = CloudProvider.DROPBOX
                            accountName = "Dropbox"
                            errorMessage = null
                        },
                        modifier = Modifier.weight(1f)
                    )
                    CloudProviderButton(
                        name = "MEGA",
                        color = Color(0xFFD9272E),
                        isSelected = selectedProvider == CloudProvider.MEGA,
                        onClick = {
                            selectedProvider = CloudProvider.MEGA
                            accountName = "MEGA"
                            errorMessage = null
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = accountName,
                    onValueChange = { accountName = it },
                    label = { Text("Drive Display Name") },
                    placeholder = { Text(providerDisplayName, color = TextSecondary.copy(alpha = 0.6f)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = TealPrimary,
                        unfocusedBorderColor = TextSecondary,
                        cursorColor = TealPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (selectedProvider == CloudProvider.MEGA) {
                    Text(
                        text = "Sign in with your MEGA email & password",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = megaEmail,
                        onValueChange = { megaEmail = it; errorMessage = null; onClearAddAccountError() },
                        label = { Text("MEGA email") },
                        singleLine = true,
                        enabled = !isAddingAccount,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = TealPrimary,
                            unfocusedBorderColor = TextSecondary,
                            cursorColor = TealPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = megaPassword,
                        onValueChange = { megaPassword = it; errorMessage = null; onClearAddAccountError() },
                        label = { Text("Password") },
                        singleLine = true,
                        enabled = !isAddingAccount,
                        visualTransformation = if (megaPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { megaPasswordVisible = !megaPasswordVisible }) {
                                Icon(
                                    imageVector = if (megaPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (megaPasswordVisible) "Hide password" else "Show password",
                                    tint = TextSecondary
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = TealPrimary,
                            unfocusedBorderColor = TextSecondary,
                            cursorColor = TealPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (isAddingAccount) {
                        Spacer(modifier = Modifier.height(10.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = TealPrimary)
                    }
                    val megaError = errorMessage ?: addAccountError
                    if (megaError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = megaError, color = Color(0xFFFF6B6B), fontSize = 11.sp)
                    }
                } else {
                    Text(
                        text = "Tap 'SIGN IN & CONNECT' below to sign in securely to $providerDisplayName via the in-app browser.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = errorMessage!!, color = Color(0xFFFF6B6B), fontSize = 11.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when (selectedProvider) {
                        CloudProvider.DROPBOX -> {
                            com.antigravity.filemanager.data.remote.cloud.api.DropboxAuthManager.startAuth(context)
                            onDismiss()
                        }
                        CloudProvider.GOOGLE_DRIVE -> startGoogleSignIn()
                        CloudProvider.MEGA -> {
                            val trimmedEmail = megaEmail.trim()
                            if (trimmedEmail.isBlank() || megaPassword.isBlank()) {
                                errorMessage = "Please enter both email and password."
                            } else {
                                errorMessage = null
                                onSelectProvider(CloudProvider.MEGA, accountName.ifBlank { "MEGA" }, trimmedEmail, null, megaPassword)
                            }
                        }
                    }
                },
                enabled = !isAddingAccount,
                colors = ButtonDefaults.buttonColors(containerColor = providerColor, contentColor = Color.White),
                shape = RoundedCornerShape(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Login,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (selectedProvider == CloudProvider.MEGA) {
                        if (isAddingAccount) "SIGNING IN..." else "SIGN IN"
                    } else {
                        "SIGN IN & CONNECT"
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "CANCEL", color = TextSecondary)
            }
        },
        containerColor = DarkCard
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudLoginWebViewDialog(
    provider: CloudProvider,
    customAccountName: String,
    initialEmail: String = "",
    onAuthSuccess: (CloudProvider, String, String, String?, String?) -> Unit,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var isLoading by remember { mutableStateOf(true) }
    var detectedEmail by remember { mutableStateOf(initialEmail) }
    val finalAccountName = remember { customAccountName.ifBlank { provider.name } }
    var hasRedirected by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    val startUrl = remember(provider) {
        when (provider) {
            CloudProvider.GOOGLE_DRIVE -> "https://accounts.google.com/signin/v2/identifier?service=wise&passive=1209600&continue=https%3A%2F%2Fdrive.google.com%2Fdrive%2Fmy-drive&flowName=GlifWebSignIn&flowEntry=ServiceLogin"
            CloudProvider.DROPBOX -> "https://www.dropbox.com/login"
            CloudProvider.MEGA -> "https://mega.nz/login"
        }
    }


    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = DarkBackground
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // In-App Browser Top Bar
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "Sign in to ${provider.name}",
                                color = TextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (isLoading) {
                                Text(
                                    text = "Loading portal...",
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = TextPrimary)
                        }
                    },
                    actions = {
                        // Force Done Button
                        Button(
                            onClick = {
                                webViewRef?.let { wv ->
                                    val currentUrl = (wv.url ?: "").lowercase(Locale.getDefault())
                                    val cookieManager = CookieManager.getInstance()
                                    val cookieUrl = when (provider) {
                                        CloudProvider.GOOGLE_DRIVE -> "https://drive.google.com"
                                        CloudProvider.DROPBOX -> "https://www.dropbox.com"
                                        CloudProvider.MEGA -> "https://mega.nz"
                                    }
                                    val cookies = cookieManager.getCookie(cookieUrl) ?: cookieManager.getCookie("https://accounts.google.com") ?: ""

                                    when (provider) {
                                        CloudProvider.DROPBOX -> {
                                            cookieManager.flush()
                                            val parsedPath = try { java.net.URI(wv.url ?: "").path?.lowercase(Locale.getDefault()) ?: "" } catch (e: Exception) { "" }
                                            val isDropboxHome = (parsedPath.startsWith("/home") || parsedPath.startsWith("/personal") || parsedPath.startsWith("/work") || parsedPath.startsWith("/browse")) && !parsedPath.contains("login")
                                            val isDropboxAuthed = cookies.contains("t=") || cookies.contains("lid=") || cookies.contains("jar=") || cookies.contains("sjar=")
                                            if (!isDropboxHome && !isDropboxAuthed && parsedPath.contains("login")) {
                                                Toast.makeText(context, "Vui lòng hoàn tất đăng nhập Dropbox trước khi bấm DONE.", Toast.LENGTH_SHORT).show()
                                                return@Button
                                            }
                                        }
                                        CloudProvider.GOOGLE_DRIVE -> {
                                            val parsedPath = try { java.net.URI(wv.url ?: "").path?.lowercase(Locale.getDefault()) ?: "" } catch (e: Exception) { "" }
                                            val isAuthed = (parsedPath.contains("/drive/my-drive") || parsedPath.contains("/drive/u/")) || cookies.contains("SID=") || cookies.contains("SSID=")
                                            if (!isAuthed) {
                                                Toast.makeText(context, "Vui lòng hoàn tất đăng nhập Google trước khi bấm DONE.", Toast.LENGTH_SHORT).show()
                                                return@Button
                                            }
                                        }
                                        CloudProvider.MEGA -> {}
                                    }

                                    // Trigger unified extraction logic
                                    wv.evaluateJavascript(
                                        """
                                        (function() {
                                            try {
                                                var sid = window.u_sid || (window.localStorage ? window.localStorage.getItem('sid') : '') || '';
                                                var email = (window.M && window.M.account ? window.M.account.email : '') || '';
                                                
                                                if (!email && window.Dropbox) {
                                                    if (window.Dropbox.accountData && window.Dropbox.accountData.email) email = window.Dropbox.accountData.email;
                                                    else if (window.Dropbox.viewer && window.Dropbox.viewer.email) email = window.Dropbox.viewer.email;
                                                    else if (window.Dropbox.email) email = window.Dropbox.email;
                                                }
                                                if (!email && window.__PRELOADED_STATE__) {
                                                    try {
                                                        var ps = JSON.stringify(window.__PRELOADED_STATE__);
                                                        var mEmail = ps.match(/"email"\s*:\s*"([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,})"/);
                                                        if (mEmail) email = mEmail[1];
                                                    } catch(e) {}
                                                }
                                                if (!email) {
                                                    var emailEl = document.querySelector('[data-email], a[aria-label*="@"], div[data-identifier], .gb_d, [aria-label*="Google Account"], [aria-label*="Tài khoản Google"]');
                                                    if (emailEl) {
                                                        var attr = emailEl.getAttribute('data-email') || emailEl.getAttribute('data-identifier') || emailEl.getAttribute('aria-label') || emailEl.innerText || '';
                                                        var m = attr.match(/[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}/);
                                                        if (m && !m[0].includes("dropbox.com-") && m[0].length < 45) email = m[0];
                                                    }
                                                }
                                                var rootId = (window.M && window.M.RootID) ? window.M.RootID : '';
                                                var folders = [];
                                                var seen = {};

                                                if (window.M && window.M.d) {
                                                    for (var k in window.M.d) {
                                                        var n = window.M.d[k];
                                                        if (n) {
                                                            if (!rootId && n.t === 2) rootId = n.h || k;
                                                            var name = n.name || (n.a ? n.a.n : '');
                                                            if (name) {
                                                                var isDir = n.t === 1 || n.t === 2;
                                                                var rawKey = n.key || n.k;
                                                                var kVal2 = '';
                                                                if (Array.isArray(rawKey)) kVal2 = rawKey.join(',');
                                                                else if (typeof rawKey === 'string') kVal2 = rawKey;
                                                                folders.push({ id: n.h || k, p: n.p || '', name: name, isDir: isDir, size: n.s || 0, ts: n.ts || 0, t: n.t !== undefined ? n.t : (isDir ? 1 : 0), k: kVal2 });
                                                            }
                                                        }
                                                    }
                                                }

                                                // Extract Dropbox folders/files from window.__PRELOADED_STATE__ or DOM
                                                try {
                                                    var blacklistedNames = ["apps menu", "resize left nav", "upgrade", "all files", "sharing", "file requests", "deleted files", "settings", "help", "home", "search", "recents", "starred", "photos", "signatures"];
                                                    if (window.__PRELOADED_STATE__) {
                                                        var ps = window.__PRELOADED_STATE__;
                                                        var findEntries = function(obj, depth) {
                                                            if (!obj || depth > 5) return;
                                                            if (Array.isArray(obj)) {
                                                                for (var i = 0; i < obj.length; i++) findEntries(obj[i], depth + 1);
                                                            } else if (typeof obj === 'object') {
                                                                if (obj.name && (obj['.tag'] === 'folder' || obj['.tag'] === 'file' || obj.is_dir !== undefined || obj.type === 'folder' || obj.type === 'file')) {
                                                                    var fName = String(obj.name).trim();
                                                                    var isDir = obj['.tag'] === 'folder' || obj.is_dir === true || obj.type === 'folder';
                                                                    if (fName && !seen[fName.toLowerCase()] && !blacklistedNames.includes(fName.toLowerCase())) {
                                                                        seen[fName.toLowerCase()] = true;
                                                                        folders.push({
                                                                            id: obj.id || obj.path_lower || fName,
                                                                            p: 'root',
                                                                            name: fName,
                                                                            isDir: isDir,
                                                                            size: obj.size || 0,
                                                                            ts: obj.server_modified ? new Date(obj.server_modified).getTime() : Date.now(),
                                                                            t: isDir ? 1 : 0
                                                                        });
                                                                    }
                                                                }
                                                                for (var k in obj) {
                                                                    if (obj.hasOwnProperty(k) && typeof obj[k] === 'object') findEntries(obj[k], depth + 1);
                                                                }
                                                            }
                                                        };
                                                        findEntries(ps, 0);
                                                    }
                                                    // Also check table rows / file rows
                                                    var fileRows = document.querySelectorAll('[data-filename], tr[data-testid*="row"], tr.dig-Table-row, a[href*="/home/"], a[href*="/personal/"], a[href*="/scl/fo/"]');
                                                    for (var fi = 0; fi < fileRows.length; fi++) {
                                                        var r = fileRows[fi];
                                                        var rName = r.getAttribute('data-filename') || r.innerText.split('\n')[0] || '';
                                                        rName = rName.trim();
                                                        if (rName && rName.length < 100 && !seen[rName.toLowerCase()] && !blacklistedNames.includes(rName.toLowerCase())) {
                                                            seen[rName.toLowerCase()] = true;
                                                            var isFolder = !rName.includes('.') || r.getAttribute('href')?.includes('/fo/') == true;
                                                            folders.push({
                                                                id: 'dbx_' + fi,
                                                                p: 'root',
                                                                name: rName,
                                                                isDir: isFolder,
                                                                size: 0,
                                                                ts: Date.now(),
                                                                t: isFolder ? 1 : 0
                                                            });
                                                        }
                                                    }
                                                } catch(de) {}

                                                var uidVal = '';
                                                try {
                                                    if (window.Dropbox) {
                                                        if (window.Dropbox.accountData && window.Dropbox.accountData.userId) uidVal = String(window.Dropbox.accountData.userId);
                                                        else if (window.Dropbox.viewer && window.Dropbox.viewer.userId) uidVal = String(window.Dropbox.viewer.userId);
                                                        else if (window.Dropbox.userId) uidVal = String(window.Dropbox.userId);
                                                    }
                                                    if (!uidVal && window.__PRELOADED_STATE__) {
                                                        var ps = JSON.stringify(window.__PRELOADED_STATE__);
                                                        var mPs = ps.match(/"userId"\s*:\s*(\d+)/) || ps.match(/"user_id"\s*:\s*(\d+)/);
                                                        if (mPs) uidVal = mPs[1];
                                                    }
                                                    if (!uidVal) {
                                                        var scs = document.querySelectorAll('script');
                                                        for (var si = 0; si < scs.length; si++) {
                                                            var stxt = scs[si].textContent || '';
                                                            var mU = stxt.match(/"userId"\s*:\s*(\d+)/) || stxt.match(/"user_id"\s*:\s*(\d+)/) || stxt.match(/"account_id"\s*:\s*"([^"]+)"/);
                                                            if (mU) { uidVal = mU[1]; break; }
                                                        }
                                                    }
                                                    if (!uidVal && document.documentElement) {
                                                        var html = document.documentElement.innerHTML || '';
                                                        var mU = html.match(/"userId"\s*:\s*(\d+)/) || html.match(/"user_id"\s*:\s*(\d+)/) || html.match(/"account_id"\s*:\s*"([^"]+)"/);
                                                        if (mU) uidVal = mU[1];
                                                    }
                                                } catch(ue) {}

                                                return JSON.stringify({ sid: sid, email: email, rootId: rootId, uid: uidVal, folders: folders });
                                            } catch(e) {
                                                return JSON.stringify({ error: e.message });
                                            }
                                        })();
                                        """.trimIndent()
                                    ) { res ->
                                        try {
                                            val cleaned = res?.replace("\\\"", "\"")?.trim('"', ' ') ?: "{}"
                                            val json = org.json.JSONObject(cleaned)
                                            val sid = json.optString("sid").ifBlank { "session_active" }
                                            val uidVal = json.optString("uid", "")
                                            var currentCookies = cookieManager.getCookie(cookieUrl) 
                                                ?: cookieManager.getCookie("https://accounts.google.com")
                                                ?: sid

                                            if (uidVal.isNotBlank() && !currentCookies.contains("uid=")) {
                                                currentCookies = "$currentCookies; uid=$uidVal"
                                            }

                                            var email = json.optString("email").trim()
                                            if (email.isBlank() || email.contains("dropbox.com-") || email.length > 45) {
                                                email = detectedEmail.ifBlank {
                                                    if (initialEmail.isNotBlank()) initialEmail else "user@${provider.name.lowercase(Locale.getDefault())}.com"
                                                }
                                            }

                                            if (provider == CloudProvider.MEGA && sid == "session_active" && email.startsWith("user@")) {
                                                Toast.makeText(context, "Vui lòng hoàn tất đăng nhập MEGA trước khi bấm DONE.", Toast.LENGTH_SHORT).show()
                                                return@evaluateJavascript
                                            }

                                            val rawSession = if (cleaned.length > 20) cleaned else currentCookies
                                            hasRedirected = true
                                            onAuthSuccess(provider, finalAccountName, email, currentCookies, rawSession)
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = TealPrimary, contentColor = Color.Black),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text("DONE", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkCard)
                )

                if (isLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = TealPrimary
                    )
                }

                // In-App WebView with automatic redirect capture and intent protection
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            webViewRef = this
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                loadWithOverviewMode = true
                                useWideViewPort = true
                                javaScriptCanOpenWindowsAutomatically = true
                                setSupportMultipleWindows(true)
                                // Google blocks its own WebView UA, so spoof desktop Firefox there.
                                // Dropbox/MEGA serve modern JS bundles to that UA that this WebView's
                                // (old, non-Play-Store-updatable) Chromium engine can't parse, leaving
                                // a blank page — so only spoof for Google Drive.
                                if (provider == CloudProvider.GOOGLE_DRIVE) {
                                    userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:129.0) Gecko/20100101 Firefox/129.0"
                                }
                            }

                            val cookieManager = CookieManager.getInstance()
                            cookieManager.setAcceptCookie(true)
                            cookieManager.setAcceptThirdPartyCookies(this, true)
                            fun performExtractionAndFinish(url: String?) {
                                if (hasRedirected) return
                                evaluateJavascript(
                                    """
                                    (function() {
                                        try {
                                            var sid = window.u_sid || (window.localStorage ? window.localStorage.getItem('sid') : '') || '';
                                            var email = '';
                                            // 0. MEGA specific properties
                                            if (window.u_attr && window.u_attr.email) email = window.u_attr.email;
                                            else if (window.M && window.M.account && window.M.account.email) email = window.M.account.email;
                                            else if (window.M && window.M.user && window.M.user.email) email = window.M.user.email;
                                            else if (window.u_email) email = window.u_email;
                                            else if (typeof window.u_handle === 'string' && window.u_handle.indexOf('@') !== -1) email = window.u_handle;
                                            try {
                                                if (!email && window.localStorage) {
                                                    var lEmail = window.localStorage.getItem('email') || window.localStorage.getItem('user_email') || window.localStorage.getItem('useremail') || window.localStorage.getItem('mega_email');
                                                    if (lEmail && lEmail.indexOf('@') !== -1 && lEmail.length < 50) email = lEmail;
                                                }
                                            } catch(e) {}

                                            if (!email && window.Dropbox) {
                                                if (window.Dropbox.accountData && window.Dropbox.accountData.email) email = window.Dropbox.accountData.email;
                                                else if (window.Dropbox.viewer && window.Dropbox.viewer.email) email = window.Dropbox.viewer.email;
                                                else if (window.Dropbox.email) email = window.Dropbox.email;
                                            }
                                            if (!email && window.__PRELOADED_STATE__) {
                                                try {
                                                    var ps = JSON.stringify(window.__PRELOADED_STATE__);
                                                    var mEmail = ps.match(/"email"\s*:\s*"([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,})"/);
                                                    if (mEmail) email = mEmail[1];
                                                } catch(e) {}
                                            }

                                            var emailInput = document.querySelector('input[type="email"]');
                                            if (emailInput && emailInput.value && emailInput.value.indexOf('@') !== -1) {
                                                email = emailInput.value.trim();
                                            }
                                            if (!email) {
                                                var emailEl = document.querySelector('[data-email], a[aria-label*="@"], div[data-identifier], .gb_d, [aria-label*="Google Account"], [aria-label*="Tài khoản Google"]');
                                                if (emailEl) {
                                                    var attr = emailEl.getAttribute('data-email') || emailEl.getAttribute('data-identifier') || emailEl.getAttribute('aria-label') || emailEl.innerText || '';
                                                    var m = attr.match(/[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}/);
                                                    if (m && !m[0].includes("dropbox.com-") && m[0].length < 45) email = m[0];
                                                }
                                            }
                                            if (!email && window.WIZ_global_data && window.WIZ_global_data.oPEP7c) {
                                                email = window.WIZ_global_data.oPEP7c;
                                            }
                                            if (!email) {
                                                var allAria = document.querySelectorAll('[aria-label], [title], [alt], img');
                                                for (var a = 0; a < allAria.length; a++) {
                                                    var str = (allAria[a].getAttribute('aria-label') || '') + ' ' + (allAria[a].getAttribute('title') || '') + ' ' + (allAria[a].getAttribute('alt') || '');
                                                    var mMatch = str.match(/[a-zA-Z0-9._%+-]+@(gmail\.com|[a-zA-Z0-9.-]+\.[a-zA-Z]{2,})/);
                                                    if (mMatch && !mMatch[0].includes("dropbox.com-") && mMatch[0].length < 45) {
                                                        email = mMatch[0];
                                                        break;
                                                    }
                                                }
                                            }
                                            if (!email) {
                                                var bodyText = document.body ? (document.body.innerText || document.body.textContent || '') : '';
                                                var m2 = bodyText.match(/[a-zA-Z0-9._%+-]+@(gmail\.com|[a-zA-Z0-9.-]+\.[a-zA-Z]{2,})/);
                                                if (m2 && !m2[0].includes("dropbox.com-") && m2[0].length < 45) email = m2[0];
                                            }
                                            var rootId = (window.M && window.M.RootID) ? window.M.RootID : '';
                                            var folders = [];
                                            var seen = {};

                                            // 1. MEGA Decrypted Tree
                                            if (window.M && window.M.d) {
                                                for (var k in window.M.d) {
                                                    var n = window.M.d[k];
                                                    if (n) {
                                                        if (!rootId && n.t === 2) rootId = n.h || k;
                                                        var name = n.name || (n.a ? n.a.n : '');
                                                        if (name) {
                                                            var isDir = n.t === 1 || n.t === 2;
                                                            var kVal = '';
                                                            var rawKey = n.key || n.k;
                                                            if (rawKey) {
                                                                if (Array.isArray(rawKey)) {
                                                                    kVal = rawKey.join(',');
                                                                } else if (typeof rawKey === 'string') {
                                                                    kVal = rawKey;
                                                                }
                                                            }
                                                            folders.push({
                                                                id: n.h || k,
                                                                p: n.p || '',
                                                                name: name,
                                                                isDir: isDir,
                                                                size: n.s || 0,
                                                                ts: n.ts || 0,
                                                                t: n.t !== undefined ? n.t : (isDir ? 1 : 0),
                                                                k: kVal
                                                            });
                                                        }
                                                    }
                                                }
                                            }

                                            // 2. Google Drive Web DOM
                                            var gDriveItems = document.querySelectorAll(
                                                'div[data-id], div[role="row"], div[role="gridcell"], div[data-target="doc"], .drive-list-item, div[aria-label], a[href*="/folders/"], a[href*="/file/d/"], .c-P-p, .a-v-d, .a-t'
                                            );
                                            for (var i = 0; i < gDriveItems.length; i++) {
                                                var el = gDriveItems[i];
                                                var role = el.getAttribute('role') || '';
                                                var tagName = el.tagName ? el.tagName.toLowerCase() : '';
                                                if (tagName === 'button' || role === 'button' || role === 'menuitem' || role === 'tab' || role === 'toolbar') {
                                                    continue;
                                                }
                                                var id = el.getAttribute('data-id') || el.getAttribute('data-target-id') || ('gdrive_' + i);
                                                var label = el.getAttribute('aria-label') || '';
                                                var titleEl = el.querySelector('.c-P-p, .a-t, .title, [data-tooltip], [data-name], a');
                                                var rawName = (titleEl ? (titleEl.innerText || titleEl.textContent) : '') || label || el.innerText || '';
                                                if (rawName && rawName.length < 150) {
                                                    var cleanName = rawName.split('\n')[0].replace(/^Folder\s*[:\-]?\s*/i, '').replace(/^Thư mục\s*[:\-]?\s*/i, '').replace(/ Thư mục$/i, '').trim();
                                                    var lowerName = cleanName.toLowerCase();
                                                    if (cleanName && cleanName.length > 1 && !seen[cleanName] && 
                                                        lowerName !== 'loading' &&
                                                        lowerName !== 'loading...' &&
                                                        lowerName !== 'đang tải' &&
                                                        lowerName !== 'đang tải...' &&
                                                        lowerName !== 'choose an account' &&
                                                        lowerName !== 'use another account' &&
                                                        lowerName !== 'close' &&
                                                        lowerName !== 'close overlay' &&
                                                        lowerName !== 'details' &&
                                                        lowerName !== 'chi tiết' &&
                                                        lowerName !== 'đóng lớp phủ' &&
                                                        lowerName !== 'đóng' &&
                                                        lowerName !== 'cancel' &&
                                                        lowerName !== 'hủy' &&
                                                        lowerName !== 'more options' &&
                                                        lowerName !== 'tùy chọn khác' &&
                                                        lowerName !== 'open side menu' &&
                                                        lowerName !== 'mở menu bên' &&
                                                        lowerName !== 'search in drive' &&
                                                        lowerName !== 'tìm kiếm trong drive' &&
                                                        lowerName.indexOf('switch from') === -1 &&
                                                        lowerName.indexOf('chuyển sang chế độ') === -1 &&
                                                        cleanName.indexOf('My Drive') === -1 && 
                                                        cleanName.indexOf('Google Drive') === -1 && 
                                                        cleanName.indexOf('Shared Google Drive') === -1 && 
                                                        cleanName.indexOf('Shared drives') === -1 && 
                                                        cleanName.indexOf('Bộ nhớ dùng chung') === -1 && 
                                                        cleanName.indexOf('Sign in') === -1 && 
                                                        cleanName.indexOf('Đăng nhập') === -1 && 
                                                        cleanName.indexOf('Storage') === -1 && 
                                                        cleanName.indexOf('Bộ nhớ') === -1 && 
                                                        cleanName.indexOf('Trash') === -1 && 
                                                        cleanName.indexOf('Thùng rác') === -1 && 
                                                        cleanName.indexOf('Computers') === -1 && 
                                                        cleanName.indexOf('Máy tính') === -1 && 
                                                        cleanName.indexOf('Shared with me') === -1 && 
                                                        cleanName.indexOf('Chia sẻ với tôi') === -1 && 
                                                        cleanName.indexOf('Recent') === -1 && 
                                                        cleanName.indexOf('Gần đây') === -1 && 
                                                        cleanName.indexOf('Starred') === -1 && 
                                                        cleanName.indexOf('Có gắn dấu sao') === -1 && 
                                                        cleanName.indexOf('Get Drive') === -1) {
                                                        seen[cleanName] = true;
                                                        var isFolder = el.getAttribute('data-is-folder') === 'true' || 
                                                                       (el.getAttribute('href') && el.getAttribute('href').indexOf('/folders/') !== -1) ||
                                                                       (label && (label.toLowerCase().indexOf('folder') !== -1 || label.toLowerCase().indexOf('thư mục') !== -1)) ||
                                                                       el.querySelector('svg[aria-label*="Folder"], svg[aria-label*="Thư mục"], [data-mime-type*="folder"]') !== null;
                                                        folders.push({
                                                            id: id,
                                                            p: 'root',
                                                            name: cleanName,
                                                            isDir: isFolder,
                                                            size: 0,
                                                            ts: Date.now(),
                                                            t: isFolder ? 1 : 0
                                                        });
                                                    }
                                                }
                                            }

                                            // 3. Dropbox Web Globals & DOM
                                            try {
                                                var rawInit = window.__INITIAL_STATE__ || window.__INITIAL_DATA__ || (window.Dropbox && (window.Dropbox.initData || window.Dropbox.accountData));
                                                if (rawInit) {
                                                    var strInit = JSON.stringify(rawInit);
                                                    var matchFiles = strInit.match(/"(?:path_display|file_name|filename|entry_name|display_name)"\s*:\s*"([^"]+)"/g);
                                                    if (matchFiles) {
                                                        for (var mi = 0; mi < matchFiles.length; mi++) {
                                                            var pDisp = matchFiles[mi].replace(/"[^"]+"\s*:\s*"/, '').replace(/"/, '');
                                                            var fName = pDisp.split('/').pop().trim();
                                                            if (fName && fName.length > 1 && !seen[fName] && fName.toLowerCase() !== 'dropbox' && fName.indexOf('{') === -1 && fName.indexOf(':') === -1) {
                                                                seen[fName] = true;
                                                                var isD = pDisp.indexOf('.') === -1;
                                                                folders.push({ id: 'dbx_' + mi, p: 'root', name: fName, isDir: isD, size: 0, ts: Date.now(), t: isD ? 1 : 0 });
                                                            }
                                                        }
                                                    }
                                                }
                                            } catch(e){}

                                            var dbxItems = document.querySelectorAll(
                                                'tr, [data-testid="content-item"], [data-testid="file-cell"], [data-testid="file-row"], .dig-Table-row, .sl-grid-cell, .ue-effect-container, .mc-media-cell, [data-testid="filename-col"], a[href*="/home"], a[href*="/personal"], .browse-file-row, .brws-file-name, [data-filename]'
                                            );
                                            for (var j = 0; j < dbxItems.length; j++) {
                                                var dEl = dbxItems[j];
                                                var dName = dEl.getAttribute('data-filename') || 
                                                            dEl.getAttribute('data-name') || 
                                                            (dEl.querySelector('.brws-file-name, [data-testid="filename-col"], a, button, span') ? dEl.querySelector('.brws-file-name, [data-testid="filename-col"], a, button, span').innerText : '') || 
                                                            dEl.innerText || '';
                                                if (dName && dName.length < 120) {
                                                    var cName = dName.split('\n')[0].trim();
                                                    var lowerC = cName.toLowerCase();
                                                    if (cName && cName.length > 1 && !seen[cName] && 
                                                        lowerC !== 'dropbox' &&
                                                        lowerC !== 'loading' &&
                                                        lowerC !== 'loading...' &&
                                                        lowerC !== 'đang tải' &&
                                                        lowerC !== 'đang tải...' &&
                                                        lowerC !== 'choose an account' &&
                                                        lowerC !== 'use another account' &&
                                                        lowerC !== 'sign in' && 
                                                        lowerC !== 'đăng nhập' &&
                                                        lowerC !== 'deleted files' &&
                                                        lowerC !== 'recent' &&
                                                        lowerC !== 'all files' &&
                                                        lowerC !== 'upgrade' &&
                                                        lowerC !== 'home' &&
                                                        lowerC !== 'sharing' &&
                                                        lowerC !== 'apps menu' &&
                                                        lowerC !== 'resize left nav' &&
                                                        lowerC !== 'file requests' &&
                                                        lowerC !== 'get started' &&
                                                        lowerC !== 'starred') {
                                                        seen[cName] = true;
                                                        var dIsDir = dEl.querySelector('svg[aria-label*="folder"], [data-type="folder"], .icon--folder, svg[data-testid="folder-icon"]') !== null || 
                                                                     dEl.innerHTML.indexOf('folder') !== -1 || 
                                                                     cName.indexOf('.') === -1;
                                                        folders.push({
                                                            id: 'dbx_' + j,
                                                            p: 'root',
                                                            name: cName,
                                                            isDir: dIsDir,
                                                            size: 0,
                                                            ts: Date.now(),
                                                            t: dIsDir ? 1 : 0
                                                        });
                                                    }
                                                }
                                            }

                                            // 4. Fallback generic (only if not Dropbox)
                                            if (folders.length === 0 && (!window.Dropbox || !window.Dropbox.accountData)) {
                                                var els = document.querySelectorAll('.grid-url, .nw-fm-tree-folder, .tranfer-filetype-txt, [data-folder-id], .file-block, .folder-block, .grid-scrolling-cell');
                                                for (var k = 0; k < els.length; k++) {
                                                    var t = (els[k].innerText || els[k].textContent || '').trim();
                                                    if (t && t.length < 100) {
                                                        var line = t.split('\n')[0].trim();
                                                        var lowerL = line.toLowerCase();
                                                        if (line && !seen[line] && lowerL !== 'apps menu' && lowerL !== 'resize left nav') {
                                                            seen[line] = true;
                                                            folders.push({ id: 'dom_' + k, p: '', name: line, isDir: true, size: 0, ts: 0, t: 1 });
                                                        }
                                                    }
                                                }
                                            }

                                            var masterKey = '';
                                            var uk = (window.M && window.M.u_k) || window.u_k;
                                            if (uk && uk.length >= 4) {
                                                var b = new Uint8Array(16);
                                                for (var ki = 0; ki < 4; ki++) {
                                                    var val = uk[ki];
                                                    b[ki*4] = (val >>> 24) & 255;
                                                    b[ki*4+1] = (val >>> 16) & 255;
                                                    b[ki*4+2] = (val >>> 8) & 255;
                                                    b[ki*4+3] = val & 255;
                                                }
                                                masterKey = btoa(String.fromCharCode.apply(null, b)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '');
                                            } else if (window.localStorage && window.localStorage.getItem('k')) {
                                                masterKey = window.localStorage.getItem('k');
                                            }

                                            var uidVal = '';
                                            try {
                                                if (window.Dropbox) {
                                                    if (window.Dropbox.accountData && window.Dropbox.accountData.userId) uidVal = String(window.Dropbox.accountData.userId);
                                                    else if (window.Dropbox.viewer && window.Dropbox.viewer.userId) uidVal = String(window.Dropbox.viewer.userId);
                                                    else if (window.Dropbox.userId) uidVal = String(window.Dropbox.userId);
                                                }
                                                if (!uidVal && window.__PRELOADED_STATE__) {
                                                    var ps = JSON.stringify(window.__PRELOADED_STATE__);
                                                    var mPs = ps.match(/"userId"\s*:\s*(\d+)/) || ps.match(/"user_id"\s*:\s*(\d+)/);
                                                    if (mPs) uidVal = mPs[1];
                                                }
                                                if (!uidVal) {
                                                    var scs = document.querySelectorAll('script');
                                                    for (var si = 0; si < scs.length; si++) {
                                                        var stxt = scs[si].textContent || '';
                                                        var mU = stxt.match(/"userId"\s*:\s*(\d+)/) || stxt.match(/"user_id"\s*:\s*(\d+)/) || stxt.match(/"account_id"\s*:\s*"([^"]+)"/);
                                                        if (mU) { uidVal = mU[1]; break; }
                                                    }
                                                }
                                                if (!uidVal && document.documentElement) {
                                                    var html = document.documentElement.innerHTML || '';
                                                    var mU = html.match(/"userId"\s*:\s*(\d+)/) || html.match(/"user_id"\s*:\s*(\d+)/) || html.match(/"account_id"\s*:\s*"([^"]+)"/);
                                                    if (mU) uidVal = mU[1];
                                                }
                                            } catch(ue) {}

                                            return JSON.stringify({ sid: sid, email: email, rootId: rootId, masterKey: masterKey, uid: uidVal, folders: folders });
                                        } catch(e) {
                                            return JSON.stringify({ error: e.message });
                                        }
                                    })();
                                    """.trimIndent()
                                ) { res ->
                                    try {
                                        val cleaned = res?.replace("\\\"", "\"")?.trim('"', ' ') ?: "{}"
                                        val json = org.json.JSONObject(cleaned)
                                        val sid = json.optString("sid").ifBlank { "session_active" }
                                        val uidVal = json.optString("uid", "")
                                        val cookieUrl = when (provider) {
                                            CloudProvider.GOOGLE_DRIVE -> "https://drive.google.com"
                                            CloudProvider.DROPBOX -> "https://www.dropbox.com"
                                            CloudProvider.MEGA -> "https://mega.nz"
                                        }
                                        var cookies = cookieManager.getCookie(cookieUrl) 
                                            ?: cookieManager.getCookie("https://accounts.google.com")
                                            ?: sid

                                        if (uidVal.isNotBlank() && !cookies.contains("uid=")) {
                                            cookies = "$cookies; uid=$uidVal"
                                        }

                                        var email = json.optString("email").trim()
                                        if (email.isBlank() || email.contains("dropbox.com-") || email.length > 45) {
                                            email = detectedEmail.ifBlank {
                                                if (initialEmail.isNotBlank()) initialEmail else "user@${provider.name.lowercase(Locale.getDefault())}.com"
                                            }
                                        }
                                        if (provider == CloudProvider.DROPBOX) {
                                            val isAuthed = cookies.contains("lid=") || cookies.contains("jar=") || cookies.contains("sjar=")
                                            if (!isAuthed && cookies.contains("__Host-logged-out-session")) {
                                                return@evaluateJavascript
                                            }
                                        }

                                        val rawSession = if (cleaned.length > 20) cleaned else cookies
                                        hasRedirected = true
                                        onAuthSuccess(provider, finalAccountName, email, cookies, rawSession)
                                    } catch (e: Exception) {
                                        val cookies = cookieManager.getCookie("https://drive.google.com") ?: "session_active"
                                        hasRedirected = true
                                        onAuthSuccess(provider, finalAccountName, detectedEmail.ifBlank { "user@${provider.name.lowercase(Locale.getDefault())}.com" }, cookies, cookies)
                                    }
                                }
                            }

                            fun triggerAutoRedirect(url: String?) {
                                if (hasRedirected) return
                                val urlStr = url?.lowercase(Locale.getDefault()) ?: ""

                                // Catch OAuth callback token
                                val isOAuth = url?.contains("access_token=") == true
                                if (isOAuth) {
                                    hasRedirected = true
                                    val token = url?.substringAfter("access_token=")?.substringBefore("&")
                                    val email = detectedEmail.ifBlank {
                                        if (initialEmail.isNotBlank()) initialEmail else "user@${provider.name.lowercase(Locale.getDefault())}.com"
                                    }
                                    onAuthSuccess(provider, finalAccountName, email, token, token)
                                }
                            }

                            var childDialog: android.app.Dialog? = null

                            webChromeClient = object : WebChromeClient() {
                                override fun onCloseWindow(window: WebView?) {
                                    super.onCloseWindow(window)
                                    try {
                                        childDialog?.dismiss()
                                    } catch (e: Exception) {}
                                    childDialog = null
                                    cookieManager.flush()
                                }

                                override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                                    val ctx = view?.context ?: return false
                                    val childWebView = WebView(ctx).apply {
                                        settings.javaScriptEnabled = true
                                        settings.domStorageEnabled = true
                                        settings.databaseEnabled = true
                                        settings.setSupportMultipleWindows(true)
                                        settings.javaScriptCanOpenWindowsAutomatically = true
                                        if (provider == CloudProvider.GOOGLE_DRIVE) {
                                            settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:129.0) Gecko/20100101 Firefox/129.0"
                                        }
                                        CookieManager.getInstance().setAcceptCookie(true)
                                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                                        webChromeClient = object : WebChromeClient() {
                                            override fun onCloseWindow(w: WebView?) {
                                                try {
                                                    childDialog?.dismiss()
                                                } catch (e: Exception) {}
                                                childDialog = null
                                                cookieManager.flush()
                                            }
                                        }
                                        webViewClient = object : WebViewClient() {
                                            override fun onPageFinished(v: WebView?, u: String?) {
                                                super.onPageFinished(v, u)
                                                cookieManager.flush()
                                            }
                                        }
                                    }

                                    childDialog = android.app.Dialog(ctx, android.R.style.Theme_DeviceDefault_Light_NoActionBar_Fullscreen).apply {
                                        setContentView(childWebView)
                                        setOnDismissListener {
                                            try {
                                                childWebView.destroy()
                                            } catch (e: Exception) {}
                                            childDialog = null
                                            cookieManager.flush()
                                        }
                                        show()
                                    }

                                    val transport = resultMsg?.obj as? WebView.WebViewTransport
                                    transport?.webView = childWebView
                                    resultMsg?.sendToTarget()
                                    return true
                                }
                            }

                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    val reqUrl = request?.url?.toString() ?: ""
                                    if (reqUrl.startsWith("intent:") || reqUrl.startsWith("mega:") || (!reqUrl.startsWith("http://") && !reqUrl.startsWith("https://"))) {
                                        return true
                                    }
                                    if (reqUrl.contains("access_token=")) {
                                        triggerAutoRedirect(reqUrl)
                                        return true
                                    }
                                    return false
                                }

                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    isLoading = true
                                    if (url?.contains("access_token=") == true) {
                                        triggerAutoRedirect(url)
                                    }
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    isLoading = false

                                    val urlLower = url?.lowercase(Locale.getDefault()) ?: ""

                                    // If Google OAuth popup callback finished on Dropbox
                                    if (provider == CloudProvider.DROPBOX && (
                                        urlLower.contains("google_login") || 
                                        urlLower.contains("oauth2/callback") || 
                                        urlLower.contains("google_auth") ||
                                        urlLower.contains("accounts.google.com/rotatecookiespage") ||
                                        urlLower.contains("accounts.google.com/embed/oauth/callback") ||
                                        urlLower.contains("dropbox.com/oauth2") ||
                                        urlLower.contains("about:blank")
                                    )) {
                                        cookieManager.flush()
                                        postDelayed({
                                            if (!hasRedirected) {
                                                loadUrl("https://www.dropbox.com/home")
                                            }
                                        }, 600)
                                        return
                                    }

                                    // Update detected email progressively in background
                                    evaluateJavascript(
                                        """
                                        (function() {
                                            try {
                                                var emailEl = document.querySelector('[data-email], a[aria-label*="@"], div[data-identifier], .gb_d, [aria-label*="Google Account"], [aria-label*="Tài khoản Google"]');
                                                if (emailEl) {
                                                    var attr = emailEl.getAttribute('data-email') || emailEl.getAttribute('data-identifier') || emailEl.getAttribute('aria-label') || emailEl.innerText || '';
                                                    var m = attr.match(/[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}/);
                                                    if (m) return m[0];
                                                }
                                                var bodyText = document.body ? (document.body.innerText || document.body.textContent || '') : '';
                                                var m2 = bodyText.match(/[a-zA-Z0-9._%+-]+@(gmail\.com|[a-zA-Z0-9.-]+\.[a-zA-Z]{2,})/);
                                                if (m2) return m2[0];
                                            } catch(e) {}
                                            return '';
                                        })();
                                        """.trimIndent()
                                    ) { res ->
                                        val cleaned = res?.trim('"', ' ') ?: ""
                                        if (cleaned.isNotBlank() && cleaned.contains("@")) {
                                            detectedEmail = cleaned
                                        }
                                    }

                                    // Auto-extract session if user has successfully landed on Home/Drive pages (not in login query params)
                                    val parsedPath = try { java.net.URI(url ?: "").path?.lowercase(Locale.getDefault()) ?: "" } catch (e: Exception) { "" }
                                    val isDropboxHome = (parsedPath.startsWith("/home") || parsedPath.startsWith("/personal") || parsedPath.startsWith("/work") || parsedPath.startsWith("/browse")) && !parsedPath.contains("login") && !parsedPath.contains("verify") && !parsedPath.contains("twofactor")
                                    val isGoogleDriveHome = (parsedPath.contains("/drive/my-drive") || parsedPath.contains("/drive/u/")) && !parsedPath.contains("signin") && !parsedPath.contains("identifier")

                                    if ((isDropboxHome || isGoogleDriveHome) && !hasRedirected) {
                                        cookieManager.flush()
                                        postDelayed({
                                            if (!hasRedirected) {
                                                performExtractionAndFinish(url)
                                            }
                                        }, 1200)
                                    }
                                }
                            }

                            loadUrl(startUrl)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}




@Composable
private fun CloudProviderButton(
    name: String,
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) color.copy(alpha = 0.25f) else DarkCardSecondary,
        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, color) else null,
        modifier = modifier.height(48.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(
                text = name,
                color = if (isSelected) Color.White else TextSecondary,
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1
            )
        }
    }
}

@Composable
fun SelectCloudDestinationDialog(
    isMoveOperation: Boolean,
    itemCount: Int,
    accounts: List<CloudAccount>,
    onSelectLocal: () -> Unit,
    onSelectAccount: (CloudAccount) -> Unit,
    onDismiss: () -> Unit
) {
    val actionText = if (isMoveOperation) "Move to" else "Copy to"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.DriveFileMove,
                    contentDescription = null,
                    tint = TealPrimary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = actionText,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Select destination for $itemCount item(s):",
                    color = TextSecondary,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(12.dp))

                Surface(
                    onClick = onSelectLocal,
                    color = DarkCardSecondary,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Smartphone,
                            contentDescription = null,
                            tint = TealPrimary,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "This device",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                accounts.forEach { account ->
                    Surface(
                        onClick = { onSelectAccount(account) },
                        color = DarkCardSecondary,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CloudProviderIcon(
                                provider = account.provider,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = account.accountName,
                                    color = TextPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = account.email,
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = TextSecondary)
            }
        },
        containerColor = DarkCard
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudFolderPickerDialog(
    accountName: String,
    segments: List<String>,
    folders: List<FileItem>,
    isLoading: Boolean,
    onFolderClick: (FileItem) -> Unit,
    onSegmentClick: (Int) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    // The system back gesture/button is wired to onDismissRequest below — while drilled into a
    // subfolder, that used to close this full-screen dialog outright instead of stepping back up
    // one level the way it does everywhere else in the app (and the way a user swiping back after
    // navigating deeper would expect). Only actually dismiss once there's nowhere left to go back
    // to (segments.size == 1, i.e. still at Root). There's no dialog "outside" to tap here — the
    // Surface below fills the whole window — so onDismissRequest only ever fires from back
    // press/gesture, making this safe to redirect entirely rather than splitting it from a
    // separate click-outside case.
    val handleBackOrDismiss: () -> Unit = {
        if (segments.size > 1) onSegmentClick(segments.size - 2) else onDismiss()
    }
    Dialog(
        onDismissRequest = handleBackOrDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = DarkBackground) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text("Select folder in $accountName", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = TextPrimary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkCard)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    segments.forEachIndexed { index, segment ->
                        if (index > 0) {
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            text = segment,
                            color = if (index == segments.lastIndex) TealPrimary else TextSecondary,
                            fontWeight = if (index == segments.lastIndex) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .clickable { onSegmentClick(index) }
                                .padding(horizontal = 4.dp)
                        )
                    }
                }
                HorizontalDivider(color = Color(0xFF2E2E2E))

                Box(modifier = Modifier.weight(1f)) {
                    if (isLoading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter), color = TealPrimary)
                    }
                    if (!isLoading && folders.isEmpty()) {
                        Text(
                            text = "No subfolders here",
                            color = TextSecondary,
                            fontSize = 14.sp,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(folders, key = { it.id }) { folder ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onFolderClick(folder) }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Folder, contentDescription = null, tint = TealPrimary, modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(text = folder.name, color = TextPrimary, fontSize = 15.sp)
                            }
                        }
                    }
                }

                Surface(color = DarkCard, tonalElevation = 8.dp, modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = onConfirm,
                        colors = ButtonDefaults.buttonColors(containerColor = TealPrimary, contentColor = Color.Black),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text("SELECT THIS FOLDER", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun CloudDownloadProgressDialog(
    progress: CloudTransferProgress,
    onCancel: (() -> Unit)? = null
) {
    val isUpload = progress.isUpload
    val operationLabel = progress.operationLabel
    AlertDialog(
        onDismissRequest = { /* Modal: keep visible during transfer */ },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when {
                        operationLabel != null -> if (isUpload) Icons.Default.FolderZip else Icons.Default.Unarchive
                        isUpload -> Icons.Default.CloudUpload
                        else -> Icons.Default.CloudDownload
                    },
                    contentDescription = null,
                    tint = TealPrimary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                val titleText = if (operationLabel != null) {
                    if (progress.totalFiles > 1) "$operationLabel (${progress.currentIndex}/${progress.totalFiles})" else "$operationLabel..."
                } else if (isUpload) {
                    if (progress.totalFiles > 1) "Uploading (${progress.currentIndex}/${progress.totalFiles})" else "Uploading to Cloud"
                } else {
                    if (progress.totalFiles > 1) "Downloading (${progress.currentIndex}/${progress.totalFiles})" else "Downloading from Cloud"
                }
                Text(
                    text = titleText,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(
                    text = progress.currentFileName.ifEmpty {
                        operationLabel?.let { "Preparing to ${it.lowercase()}..." }
                            ?: if (isUpload) "Preparing upload..." else "Preparing download..."
                    },
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(14.dp))
                if (progress.isIndeterminate || progress.totalBytes <= 0) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = TealPrimary,
                        trackColor = Color(0xFF2C2C2C)
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { progress.progressFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = TealPrimary,
                        trackColor = Color(0xFF2C2C2C)
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = progress.formattedProgressText,
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                    if (progress.totalFiles > 1) {
                        Text(
                            text = "${progress.currentIndex} / ${progress.totalFiles}",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (onCancel != null) {
                TextButton(
                    onClick = onCancel,
                    colors = ButtonDefaults.textButtonColors(contentColor = TealPrimary)
                ) {
                    Text("CANCEL", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        },
        dismissButton = {},
        containerColor = DarkCard
    )
}
