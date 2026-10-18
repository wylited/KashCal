package org.onekash.kashcal.sync.contacts

import android.accounts.Account
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.OperationApplicationException
import android.database.Cursor
import android.net.Uri
import android.os.RemoteException
import android.provider.ContactsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.onekash.kashcal.sync.adapter.KashCalContactsAuthenticator
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user-facing (in-app browser) write boundary for the Contacts Provider; the sync-side
 * writes live on [AndroidContactsProviderRepository]. Both exist because
 * `ContactsProviderWriteBoundaryTest` fences every Contacts Provider write inside
 * `sync/contacts/`: a write from the UI or data layer would bypass the per-account scoping
 * rule and couple contact sync to those layers.
 *
 * Unlike the sync writes, these are user edits, so they deliberately do NOT run in
 * sync-adapter mode: a plain write lets the provider set `DIRTY`/`DELETED`, which is exactly
 * what [ContactPushStrategy] reads as the pending set. An edit or delete made here therefore
 * CardDAV-pushes to the server with no extra wiring.
 *
 * Every operation resolves the aggregate [ContactsContract.Contacts] id from a lookup key and
 * scopes its statements to that contact's RawContacts, so an edit never lands on a sibling
 * contact. New Data rows go on the raw contact [preferredRawContactId] picks (a KashCal
 * contacts-account row when one exists, so the edit syncs through our own CardDAV push, else
 * the first row). Unless noted, methods don't throw: a permission denial or provider error
 * returns `Result.failure` carrying a [ContactWriteException], mirroring the sync layer's
 * shape.
 */
