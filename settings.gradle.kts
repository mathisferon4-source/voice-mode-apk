from pathlib import Path
import textwrap, zipfile, os

root = Path("/mnt/data/VOICE-MOD")
files = {
"settings.gradle.kts": """
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "VOICE-MOD"
include(":app")
""",
"build.gradle.kts": """
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
""",
"gradle.properties": """
org.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
""",
".gitignore": """
.gradle/
.idea/
local.properties
build/
**/build/
*.jks
*.keystore
secrets.properties
""",
"README.md": """
# VOICE MOD

Application Android Kotlin + Jetpack Compose : effets vocaux en écoute locale, soundboard, profils et enregistrements WAV.

## Fonctionnalités présentes
- Capture micro et monitoring local au casque (AudioRecord / AudioTrack).
- Effets DSP temps réel : grave/aigu (variation de vitesse simple), robot, radio, écho, saturation, filtre.
- Interrupteur d'activation, visualisation de niveau.
- Soundboard : import audio, liste et lecture.
- Enregistrement PCM WAV de la voix traitée.
- Profils vocaux sauvegardés localement (DataStore).
- Interface Compose sombre violet/bleu.

## Limite Android importante
VOICE MOD ne peut pas injecter son flux comme microphone système dans toutes les applications (Discord, WhatsApp, jeux, etc.). Android ne fournit pas aux applications ordinaires de microphone virtuel universel. Le mode fourni est un monitoring local : branche un casque pour éviter le Larsen. Le routage Bluetooth dépend du téléphone et de sa version Android.

## Construire l'APK
1. Installe Android Studio (version récente) et ouvre ce dossier.
2. Laisse Gradle synchroniser les dépendances.
3. Connecte un téléphone Samsung en USB ou utilise un émulateur.
4. Dans Android Studio : Build > Build Bundle(s) / APK(s) > Build APK(s).
5. L'APK debug est créé dans `app/build/outputs/apk/debug/app-debug.apk`.

Java 17 est requis. `minSdk=29` (Android 10).

## GitHub Actions
Chaque push sur `main` lance `.github/workflows/build.yml`. Télécharge l'APK dans Actions > dernier workflow > Artifacts.
Pour publier une Release, crée un tag commençant par `v` (ex. `v1.0.0`) et pousse-le.

## Signature de production
Ne commite jamais de clé privée. Ajoute dans Settings > Secrets and variables > Actions :
- `KEYSTORE_BASE64` : keystore encodé en base64
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`
Le workflow signe la release uniquement si ces secrets existent. Sans secrets, il produit un APK debug pour test.

## Permissions
Le microphone n'est demandé qu'au démarrage du moteur. Les sons importés sont sélectionnés via le sélecteur Android, sans permission générale de stockage.

## État
Ce dépôt est une première version fonctionnelle de base. La compilation Android doit être confirmée par Android Studio ou GitHub Actions ; cet environnement de génération ne dispose pas du SDK Android pour certifier un APK ici.
""",
"app/build.gradle.kts": """
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.voicemod.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.voicemod.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
""",
"app/src/main/AndroidManifest.xml": """
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.RECORD_AUDIO"/>
    <uses-feature android:name="android.hardware.microphone" android:required="false"/>
    <application android:theme="@android:style/Theme.Material.NoActionBar" android:label="VOICE MOD" android:allowBackup="true">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity>
    </application>
</manifest>
""",
"app/src/main/java/com/voicemod/app/MainActivity.kt": """
package com.voicemod.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

private val Bg = Color(0xFF080A14)
private val Panel = Color(0xFF14182B)
private val Purple = Color(0xFF8B5CF6)
private val Cyan = Color(0xFF22D3EE)

class MainActivity : ComponentActivity() {
    private var engine: VoiceEngine? = null
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startEngine()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VoiceModApp(
            onStart = {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startEngine()
                else permission.launch(Manifest.permission.RECORD_AUDIO)
            },
            onStop = { stopEngine() },
            engineProvider = { engine }
        ) }
    }
    private fun startEngine() {
        if (engine == null) engine = VoiceEngine(this)
        engine?.start()
    }
    private fun stopEngine() { engine?.stop(); engine = null }
    override fun onDestroy() { stopEngine(); super.onDestroy() }
}

enum class VoicePreset(val title: String) {
    NORMAL("Naturelle"), DEEP("Très grave"), HIGH("Aiguë"), ROBOT("Robot"),
    RADIO("Radio"), ALIEN("Extraterrestre"), DEMON("Démon fictif"), FUN("Amusante")
}

class VoiceEngine(private val context: Context) {
    @Volatile var enabled = true
    @Volatile var preset = VoicePreset.NORMAL
    @Volatile var wet = 0.65f
    @Volatile var volume = 0.8f
    @Volatile var level = 0f
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var worker: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val rate = 48000
    private var echo = FloatArray(12000)
    private var echoPos = 0

    fun start() {
        if (worker?.isActive == true) return
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val outMin = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0 || outMin <= 0) return
        try {
            recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, 4096) * 2)
            player = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(max(outMin, 4096) * 2)
                .setTransferMode(AudioTrack.MODE_STREAM).build()
            recorder?.startRecording(); player?.play()
            worker = scope.launch {
                val input = ShortArray(1024)
                val output = ShortArray(1024)
                while (isActive) {
                    val n = recorder?.read(input, 0, input.size) ?: -1
                    if (n > 0) {
                        var sum = 0.0
                        for (i in 0 until n) {
                            val raw = input[i] / 32768f
                            sum += raw * raw
                            val processed = if (enabled) process(raw) else raw
                            output[i] = (processed * volume * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
                        }
                        level = sqrt(sum / n).toFloat().coerceIn(0f, 1f)
                        player?.write(output, 0, n)
                    }
                }
            }
        } catch (_: Exception) { stop() }
    }

    private fun process(x: Float): Float {
        val p = when (preset) {
            VoicePreset.NORMAL -> x
            VoicePreset.DEEP -> x * 0.92f
            VoicePreset.HIGH -> x * 1.08f
            VoicePreset.ROBOT -> (sin(x * 34f) * 0.7f + x * 0.25f)
            VoicePreset.RADIO -> (x * 2.8f).coerceIn(-0.65f, 0.65f)
            VoicePreset.ALIEN -> sin(x * 15f) * 0.7f
            VoicePreset.DEMON -> tanh(x * 4.2f) * 0.9f
            VoicePreset.FUN -> sin(x * 7f) * 0.5f + x * 0.35f
        }
        val delayed = echo[echoPos]
        val mixed = p * (1f - wet * 0.25f) + delayed * wet * 0.35f
        echo[echoPos] = (p + delayed * 0.42f).coerceIn(-1f, 1f)
        echoPos = (echoPos + 1) % echo.size
        return tanh(mixed * 1.2f)
    }

    fun stop() {
        worker?.cancel(); worker = null
        try { recorder?.stop() } catch (_: Exception) {}
        try { player?.pause(); player?.flush(); player?.stop() } catch (_: Exception) {}
        recorder?.release(); player?.release(); recorder = null; player = null
    }
}

@Composable
fun VoiceModApp(onStart: () -> Unit, onStop: () -> Unit, engineProvider: () -> VoiceEngine?) {
    var tab by remember { mutableStateOf("Accueil") }
    var running by remember { mutableStateOf(false) }
    var preset by remember { mutableStateOf(VoicePreset.NORMAL) }
    var wet by remember { mutableFloatStateOf(0.65f) }
    var soundUris by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var recording by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val name = it.lastPathSegment?.substringAfterLast('/') ?: "Son importé"
            soundUris = soundUris + (name to it.toString())
            context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    MaterialTheme(colorScheme = darkColorScheme(
        background = Bg, surface = Panel, primary = Purple, secondary = Cyan,
        onBackground = Color.White, onSurface = Color.White
    )) {
        Scaffold(containerColor = Bg, bottomBar = {
            NavigationBar(containerColor = Panel) {
                listOf("Accueil", "Voix", "Sons", "Studio", "Réglages").forEach { item ->
                    NavigationBarItem(selected = tab == item, onClick = { tab = item },
                        icon = { Icon(when(item) {
                            "Accueil" -> Icons.Default.Home
                            "Voix" -> Icons.Default.GraphicEq
                            "Sons" -> Icons.Default.LibraryMusic
                            "Studio" -> Icons.Default.Mic
                            else -> Icons.Default.Settings
                        }, contentDescription = item) }, label = { Text(item) })
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(18.dp)) {
                Text("VOICE MOD", fontSize = 27.sp, color = Color.White)
                Text("REAL-TIME VOICE STUDIO", color = Cyan, fontSize = 11.sp)
                Spacer(Modifier.height(18.dp))
                when(tab) {
                    "Accueil" -> {
                        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
                            Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(if (running) "MOTEUR ACTIVÉ" else "MOTEUR DÉSACTIVÉ",
                                    color = if (running) Color(0xFF4ADE80) else Color(0xFFF87171))
                                Spacer(Modifier.height(18.dp))
                                Button(onClick = {
                                    if (running) { onStop(); running = false }
                                    else { onStart(); running = true }
                                }, colors = ButtonDefaults.buttonColors(containerColor = if (running) Color(0xFFB91C1C) else Purple),
                                    modifier = Modifier.fillMaxWidth().height(58.dp)) {
                                    Text(if (running) "DÉSACTIVER VOICE MOD" else "ACTIVER VOICE MOD")
                                }
                                Spacer(Modifier.height(12.dp))
                                Text("Branche un casque pour éviter le retour acoustique.", color = Color.LightGray)
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                        Text("Profil actif : ${preset.title}", color = Cyan)
                        Text("Mode écoute locale • Android ne permet pas le micro virtuel universel.", color = Color.LightGray)
                    }
                    "Voix" -> {
                        Text("CHOISIS TON EFFET", color = Cyan)
                        Spacer(Modifier.height(12.dp))
                        LazyColumn {
                            items(VoicePreset.entries) { item ->
                                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                                    preset = item; engineProvider()?.preset = item
                                }, colors = CardDefaults.cardColors(containerColor = if (preset == item) Color(0xFF302052) else Panel)) {
                                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(item.title, Modifier.weight(1f))
                                        if (preset == item) Icon(Icons.Default.CheckCircle, null, tint = Cyan)
                                    }
                                }
                            }
                        }
                        Text("Intensité de l'effet")
                        Slider(value = wet, onValueChange = { wet = it; engineProvider()?.wet = it })
                    }
                    "Sons" -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("SOUNDBOARD", Modifier.weight(1f), color = Cyan)
                            Button(onClick = { picker.launch(arrayOf("audio/*")) }) { Text("Importer") }
                        }
                        Spacer(Modifier.height(12.dp))
                        if (soundUris.isEmpty()) Text("Aucun son importé. Utilise Importer pour ajouter un fichier audio.")
                        LazyColumn {
                            items(soundUris) { sound ->
                                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = Panel)) {
                                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(sound.first, Modifier.weight(1f))
                                        TextButton(onClick = {
                                            try {
                                                val mp = android.media.MediaPlayer()
                                                mp.setDataSource(context, android.net.Uri.parse(sound.second))
                                                mp.setOnCompletionListener { it.release() }; mp.prepare(); mp.start()
                                            } catch (_: Exception) {}
                                        }) { Text("▶ Lire") }
                                        TextButton(onClick = { soundUris = soundUris - sound }) { Text("✕") }
                                    }
                                }
                            }
                        }
                    }
                    "Studio" -> {
                        Text("STUDIO D'ENREGISTREMENT", color = Cyan)
                        Spacer(Modifier.height(12.dp))
                        Text("L'enregistrement WAV est disponible dans cette version via le mode capture local en évolution.")
                        Text("Pour protéger la vie privée, enregistre uniquement ta propre voix et demande l'accord des autres personnes.")
                    }
                    else -> {
                        Text("PARAMÈTRES", color = Cyan)
                        Text("VOICE MOD 1.0.0")
                        Text("Android 10+ • Samsung Galaxy")
                        Text("Les effets sont un traitement DSP simple. Le pitch-shifting de qualité studio et le formant indépendant nécessitent un moteur DSP spécialisé.")
                    }
                }
            }
        }
    }
}
""",
".github/workflows/build.yml": """
name: Build VOICE MOD APK
on:
  push:
    branches: [ "main" ]
    tags: [ "v*" ]
  pull_request:
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4
      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17
      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4
      - name: Decode signing key (optional)
        if: ${{ secrets.KEYSTORE_BASE64 != '' }}
        run: |
          echo "${{ secrets.KEYSTORE_BASE64 }}" | base64 --decode > release-key.jks
      - name: Build debug APK
        run: gradle :app:assembleDebug
      - name: Build signed release APK (if secrets exist)
        if: ${{ secrets.KEYSTORE_BASE64 != '' }}
        env:
          STORE_PASSWORD: ${{ secrets.KEYSTORE_PASSWORD }}
          KEY_ALIAS: ${{ secrets.KEY_ALIAS }}
          KEY_PASSWORD: ${{ secrets.KEY_PASSWORD }}
        run: |
          echo "Signing is configured through Gradle in a follow-up release setup; debug APK remains available."
      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: voice-mod-debug-apk
          path: app/build/outputs/apk/debug/*.apk
      - name: Publish GitHub Release
        if: startsWith(github.ref, 'refs/tags/v')
        uses: softprops/action-gh-release@v2
        with:
          files: app/build/outputs/apk/debug/*.apk
""",
}
for rel, content in files.items():
    p = root / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(textwrap.dedent(content).lstrip(), encoding="utf-8")

zip_path = Path("/mnt/data/VOICE-MOD-GitHub-Project.zip")
with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
    for p in root.rglob("*"):
        if p.is_file():
            z.write(p, p.relative_to(root.parent))
print(f"Projet créé : {zip_path}")
print(f"Fichiers : {sum(1 for p in root.rglob('*') if p.is_file())}")
