package org.onekash.kashcal.ui.screens.contacts

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.onekash.kashcal.R
import org.onekash.kashcal.data.contacts.ContactListItem
import org.onekash.kashcal.data.contacts.daysUntilNextOccurrence
import org.onekash.kashcal.data.contacts.sectionBucketFor
import org.onekash.kashcal.ui.components.ContactAvatar
import org.onekash.kashcal.ui.components.ContactDetailSheet
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A birthday chip shows when the next occurrence is this many days out or fewer. */
private const val BIRTHDAY_WINDOW_DAYS = 30

/**
 * The in-app contacts browser: a search field over the synced contacts, an A–Z sectioned
 * list with an upcoming-birthday chip, and the create and detail surfaces. Shown in place of
 * the home content while the view mode is CONTACTS; back returns to the previous view.
 *
 * Read access is permission-gated: until READ_CONTACTS is granted the list body is the
 * rationale with a grant button (the write permission is requested alongside it, since the
 * detail sheet edits). The list reloads on entry and after every write.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ContactsScreen(
    onBack: () -> Unit,
    viewModel: ContactsViewModel = hiltViewModel(),
) {
    BackHandler(onBack = onBack)

    val permissionGranted by viewModel.permissionGranted.collectAsStateWithLifecycle()
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val selectedLookupKey by viewModel.selectedLookupKey.collectAsStateWithLifecycle()
    val selectedDetail by viewModel.selectedDetail.collectAsStateWithLifecycle()
    val isLoadingDetail by viewModel.isLoadingDetail.collectAsStateWithLifecycle()
    val isDeleteConfirmArmed by viewModel.isDeleteConfirmArmed.collectAsStateWithLifecycle()
    val contactSyncAccount by viewModel.contactSyncAccount.collectAsStateWithLifecycle()

    // Reload on entry; re-samples the permission too, so a grant made in system settings
    // while the app was alive shows without a restart.
    LaunchedEffect(Unit) { viewModel.reload() }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.refreshPermission() }
    val requestContactsPermissions = {
        permissionLauncher.launch(
            arrayOf(
                android.Manifest.permission.READ_CONTACTS,
                android.Manifest.permission.WRITE_CONTACTS,
            )
        )
    }

    var showCreateDialog by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.view_contacts)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_cancel)
                        )
                    }
                },
                actions = {
                    if (contactSyncAccount != null) {
                        IconButton(onClick = { showCreateDialog = true }) {
                            Icon(
                                Icons.Default.PersonAdd,
                                contentDescription = stringResource(R.string.contact_new)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (!permissionGranted) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.contacts_permission_rationale),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = requestContactsPermissions) {
                            Text(stringResource(R.string.action_allow))
                        }
                    }
                }
            } else {
                var query by rememberSaveable { mutableStateOf("") }
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        viewModel.setQuery(it)
                    },
                    placeholder = { Text(stringResource(R.string.contacts_search_hint)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )

                when {
                    isLoading && contacts.isEmpty() -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }
                    contacts.isEmpty() -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.contacts_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    else -> ContactList(
                        contacts = contacts,
                        onOpen = viewModel::openContact,
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateContactDialog(
            onCreate = { name ->
                showCreateDialog = false
                viewModel.createContact(name)
            },
            onDismiss = { showCreateDialog = false }
        )
    }

    selectedLookupKey?.let {
        ContactDetailSheet(
            detail = selectedDetail,
            isLoading = isLoadingDetail,
            isDeleteConfirmArmed = isDeleteConfirmArmed,
            onDismiss = viewModel::closeContact,
            onArmDeleteConfirm = viewModel::armDeleteConfirm,
            onDisarmDeleteConfirm = viewModel::disarmDeleteConfirm,
            onDelete = viewModel::deleteSelectedContact,
            onSaveEventDate = { isBirthday, date ->
                viewModel.saveEventDate(isBirthday, date)
            },
            onSaveEdits = { name, organization, jobTitle, phone, email, note ->
                viewModel.saveContactEdits(name, organization, jobTitle, phone, email, note)
            },
        )
    }
}

/** The A–Z sectioned list; the "#" bucket (blank or non-letter start) sorts last. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactList(
    contacts: List<ContactListItem>,
    onOpen: (String) -> Unit,
) {
    val sections = remember(contacts) {
        val grouped = contacts.groupBy { sectionBucketFor(it.displayName) }
        grouped.keys.sortedWith(
            Comparator { a, b ->
                when {
                    a == "#" -> 1
                    b == "#" -> -1
                    else -> a.compareTo(b)
                }
            }
        ).map { it to grouped.getValue(it) }
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        sections.forEach { (bucket, rows) ->
            stickyHeader(key = "header_$bucket") {
                Text(
                    text = bucket,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
            items(rows, key = { it.lookupKey }) { contact ->
                ContactRow(contact = contact, onOpen = onOpen)
            }
        }
    }
}

@Composable
private fun ContactRow(
    contact: ContactListItem,
    onOpen: (String) -> Unit,
) {
    // The chip is display-only: names stay A–Z sorted, an upcoming birthday just surfaces.
    val birthdayChipDays = contact.birthday
        ?.let { daysUntilNextOccurrence(it, LocalDate.now()) }
        ?.takeIf { it in 0..BIRTHDAY_WINDOW_DAYS }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(contact.lookupKey) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ContactAvatar(name = contact.displayName, size = 40.dp)
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = contact.displayName.takeIf(String::isNotBlank)
                ?: stringResource(R.string.contact_untitled),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (birthdayChipDays != null && contact.birthday != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer
            ) {
                Text(
                    text = stringResource(
                        R.string.contact_birthday_chip,
                        contact.birthday.format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()))
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

/** The minimal create dialog: just a name; everything else is edited on the detail. */
@Composable
private fun CreateContactDialog(
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.contact_new)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.label_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name) },
                enabled = name.isNotBlank()
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
