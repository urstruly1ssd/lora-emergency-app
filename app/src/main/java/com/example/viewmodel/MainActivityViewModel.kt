package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

enum class AppScreen {
    Splash,
    Dashboard,
    NodeScan,
    ChatsList,
    ChatThread,
    Offline,
    Map
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainActivityViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    val repository = AppRepository(application, db.appDao())

    // Navigation and UX states
    private val _currentScreen = MutableStateFlow(AppScreen.Splash)
    val currentScreen: StateFlow<AppScreen> = _currentScreen.asStateFlow()

    private val _activeResponderId = MutableStateFlow<String?>(null)
    val activeResponderId: StateFlow<String?> = _activeResponderId.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Telemetry and hardware inputs (battery, GPS, beacon, scanning)
    val batteryLevel: StateFlow<Int> = repository.batteryLevel
    val gpsLocation: StateFlow<Pair<Double, Double>> = repository.gpsLocation
    val isBeaconActive: StateFlow<Boolean> = repository.isBeaconActive
    val isScanning: StateFlow<Boolean> = repository.isScanning
    val connectedNode: StateFlow<NodeEntity?> = repository.connectedNode.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )
    val allNodes: StateFlow<List<NodeEntity>> = repository.allNodes.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )
    val allResponders: StateFlow<List<ResponderEntity>> = repository.allResponders.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )
    val unsentMessagesCount: StateFlow<Int> = repository.unsentMessagesCount.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = 0
    )

    // Chat Thread Messages stream for the selected responder
    val messagesForActiveChat: Flow<List<MessageEntity>> = combine(_activeResponderId, connectedNode) { responderId, node ->
        responderId ?: node?.name
    }.flatMapLatest { id ->
        if (id == null) {
            flowOf(emptyList())
        } else {
            repository.getMessagesForResponder(id)
        }
    }

    // Modal dialog controller triggers
    private val _showRecipientSelector = MutableStateFlow(false)
    val showRecipientSelector: StateFlow<Boolean> = _showRecipientSelector.asStateFlow()

    private val _showSOSDialog = MutableStateFlow(false)
    val showSOSDialog: StateFlow<Boolean> = _showSOSDialog.asStateFlow()

    init {
        // Trigger automated transition from Splash to Dashboard after 3s
        viewModelScope.launch {
            delay(3000)
            _currentScreen.value = AppScreen.Dashboard
            autoConnectBest()
        }

        // Catch extreme hardware sensor shake inputs to override and auto-trigger SOS
        viewModelScope.launch {
            repository.shakeTriggered.collect {
                if (!_showSOSDialog.value) {
                    _showSOSDialog.value = true
                    activateSOSBeacon()
                }
            }
        }

        // Observe connected node to set active responder ID
        viewModelScope.launch {
            connectedNode.collect { node ->
                if (node != null) {
                    _activeResponderId.value = node.name
                } else {
                    _activeResponderId.value = null
                }
            }
        }
    }

    // --- Navigation Controls ---
    fun navigateTo(screen: AppScreen) {
        // Double check online state. If going to Chat or Map but disconnected, suggest scan / allow offline
        _currentScreen.value = screen
    }

    fun openChatWith(responderId: String) {
        _activeResponderId.value = responderId
        _currentScreen.value = AppScreen.ChatThread
        _showRecipientSelector.value = false
    }

    fun setQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleRecipientSelector(show: Boolean) {
        _showRecipientSelector.value = show
    }

    // --- BLE Simulation triggers ---
    fun triggerScan() {
        viewModelScope.launch {
            repository.startScanning()
        }
    }

    fun connectToNode(name: String) {
        viewModelScope.launch {
            repository.connectToNode(name)
        }
    }

    fun disconnectNode() {
        viewModelScope.launch {
            repository.disconnectNode()
        }
    }

    fun autoConnectBest() {
        viewModelScope.launch {
            repository.autoConnectToStrongest()
        }
    }

    // --- Chat Transmit & Input functions ---
    fun submitMessage(text: String, attachmentType: String = "NONE", attachmentData: String? = null) {
        val responderId = _activeResponderId.value ?: connectedNode.value?.name ?: "esp32_serial"
        viewModelScope.launch {
            repository.sendMessage(responderId, text, attachmentType, attachmentData)
        }
    }

    fun deleteChat(responderId: String) {
        viewModelScope.launch {
            repository.clearChatHistory(responderId)
        }
    }

    // --- SOS Beacon Sequence ---
    fun triggerSOSHold() {
        _showSOSDialog.value = true
        activateSOSBeacon()
    }

    fun dismissSOSDialog() {
        _showSOSDialog.value = false
    }

    private fun activateSOSBeacon() {
        repository.toggleSOSBeacon(true)
        // Auto-switch to Map Screen to trace path escape route maps
        viewModelScope.launch {
            delay(1200)
            _currentScreen.value = AppScreen.Map
        }
    }

    fun deactivateSOS() {
        _showSOSDialog.value = false
        repository.toggleSOSBeacon(false)
    }

    override fun onCleared() {
        super.onCleared()
        repository.cleanUp()
    }
}
