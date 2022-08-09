package com.daniilk.netologyvoiceassistant

import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.SimpleAdapter
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.wolfram.alpha.WAEngine
import com.wolfram.alpha.WAPlainText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*

class MainActivity : AppCompatActivity() {
    private lateinit var requestInput: TextInputEditText
    private lateinit var podsAdapter: SimpleAdapter
    private lateinit var progressBar: ProgressBar

    private lateinit var waEngine: WAEngine

    // данные
    private val pods = mutableListOf<HashMap<String, String>>()

    private lateinit var textToSpeech: TextToSpeech
    private var isTTSReady: Boolean = false

    private lateinit var launcher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initViews()
        initWolframEngine()
        initTTS()
        initResult()
    }

    // инициализация view
    private fun initViews() {
        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        requestInput = findViewById(R.id.text_input_edit)
        requestInput.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                pods.clear()
                podsAdapter.notifyDataSetChanged()

                val question = requestInput.text.toString()
                askWolfram(question)
            }
            return@setOnEditorActionListener false
        }

        val podsList: ListView = findViewById(R.id.pods_list)
        podsAdapter = SimpleAdapter(
            applicationContext,
            pods,
            R.layout.item_pod,
            arrayOf("Title", "Content"),
            intArrayOf(R.id.title, R.id.content)
        )
        podsList.adapter = podsAdapter
        podsList.setOnItemClickListener { parent, view, position, id ->
            if (isTTSReady) {
                val title = pods[position]["Title"]
                val content = pods[position]["Content"]
                textToSpeech.speak(content, TextToSpeech.QUEUE_FLUSH, null, title)
            }
        }

        val voiceInputButton: FloatingActionButton = findViewById(R.id.voice_input_button)
        voiceInputButton.setOnClickListener {
            pods.clear()
            podsAdapter.notifyDataSetChanged()
            if (isTTSReady) {
                textToSpeech.stop()
            }
            showVoiceInputDialog()
        }
        progressBar = findViewById(R.id.progress_bar)
    }

    // создание движка
    private fun initWolframEngine() {
        waEngine = WAEngine().apply {
            appID = "TUQAA6-8K6A8JLELV"
            addFormat("plaintext")
        }
    }

    // отображение snackbar
    private fun showSnackbar(message: String) {
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_INDEFINITE)
            .apply {
                setAction(android.R.string.ok) {
                    dismiss()
                }
                show()
            }
    }

    // запрос
    private fun askWolfram(request: String) {
        progressBar.visibility = View.VISIBLE // показываем прогрес бар
        CoroutineScope(Dispatchers.IO).launch { // в io потоке
            val query = waEngine.createQuery().apply { input = request } // создание запроса
            runCatching { // проверка и отработка запроса
                waEngine.performQuery(query)
            }.onSuccess { result -> // обработка запрос-ответа в главном потоке
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE // скрываем прогрес бар
                    if (result.isError) { // при ошибке
                        showSnackbar(result.errorMessage)
                        return@withContext
                    }
                    if (!result.isSuccess) { // при неуспешном (неизвестной ошибке)
                        requestInput.error =
                            getString(R.string.error_do_not_understand) // раскраска поля
                        return@withContext
                    }
                    // успешно идем дальше
                    for (pod in result.pods) { // проходим по списку ответов (стручков)
                        if (pod.isError) continue // пропуск ошибок
                        val content = StringBuilder() // сбор контента (расш. текст, строчки)
                        for (subpod in pod.subpods) { // под-компоненты
                            for (element in subpod.contents) { // получение элементов
                                if (element is WAPlainText) { // проверка на текст
                                    content.append(element.text) // добавление строк
                                }
                            }
                        }
                        // добавление данных в список
                        pods.add(0, HashMap<String, String>().apply {
                            put("Title", pod.title)
                            put("Content", content.toString())
                        })
                        podsAdapter.notifyDataSetChanged() // обновление
                    }
                }
            }.onFailure { t -> // обработка ошибки в главном потоке
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    showSnackbar(t.message ?: getString(R.string.error_something_went_wrong))
                }
            }
        }
    }

    private fun initTTS() {
        textToSpeech = TextToSpeech(this) { code ->
            if (code != TextToSpeech.SUCCESS) {
                Log.e(TAG, "initTTS: TTS error code $code")
                showSnackbar(getString(R.string.error_tts_is_not_ready))
            } else {
                isTTSReady = true
            }
        }
        textToSpeech.language = Locale.US
    }

    private fun initResult() {
        launcher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
                if (result.resultCode == RESULT_OK) {
                    val res: ArrayList<String> =
                        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS) as ArrayList<String>

                    val resultStr = Objects.requireNonNull(res)[0]
                    requestInput.setText(resultStr)
                    askWolfram(resultStr)
                }
            }
    }

    private fun showVoiceInputDialog() {
        val language = "us-US"
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                language)
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.request_hint))
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        }
        runCatching {
            launcher.launch(intent)
        }.onFailure { t ->
            showSnackbar(t.message ?: getString(R.string.error_voice_recognition_unavailable))
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.toolbar_menu, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_stop -> {
                if (isTTSReady) {
                    textToSpeech.stop()
                }
                return true
            }
            R.id.action_clear -> {
                requestInput.text?.clear()
                pods.clear()
                podsAdapter.notifyDataSetChanged()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}