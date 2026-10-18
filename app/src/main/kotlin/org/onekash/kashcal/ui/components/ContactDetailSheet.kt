package org.onekash.kashcal.ui.components

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Note
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.onekash.kashcal.R
import org.onekash.kashcal.data.contacts.ContactDetail
import org.onekash.kashcal.ui.components.pickers.InlineDatePickerContent
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

/** Which date row's picker is open. */
private enum class ActiveDatePick { BIRTHDAY, ANNIVERSARY }

/**
 * The letter avatar the browser uses everywhere: a colored circle with the name's first
 * letter. No Coil in the app, so synced photos render only through the system UI; this is
 * the deliberately dependency-free stand-in.
 */
@Composable
fun ContactAvatar(name: String, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = name.trim().firstOrNull()?.uppercase() ?: "#",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

/**
 * Shows a contact's details full screen, in the [org.onekash.kashcal.ui.components.EventFormSheet]
 * Dialog shell, with call/email/map actions, editable birthday and anniversary rows and
 * Edit/Delete actions. Hosted from the contacts screen and, for a birthday event's "View
 * contact" jump, from the activity.
 *
 * The sheet is presentational: it holds only its own dialog state (the editor, the date
 * picker, the two-tap delete's armed flag arrives via [isDeleteConfirmArmed] because the
 * view model owns the guard across hosts). Every write goes through a callback.
 *
 * @param detail the loaded contact; null shows a spinner ([isLoading]) or a placeholder when
 *   the lookup no longer resolves.
 * @param onSaveEventDate writes the picked birthday/anniversary; null clears the row.
 * @param onSaveEdits writes the minimal editor's fields; the job title rides through so the
 *   editor (which doesn't show it) can't erase it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetailSheet(
    detail: ContactDetail?,
    isLoading: Boolean,
    isDeleteConfirmArmed: Boolean,
    onDismiss: () -> Unit,
    onArmDeleteConfirm: () -> Unit,
    onDisarmDeleteConfirm: () -> Unit,
    onDelete: () -> Unit,
    onSaveEventDate: (isBirthday: Boolean, date: LocalDate?) -> Unit,
    onSaveEdits: (
        displayName: String,
        organization: String?,
        jobTitle: String?,
        phone: String?,
        email: String?,
        note: String?,
    ) -> Unit,
) {
    val context = LocalContext.current
    var isEditing by remember { mutableStateOf(false) }
    var activeDatePick by remember { mutableStateOf<ActiveDatePick?>(null) }

    // Back goes through the content, like the event form: close the editor first, else the
    // sheet. The Dialog's own back dismissal is off so this owns the order.
    BackHandler {
        when {
            isEditing -> isEditing = false
            activeDatePick != null -> activeDatePick = null
            else -> onDismiss()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = false,
        )
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_cancel)
                        )
                    }
                    Text(
                        text = detail?.displayName?.takeIf(String::isNotBlank)
                            ?: stringResource(R.string.contact_untitled),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }

                when {
                    isEditing && detail != null -> ContactEditForm(
                        detail = detail,
                        onCancel = { isEditing = false },
                        onSave = { name, organization, phone, email, note ->
                            isEditing = false
                            onSaveEdits(name, organization, detail.jobTitle, phone, email, note)
                        }
                    )
                    detail == null -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator()
                        } else {
                            Text(stringResource(R.string.contacts_empty))
                        }
                    }
                    else -> ContactDetailContent(
                        detail = detail,
                        isDeleteConfirmArmed = isDeleteConfirmArmed,
                        onArmDeleteConfirm = onArmDeleteConfirm,
                        onDisarmDeleteConfirm = onDisarmDeleteConfirm,
                        onDelete = onDelete,
                        onEdit = { isEditing = true },
                        onEditDate = { activeDatePick = it },
                        onCall = { number ->
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}"))
                                )
                            }
                        },
                        onEmail = { address ->
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(address)}"))
                                )
                            }
                        },
                        onMap = { address ->
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("geo:0,0?q=${Uri.encode(address)}")
                                    )
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    activeDatePick?.let { pick ->
        val zone = ZoneId.systemDefault()
        val initialDate = when (pick) {
            ActiveDatePick.BIRTHDAY -> detail?.birthday
            ActiveDatePick.ANNIVERSARY -> detail?.anniversary
        } ?: LocalDate.now()
        var displayedMonth by remember(pick) {
            mutableStateOf(Calendar.getInstance().apply {
                timeInMillis = initialDate.atStartOfDay(zone).toInstant().toEpochMilli()
            })
        }
        ModalBottomSheet(
            onDismissRequest = { activeDatePick = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    text = stringResource(
                        if (pick == ActiveDatePick.BIRTHDAY) {
                            R.string.contact_edit_birthday
                        } else {
                            R.string.contact_edit_anniversary
                        }
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                InlineDatePickerContent(
                    selectedDateMillis = initialDate.atStartOfDay(zone).toInstant().toEpochMilli(),
                    displayedMonth = displayedMonth,
                    onDateSelect = { dateMillis ->
                        val picked = java.time.Instant.ofEpochMilli(dateMillis)
                            .atZone(zone)
                            .toLocalDate()
                        onSaveEventDate(pick == ActiveDatePick.BIRTHDAY, picked)
                        activeDatePick = null
                    },
                    onMonthChange = { displayedMonth = it },
                )
            }
        }
    }
}

/** The read-only detail body: header, sections and the Edit/Delete action row. */
@Composable
private fun ContactDetailContent(
    detail: ContactDetail,
    isDeleteConfirmArmed: Boolean,
    onArmDeleteConfirm: () -> Unit,
    onDisarmDeleteConfirm: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onEditDate: (ActiveDatePick) -> Unit,
    onCall: (String) -> Unit,
    onEmail: (String) -> Unit,
    onMap: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ContactAvatar(name = detail.displayName, size = 56.dp)
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = detail.displayName.takeIf(String::isNotBlank)
                        ?: stringResource(R.string.contact_untitled),
                    style = MaterialTheme.typography.titleMedium
                )
                val orgLine = listOfNotNull(
                    detail.organization?.takeIf(String::isNotBlank),
                    detail.jobTitle?.takeIf(String::isNotBlank)
                ).joinToString(" · ")
                if (orgLine.isNotBlank()) {
                    Text(
                        text = orgLine,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (detail.phones.isNotEmpty()) {
            SectionHeader(stringResource(R.string.contact_section_phones))
            detail.phones.forEach { phone ->
                ActionRow(
                    icon = { Icon(Icons.Default.Call, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    label = phone.number,
                    supporting = phone.typeLabel,
                    onClick = { onCall(phone.number) }
                )
            }
        }

        if (detail.emails.isNotEmpty()) {
            SectionHeader(stringResource(R.string.contact_section_emails))
            detail.emails.forEach { email ->
                ActionRow(
                    icon = { Icon(Icons.Default.Email, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    label = email.address,
                    supporting = email.typeLabel,
                    onClick = { onEmail(email.address) }
                )
            }
        }

        if (detail.addresses.isNotEmpty()) {
            SectionHeader(stringResource(R.string.contact_section_addresses))
            detail.addresses.forEach { address ->
                ActionRow(
                    icon = { Icon(Icons.Default.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    label = address.formatted,
                    supporting = null,
                    onClick = { onMap(address.formatted) }
                )
            }
        }

        SectionHeader(stringResource(R.string.contact_section_events))
        DateRow(
            icon = { Icon(Icons.Default.Cake, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            // Month and day only: a year-less provider date reads as the current year, so a
            // year here would often be wrong.
            label = detail.birthday?.format(DateTimeFormatter.ofPattern("MMMM d", Locale.getDefault())),
            onEdit = { onEditDate(ActiveDatePick.BIRTHDAY) }
        )
        DateRow(
            icon = { Icon(Icons.Default.Favorite, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            label = detail.anniversary?.format(DateTimeFormatter.ofPattern("MMMM d", Locale.getDefault())),
            onEdit = { onEditDate(ActiveDatePick.ANNIVERSARY) }
        )

        if (detail.note != null) {
            SectionHeader(stringResource(R.string.contact_section_notes))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Icon(
                    Icons.Default.Note,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = detail.note.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!isDeleteConfirmArmed) {
                FilledTonalButton(onClick = onEdit, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.contact_edit))
                }
                FilledTonalButton(
                    onClick = onArmDeleteConfirm,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.contact_delete))
                }
            } else {
                FilledTonalButton(
                    onClick = onDisarmDeleteConfirm,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        stringResource(R.string.action_cancel),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
                FilledTonalButton(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text(
                        stringResource(R.string.contact_delete_confirm),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/** The minimal edit form: name, organization, first phone, first email and note. */
@Composable
private fun ContactEditForm(
    detail: ContactDetail,
    onCancel: () -> Unit,
    onSave: (
        displayName: String,
        organization: String?,
        phone: String?,
        email: String?,
        note: String?,
    ) -> Unit,
) {
    var name by remember { mutableStateOf(detail.displayName) }
    var organization by remember { mutableStateOf(detail.organization.orEmpty()) }
    var phone by remember { mutableStateOf(detail.phones.firstOrNull()?.number.orEmpty()) }
    var email by remember { mutableStateOf(detail.emails.firstOrNull()?.address.orEmpty()) }
    var note by remember { mutableStateOf(detail.note.orEmpty()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.label_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = organization,
            onValueChange = { organization = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.action_cancel))
            }
            Button(
                onClick = {
                    onSave(
                        name,
                        organization.trim().takeIf(String::isNotBlank),
                        phone.trim().takeIf(String::isNotBlank),
                        email.trim().takeIf(String::isNotBlank),
                        note.trim().takeIf(String::isNotBlank),
                    )
                },
                enabled = name.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.action_save))
            }
        }
    }
}

/** A section caption between the detail blocks. */
@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp)
    )
}

/** A tappable list row with a leading icon, a primary label and an optional type label. */
@Composable
private fun ActionRow(
    icon: @Composable () -> Unit,
    label: String,
    supporting: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** A birthday/anniversary row: value or an em dash when unset, with an edit action. */
@Composable
private fun DateRow(
    icon: @Composable () -> Unit,
    label: String?,
    onEdit: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label ?: "—",
            style = MaterialTheme.typography.bodyLarge,
            color = if (label == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onEdit) {
            Text(stringResource(R.string.contact_edit))
        }
    }
}
