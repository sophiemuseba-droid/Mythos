package com.mythos.assistant

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private var recognizer: SpeechRecognizer? = null
    private var torchOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        val pad = (16 * resources.displayMetrics.density).toInt()

        log = TextView(this).apply {
            textSize = 16f
            text = "Dites par exemple : « ouvre WhatsApp », « alarme à 7 heures 30 », « minuteur 5 minutes », « allume la lampe torche », « volume plus », « batterie », « cherche la masse volumique du PET ».\n"
        }
        scroll = ScrollView(this).apply { addView(log) }
        input = EditText(this).apply {
            hint = "Écrivez une commande…"
            imeOptions = EditorInfo.IME_ACTION_SEND
            setSingleLine()
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEND) { send(); true } else false
            }
        }
        val mic = Button(this).apply { text = "🎤 Parler"; setOnClickListener { listen() } }
        val go = Button(this).apply { text = "Envoyer"; setOnClickListener { send() } }
        val row = LinearLayout(this).apply {
            addView(mic, LinearLayout.LayoutParams(0, -2, 1f))
            addView(go, LinearLayout.LayoutParams(0, -2, 1f))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(input, LinearLayout.LayoutParams(-1, -2))
            addView(row, LinearLayout.LayoutParams(-1, -2))
        })
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts.language = Locale.FRANCE
    }

    override fun onDestroy() {
        recognizer?.destroy(); tts.shutdown(); super.onDestroy()
    }

    // ---------- Sortie ----------
    private fun say(t: String) {
        log.append("Assistant : $t\n\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "a")
    }

    private fun send() {
        val t = input.text.toString().trim()
        input.setText("")
        if (t.isNotEmpty()) handle(t)
    }

    // ---------- Entrée vocale ----------
    private fun listen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            say("La reconnaissance vocale n’est pas disponible sur cet appareil."); return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(r: Bundle?) {
                    r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { handle(it) }
                }
                override fun onError(e: Int) { say("Je n’ai rien entendu, réessayez.") }
                override fun onReadyForSpeech(p: Bundle?) { Toast.makeText(this@MainActivity, "Je vous écoute…", Toast.LENGTH_SHORT).show() }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(p: Bundle?) {}
                override fun onEvent(t: Int, p: Bundle?) {}
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            })
        }
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        if (r.firstOrNull() == PackageManager.PERMISSION_GRANTED) listen()
        else say("Sans l’autorisation du micro, utilisez la saisie au clavier.")
    }

    // ---------- Actions système ----------
    private fun launch(i: Intent, ok: String) {
        try { startActivity(i); say(ok) } catch (e: ActivityNotFoundException) { say("Aucune application ne peut traiter cette demande.") }
    }

    private fun handle(raw: String) {
        log.append("Vous : $raw\n")
        val q = raw.lowercase(Locale.FRANCE).trim()
        val loc = Locale.FRANCE
        when {
            Regex("quelle heure").containsMatchIn(q) ->
                say("Il est " + SimpleDateFormat("HH:mm", loc).format(Date()) + ".")
            Regex("quel jour|quelle date").containsMatchIn(q) ->
                say("Nous sommes le " + SimpleDateFormat("EEEE d MMMM yyyy", loc).format(Date()) + ".")

            Regex("alarme.*?(\\d{1,2})\\s*(?:h|heures?|:)\\s*(\\d{1,2})?").find(q)?.let { m ->
                val h = m.groupValues[1].toInt(); val mn = m.groupValues[2].toIntOrNull() ?: 0
                if (h in 0..23 && mn in 0..59) {
                    launch(Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, mn)
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true), "Alarme réglée à %02d:%02d.".format(h, mn))
                } else say("Heure invalide.")
                true
            } == true -> {}

            Regex("(?:minuteur|compte à rebours).*?(\\d+)\\s*(seconde|minute|heure)").find(q)?.let { m ->
                val n = m.groupValues[1].toInt()
                val s = n * when (m.groupValues[2]) { "seconde" -> 1; "minute" -> 60; else -> 3600 }
                launch(Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, s).putExtra(AlarmClock.EXTRA_SKIP_UI, true),
                    "Minuteur lancé pour ${m.groupValues[1]} ${m.groupValues[2]}${if (n > 1) "s" else ""}.")
                true
            } == true -> {}

            Regex("(?:appelle|compose)(?: le)? ([\\d\\s]+)").find(q)?.let { m ->
                val num = m.groupValues[1].filter { it.isDigit() }
                if (num.length >= 3) launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$num")), "Je prépare l’appel.")
                else say("Je n’ai pas compris le numéro.")
                true
            } == true -> {}

            Regex("(?:lampe torche|torche|lampe de poche)").containsMatchIn(q) -> {
                val on = !Regex("éteins|éteindre|coupe").containsMatchIn(q)
                try {
                    val cm = getSystemService(CameraManager::class.java)
                    val id = cm.cameraIdList.firstOrNull {
                        cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    }
                    if (id == null) say("Aucun flash détecté.")
                    else { cm.setTorchMode(id, on); torchOn = on; say(if (on) "Lampe torche allumée." else "Lampe torche éteinte.") }
                } catch (e: Exception) { say("Impossible de commander la lampe torche.") }
            }

            Regex("volume").containsMatchIn(q) -> {
                val am = getSystemService(AudioManager::class.java)
                val dir = if (Regex("plus|monte|augmente").containsMatchIn(q)) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
                say(if (dir == AudioManager.ADJUST_RAISE) "Volume augmenté." else "Volume baissé.")
            }

            Regex("wi-?fi").containsMatchIn(q) -> launch(Intent(Settings.ACTION_WIFI_SETTINGS), "Réglages Wi-Fi.")
            Regex("bluetooth").containsMatchIn(q) -> launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), "Réglages Bluetooth.")

            Regex("batterie").containsMatchIn(q) -> {
                val p = getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                say("La batterie est à $p pour cent.")
            }

            Regex("(?:itinéraire|navigue|emmène-moi)(?: vers| à| jusqu’à)? (.+)").find(q)?.let { m ->
                launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(m.groupValues[1]))), "Je cherche ${m.groupValues[1]} sur la carte.")
                true
            } == true -> {}

            Regex("(?:cherche|recherche)(?: sur le web)? (.+)").find(q)?.let { m ->
                launch(Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, m.groupValues[1]), "Voici les résultats.")
                true
            } == true -> {}

            Regex("(?:ouvre|lance)(?: l’application| l'application| le| la| l’| l')? ?(.+)").find(q)?.let { m ->
                val name = m.groupValues[1].trim()
                val pm = packageManager
                val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                val hit = apps.firstOrNull { it.loadLabel(pm).toString().lowercase(loc).contains(name) }
                val li = hit?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) }
                if (li != null) launch(li, "J’ouvre ${hit?.loadLabel(pm)}.") else say("Je n’ai pas trouvé l’application « $name ».")
                true
            } == true -> {}

            else -> say("Je n’ai pas compris cette commande.")
        }
    }
}
