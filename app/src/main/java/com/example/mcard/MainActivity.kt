package com.example.mcard

import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.mcard.protocol.BasicInfoCodec
import com.example.mcard.storage.MCardStore
import com.example.mcard.ui.MapleColors
import com.example.mcard.ui.MapleTheme
import com.example.mcard.ui.MapleToken
import com.example.mcard.ui.TokenState
import java.time.LocalDate
import java.time.Period
import java.util.Locale
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MapleTheme {
                MCardApp()
            }
        }
    }
}

private enum class AppScreen { Welcome, Setup, Home, Notes, Me }
private enum class AppLanguage { English, Hindi }

@Composable
private fun MCardApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { MCardStore(context) }
    var language by remember { mutableStateOf(AppLanguage.English) }
    var screen by remember { mutableStateOf(AppScreen.Welcome) }
    val copy = remember(language) { Copy(language) }
    val tts = rememberTextToSpeech(language)
    val basicInfo by produceState<BasicInfoCodec.BasicInfo?>(initialValue = null, screen) {
        value = store.getBasicInfoBlock()?.let(BasicInfoCodec::decode)
    }

    LaunchedEffect(basicInfo) {
        if (basicInfo != null && screen == AppScreen.Welcome) screen = AppScreen.Home
    }

    Scaffold(
        containerColor = MapleColors.Sage,
        bottomBar = {
            if (screen in setOf(AppScreen.Home, AppScreen.Notes, AppScreen.Me)) {
                BottomBar(screen, copy) { screen = it }
            }
        },
    ) { padding ->
        val listen: (List<String>) -> Unit = { lines ->
            tts.speak(lines.joinToString(". "), TextToSpeech.QUEUE_FLUSH, null, "mcard-screen")
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            when (screen) {
                AppScreen.Welcome -> WelcomeScreen(
                    copy = copy,
                    language = language,
                    onLanguage = { language = if (language == AppLanguage.English) AppLanguage.Hindi else AppLanguage.English },
                    onListen = listen,
                    onSetup = { screen = AppScreen.Setup },
                    onAlreadyHaveCard = { screen = AppScreen.Home },
                )

                AppScreen.Setup -> SetupBasicInfoScreen(
                    copy = copy,
                    onListen = listen,
                    onBack = { screen = AppScreen.Welcome },
                    onSave = { block ->
                        scope.launch {
                            if (store.writeBasicInfoBlock(block)) screen = AppScreen.Home
                        }
                    },
                )

                AppScreen.Home -> HomeScreen(copy, basicInfo, listen)
                AppScreen.Notes -> MyNotesScreen(copy, listen)
                AppScreen.Me -> MeScreen(copy, language, listen) {
                    language = if (language == AppLanguage.English) AppLanguage.Hindi else AppLanguage.English
                }
            }
        }
    }
}

@Composable
private fun rememberTextToSpeech(language: AppLanguage): TextToSpeech {
    val context = LocalContext.current
    val tts = remember {
        TextToSpeech(context) { }
    }
    LaunchedEffect(language) {
        tts.language = when (language) {
            AppLanguage.English -> Locale.ENGLISH
            AppLanguage.Hindi -> Locale("hi", "IN")
        }
    }
    DisposableEffect(Unit) {
        onDispose { tts.shutdown() }
    }
    return tts
}

@Composable
private fun ScreenFrame(
    title: String,
    listenLabel: String,
    onListen: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 600.dp)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.headlineLarge)
            TextButton(
                onClick = onListen,
                modifier = Modifier.semantics { contentDescription = listenLabel },
            ) {
                Text(listenLabel)
            }
        }
        Spacer(Modifier.height(24.dp))
        content()
    }
}

