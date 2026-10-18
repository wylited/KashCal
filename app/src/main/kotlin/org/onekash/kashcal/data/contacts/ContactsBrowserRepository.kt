package org.onekash.kashcal.data.contacts

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.onekash.kashcal.sync.contacts.ContactsBrowserWriteRepository
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One row of the contacts browser list.
 *
 * @param birthday the contact's birthday placed at the current year when the stored date has
 *   no year; only the month and day carry meaning in that case.
 * @param anniversary same convention as [birthday].
 */
data class ContactListItem(
    val lookupKey: String,
    val displayName: String,
    val photoUri: String?,
    val birthday: LocalDate?,
    val anniversary: LocalDate?,
)

/** A phone number with its localized type label, e.g. "Mobile" / "+1 555 0100". */
data class LabeledPhone(val typeLabel: String, val number: String)

/** An email address with its localized type label. */
data class LabeledEmail(val typeLabel: String, val address: String)

/** A postal address as the provider's preformatted string. */
data class LabeledAddress(val formatted: String)

/** Everything the contact detail sheet shows for one contact. */
data class ContactDetail(
    val lookupKey: String,
    val displayName: String,
    val photoUri: String?,
    val organization: String?,
    val jobTitle: String?,
    val phones: List<LabeledPhone>,
    val emails: List<LabeledEmail>,
    val addresses: List<LabeledAddress>,
    val note: String?,
    val birthday: LocalDate?,
    val anniversary: LocalDate?,
)

/**
 * Reads the device's aggregated contacts for the in-app browser, and routes its writes to
 * [ContactsBrowserWriteRepository], which is where Contacts Provider writes live (see the
 * boundary test's KDoc). Reads run on Dispatchers.IO and return empty (never throw) when the
 * provider is unavailable or READ_CONTACTS is revoked, mirroring the sync layer's read shape.
 *
 * Dates: birthday and anniversary rows are parsed with [ContactEventUtils.parseContactDate]
 * (the same parser the birthday calendar syncs from), so every platform format, year-less
 * `--MM-DD` included, reads the same everywhere in the app.
 */
