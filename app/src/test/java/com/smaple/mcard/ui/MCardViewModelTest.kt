package com.smaple.mcard.ui

import com.smaple.core.fakes.FakeBackendApi
import com.smaple.core.fakes.FakeCredentialStore
import com.smaple.core.fakes.FakeSessionCoordinator
import com.smaple.core.model.MedicalRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class MCardViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mainViewModel: MainViewModel
    private lateinit var backendApi: FakeBackendApi
    private lateinit var credentialStore: FakeCredentialStore
    private lateinit var sessionCoordinator: FakeSessionCoordinator

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        backendApi = FakeBackendApi()
        credentialStore = FakeCredentialStore()
        sessionCoordinator = FakeSessionCoordinator()
        mainViewModel = MainViewModel(sessionCoordinator, backendApi, credentialStore)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // regression test for architecture-decisions-log.md 2026-09-09, bug 3: blank-field bleed (storage layer)
    @Test
    fun testBlankFieldBleed_Storage_Regression3() = runTest {
        val recordA = MedicalRecord("id1", "p1", "hp1", "John", "120/80", 72, 16, 98.6f, "Med", "Desc")
        mainViewModel.storeRecord(recordA) // Simulates receiving first version
        
        // Simulating receiving an updated version of the same record ID but with blank fields
        val recordB = MedicalRecord("id1", "p1", "hp1", "John", null, null, null, null, null, null)
        mainViewModel.storeRecord(recordB)
        
        val stored = mainViewModel.localRecords.value["id1"]
        assertNotNull(stored)
        assertNull("Should be full replacement, blank field bled", stored!!.bloodPressure)
        assertNull("Should be full replacement, blank field bled", stored.heartRate)
    }
}