@Composable
private fun WelcomeScreen(
    copy: Copy,
    language: AppLanguage,
    onLanguage: () -> Unit,
    onListen: (List<String>) -> Unit,
    onSetup: () -> Unit,
    onAlreadyHaveCard: () -> Unit,
) {
    val lines = listOf(copy.welcomeTitle, copy.welcomeBody, copy.setupMyCard, copy.alreadyHaveCard)
    ScreenFrame(copy.welcomeTitle, copy.listen, { onListen(lines) }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            PillButton(text = if (language == AppLanguage.English) "हिन्दी" else "English", onClick = onLanguage)
        }
        Spacer(Modifier.height(20.dp))
        MapleToken(TokenState.Ready, 148.dp, copy.readyDescription, Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(20.dp))
        Text(copy.welcomeBody, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(28.dp))
        PrimaryButton(copy.setupMyCard, onSetup)
        Spacer(Modifier.height(12.dp))
        SecondaryButton(copy.alreadyHaveCard, onAlreadyHaveCard)
    }
}

@Composable
private fun SetupBasicInfoScreen(
    copy: Copy,
    onListen: (List<String>) -> Unit,
    onBack: () -> Unit,
    onSave: (ByteArray) -> Unit,
) {
    var patientId by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var dob by remember { mutableStateOf("1972-05-20") }
    var gender by remember { mutableStateOf(BasicInfoCodec.Gender.Female) }
    var blood by remember { mutableStateOf(BasicInfoCodec.BloodGroup.BPositive) }
    var error by remember { mutableStateOf<String?>(null) }
    val lines = listOf(copy.setupTitle, copy.setupHelp, copy.patientId, copy.name, copy.dateOfBirth, copy.gender, copy.bloodGroup)

    ScreenFrame(copy.setupTitle, copy.listen, { onListen(lines) }) {
        Text(copy.setupHelp, style = MaterialTheme.typography.bodyMedium, color = MapleColors.Moss)
        Spacer(Modifier.height(16.dp))
        BigTextField(copy.patientId, patientId, { patientId = it.take(12) }, KeyboardType.Ascii)
        BigTextField(copy.name, name, { name = it.take(40) }, KeyboardType.Text)
        BigTextField(copy.dateOfBirth, dob, { dob = it.take(10) }, KeyboardType.Number)
        Text(copy.gender, style = MaterialTheme.typography.labelLarge)
        ChipRow(BasicInfoCodec.Gender.entries.map { it.label }, gender.label) { selected ->
            gender = BasicInfoCodec.Gender.entries.first { it.label == selected }
        }
        Text(copy.bloodGroup, style = MaterialTheme.typography.labelLarge)
        ChipRow(BasicInfoCodec.BloodGroup.entries.map { it.label }, blood.label) { selected ->
            blood = BasicInfoCodec.BloodGroup.entries.first { it.label == selected }
        }
        error?.let {
            Spacer(Modifier.height(12.dp))
            ProblemBanner(it)
        }
        Spacer(Modifier.height(20.dp))
        PrimaryButton(copy.saveBasicInfo) {
            runCatching {
                BasicInfoCodec.encode(
                    BasicInfoCodec.BasicInfo(
                        patientId = patientId.trim(),
                        name = name.trim(),
                        dateOfBirth = LocalDate.parse(dob.trim()),
                        gender = gender,
                        bloodGroup = blood,
                    ),
                )
            }.onSuccess {
                error = null
                onSave(it)
            }.onFailure {
                error = copy.checkDetails
            }
        }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(copy.back)
        }
    }
}