@Singleton
class ContactsBrowserWriteRepository @Inject constructor(
    private val contentResolver: ContentResolver
) {

    companion object {
        private const val TAG = "ContactsBrowserWriteRepo"
    }

    /**
     * Applies a caller-built batch against the contacts authority. Exposed for callers that
     * compose several write shapes this class doesn't model; the op list must already be
     * scoped to the contacts the caller means to touch.
     */
    suspend fun apply(ops: ArrayList<ContentProviderOperation>): Result<Unit> = withContext(Dispatchers.IO) {
        if (ops.isEmpty()) return@withContext Result.success(Unit)
        try {
            contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            Result.success(Unit)
        } catch (e: SecurityException) {
            Log.w(TAG, "WRITE_CONTACTS revoked; contact edit skipped", e)
            Result.failure(ContactWriteException(ContactWriteFailure.PERMISSION_DENIED))
        } catch (e: OperationApplicationException) {
            Log.w(TAG, "Contact edit batch failed to apply", e)
            Result.failure(ContactWriteException(ContactWriteFailure.PROVIDER_ERROR))
        } catch (e: RemoteException) {
            Log.w(TAG, "Contacts Provider unavailable during contact edit", e)
            Result.failure(ContactWriteException(ContactWriteFailure.PROVIDER_ERROR))
        } catch (e: Exception) {
            Log.w(TAG, "Contact edit batch failed unexpectedly", e)
            Result.failure(ContactWriteException(ContactWriteFailure.PROVIDER_ERROR))
        }
    }

    /**
     * Creates a contact named [displayName] as a RawContact under [account] (a KashCal
     * contacts system account; null fails, and the UI hides the create action before this is
     * ever reached). The provider marks the row `DIRTY`, so the next push creates the server
     * resource and stamps its href (see [ContactsProviderRepository.markNewContactUploaded]).
     */
    suspend fun createContact(displayName: String, account: Account?): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (account == null) {
                Log.w(TAG, "Create contact refused: no contacts system account")
                return@withContext Result.failure(
                    ContactWriteException(ContactWriteFailure.PROVIDER_ERROR)
                )
            }

            val ops = arrayListOf<ContentProviderOperation>(
                ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, account.name)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, account.type)
                    .build(),
                ContentProviderOperation
                    .newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
                    )
                    .withValue(
                        ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
                        displayName.trim()
                    )
                    .build(),
            )
            apply(ops)
        }

    /**
     * Writes the birthday ([isBirthday]) or anniversary row of the contact at [lookupKey] in
     * one batch: deletes every row of the type and, when [date] is non-null, inserts a fresh
     * one on the preferred raw contact, so a multi-row contact collapses to a single date.
     * A null [date] is the clear path.
     *
     * The row value is `START_DATE` in `yyyy-MM-dd` (the format the platform's own editors
     * write for dated events; year-less `--MM-DD` input is parsed on read by
     * [org.onekash.kashcal.data.contacts.ContactEventUtils.parseContactDate]).
     */
    suspend fun setContactEventDate(
        lookupKey: String,
        isBirthday: Boolean,
        date: LocalDate?,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val contactId = resolveContactId(lookupKey)
                ?: return@withContext Result.failure(
                    ContactWriteException(ContactWriteFailure.PROVIDER_ERROR)
                )
            val eventType = if (isBirthday) {
                ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY
            } else {
                ContactsContract.CommonDataKinds.Event.TYPE_ANNIVERSARY
            }
            val eventMimetype = ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE
            val existingIds = queryDataRowIds(
                contactId,
                "${ContactsContract.Data.MIMETYPE} = ? AND " +
                    "${ContactsContract.CommonDataKinds.Event.TYPE} = ?",
                arrayOf(eventMimetype, eventType.toString())
            )

            val ops = arrayListOf<ContentProviderOperation>()
            for (id in existingIds) {
                ops.add(
                    ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                        .withSelection("${ContactsContract.Data._ID} = ?", arrayOf(id.toString()))
                        .build()
                )
            }
            if (date != null) {
                val rawContactId = preferredRawContactId(contactId)
                    ?: return@withContext Result.failure(
                        ContactWriteException(ContactWriteFailure.PROVIDER_ERROR)
                    )
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                        .withValue(ContactsContract.Data.MIMETYPE, eventMimetype)
                        .withValue(
                            ContactsContract.CommonDataKinds.Event.TYPE,
                            eventType
                        )
                        .withValue(
                            ContactsContract.CommonDataKinds.Event.START_DATE,
                            date.format(DateTimeFormatter.ISO_LOCAL_DATE)
                        )
                        .build()
                )
            }
            apply(ops)
        } catch (e: Exception) {
            Log.w(TAG, "Setting contact date failed", e)
            Result.failure(ContactWriteException(ContactWriteFailure.PROVIDER_ERROR))
        }
    }

    /**
     * Edits the browser's supported fields of the contact at [lookupKey] in one batch: display
     * name, organization/company and job title, first phone, first email and note. A blank
     * phone, email or note deletes that row; a blank display name keeps the current name (a
     * contact without a name is unreadable in every list). [jobTitle] is written alongside the
     * company so the caller can carry the current title through unchanged.
     */
    suspend fun updateContactBasics(
        lookupKey: String,
        displayName: String,
        organization: String?,
        jobTitle: String?,
        phone: String?,
        email: String?,
        note: String?,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val contactId = resolveContactId(lookupKey)
                ?: return@withContext Result.failure(
                    ContactWriteException(ContactWriteFailure.PROVIDER_ERROR)
                )
            val rows = queryEditableRows(contactId)
            val rawContactId = preferredRawContactId(contactId)
                ?: return@withContext Result.failure(
                    ContactWriteException(ContactWriteFailure.PROVIDER_ERROR)
                )

            val ops = arrayListOf<ContentProviderOperation>()
            editStructuredName(ops, rows, rawContactId, displayName.trim())
            editOrganization(ops, rows, rawContactId, organization, jobTitle)
            editSingleDataRow(
                ops, rows, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Phone.NUMBER, phone?.trim(),
                rawContactId,
            ) {
                it.withValue(
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                )
            }
            editSingleDataRow(
                ops, rows, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Email.ADDRESS, email?.trim(),
                rawContactId,
            ) {
                it.withValue(
                    ContactsContract.CommonDataKinds.Email.TYPE,
                    ContactsContract.CommonDataKinds.Email.TYPE_OTHER
                )
            }
            editSingleDataRow(
                ops, rows, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Note.NOTE, note?.trim(),
                rawContactId,
            )
            apply(ops)
        } catch (e: Exception) {
            Log.w(TAG, "Contact edit failed", e)
            Result.failure(ContactWriteException(ContactWriteFailure.PROVIDER_ERROR))
        }
    }

    /**
     * Deletes the contact at [lookupKey] by deleting its RawContacts with a plain (non-sync
     * adapter) delete, which tombstones synced rows for [ContactPushStrategy] to push and
     * hard-deletes local-only ones.
     */
    suspend fun deleteContact(lookupKey: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val contactId = resolveContactId(lookupKey)
                ?: return@withContext Result.failure(
                    ContactWriteException(ContactWriteFailure.PROVIDER_ERROR)
                )
            contentResolver.delete(
                ContactsContract.RawContacts.CONTENT_URI,
                "${ContactsContract.RawContacts.CONTACT_ID} = ?",
                arrayOf(contactId.toString())
            )
            Result.success(Unit)
        } catch (e: SecurityException) {
            Log.w(TAG, "WRITE_CONTACTS revoked; contact delete skipped", e)
            Result.failure(ContactWriteException(ContactWriteFailure.PERMISSION_DENIED))
        } catch (e: Exception) {
            Log.w(TAG, "Contact delete failed", e)
            Result.failure(ContactWriteException(ContactWriteFailure.PROVIDER_ERROR))
        }
    }

    // ==================== Resolution helpers ====================

    /** Returns the aggregate contact id for [lookupKey], or null when it no longer resolves. */
    private fun resolveContactId(lookupKey: String): Long? =
        contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID),
            "${ContactsContract.Contacts.LOOKUP_KEY} = ?",
            arrayOf(lookupKey),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }

    /**
     * Returns the raw contact the next inserted Data row should live on: a row under the
     * KashCal contacts account type, so user edits sync through our CardDAV push, else the
     * lowest-id raw contact of the aggregate. Null when the contact has no raw contacts.
     */
    private fun preferredRawContactId(contactId: Long): Long? {
        val raws = queryRawContacts(contactId)
        return raws.firstOrNull {
            it.second == KashCalContactsAuthenticator.ACCOUNT_TYPE
        }?.first ?: raws.minByOrNull { it.first }?.first
    }

    /** Lists (id, account type) for the live raw contacts of [contactId], lowest id first. */
    private fun queryRawContacts(contactId: Long): List<Pair<Long, String>> {
        val ids = mutableListOf<Pair<Long, String>>()
        query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(
                ContactsContract.RawContacts._ID,
                ContactsContract.RawContacts.ACCOUNT_TYPE
            ),
            "${ContactsContract.RawContacts.CONTACT_ID} = ? AND " +
                "${ContactsContract.RawContacts.DELETED} = 0",
            arrayOf(contactId.toString())
        ) { cursor ->
            ids.add(cursor.getLong(0) to cursor.getString(1).orEmpty())
        }
        return ids
    }

    /** Returns the `_ID`s of the contact's Data rows matching [selection]/[args]. */
    private fun queryDataRowIds(contactId: Long, selection: String, args: Array<String>): List<Long> {
        val ids = mutableListOf<Long>()
        query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data._ID),
            "${ContactsContract.Data.CONTACT_ID} = ? AND $selection",
            arrayOf(contactId.toString()) + args
        ) { cursor ->
            ids.add(cursor.getLong(0))
        }
        return ids
    }

    /** The `_ID`, mimetype and DATA1-DATA4 of one row per editable mimetype of the contact. */
    private data class EditableRow(
        val id: Long,
        val mimetype: String,
        val data1: String?,
        val data2: String?,
        val data3: String?,
        val data4: String?,
    )

    private fun queryEditableRows(contactId: Long): Map<String, EditableRow> {
        val wanted = listOf(
            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE,
        )
        val placeholders = wanted.joinToString(",") { "?" }
        val rows = mutableMapOf<String, EditableRow>()
        query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data._ID,
                ContactsContract.Data.MIMETYPE,
                ContactsContract.Data.DATA1,
                ContactsContract.Data.DATA2,
                ContactsContract.Data.DATA3,
                ContactsContract.Data.DATA4,
            ),
            "${ContactsContract.Data.CONTACT_ID} = ? AND " +
                "${ContactsContract.Data.MIMETYPE} IN ($placeholders)",
            arrayOf(contactId.toString()) + wanted.toTypedArray()
        ) { cursor ->
            val mimetype = cursor.getString(1) ?: return@query
            // First row of each mimetype wins, matching the "first phone / first email" model.
            if (rows.containsKey(mimetype)) return@query
            rows[mimetype] = EditableRow(
                id = cursor.getLong(0),
                mimetype = mimetype,
                data1 = cursor.getString(2),
                data2 = cursor.getString(3),
                data3 = cursor.getString(4),
                data4 = cursor.getString(5),
            )
        }
        return rows
    }

    /** Runs [uri] and hands each row to [read]; exceptions propagate to the caller's guard. */
    private inline fun query(
        uri: Uri,
        projection: Array<String>,
        selection: String,
        args: Array<String>,
        read: (Cursor) -> Unit,
    ) {
        contentResolver.query(uri, projection, selection, args, null)?.use { cursor ->
            while (cursor.moveToNext()) read(cursor)
        }
    }

    // ==================== Batch builders ====================

    /** Updates the display name on the existing name row; a blank name is a no-op. */
    private fun editStructuredName(
        ops: MutableList<ContentProviderOperation>,
        rows: Map<String, EditableRow>,
        rawContactId: Long,
        displayName: String,
    ) {
        if (displayName.isBlank()) return
        val row = rows[ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE]
        val builder = if (row != null) {
            ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                .withSelection("${ContactsContract.Data._ID} = ?", arrayOf(row.id.toString()))
        } else {
            // No name row (shouldn't happen for a listed contact); insert one so the edit
            // isn't silently dropped.
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                .withValue(ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
        }
        ops.add(
            builder.withValue(
                ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
                displayName
            ).build()
        )
    }

    /**
     * Upserts the organization row with [organization] (company) and [jobTitle] (title); both
     * blank deletes the row.
     */
    private fun editOrganization(
        ops: MutableList<ContentProviderOperation>,
        rows: Map<String, EditableRow>,
        rawContactId: Long,
        organization: String?,
        jobTitle: String?,
    ) {
        val company = organization.orEmpty().trim()
        val title = jobTitle.orEmpty().trim()
        val row = rows[ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE]
        if (row == null) {
            if (company.isEmpty() && title.isEmpty()) return
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                    .withValue(ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, company)
                    .withValue(ContactsContract.CommonDataKinds.Organization.TITLE, title)
                    .build()
            )
            return
        }
        if (company.isEmpty() && title.isEmpty()) {
            ops.add(
                ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                    .withSelection("${ContactsContract.Data._ID} = ?", arrayOf(row.id.toString()))
                    .build()
            )
            return
        }
        ops.add(
            ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                .withSelection("${ContactsContract.Data._ID} = ?", arrayOf(row.id.toString()))
                .withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, company)
                .withValue(ContactsContract.CommonDataKinds.Organization.TITLE, title)
                .build()
        )
    }

    /**
     * Upserts the first row of [mimetype]: [value] blank deletes the row, non-blank updates
     * the [column] in place (keeping type/label), and no row inserts one on [rawContactId]
     * with [extraInsertValues] applied. The note passes [extraInsertValues] as null.
     */
    private fun editSingleDataRow(
        ops: MutableList<ContentProviderOperation>,
        rows: Map<String, EditableRow>,
        mimetype: String,
        column: String,
        value: String?,
        rawContactId: Long,
        extraInsertValues: (ContentProviderOperation.Builder) -> ContentProviderOperation.Builder = { it },
    ) {
        val row = rows[mimetype]
        if (value.isNullOrEmpty()) {
            if (row != null) {
                ops.add(
                    ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                        .withSelection("${ContactsContract.Data._ID} = ?",
                            arrayOf(row.id.toString()))
                        .build()
                )
            }
            return
        }
        val builder = if (row != null) {
            ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                .withSelection("${ContactsContract.Data._ID} = ?", arrayOf(row.id.toString()))
        } else {
            extraInsertValues(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                    .withValue(ContactsContract.Data.MIMETYPE, mimetype)
            )
        }
        ops.add(builder.withValue(column, value).build())
    }
}
