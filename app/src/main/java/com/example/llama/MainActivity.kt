package com.example.llama

import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.documentfile.provider.DocumentFile
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.isModelLoaded
import com.arm.aichat.isUninterruptible
import com.arm.aichat.gguf.GgufMetadata
import com.arm.aichat.gguf.GgufMetadataReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var toolbarTitleTv: TextView
    private lateinit var toolbarSubtitleTv: TextView
    private lateinit var loadingProgress: android.widget.ProgressBar
    private lateinit var messagesRv: RecyclerView
    private lateinit var emptyState: android.view.View
    private lateinit var userInputEt: EditText
    private lateinit var sendBtn: ImageButton
    private lateinit var importBtn: ImageButton
    private lateinit var attachBtn: ImageButton
    private lateinit var attachmentChipRoot: View
    private lateinit var attachmentChipName: TextView
    private lateinit var attachmentChipRemove: ImageButton

    private lateinit var rowNewChat: LinearLayout
    private lateinit var rowImportModel: LinearLayout
    private lateinit var rowImportMmproj: LinearLayout
    private lateinit var rowSettings: LinearLayout
    private lateinit var modelsContainer: LinearLayout
    private lateinit var modelsEmptyHint: TextView
    private lateinit var chatsContainer: LinearLayout
    private lateinit var chatsEmptyHint: TextView

    // Bound only while the info/settings bottom sheet is visible.
    private var sheetStatusTv: TextView? = null
    private var sheetDetailsTv: TextView? = null

    private var latestStatusText: String = ""

    private lateinit var engine: InferenceEngine
    private var generationJob: Job? = null

    private val messages = mutableListOf<Message>()
    private val models = mutableListOf<ModelEntry>()
    private val chats = mutableListOf<ChatSession>()
    private val messageAdapter = MessageAdapter(messages)

    private var loadedModelPath: String? = null
    private var selectedModelId: String? = null
    private var activeChatId: String? = null
    private var isBusy = false
    private var pendingAttachment: Attachment? = null

    private var generationSettings = GenerationSettings()

    private val modelsFile by lazy { File(filesDir, "models_registry.json") }
    private val historyFile by lazy { File(filesDir, "chat_history.json") }
    private val chatsFile by lazy { File(filesDir, "chats_v1.json") }
    private val settingsFile by lazy { File(filesDir, "generation_settings.json") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        drawerLayout = findViewById(R.id.drawer_layout)
        toolbar = findViewById(R.id.toolbar)
        toolbarTitleTv = findViewById(R.id.toolbar_title)
        toolbarSubtitleTv = findViewById(R.id.toolbar_subtitle)
        loadingProgress = findViewById(R.id.loading_progress)
        messagesRv = findViewById(R.id.messages)
        emptyState = findViewById(R.id.empty_state)
        userInputEt = findViewById(R.id.user_input)
        sendBtn = findViewById(R.id.btn_send)
        importBtn = findViewById(R.id.btn_import)
        attachBtn = findViewById(R.id.btn_attach)
        attachmentChipRoot = findViewById(R.id.attachment_chip)
        attachmentChipName = findViewById(R.id.attachment_chip_name)
        attachmentChipRemove = findViewById(R.id.attachment_chip_remove)

        rowNewChat = findViewById(R.id.row_new_chat)
        rowImportModel = findViewById(R.id.row_import_model)
        rowImportMmproj = findViewById(R.id.row_import_mmproj)
        rowSettings = findViewById(R.id.row_settings)
        modelsContainer = findViewById(R.id.models_container)
        modelsEmptyHint = findViewById(R.id.models_empty_hint)
        chatsContainer = findViewById(R.id.chats_container)
        chatsEmptyHint = findViewById(R.id.chats_empty_hint)

        setSupportActionBar(toolbar)
        val drawerToggle = ActionBarDrawerToggle(
            this, drawerLayout, toolbar,
            R.string.action_new_chat, R.string.action_new_chat
        )
        drawerLayout.addDrawerListener(drawerToggle)
        drawerToggle.syncState()

        messagesRv.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        messagesRv.adapter = messageAdapter

        engine = AiChat.getInferenceEngine(applicationContext)

        lifecycleScope.launch {
            engine.state.collectLatest { renderState(it) }
        }

        importBtn.setOnClickListener { getContent.launch(arrayOf("*/*")) }
        attachBtn.setOnClickListener { getAttachmentContent.launch(arrayOf("*/*")) }
        attachmentChipRemove.setOnClickListener { clearAttachment() }
        sendBtn.setOnClickListener { handleUserInput() }
        userInputEt.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updateSendButtonState()
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })

        rowNewChat.setOnClickListener { startNewChat() }
        rowImportModel.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            getContent.launch(arrayOf("*/*"))
        }
        rowImportMmproj.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            val model = currentSelectedModel()
            if (model == null) {
                Toast.makeText(this, getString(R.string.toast_mmproj_no_model), Toast.LENGTH_SHORT).show()
            } else {
                modelAwaitingMmproj = model
                getMmprojContent.launch(arrayOf("*/*"))
            }
        }
        rowSettings.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            showModelSettingsSheet()
        }

        restoreModels()
        restoreChats()
        restoreGenerationSettings()
        renderModelsList()
        renderChatsList()
        currentChat()?.let { chat ->
            messages.clear()
            messages.addAll(chat.messages)
            messageAdapter.notifyDataSetChanged()
            if (messages.isNotEmpty()) messagesRv.scrollToPosition(messages.lastIndex)
        }
        currentSelectedModel()?.let { loadModelIntoMemory(it) }
        updateEmptyState()
    }

    private val getContent = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { handleSelectedModel(it) }
    }

    private val getAttachmentContent = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { handleAttachmentPicked(it) }
    }

    private fun handleSelectedModel(uri: Uri) {
        importBtn.isEnabled = false
        setStatusText(getString(R.string.status_importing))
        userInputEt.hint = getString(R.string.hint_importing)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val metadata = contentResolver.openInputStream(uri)?.buffered()?.use {
                    GgufMetadataReader.create().readStructuredMetadata(it)
                } ?: error("Не удалось прочитать GGUF")

                val originalName = queryDisplayName(uri) ?: metadata.filename()
                val targetName = buildTargetFileName(originalName, metadata)
                val storedFile = contentResolver.openInputStream(uri)?.use { input ->
                    ensureModelFile(targetName, input)
                } ?: error("Не удалось импортировать GGUF")

                val modelEntry = ModelEntry(
                    id = UUID.randomUUID().toString(),
                    displayName = metadata.basic.name ?: originalName.removeSuffix(FILE_EXTENSION_GGUF),
                    fileName = storedFile.name,
                    filePath = storedFile.absolutePath,
                    architecture = metadata.architecture?.architecture,
                    sizeLabel = metadata.basic.sizeLabel,
                    contextLength = metadata.dimensions?.contextLength,
                    sourceName = originalName
                )

                upsertModel(modelEntry)
                saveModels()

                withContext(Dispatchers.Main) {
                    selectedModelId = modelEntry.id
                    renderModelsList()
                    Toast.makeText(this@MainActivity, getString(R.string.toast_model_imported, modelEntry.displayName), Toast.LENGTH_SHORT).show()
                    loadModelIntoMemory(modelEntry)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setStatusText(e.message ?: getString(R.string.status_error))
                    Toast.makeText(this@MainActivity, e.message ?: getString(R.string.status_error), Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    importBtn.isEnabled = true
                    updateIdleHint()
                }
            }
        }
    }

    private suspend fun ensureModelFile(modelName: String, input: InputStream): File =
        withContext(Dispatchers.IO) {
            File(ensureModelsDirectory(), modelName).also { file ->
                FileOutputStream(file).use { input.copyTo(it) }
            }
        }

    // ===================== File attachment (read-once context) =====================

    private fun handleAttachmentPicked(uri: Uri) {
        val mimeType = contentResolver.getType(uri)
        if (mimeType?.startsWith("image/") == true) {
            handleImageAttachmentPicked(uri)
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "файл"
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error(getString(R.string.status_error))

                if (bytes.size > MAX_ATTACHMENT_BYTES) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.toast_attachment_too_big, MAX_ATTACHMENT_BYTES / 1024),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                }

                val text = runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull()
                if (text == null || looksBinary(text)) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, getString(R.string.toast_attachment_not_text), Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    pendingAttachment = Attachment(name, text)
                    renderAttachmentChip()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, e.message ?: getString(R.string.status_error), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun looksBinary(text: String): Boolean {
        if (text.isEmpty()) return false
        val sampleLen = minOf(text.length, 2000)
        var suspicious = 0
        for (i in 0 until sampleLen) {
            val c = text[i]
            if (c == '\u0000') return true
            if (c.code < 32 && c != '\n' && c != '\r' && c != '\t') suspicious++
        }
        return suspicious.toDouble() / sampleLen > 0.05
    }

    private fun renderAttachmentChip() {
        val attachment = pendingAttachment
        attachmentChipRoot.visibility = if (attachment != null) View.VISIBLE else View.GONE
        attachmentChipName.text = if (attachment != null) {
            if (attachment.isImage) "🖼 ${attachment.name}" else attachment.name
        } else {
            ""
        }
    }

    private fun clearAttachment() {
        pendingAttachment = null
        renderAttachmentChip()
    }

    // ===================== Image attachment (VL models) =====================

    /** Set right before launching [getMmprojContent], so the result can be attached to the right model. */
    private var modelAwaitingMmproj: ModelEntry? = null

    private val getMmprojContent = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val model = modelAwaitingMmproj
        modelAwaitingMmproj = null
        if (uri != null && model != null) handleMmprojPicked(uri, model)
    }

    private fun handleImageAttachmentPicked(uri: Uri) {
        val model = currentSelectedModel()
        if (model == null) {
            Toast.makeText(this, getString(R.string.toast_pick_model), Toast.LENGTH_SHORT).show()
            return
        }
        if (!model.isVisionLanguageModel) {
            Toast.makeText(this, getString(R.string.toast_model_not_vision), Toast.LENGTH_LONG).show()
            return
        }
        if (model.mmprojPath == null) {
            // No vision projector configured yet for this model — ask the user to pick one
            // (a separate mmproj gguf file, usually downloaded alongside the VL model).
            Toast.makeText(this, getString(R.string.toast_pick_mmproj), Toast.LENGTH_LONG).show()
            modelAwaitingMmproj = model
            getMmprojContent.launch(arrayOf("*/*"))
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "изображение"
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error(getString(R.string.status_error))
                if (bytes.size > MAX_IMAGE_ATTACHMENT_BYTES) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.toast_attachment_too_big, MAX_IMAGE_ATTACHMENT_BYTES / 1024),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    pendingAttachment = Attachment(name = name, imageBytes = bytes)
                    renderAttachmentChip()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, e.message ?: getString(R.string.status_error), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun handleMmprojPicked(uri: Uri, model: ModelEntry) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val name = queryDisplayName(uri) ?: "mmproj.gguf"
                val storedFile = contentResolver.openInputStream(uri)?.use { input ->
                    ensureModelFile("mmproj-${model.id}-$name", input)
                } ?: error(getString(R.string.status_error))

                val updated = model.copy(mmprojPath = storedFile.absolutePath)
                upsertModel(updated)
                saveModels()

                withContext(Dispatchers.Main) {
                    renderModelsList()
                    setStatusText(getString(R.string.status_loading_mmproj))
                }

                val modelForReply = if (currentSelectedModel()?.id == updated.id) updated else null
                if (modelForReply != null) {
                    ensureEngineReady(modelForReply)
                    val ok = runCatching { engine.loadMultimodalProjector(storedFile.absolutePath) }.getOrDefault(false)
                    withContext(Dispatchers.Main) {
                        if (ok) {
                            updateIdleHint()
                            Toast.makeText(this@MainActivity, getString(R.string.toast_mmproj_loaded), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MainActivity, getString(R.string.toast_mmproj_failed), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, e.message ?: getString(R.string.status_error), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ===================== Sending messages / conversation turns =====================

    private fun handleUserInput() {
        val selectedModel = currentSelectedModel()
        if (selectedModel == null) {
            Toast.makeText(this, getString(R.string.toast_pick_model), Toast.LENGTH_SHORT).show()
            return
        }

        val typedText = userInputEt.text.toString().trim()
        val attachment = pendingAttachment
        if (typedText.isEmpty() && attachment == null) {
            Toast.makeText(this, getString(R.string.toast_empty_message), Toast.LENGTH_SHORT).show()
            return
        }

        userInputEt.text = null
        val displayText = if (attachment != null) {
            val prefix = if (attachment.isImage) "🖼 ${attachment.name}" else "📎 ${attachment.name}"
            if (typedText.isEmpty()) prefix else "$prefix\n$typedText"
        } else {
            typedText
        }
        appendMessage(Message(UUID.randomUUID().toString(), displayText, true, getString(R.string.label_you)))

        val promptForModel = if (attachment != null && !attachment.isImage) {
            if (typedText.isEmpty()) {
                getString(R.string.attachment_prompt_template_no_question, attachment.name, attachment.content)
            } else {
                getString(R.string.attachment_prompt_template, attachment.name, attachment.content, typedText)
            }
        } else {
            typedText
        }
        val imageBytesForModel = attachment?.imageBytes
        clearAttachment()

        val assistantIndex = messages.size
        appendMessage(Message(UUID.randomUUID().toString(), "", false, selectedModel.displayName))

        generationJob?.cancel()
        generationJob = lifecycleScope.launch {
            try {
                val modelForReply = currentSelectedModel() ?: selectedModel
                ensureEngineReady(modelForReply)
                runConversationTurn(promptForModel, imageBytesForModel, assistantIndex, modelForReply)
            } catch (e: Exception) {
                val current = messages.getOrNull(assistantIndex)
                if (current != null && current.content.isBlank()) {
                    messages[assistantIndex] = current.copy(content = getString(R.string.error_prefix, e.message ?: getString(R.string.status_error)))
                    messageAdapter.notifyItemChanged(assistantIndex)
                }
                syncActiveChatMessages()
            }
        }
    }

    private suspend fun runConversationTurn(
        initialPrompt: String,
        imageBytes: ByteArray?,
        firstAssistantIndex: Int,
        model: ModelEntry,
    ) {
        engine.sendUserPrompt(initialPrompt, generationSettings.maxTokens, imageBytes).collect { token ->
            val current = messages[firstAssistantIndex]
            messages[firstAssistantIndex] = current.copy(content = current.content + token)
            messageAdapter.notifyItemChanged(firstAssistantIndex)
            messagesRv.scrollToPosition(messages.lastIndex)
        }
        syncActiveChatMessages()
    }

    private var loadModelJob: Job? = null

    /**
     * Loads the given model into memory right away (on selection/import),
     * instead of waiting for the user's first message.
     */
    private fun loadModelIntoMemory(model: ModelEntry) {
        if (loadedModelPath == model.filePath && engine.state.value.isModelLoaded) return
        loadModelJob?.cancel()
        loadModelJob = lifecycleScope.launch {
            try {
                ensureEngineReady(model)
            } catch (e: Exception) {
                setStatusText(getString(R.string.error_prefix, e.message ?: getString(R.string.status_error)))
            }
        }
    }

    private suspend fun ensureEngineReady(model: ModelEntry) {
        waitForEngineInitialization()
        val state = engine.state.value
        val shouldReload = loadedModelPath != model.filePath || !state.isModelLoaded
        if (shouldReload) {
            if (state.isModelLoaded) {
                runCatching { engine.cleanUp() }
            }
            setStatusText(getString(R.string.status_loading_model, model.displayName))
            engine.loadModel(model.filePath)
            loadedModelPath = model.filePath
            val systemPrompt = generationSettings.systemPrompt.trim()
            if (systemPrompt.isNotBlank()) {
                runCatching { engine.setSystemPrompt(systemPrompt) }
            }
            model.mmprojPath?.let { mmprojPath ->
                // Best-effort: if this fails, images just won't be usable until re-picked.
                runCatching { engine.loadMultimodalProjector(mmprojPath) }
            }
        }
    }

    private suspend fun waitForEngineInitialization() {
        engine.state.first {
            it !is InferenceEngine.State.Uninitialized && it !is InferenceEngine.State.Initializing
        }
    }

    private fun stopGeneration() {
        runCatching { engine.stopGeneration() }
        generationJob?.cancel()
    }

    private fun clearConversation() {
        messages.clear()
        messageAdapter.notifyDataSetChanged()
        syncActiveChatMessages()
        updateEmptyState()
        Toast.makeText(this, getString(R.string.toast_history_cleared), Toast.LENGTH_SHORT).show()
    }

    private fun appendMessage(message: Message) {
        messages.add(message)
        messageAdapter.notifyItemInserted(messages.lastIndex)
        messagesRv.scrollToPosition(messages.lastIndex)
        syncActiveChatMessages()
        updateEmptyState()
    }

    private fun updateIdleHint() {
        userInputEt.hint = if (models.isEmpty()) {
            getString(R.string.hint_pick_model_first)
        } else {
            getString(R.string.hint_send_message)
        }
    }

    private fun renderState(state: InferenceEngine.State) {
        setStatusText(
            when (state) {
                is InferenceEngine.State.Uninitialized -> getString(R.string.status_engine_booting)
                is InferenceEngine.State.Initializing -> getString(R.string.status_engine_booting)
                is InferenceEngine.State.Initialized -> getString(R.string.status_engine_ready)
                is InferenceEngine.State.LoadingModel -> getString(R.string.status_loading_selected)
                is InferenceEngine.State.UnloadingModel -> getString(R.string.status_unloading)
                is InferenceEngine.State.ModelReady -> currentSelectedModel()?.let {
                    getString(R.string.status_model_ready, it.displayName)
                } ?: getString(R.string.status_engine_ready)
                is InferenceEngine.State.Benchmarking -> getString(R.string.status_busy)
                is InferenceEngine.State.ProcessingSystemPrompt -> getString(R.string.status_busy)
                is InferenceEngine.State.ProcessingUserPrompt -> getString(R.string.status_prompt_processing)
                is InferenceEngine.State.Generating -> getString(R.string.status_generating)
                is InferenceEngine.State.Error -> getString(R.string.error_prefix, state.exception.message ?: getString(R.string.status_error))
            }
        )

        val busy = state.isUninterruptible || state is InferenceEngine.State.Generating
        val generating = state is InferenceEngine.State.Generating || state is InferenceEngine.State.ProcessingUserPrompt
        val loadingModel = state is InferenceEngine.State.LoadingModel || state is InferenceEngine.State.Initializing

        isBusy = busy
        loadingProgress.visibility = if (loadingModel) View.VISIBLE else View.GONE

        importBtn.isEnabled = !busy
        attachBtn.isEnabled = !busy
        rowImportModel.isEnabled = !busy
        rowImportMmproj.isEnabled = !busy
        val canSend = !busy && models.isNotEmpty() && userInputEt.text?.isNotBlank() == true
        sendBtn.isEnabled = canSend
        sendBtn.setBackgroundResource(if (canSend) R.drawable.bg_send_button else R.drawable.bg_send_button_disabled)
        userInputEt.isEnabled = !busy && models.isNotEmpty()

        sheetStatusTv?.let { updateSheetStopEnabled(generating) }

        if (!busy) {
            updateIdleHint()
        }
    }

    private var sheetStopEnabled = false
    private var sheetStopButtonRef: MaterialButton? = null
    private fun updateSheetStopEnabled(generating: Boolean) {
        sheetStopEnabled = generating
        sheetStopButtonRef?.isEnabled = generating
    }

    private fun updateSendButtonState() {
        val canSend = userInputEt.isEnabled && models.isNotEmpty() && userInputEt.text?.isNotBlank() == true
        sendBtn.isEnabled = canSend
        sendBtn.setBackgroundResource(if (canSend) R.drawable.bg_send_button else R.drawable.bg_send_button_disabled)
    }

    private fun setStatusText(text: String) {
        latestStatusText = text
        toolbarSubtitleTv.text = text
        sheetStatusTv?.text = text
    }

    /** Rebuilds the drawer's model list (rows) from [models], highlighting the selection. */
    private fun renderModelsList() {
        modelsContainer.removeAllViews()
        modelsEmptyHint.visibility = if (models.isEmpty()) View.VISIBLE else View.GONE

        if (models.isEmpty()) {
            selectedModelId = null
            toolbarTitleTv.text = getString(R.string.app_name)
            updateSheetDetails(null)
            updateIdleHint()
            return
        }

        if (selectedModelId == null || models.none { it.id == selectedModelId }) {
            selectedModelId = models.last().id
        }

        val inflater = LayoutInflater.from(this)
        models.forEach { model ->
            val row = inflater.inflate(R.layout.row_model, modelsContainer, false)
            val title = row.findViewById<TextView>(R.id.model_row_title)
            val check = row.findViewById<ImageView>(R.id.model_row_check)
            val selected = model.id == selectedModelId
            title.text = model.displayName
            check.visibility = if (selected) View.VISIBLE else View.GONE
            row.setBackgroundResource(
                if (selected) R.drawable.bg_drawer_item_selected else R.drawable.bg_drawer_item
            )
            row.setOnClickListener {
                if (isBusy) {
                    Toast.makeText(this, getString(R.string.toast_busy_wait), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                selectedModelId = model.id
                renderModelsList()
                drawerLayout.closeDrawer(GravityCompat.START)
                loadModelIntoMemory(model)
            }
            modelsContainer.addView(row)
        }

        currentSelectedModel()?.let {
            toolbarTitleTv.text = it.displayName
            updateSheetDetails(it)
        }
        updateIdleHint()
    }

    private fun updateSheetDetails(model: ModelEntry?) {
        val text = if (model == null) {
            getString(R.string.no_models_text)
        } else {
            buildString {
                appendLine("Название: ${model.displayName}")
                appendLine("Файл: ${model.sourceName}")
                appendLine("Архитектура: ${model.architecture ?: "неизвестно"}")
                appendLine("Размер/квант: ${model.sizeLabel ?: "неизвестно"}")
                appendLine("Контекст: ${model.contextLength?.toString() ?: "неизвестно"}")
                append("Хранение: ${model.fileName}")
            }
        }
        sheetDetailsTv?.text = text
    }

    private fun showModelSettingsSheet() {
        val sheet = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.sheet_model_info, null)
        sheetDetailsTv = view.findViewById(R.id.sheet_model_details)
        sheetStatusTv = view.findViewById(R.id.sheet_status)
        val stopBtn = view.findViewById<MaterialButton>(R.id.sheet_btn_stop)
        val clearBtnV = view.findViewById<MaterialButton>(R.id.sheet_btn_clear)
        val systemPromptEt = view.findViewById<EditText>(R.id.sheet_system_prompt)
        val maxTokensEt = view.findViewById<EditText>(R.id.sheet_max_tokens)
        val saveSettingsBtn = view.findViewById<MaterialButton>(R.id.sheet_btn_save_settings)
        sheetStopButtonRef = stopBtn

        sheetStatusTv?.text = latestStatusText
        updateSheetDetails(currentSelectedModel())
        stopBtn.isEnabled = sheetStopEnabled
        systemPromptEt.setText(generationSettings.systemPrompt)
        maxTokensEt.setText(generationSettings.maxTokens.toString())

        stopBtn.setOnClickListener { stopGeneration() }
        clearBtnV.setOnClickListener {
            clearConversation()
            sheet.dismiss()
        }
        saveSettingsBtn.setOnClickListener {
            val newPrompt = systemPromptEt.text.toString()
            val requestedTokens = maxTokensEt.text.toString().toIntOrNull()
            val newMaxTokens = (requestedTokens ?: generationSettings.maxTokens).coerceIn(MIN_TOKENS, MAX_TOKENS)
            maxTokensEt.setText(newMaxTokens.toString())
            applyGenerationSettings(newPrompt, newMaxTokens)
        }

        sheet.setContentView(view)
        sheet.setOnDismissListener {
            sheetDetailsTv = null
            sheetStatusTv = null
            sheetStopButtonRef = null
        }
        sheet.show()
    }

    private fun applyGenerationSettings(newSystemPrompt: String, newMaxTokens: Int) {
        val oldPrompt = generationSettings.systemPrompt.trim()
        generationSettings = generationSettings.copy(
            systemPrompt = newSystemPrompt,
            maxTokens = newMaxTokens
        )
        saveGenerationSettings()
        Toast.makeText(this, getString(R.string.toast_settings_saved), Toast.LENGTH_SHORT).show()
        if (oldPrompt != newSystemPrompt.trim()) {
            val model = currentSelectedModel()
            if (model != null) {
                if (!isBusy) {
                    loadedModelPath = null
                    loadModelIntoMemory(model)
                } else {
                    Toast.makeText(this, getString(R.string.toast_prompt_will_apply_next_load), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun restoreGenerationSettings() {
        if (!settingsFile.exists()) return
        runCatching {
            val json = JSONObject(settingsFile.readText())
            generationSettings = GenerationSettings(
                systemPrompt = json.optString("systemPrompt", ""),
                maxTokens = json.optInt("maxTokens", InferenceEngine.DEFAULT_PREDICT_LENGTH).takeIf { it > 0 }
                    ?: InferenceEngine.DEFAULT_PREDICT_LENGTH
            )
        }
    }

    private fun saveGenerationSettings() {
        val json = JSONObject()
            .put("systemPrompt", generationSettings.systemPrompt)
            .put("maxTokens", generationSettings.maxTokens)
        settingsFile.writeText(json.toString())
    }

    private fun updateEmptyState() {
        emptyState.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun currentSelectedModel(): ModelEntry? =
        models.firstOrNull { it.id == selectedModelId } ?: models.firstOrNull()

    private fun restoreModels() {
        if (!modelsFile.exists()) return
        runCatching {
            val restored = JSONArray(modelsFile.readText())
            for (index in 0 until restored.length()) {
                ModelEntry.fromJson(restored.getJSONObject(index))?.let { entry ->
                    if (File(entry.filePath).exists()) {
                        models.add(entry)
                    }
                }
            }
        }
    }

    private fun saveModels() {
        val array = JSONArray()
        models.forEach { array.put(it.toJson()) }
        modelsFile.writeText(array.toString())
    }

    private fun upsertModel(modelEntry: ModelEntry) {
        val existingIndex = models.indexOfFirst { it.filePath == modelEntry.filePath || it.displayName == modelEntry.displayName }
        if (existingIndex >= 0) {
            models[existingIndex] = modelEntry.copy(id = models[existingIndex].id)
        } else {
            models.add(modelEntry)
        }
    }

    // ===================== Chats =====================

    private fun currentChat(): ChatSession? =
        chats.firstOrNull { it.id == activeChatId } ?: chats.firstOrNull()

    private fun restoreChats() {
        if (chatsFile.exists()) {
            runCatching {
                val root = JSONObject(chatsFile.readText())
                activeChatId = root.optString("activeChatId").takeIf { it.isNotBlank() }
                val array = root.optJSONArray("chats") ?: JSONArray()
                for (index in 0 until array.length()) {
                    ChatSession.fromJson(array.getJSONObject(index))?.let(chats::add)
                }
            }
        }

        if (chats.isEmpty()) {
            // First run after the chats feature was added: migrate the old
            // single global history (if any) into the first chat.
            val migratedMessages = mutableListOf<Message>()
            if (historyFile.exists()) {
                runCatching {
                    val array = JSONArray(historyFile.readText())
                    for (index in 0 until array.length()) {
                        Message.fromJson(array.getJSONObject(index))?.let(migratedMessages::add)
                    }
                }
            }
            val now = System.currentTimeMillis()
            val chat = ChatSession(
                id = UUID.randomUUID().toString(),
                title = getString(R.string.chat_default_title),
                pinned = false,
                titleManual = false,
                createdAt = now,
                updatedAt = now,
                messages = migratedMessages
            )
            migratedMessages.firstOrNull { it.isUser }?.content?.trim()?.takeIf { it.isNotBlank() }?.let {
                chat.title = truncateTitle(it)
                chat.titleManual = false
            }
            chats.add(chat)
        }

        if (activeChatId == null || chats.none { it.id == activeChatId }) {
            activeChatId = chats.maxByOrNull { it.updatedAt }?.id
        }
    }

    private fun saveChats() {
        val root = JSONObject()
        root.put("activeChatId", activeChatId ?: JSONObject.NULL)
        val array = JSONArray()
        chats.forEach { array.put(it.toJson()) }
        root.put("chats", array)
        chatsFile.writeText(root.toString())
    }

    /** Copies the on-screen [messages] into the active [ChatSession] and persists everything. */
    private fun syncActiveChatMessages() {
        val chat = currentChat() ?: return
        chat.messages.clear()
        chat.messages.addAll(messages)
        chat.updatedAt = System.currentTimeMillis()
        if (!chat.titleManual) {
            val firstUser = messages.firstOrNull { it.isUser }?.content?.trim()
            if (!firstUser.isNullOrBlank()) {
                chat.title = truncateTitle(firstUser)
            }
        }
        saveChats()
        renderChatsList()
    }

    private fun truncateTitle(text: String): String =
        if (text.length > 40) text.take(40).trimEnd() + "…" else text

    private fun startNewChat() {
        if (isBusy) {
            Toast.makeText(this, getString(R.string.toast_busy_wait), Toast.LENGTH_SHORT).show()
            return
        }

        // Avoid piling up empty "Новый чат" entries: reuse the current chat
        // if it's already a fresh, untitled, unpinned, empty one.
        val current = currentChat()
        if (current != null && current.messages.isEmpty() && !current.titleManual && !current.pinned) {
            drawerLayout.closeDrawer(GravityCompat.START)
            return
        }

        val now = System.currentTimeMillis()
        val chat = ChatSession(
            id = UUID.randomUUID().toString(),
            title = getString(R.string.chat_default_title),
            pinned = false,
            titleManual = false,
            createdAt = now,
            updatedAt = now,
            messages = mutableListOf()
        )
        chats.add(0, chat)
        activeChatId = chat.id
        saveChats()
        switchToChat(chat)
    }

    private fun switchToChat(chat: ChatSession, closeDrawer: Boolean = true) {
        activeChatId = chat.id
        messages.clear()
        messages.addAll(chat.messages)
        messageAdapter.notifyDataSetChanged()
        if (messages.isNotEmpty()) {
            messagesRv.scrollToPosition(messages.lastIndex)
        }
        updateEmptyState()
        renderChatsList()
        if (closeDrawer) {
            drawerLayout.closeDrawer(GravityCompat.START)
        }
    }

    private fun renderChatsList() {
        chatsContainer.removeAllViews()
        chatsEmptyHint.visibility = if (chats.isEmpty()) View.VISIBLE else View.GONE

        val sorted = chats.sortedWith(
            compareByDescending<ChatSession> { it.pinned }.thenByDescending { it.updatedAt }
        )
        val inflater = LayoutInflater.from(this)
        sorted.forEach { chat ->
            val row = inflater.inflate(R.layout.row_chat, chatsContainer, false)
            val title = row.findViewById<TextView>(R.id.chat_row_title)
            val pin = row.findViewById<ImageView>(R.id.chat_row_pin)
            val more = row.findViewById<ImageButton>(R.id.chat_row_more)
            val selected = chat.id == activeChatId

            title.text = chat.title
            pin.visibility = if (chat.pinned) View.VISIBLE else View.GONE
            row.setBackgroundResource(
                if (selected) R.drawable.bg_drawer_item_selected else R.drawable.bg_drawer_item
            )

            row.setOnClickListener {
                if (isBusy) {
                    Toast.makeText(this, getString(R.string.toast_busy_wait), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (chat.id != activeChatId) {
                    switchToChat(chat)
                } else {
                    drawerLayout.closeDrawer(GravityCompat.START)
                }
            }
            more.setOnClickListener { showChatRowMenu(more, chat) }

            chatsContainer.addView(row)
        }
    }

    private fun showChatRowMenu(anchor: View, chat: ChatSession) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.chat_row_menu, popup.menu)
        popup.menu.findItem(R.id.menu_pin_toggle).title =
            getString(if (chat.pinned) R.string.action_unpin else R.string.action_pin)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_rename -> {
                    showRenameChatDialog(chat)
                    true
                }
                R.id.menu_pin_toggle -> {
                    chat.pinned = !chat.pinned
                    saveChats()
                    renderChatsList()
                    true
                }
                R.id.menu_delete -> {
                    confirmDeleteChat(chat)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showRenameChatDialog(chat: ChatSession) {
        val density = resources.displayMetrics.density
        val horizontalPadding = (20 * density).toInt()
        val topPadding = (8 * density).toInt()

        val input = EditText(this).apply {
            setText(chat.title)
            setSingleLine()
            setSelection(text.length)
        }
        val container = FrameLayout(this).apply {
            setPadding(horizontalPadding, topPadding, horizontalPadding, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_rename_title)
            .setView(container)
            .setPositiveButton(R.string.action_ok) { _, _ ->
                val newTitle = input.text.toString().trim()
                if (newTitle.isNotEmpty()) {
                    chat.title = newTitle
                    chat.titleManual = true
                    saveChats()
                    renderChatsList()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun confirmDeleteChat(chat: ChatSession) {
        if (isBusy && chat.id == activeChatId) {
            Toast.makeText(this, getString(R.string.toast_busy_wait), Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_delete_chat_title)
            .setMessage(getString(R.string.dialog_delete_chat_message, chat.title))
            .setPositiveButton(R.string.action_delete_chat) { _, _ -> deleteChat(chat) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun deleteChat(chat: ChatSession) {
        val wasActive = chat.id == activeChatId
        chats.remove(chat)

        if (chats.isEmpty()) {
            val now = System.currentTimeMillis()
            val fresh = ChatSession(
                id = UUID.randomUUID().toString(),
                title = getString(R.string.chat_default_title),
                pinned = false,
                titleManual = false,
                createdAt = now,
                updatedAt = now,
                messages = mutableListOf()
            )
            chats.add(fresh)
            activeChatId = fresh.id
        } else if (wasActive) {
            activeChatId = chats.maxByOrNull { it.updatedAt }?.id
        }

        saveChats()
        Toast.makeText(this, getString(R.string.toast_chat_deleted), Toast.LENGTH_SHORT).show()

        if (wasActive) {
            currentChat()?.let { switchToChat(it, closeDrawer = false) }
        } else {
            renderChatsList()
        }
    }

    // ===================== Model file helpers =====================

    private fun ensureModelsDirectory(): File =
        File(filesDir, DIRECTORY_MODELS).also {
            if (it.exists() && !it.isDirectory) it.delete()
            if (!it.exists()) it.mkdirs()
        }

    private fun queryDisplayName(uri: Uri): String? {
        val cursor: Cursor? = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        return cursor?.use {
            if (it.moveToFirst()) {
                it.getString(0)
            } else null
        }
    }

    private fun buildTargetFileName(originalName: String, metadata: GgufMetadata): String {
        val base = (metadata.basic.name ?: originalName.removeSuffix(FILE_EXTENSION_GGUF))
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_')
            .ifBlank { "model" }
        val size = metadata.basic.sizeLabel?.replace(Regex("[^A-Za-z0-9._-]"), "_") ?: "gguf"
        return "$base-$size$FILE_EXTENSION_GGUF"
    }

    override fun onDestroy() {
        generationJob?.cancel()
        runCatching { engine.destroy() }
        super.onDestroy()
    }

    companion object {
        private const val DIRECTORY_MODELS = "models"
        private const val FILE_EXTENSION_GGUF = ".gguf"
        private const val MIN_TOKENS = 32
        private const val MAX_TOKENS = 4096
        private const val MAX_ATTACHMENT_BYTES = 150 * 1024
        private const val MAX_IMAGE_ATTACHMENT_BYTES = 15 * 1024 * 1024
    }
}

private data class ModelEntry(
    val id: String,
    val displayName: String,
    val fileName: String,
    val filePath: String,
    val architecture: String?,
    val sizeLabel: String?,
    val contextLength: Int?,
    val sourceName: String,
    /** Path to a separately imported mmproj (vision projector) gguf, if any. */
    val mmprojPath: String? = null,
) {
    /** True if this model's name suggests it's a vision-language model (e.g. "...-VL-...") */
    val isVisionLanguageModel: Boolean
        get() = VL_NAME_REGEX.containsMatchIn(displayName) || VL_NAME_REGEX.containsMatchIn(fileName)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("displayName", displayName)
        .put("fileName", fileName)
        .put("filePath", filePath)
        .put("architecture", architecture)
        .put("sizeLabel", sizeLabel)
        .put("contextLength", contextLength)
        .put("sourceName", sourceName)
        .put("mmprojPath", mmprojPath)

    companion object {
        // Matches "VL" as a standalone token in model names, e.g. "Qwen2-VL-7B", "InternVL-2".
        // Word boundary on the left only, since names like "InternVL" glue it to the previous word.
        private val VL_NAME_REGEX = Regex("VL", RegexOption.IGNORE_CASE)

        fun fromJson(json: JSONObject): ModelEntry? = runCatching {
            ModelEntry(
                id = json.optString("id"),
                displayName = json.optString("displayName"),
                fileName = json.optString("fileName"),
                filePath = json.optString("filePath"),
                architecture = json.optString("architecture").takeIf { it.isNotBlank() },
                sizeLabel = json.optString("sizeLabel").takeIf { it.isNotBlank() },
                contextLength = if (json.isNull("contextLength")) null else json.optInt("contextLength"),
                sourceName = json.optString("sourceName"),
                mmprojPath = json.optString("mmprojPath").takeIf { it.isNotBlank() },
            )
        }.getOrNull()
    }
}

/** User-configurable generation options, applied on top of the loaded model. */
private data class GenerationSettings(
    val systemPrompt: String = "",
    val maxTokens: Int = InferenceEngine.DEFAULT_PREDICT_LENGTH,
)

/**
 * A file the user attached to their next message (read once, not persisted).
 * Either a text/code file ([content] set) or an image for a VL model ([imageBytes] set).
 */
private data class Attachment(
    val name: String,
    val content: String? = null,
    val imageBytes: ByteArray? = null,
) {
    val isImage: Boolean get() = imageBytes != null
}

fun GgufMetadata.filename(): String = when {
    basic.name != null -> {
        basic.name?.let { name ->
            basic.sizeLabel?.let { size -> "$name-$size" } ?: name
        } ?: "model"
    }
    architecture?.architecture != null -> {
        val arch = architecture?.architecture ?: "model"
        val uuid = basic.uuid ?: java.lang.Long.toHexString(System.currentTimeMillis())
        "$arch-$uuid"
    }
    else -> "model-${java.lang.Long.toHexString(System.currentTimeMillis())}"
}