@Composable
private fun HomeScreen(
    copy: Copy,
    info: BasicInfoCodec.BasicInfo?,
    onListen: (List<String>) -> Unit,
) {
    val greeting = info?.let { "${copy.hello} ${it.name}" } ?: copy.hello
    val instruction = copy.readyInstruction
    ScreenFrame(greeting, copy.listen, { onListen(listOf(greeting, copy.ready, instruction, copy.noNotesYet)) }) {
        MapleToken(TokenState.Ready, 236.dp, copy.readyDescription, Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(12.dp))
        Text(copy.ready, style = MaterialTheme.typography.displayMedium, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(12.dp))
        Text(instruction, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(20.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = MapleColors.White),
            border = BorderStroke(1.dp, MapleColors.Line),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(copy.latestNote, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(copy.noNotesYet, style = MaterialTheme.typography.bodyMedium, color = MapleColors.Moss)
                info?.let {
                    Spacer(Modifier.height(12.dp))
                    Text("${copy.patientId}: ${it.patientId}", style = MaterialTheme.typography.bodyMedium)
                    Text("${copy.age}: ${Period.between(it.dateOfBirth, LocalDate.now()).years}", style = MaterialTheme.typography.bodyMedium)
                    Text("${copy.bloodGroup}: ${it.bloodGroup.label}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun MyNotesScreen(copy: Copy, onListen: (List<String>) -> Unit) {
    var selected by remember { mutableStateOf(10) }
    ScreenFrame(copy.myNotes, copy.listen, { onListen(listOf(copy.myNotes, copy.chooseNotes, copy.getLatestNotes, copy.noNotesYet)) }) {
        Text(copy.chooseNotes, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        ChipRow(listOf("5", "10", "20"), selected.toString()) { selected = it.toInt() }
        Spacer(Modifier.height(20.dp))
        PrimaryButton(copy.getLatestNotes) { }
        Spacer(Modifier.height(20.dp))
        Text(copy.noNotesYet, style = MaterialTheme.typography.bodyMedium, color = MapleColors.Moss)
        Spacer(Modifier.height(20.dp))
        SecondaryButton(copy.shareMyNotes) { }
        Text(copy.shareWarning, style = MaterialTheme.typography.bodyMedium, color = MapleColors.Moss)
    }
}

@Composable
private fun MeScreen(
    copy: Copy,
    language: AppLanguage,
    onListen: (List<String>) -> Unit,
    onLanguage: () -> Unit,
) {
    ScreenFrame(copy.me, copy.listen, { onListen(listOf(copy.me, copy.language, copy.textHelp)) }) {
        Text(copy.language, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        PillButton(text = if (language == AppLanguage.English) "हिन्दी" else "English", onClick = onLanguage)
        Spacer(Modifier.height(24.dp))
        Text(copy.textHelp, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun BottomBar(current: AppScreen, copy: Copy, onSelect: (AppScreen) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .background(MapleColors.White)
            .border(1.dp, MapleColors.Line),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BottomItem(copy.home, current == AppScreen.Home) { onSelect(AppScreen.Home) }
        BottomItem(copy.myNotes, current == AppScreen.Notes) { onSelect(AppScreen.Notes) }
        BottomItem(copy.me, current == AppScreen.Me) { onSelect(AppScreen.Me) }
    }
}

@Composable
private fun BottomItem(text: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(if (selected) MapleColors.Mist2 else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                Text(text.take(1), style = MaterialTheme.typography.labelLarge, color = MapleColors.Pine)
            }
            Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) MapleColors.Pine else MapleColors.Moss)
        }
    }
}

@Composable
private fun BigTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType,
) {
    Spacer(Modifier.height(12.dp))
    Text(label, style = MaterialTheme.typography.labelLarge)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.titleLarge,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChipRow(options: List<String>, selected: String, onSelected: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelected(option) },
                label = { Text(option) },
                modifier = Modifier.height(56.dp),
            )
        }
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MapleColors.Pine, contentColor = MapleColors.White),
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MapleColors.Pine),
        border = BorderStroke(2.dp, MapleColors.Pine),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun PillButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = MapleColors.Mist, contentColor = MapleColors.PineDeep),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ProblemBanner(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MapleColors.RedTint)
            .padding(16.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MapleColors.Ink, fontWeight = FontWeight.Bold)
    }
}

