package org.onekash.kashcal.ui.screens.contacts

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.onekash.kashcal.data.contacts.ContactDetail
import org.onekash.kashcal.data.contacts.ContactListItem
import org.onekash.kashcal.data.contacts.ContactsBrowserRepository
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.sync.adapter.KashCalContactsAuthenticator
import org.onekash.kashcal.ui.permission.PermissionChecker
import java.time.LocalDate
import javax.inject.Inject

/**
 * Drives the contacts browser: the list (query + permission + reload reactive), the opened
 * contact's detail, and every browser write. Write successes refresh the list and, when a
 * contact is open, its detail, so an edit is visible without leaving the screen.
 *
 * The write results are logged, not surfaced: the frozen string set has no write-error copy,
 * and a failed provider write (WRITE_CONTACTS revoked) leaves the row unchanged, which the
 * reload shows.
 *
 * Create is only offered when a contact-sync-enabled login also has its KashCal contacts
 * system account registered in AccountManager — the account a new RawContact needs to be
 * attributed to so [org.onekash.kashcal.sync.contacts.ContactPushStrategy] can push it.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val contactsBrowserRepository: ContactsBrowserRepository,
    private val accountRepository: AccountRepository,
    private val permissionChecker: PermissionChecker,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    companion object {
        private const val TAG = "ContactsViewModel"

        /** Keystroke settle time before re-querying the provider. */
        private const val SEARCH_DEBOUNCE_MS = 250L
    }

    /** The typed search text; blank means unfiltered. */
    private val searchQuery = MutableStateFlow("")

    /** Bumped by [reload]; the list pipeline re-queries on every increment. */
    private val reloadTick = MutableStateFlow(0)

    private val _permissionGranted = MutableStateFlow(false)
    val permissionGranted: StateFlow<Boolean> = _permissionGranted.asStateFlow()

    private val _contacts = MutableStateFlow<List<ContactListItem>>(emptyList())
    val contacts: StateFlow<List<ContactListItem>> = _contacts.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _selectedLookupKey = MutableStateFlow<String?>(null)
    val selectedLookupKey: StateFlow<String?> = _selectedLookupKey.asStateFlow()

    private val _selectedDetail = MutableStateFlow<ContactDetail?>(null)
    val selectedDetail: StateFlow<ContactDetail?> = _selectedDetail.asStateFlow()

    private val _isLoadingDetail = MutableStateFlow(false)
    val isLoadingDetail: StateFlow<Boolean> = _isLoadingDetail.asStateFlow()

    /**
     * Two-tap delete guard: the first tap arms it (the button relabels to the confirm copy),
     * the second deletes. Any close or reopen disarms.
     */
    private val _isDeleteConfirmArmed = MutableStateFlow(false)
    val isDeleteConfirmArmed: StateFlow<Boolean> = _isDeleteConfirmArmed.asStateFlow()

    /**
     * The KashCal contacts system account a new contact is created under, when a
     * contact-sync-enabled login has one registered; null hides the create action.
     */
    val contactSyncAccount: StateFlow<Account?> =
        accountRepository.getAllAccountsFlow()
            .map { accounts ->
                accounts.firstOrNull {
                    it.isEnabled && it.contactSyncEnabled && it.provider.supportsCardDAV
                }?.email
            }
            .map { email ->
                if (email == null) {
                    null
                } else {
                    AccountManager.get(context)
                        .getAccountsByType(KashCalContactsAuthenticator.ACCOUNT_TYPE)
                        .firstOrNull { it.name == email }
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        refreshPermission()
        viewModelScope.launch {
            combine(searchQuery, reloadTick, _permissionGranted) { query, _, granted ->
                query to granted
            }
                .debounce(SEARCH_DEBOUNCE_MS)
                .collectLatest { (query, granted) ->
                    if (!granted) {
                        _contacts.value = emptyList()
                        _isLoading.value = false
                        return@collectLatest
                    }
                    _isLoading.value = true
                    _contacts.value = contactsBrowserRepository
                        .observeContacts(query.trim().ifBlank { null })
                        .first()
                    _isLoading.value = false
                }
        }
    }

    /** Re-reads the grant state; called on entry and after each permission request. */
    fun refreshPermission() {
        _permissionGranted.value = permissionChecker.hasReadContactsPermission()
    }

    /** Types into the search field. */
    fun setQuery(query: String) {
        searchQuery.value = query
    }

    /** Re-queries the list; also re-samples the permission so a Settings-side grant shows. */
    fun reload() {
        refreshPermission()
        reloadTick.value++
    }

    // ==================== Detail ====================

    /** Opens a contact: remembers the key and loads its detail. */
    fun openContact(lookupKey: String) {
        _selectedLookupKey.value = lookupKey
        _isDeleteConfirmArmed.value = false
        loadDetail(lookupKey)
    }

    /** Closes the detail sheet and disarms the delete confirm. */
    fun closeContact() {
        _selectedLookupKey.value = null
        _selectedDetail.value = null
        _isDeleteConfirmArmed.value = false
    }

    fun armDeleteConfirm() {
        _isDeleteConfirmArmed.value = true
    }

    fun disarmDeleteConfirm() {
        _isDeleteConfirmArmed.value = false
    }

    private fun loadDetail(lookupKey: String) {
        viewModelScope.launch {
            _isLoadingDetail.value = true
            // A stale load (the sheet closed or switched mid-flight) is dropped by keying on
            // the current selection.
            val detail = contactsBrowserRepository.getContact(lookupKey)
            if (_selectedLookupKey.value == lookupKey) {
                _selectedDetail.value = detail
            }
            _isLoadingDetail.value = false
        }
    }

    // ==================== Writes ====================

    /** Saves (or clears with null) the open contact's birthday/anniversary row. */
    fun saveEventDate(isBirthday: Boolean, date: LocalDate?) {
        val key = _selectedLookupKey.value ?: return
        viewModelScope.launch {
            contactsBrowserRepository.setContactEventDate(key, isBirthday, date)
                .onSuccess { refreshAfterWrite(key) }
                .onFailure { Log.w(TAG, "Saving contact date failed", it) }
        }
    }

    /** Saves the minimal editor's fields for the open contact. */
    fun saveContactEdits(
        displayName: String,
        organization: String?,
        jobTitle: String?,
        phone: String?,
        email: String?,
        note: String?,
    ) {
        val key = _selectedLookupKey.value ?: return
        viewModelScope.launch {
            contactsBrowserRepository.updateContactBasics(
                key, displayName, organization, jobTitle, phone, email, note
            ).onSuccess { refreshAfterWrite(key) }
                .onFailure { Log.w(TAG, "Saving contact edits failed", it) }
        }
    }

    /** Deletes the open contact (the sheet's second confirm tap). */
    fun deleteSelectedContact() {
        val key = _selectedLookupKey.value ?: return
        viewModelScope.launch {
            contactsBrowserRepository.deleteContact(key)
                .onSuccess {
                    closeContact()
                    reload()
                }
                .onFailure { Log.w(TAG, "Deleting contact failed", it) }
        }
    }

    /** Creates a contact named [displayName] under the first contact-sync login's account. */
    fun createContact(displayName: String) {
        val account = contactSyncAccount.value ?: return
        viewModelScope.launch {
            contactsBrowserRepository.createContact(displayName.trim(), account)
                .onSuccess { reload() }
                .onFailure { Log.w(TAG, "Creating contact failed", it) }
        }
    }

    private suspend fun refreshAfterWrite(lookupKey: String) {
        reload()
        _selectedDetail.value = contactsBrowserRepository.getContact(lookupKey)
    }
}
