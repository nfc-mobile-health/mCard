package com.example.mcard.hce

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import com.example.mcard.protocol.MCardApduProcessor
import com.example.mcard.storage.MCardStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MCardHostApduService : HostApduService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processor by lazy { MCardApduProcessor(MCardStore(this), requireSecureSession = true) }

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray? {
        scope.launch {
            sendResponseApdu(processor.process(commandApdu))
        }
        return null
    }

    override fun onDeactivated(reason: Int) {
        processor.resetSession()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