private class Copy(private val currentLanguage: AppLanguage) {
    val listen = if (currentLanguage == AppLanguage.Hindi) "सुनें" else "Listen"
    val welcomeTitle = if (currentLanguage == AppLanguage.Hindi) "mCard में आपका स्वागत है" else "Welcome to mCard"
    val welcomeBody = if (currentLanguage == AppLanguage.Hindi) "अपना कार्ड सेट करें और फोन को तैयार रखें।" else "Set up your card and keep your phone ready."
    val setupMyCard = if (currentLanguage == AppLanguage.Hindi) "मेरा कार्ड सेट करें" else "Set up my card"
    val alreadyHaveCard = if (currentLanguage == AppLanguage.Hindi) "मेरे पास पहले से कार्ड है" else "I already have a card"
    val setupTitle = if (currentLanguage == AppLanguage.Hindi) "मूल जानकारी" else "Basic information"
    val setupHelp = if (currentLanguage == AppLanguage.Hindi) "यह जानकारी नर्स के फोन को दिखाई देगी।" else "This information is shown to your nurse's phone."
    val patientId = if (currentLanguage == AppLanguage.Hindi) "मरीज आईडी" else "Patient ID"
    val name = if (currentLanguage == AppLanguage.Hindi) "नाम" else "Name"
    val dateOfBirth = if (currentLanguage == AppLanguage.Hindi) "जन्म तारीख, जैसे 1972-05-20" else "Date of birth, like 1972-05-20"
    val gender = if (currentLanguage == AppLanguage.Hindi) "लिंग" else "Gender"
    val bloodGroup = if (currentLanguage == AppLanguage.Hindi) "ब्लड ग्रुप" else "Blood group"
    val saveBasicInfo = if (currentLanguage == AppLanguage.Hindi) "जानकारी सेव करें" else "Save information"
    val checkDetails = if (currentLanguage == AppLanguage.Hindi) "जानकारी जांचें और फिर से कोशिश करें।" else "Check the details and try again."
    val back = if (currentLanguage == AppLanguage.Hindi) "वापस" else "Back"
    val hello = if (currentLanguage == AppLanguage.Hindi) "नमस्ते" else "Hello"
    val ready = if (currentLanguage == AppLanguage.Hindi) "तैयार" else "Ready"
    val readyInstruction = if (currentLanguage == AppLanguage.Hindi) "अपने फोन का पिछला हिस्सा नर्स के फोन के पास रखें।" else "Hold the back of your phone against your nurse's phone."
    val readyDescription = if (currentLanguage == AppLanguage.Hindi) "कार्ड तैयार है" else "Card is ready"
    val latestNote = if (currentLanguage == AppLanguage.Hindi) "नया नोट" else "Latest note"
    val noNotesYet = if (currentLanguage == AppLanguage.Hindi) "अभी कोई नोट नहीं है। आपकी नर्स के नोट यहां दिखेंगे।" else "No notes yet. Notes your nurse adds will show here."
    val age = if (currentLanguage == AppLanguage.Hindi) "उम्र" else "Age"
    val home = if (currentLanguage == AppLanguage.Hindi) "होम" else "Home"
    val myNotes = if (currentLanguage == AppLanguage.Hindi) "मेरे नोट" else "My notes"
    val me = if (currentLanguage == AppLanguage.Hindi) "मैं" else "Me"
    val chooseNotes = if (currentLanguage == AppLanguage.Hindi) "कितने नोट चाहिए" else "Choose how many notes"
    val getLatestNotes = if (currentLanguage == AppLanguage.Hindi) "नए नोट पाएं" else "Get latest notes"
    val shareMyNotes = if (currentLanguage == AppLanguage.Hindi) "मेरे नोट शेयर करें" else "Share my notes"
    val shareWarning = if (currentLanguage == AppLanguage.Hindi) "जिससे आप शेयर करेंगे वह ये नोट पढ़ सकेगा।" else "Anyone you share with can read these notes."
    val language = if (currentLanguage == AppLanguage.Hindi) "भाषा" else "Language"
    val textHelp = if (currentLanguage == AppLanguage.Hindi) "हर स्क्रीन पर सुनें बटन लिखा हुआ पाठ पढ़ता है।" else "The Listen button reads the visible text on each screen."
}