@Singleton
class ContactsBrowserRepository @Inject constructor(
    private val contentResolver: ContentResolver,
    @ApplicationContext private val context: Context,
    private val writeRepository: ContactsBrowserWriteRepository,
) {

    companion object {
        private const val TAG = "ContactsBrowserRepo"

        /** IN-list chunk for filtered contact ids, under SQLite's bound-variable limit. */
        private const val MAX_IDS_PER_QUERY = 500
    }

    /**
     * Emits the contact list, sorted by display name (case-insensitive, blank names last);
     * the view model re-collects it on query, permission and reload changes.
     *
     * A blank [query] lists every contact. A non-blank one filters on the Contacts table's
     * display name and, because names rarely contain digits, on phone numbers too, so the
     * user can search by the number they dial.
     */
    fun observeContacts(query: String?): Flow<List<ContactListItem>> = flow {
        emit(readContacts(query))
    }.flowOn(Dispatchers.IO)

    /** Resolves [lookupKey] to the contact's detail, or null when it no longer resolves. */
    suspend fun getContact(lookupKey: String): ContactDetail? = withContext(Dispatchers.IO) {
        try {
            readContactDetail(lookupKey)
        } catch (e: SecurityException) {
            Log.w(TAG, "READ_CONTACTS revoked; contact detail unavailable", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "Contact detail query failed", e)
            null
        }
    }

    // ==================== Writes (delegated to the sync-layer boundary) ====================

    /**
     * Writes the birthday or anniversary date of the contact at [lookupKey]; null deletes it.
     * See [ContactsBrowserWriteRepository.setContactEventDate] for the row semantics.
     */
    suspend fun setContactEventDate(
        lookupKey: String,
        isBirthday: Boolean,
        date: LocalDate?,
    ): Result<Unit> = writeRepository.setContactEventDate(lookupKey, isBirthday, date)

    /**
     * Edits the browser's supported fields of the contact at [lookupKey] in one batch; a blank
     * phone/email/note deletes that row and a blank name keeps the current one.
     */
    suspend fun updateContactBasics(
        lookupKey: String,
        displayName: String,
        organization: String?,
        jobTitle: String?,
        phone: String?,
        email: String?,
        note: String?,
    ): Result<Unit> = writeRepository.updateContactBasics(
        lookupKey, displayName, organization, jobTitle, phone, email, note
    )

    /** Deletes the contact at [lookupKey]; its CardDAV resource is pushed as a delete. */
    suspend fun deleteContact(lookupKey: String): Result<Unit> =
        writeRepository.deleteContact(lookupKey)

    /**
     * Creates a contact named [displayName] under [account] (a KashCal contacts system
     * account; null fails and the UI hides the create action first).
     */
    suspend fun createContact(
        displayName: String,
        account: android.accounts.Account?,
    ): Result<Unit> = writeRepository.createContact(displayName, account)

    // ==================== Reads ====================

    /** (contact id, lookup key, display name, photo uri) for one list row. */
    private data class ContactRow(
        val contactId: Long,
        val lookupKey: String,
        val displayName: String,
        val photoUri: String?,
    )

    private fun readContacts(query: String?): List<ContactListItem> {
        return try {
            val rows = readContactRows(query)
            val eventsByContactId = readContactEventDates(rows.map { it.contactId }, query != null)
            rows.map { row ->
                val dates = eventsByContactId[row.contactId]
                ContactListItem(
                    lookupKey = row.lookupKey,
                    displayName = row.displayName,
                    photoUri = row.photoUri,
                    birthday = dates?.first,
                    anniversary = dates?.second,
                )
            }.sortedWith(
                // Blank names sort last (case-insensitive), lookup key breaks ties so the
                // order is stable across reloads.
                Comparator<ContactListItem> { a, b ->
                    val an = a.displayName.trim().ifBlank { null }
                    val bn = b.displayName.trim().ifBlank { null }
                    when {
                        an == null && bn == null -> 0
                        an == null -> 1
                        bn == null -> -1
                        else -> String.CASE_INSENSITIVE_ORDER.compare(an, bn)
                    }
                }.thenComparator { a, b -> a.lookupKey.compareTo(b.lookupKey) }
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "READ_CONTACTS revoked; contacts list empty", e)
            emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Contacts list query failed", e)
            emptyList()
        }
    }

    /**
     * Lists the contact rows, name-sorted by the provider (the in-memory sort re-applies the
     * blank-names-last order after the date join). A blank [query] is one pass over the
     * Contacts table; a typed query is a two-step filter: display-name matches on the
     * Contacts table plus phone matches on the Data table, then one name-sorted pass over
     * the union of the matched ids.
     */
    private fun readContactRows(query: String?): List<ContactRow> {
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME,
            ContactsContract.Contacts.PHOTO_URI,
        )
        val sort = "${ContactsContract.Contacts.DISPLAY_NAME} COLLATE NOCASE ASC"
        val contactsUri = ContactsContract.Contacts.CONTENT_URI

        if (query.isNullOrBlank()) {
            val rows = mutableListOf<ContactRow>()
            queryRows(contactsUri, projection, null, null, sort) { cursor ->
                rows.add(cursor.toContactRow())
            }
            return rows
        }

        val escaped = escapeLike(query.trim())
        val ids = LinkedHashSet<Long>()
        queryRows(
            contactsUri,
            arrayOf(ContactsContract.Contacts._ID),
            "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ? ESCAPE '\\'",
            arrayOf("%$escaped%"),
            null,
        ) { cursor -> ids.add(cursor.getLong(0)) }
        queryRows(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.CONTACT_ID),
            "${ContactsContract.Data.MIMETYPE} = ? AND " +
                "(${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ? ESCAPE '\\' OR " +
                "${ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER} LIKE ? ESCAPE '\\')",
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                "%$escaped%", "%$escaped%",
            ),
            null,
        ) { cursor -> ids.add(cursor.getLong(0)) }
        if (ids.isEmpty()) return emptyList()

        val rows = mutableListOf<ContactRow>()
        for (chunk in ids.chunked(MAX_IDS_PER_QUERY)) {
            val placeholders = chunk.joinToString(",") { "?" }
            queryRows(
                contactsUri,
                projection,
                "${ContactsContract.Contacts._ID} IN ($placeholders)",
                chunk.map(Long::toString).toTypedArray(),
                sort,
            ) { cursor -> rows.add(cursor.toContactRow()) }
        }
        return rows
    }

    private fun Cursor.toContactRow(): ContactRow = ContactRow(
        contactId = getLong(0),
        lookupKey = getString(1).orEmpty(),
        displayName = getString(2).orEmpty(),
        photoUri = getString(3),
    )

    /**
     * Maps contact id to (birthday, anniversary), reading only Event rows. Each component is
     * present only when that contact has an Event row of its own type; neither is defaulted
     * from the other. [filtered] scopes
     * the read to the already-matched contact ids; an unfiltered list reads every Event row
     * in one pass instead of building a huge IN list.
     */
    private fun readContactEventDates(
        onlyIds: List<Long>,
        filtered: Boolean,
    ): Map<Long, Pair<LocalDate?, LocalDate?>> {
        if (filtered && onlyIds.isEmpty()) return emptyMap()
        val projection = arrayOf(
            ContactsContract.Data.CONTACT_ID,
            ContactsContract.CommonDataKinds.Event.TYPE,
            ContactsContract.CommonDataKinds.Event.START_DATE,
        )
        val baseSelection =
            "${ContactsContract.Data.MIMETYPE} = ? AND " +
                "${ContactsContract.CommonDataKinds.Event.TYPE} IN (?, ?)"
        val baseArgs = listOf(
            ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY.toString(),
            ContactsContract.CommonDataKinds.Event.TYPE_ANNIVERSARY.toString(),
        )

        val thisYear = LocalDate.now().year
        val out = mutableMapOf<Long, Pair<LocalDate?, LocalDate?>>()
        // Chunked like the sibling IN reads so a broad filter ("a") stays under SQLite's
        // bound-variable limit. Unfiltered, one pass reads every Event row.
        val idChunks = if (filtered) onlyIds.chunked(MAX_IDS_PER_QUERY) else listOf(emptyList())
        for (chunk in idChunks) {
            val selection = if (filtered) {
                val placeholders = chunk.joinToString(",") { "?" }
                "$baseSelection AND ${ContactsContract.Data.CONTACT_ID} IN ($placeholders)"
            } else {
                baseSelection
            }
            val args = if (filtered) {
                baseArgs + chunk.map(Long::toString)
            } else {
                baseArgs
            }
            queryRows(
                ContactsContract.Data.CONTENT_URI,
                projection,
                selection,
                args.toTypedArray(),
                null,
            ) { cursor ->
                val contactId = cursor.getLong(0)
                val parsed = ContactEventUtils.parseContactDate(cursor.getString(2))
                    ?: return@queryRows
                // A year-less date is placed at the current year: the browser only formats the
                // month and day, and LocalDate needs a year to exist.
                val date = LocalDate.of(parsed.year ?: thisYear, parsed.month, parsed.day)
                val isBirthday =
                    cursor.getInt(1) == ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY
                val existing = out[contactId]
                out[contactId] = if (isBirthday) {
                    date to existing?.second
                } else {
                    existing?.first to date
                }
            }
        }
        return out
    }

    private fun readContactDetail(lookupKey: String): ContactDetail? {
        val contactId = resolveContactId(lookupKey) ?: return null
        var displayName = ""
        var photoUri: String? = null
        queryRows(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME,
                ContactsContract.Contacts.PHOTO_URI,
            ),
            "${ContactsContract.Contacts._ID} = ?",
            arrayOf(contactId.toString()),
            null,
        ) { cursor ->
            displayName = cursor.getString(1).orEmpty()
            photoUri = cursor.getString(2)
        }

        var organization: String? = null
        var jobTitle: String? = null
        var note: String? = null
        var birthday: LocalDate? = null
        var anniversary: LocalDate? = null
        val phones = mutableListOf<LabeledPhone>()
        val emails = mutableListOf<LabeledEmail>()
        val addresses = mutableListOf<LabeledAddress>()
        val thisYear = LocalDate.now().year

        queryRows(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data.MIMETYPE,
                ContactsContract.Data.DATA1,
                ContactsContract.Data.DATA2,
                ContactsContract.Data.DATA3,
                ContactsContract.Data.DATA4,
            ),
            "${ContactsContract.Data.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null,
        ) { cursor ->
            when (cursor.getString(0)) {
                ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> {
                    organization = cursor.getString(1)?.takeIf(String::isNotBlank)
                    // Organization keeps its company in DATA1 and its title in DATA4.
                    jobTitle = cursor.getString(4)?.takeIf(String::isNotBlank)
                }
                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> {
                    val number = cursor.getString(1)?.takeIf(String::isNotBlank)
                        ?: return@queryRows
                    phones.add(
                        LabeledPhone(
                            typeLabel = phoneTypeLabel(cursor.getInt(2), cursor.getString(3)),
                            number = number,
                        )
                    )
                }
                ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> {
                    val address = cursor.getString(1)?.takeIf(String::isNotBlank)
                        ?: return@queryRows
                    emails.add(
                        LabeledEmail(
                            typeLabel = emailTypeLabel(cursor.getInt(2), cursor.getString(3)),
                            address = address,
                        )
                    )
                }
                ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE -> {
                    val formatted = cursor.getString(1)?.takeIf(String::isNotBlank)
                        ?: return@queryRows
                    addresses.add(LabeledAddress(formatted = formatted))
                }
                ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE -> {
                    note = cursor.getString(1)?.takeIf(String::isNotBlank)
                }
                ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE -> {
                    val parsed = ContactEventUtils.parseContactDate(cursor.getString(1))
                        ?: return@queryRows
                    val date = LocalDate.of(parsed.year ?: thisYear, parsed.month, parsed.day)
                    when (cursor.getInt(2)) {
                        ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY ->
                            if (birthday == null) birthday = date
                        ContactsContract.CommonDataKinds.Event.TYPE_ANNIVERSARY ->
                            if (anniversary == null) anniversary = date
                    }
                }
            }
        }

        return ContactDetail(
            lookupKey = lookupKey,
            displayName = displayName,
            photoUri = photoUri,
            organization = organization,
            jobTitle = jobTitle,
            phones = phones,
            emails = emails,
            addresses = addresses,
            note = note,
            birthday = birthday,
            anniversary = anniversary,
        )
    }

    /** Returns the aggregate contact id for [lookupKey], or null when it no longer resolves. */
    private fun resolveContactId(lookupKey: String): Long? {
        var id: Long? = null
        queryRows(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID),
            "${ContactsContract.Contacts.LOOKUP_KEY} = ?",
            arrayOf(lookupKey),
            null,
        ) { cursor ->
            if (id == null) id = cursor.getLong(0)
        }
        return id
    }

    // ==================== Query plumbing ====================

    /**
     * Runs one provider query at [uri] and hands each row to [read]. All cursor access in
     * this file funnels through here, so one guard covers every read.
     */
    private inline fun queryRows(
        uri: Uri,
        projection: Array<String>,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
        read: (Cursor) -> Unit,
    ) {
        contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            while (cursor.moveToNext()) read(cursor)
        }
    }

    /** Escapes LIKE wildcards in user input so "50%" matches a literal percent sign. */
    private fun escapeLike(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun phoneTypeLabel(type: Int, customLabel: String?): String =
        ContactsContract.CommonDataKinds.Phone
            .getTypeLabel(context.resources, type, customLabel).toString()

    private fun emailTypeLabel(type: Int, customLabel: String?): String =
        ContactsContract.CommonDataKinds.Email
            .getTypeLabel(context.resources, type, customLabel).toString()
}

/**
 * The list's display-name bucket: the uppercase first letter, else "#" for a blank or
 * non-letter start, so digits and punctuation group together under one header.
 */
fun sectionBucketFor(displayName: String): String {
    val first = displayName.trim().uppercase(Locale.getDefault()).firstOrNull() ?: return "#"
    return if (first.isLetter()) first.toString() else "#"
}

/** Days until [date]'s next occurrence from [today]; 0 means today. */
fun daysUntilNextOccurrence(date: LocalDate, today: LocalDate): Long {
    val thisYear = date.withYear(today.year)
    val next = if (!thisYear.isBefore(today)) thisYear else thisYear.plusYears(1)
    return ChronoUnit.DAYS.between(today, next)
}
