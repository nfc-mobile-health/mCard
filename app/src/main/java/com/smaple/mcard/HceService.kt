/**
 * Android HCE (Host Card Emulation) service that routes inbound APDUs
 * to the shared-core ProtocolEngine.
 */
package com.smaple.mcard

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import com.smaple.core.protocol.ApduConstants
import com.smaple.core.protocol.ProtocolEngine
import com.smaple.core.protocol.ProtocolEvent
import com.smaple.core.session.CredentialStore
import com.smaple.core.session.SessionCoordinator
import com.smaple.core.session.SessionEvent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class HceService : HostApduService() {

    @Inject
    lateinit var engine: ProtocolEngine

    @Inject
    lateinit var credentialStore: CredentialStore

    @Inject
    lateinit var sessionCoordinator: SessionCoordinator

    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            engine.events.collect { event ->
                if (event is ProtocolEvent.Completed) {
                    sessionCoordinator.dispatchEvent(SessionEvent.Completed(event.result))
                }
            }
        }
    }

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null) return ApduConstants.SW_UNKNOWN
        val cred = credentialStore.currentCredential ?: return ApduConstants.SW_NOT_READY
        
        return runBlocking {
            try {
                engine.handleInboundApdu(commandApdu, cred, sessionCoordinator.preparedOffer)
            } catch (e: Exception) {
                ApduConstants.SW_UNKNOWN
            }
        }
    }

    override fun onDeactivated(reason: Int) {
        engine.onLinkLost()
        sessionCoordinator.dispatchEvent(SessionEvent.Idle)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
