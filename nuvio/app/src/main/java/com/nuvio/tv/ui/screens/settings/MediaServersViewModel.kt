package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.mediaserver.PinSignInProvider
import com.nuvio.tv.data.mediaserver.ServerChoice
import com.nuvio.tv.data.mediaserver.ServerConnection
import com.nuvio.tv.data.mediaserver.ServerException
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.ServerPin
import com.nuvio.tv.data.mediaserver.ServerProvider
import com.nuvio.tv.data.mediaserver.ServerRepository
import com.nuvio.tv.data.mediaserver.ServersUiState
import com.nuvio.tv.data.mediaserver.serverFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ServerSignInState(
    val connecting: Boolean = false,
    val error: ServerFailure? = null
)

/** Vibe fork: sign-in through a code approved on another device (Plex). */
data class ServerPinState(
    val pin: ServerPin? = null,
    val choices: List<ServerChoice> = emptyList(),
    val connecting: Boolean = false,
    val expired: Boolean = false,
    val error: ServerFailure? = null
)

@HiltViewModel
class MediaServersViewModel @Inject constructor(
    private val repository: ServerRepository
) : ViewModel() {
    val uiState: StateFlow<ServersUiState> = repository.uiState

    val providers: List<ServerProvider>
        get() = repository.providers

    private val mutableSignIn = MutableStateFlow(ServerSignInState())
    val signIn: StateFlow<ServerSignInState> = mutableSignIn.asStateFlow()

    private val mutablePinSignIn = MutableStateFlow(ServerPinState())
    val pinSignIn: StateFlow<ServerPinState> = mutablePinSignIn.asStateFlow()
    private var pinJob: Job? = null
    private var pinAccountToken: String? = null

    private val mutableRefreshing = MutableStateFlow<String?>(null)
    val refreshing: StateFlow<String?> = mutableRefreshing.asStateFlow()

    init {
        repository.ensureLoaded()
    }

    fun provider(connection: ServerConnection): ServerProvider? = repository.provider(connection)

    fun resetSignIn() {
        mutableSignIn.value = ServerSignInState()
    }

    fun connect(
        provider: ServerProvider,
        address: String,
        username: String,
        password: String,
        onConnected: (ServerConnection) -> Unit
    ) {
        if (mutableSignIn.value.connecting) return
        mutableSignIn.value = ServerSignInState(connecting = true)
        viewModelScope.launch {
            try {
                val connection = repository.connect(provider, address, username.trim(), password)
                mutableSignIn.value = ServerSignInState()
                onConnected(connection)
            } catch (error: CancellationException) {
                mutableSignIn.value = ServerSignInState()
                throw error
            } catch (error: ServerException) {
                mutableSignIn.value = ServerSignInState(error = error.failure)
            } catch (_: Exception) {
                mutableSignIn.value = ServerSignInState(error = ServerFailure.FAILED)
            }
        }
    }

    /** Vibe fork: shows a new code and waits for it to be approved; one server connects straight away. */
    fun startPinSignIn(provider: ServerProvider, onConnected: (ServerConnection) -> Unit) {
        val pinProvider = provider as? PinSignInProvider ?: return
        cancelPinSignIn()
        pinJob = viewModelScope.launch {
            try {
                val pin = pinProvider.createPin()
                mutablePinSignIn.value = ServerPinState(pin = pin)
                var token: String? = null
                while (token == null) {
                    if (pin.expiresAtEpochMs != null && System.currentTimeMillis() > pin.expiresAtEpochMs) {
                        mutablePinSignIn.value = ServerPinState(pin = pin, expired = true)
                        return@launch
                    }
                    delay(PIN_POLL_INTERVAL_MS)
                    token = pinProvider.pollPin(pin)
                }
                pinAccountToken = token
                val choices = pinProvider.servers(token)
                when (choices.size) {
                    0 -> mutablePinSignIn.value = ServerPinState(pin = pin, error = ServerFailure.NOT_FOUND)
                    1 -> connectPin(provider, pinProvider, token, choices.single(), onConnected)
                    else -> mutablePinSignIn.value = ServerPinState(pin = pin, choices = choices)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutablePinSignIn.value = mutablePinSignIn.value.copy(connecting = false, error = error.serverFailure())
            }
        }
    }

    fun choosePinServer(provider: ServerProvider, choice: ServerChoice, onConnected: (ServerConnection) -> Unit) {
        val pinProvider = provider as? PinSignInProvider ?: return
        val token = pinAccountToken ?: return
        if (mutablePinSignIn.value.connecting) return
        pinJob = viewModelScope.launch {
            try {
                connectPin(provider, pinProvider, token, choice, onConnected)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutablePinSignIn.value = mutablePinSignIn.value.copy(connecting = false, error = error.serverFailure())
            }
        }
    }

    private suspend fun connectPin(
        provider: ServerProvider,
        pinProvider: PinSignInProvider,
        token: String,
        choice: ServerChoice,
        onConnected: (ServerConnection) -> Unit
    ) {
        mutablePinSignIn.value = mutablePinSignIn.value.copy(connecting = true, error = null)
        val connection = repository.connect(provider, pinProvider.signIn(token, choice))
        pinAccountToken = null
        mutablePinSignIn.value = ServerPinState()
        onConnected(connection)
    }

    fun cancelPinSignIn() {
        pinJob?.cancel()
        pinJob = null
        pinAccountToken = null
        mutablePinSignIn.value = ServerPinState()
    }

    fun refreshLibraries(connectionId: String, onFailure: (ServerFailure) -> Unit) {
        if (mutableRefreshing.value != null) return
        mutableRefreshing.value = connectionId
        viewModelScope.launch {
            try {
                repository.refreshLibraries(connectionId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                onFailure(error.serverFailure())
            } finally {
                mutableRefreshing.value = null
            }
        }
    }

    fun setEnabled(connectionId: String, enabled: Boolean) = repository.setEnabled(connectionId, enabled)

    fun setCatalogMetadata(connectionId: String, enabled: Boolean) = repository.setCatalogMetadata(connectionId, enabled)

    fun setImportWatchState(connectionId: String, enabled: Boolean) = repository.setImportWatchState(connectionId, enabled)

    fun setLibrarySelected(connectionId: String, libraryId: String, selected: Boolean) =
        repository.setLibrarySelected(connectionId, libraryId, selected)

    fun remove(connectionId: String) = repository.remove(connectionId)

    private companion object {
        const val PIN_POLL_INTERVAL_MS = 2_000L
    }
}
