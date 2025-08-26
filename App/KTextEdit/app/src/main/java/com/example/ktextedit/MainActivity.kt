package com.example.ktextedit

import androidx.compose.animation.AnimatedVisibility
import androidx.activity.compose.BackHandler
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.graphics.text.LineBreaker
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.Layout
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.amrdeveloper.codeview.CodeView
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.regex.Pattern
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import org.json.JSONObject
import androidx.core.widget.addTextChangedListener
import androidx.core.graphics.toColorInt
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState as rememberHScrollState

// ---------- Simple Undo/Redo ----------
class UndoRedoManager {
    private val undo = ArrayDeque<String>()
    private val redo = ArrayDeque<String>()
    private var suppress = false
    fun onTextChange(newText: String) {
        if (!suppress) {
            if (undo.isEmpty() || undo.last() != newText) {
                undo.addLast(newText)
                if (undo.size > 100) undo.removeFirst()
                redo.clear()
            }
        }
    }
    fun canUndo() = undo.size > 1
    fun canRedo() = redo.isNotEmpty()
    fun current(): String = if (undo.isEmpty()) "" else undo.last()
    fun setInitial(text: String) { undo.clear(); redo.clear(); undo.addLast(text) }
    fun undo(): String {
        if (undo.size <= 1) return current()
        suppress = true
        val top = undo.removeLast()
        redo.addLast(top)
        val prev = undo.last()
        suppress = false
        return prev
    }
    fun redo(): String {
        if (redo.isEmpty()) return current()
        suppress = true
        val next = redo.removeLast()
        undo.addLast(next)
        suppress = false
        return next
    }
}

data class SyntaxConfig(
    val language: String,
    val keywords: List<String> = emptyList(),
    val singleLineComment: String? = null,
    val multiLineCommentStart: String? = null,
    val multiLineCommentEnd: String? = null,
    val stringDelims: List<String> = listOf("\"", "'")
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { EditorApp() }
    }
}

// ---------- Error highlighter for editor ----------
class ErrorHighlighter {
    private val spans = mutableListOf<Any>()

    fun clear(editable: Editable) {
        spans.forEach { editable.removeSpan(it) }
        spans.clear()
    }

    fun applyToLines(editable: Editable, text: String, lines: Set<Int>) {
        clear(editable)
        if (lines.isEmpty()) return
        val bgColor = Color.parseColor("#33EF4444") // translucent red
        for (line in lines) {
            val range = lineRange(text, line) ?: continue
            val bg = BackgroundColorSpan(bgColor)
            editable.setSpan(bg, range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            spans.add(bg)
        }
    }

    private fun lineRange(text: String, lineNum: Int): IntRange? {
        if (lineNum <= 0) return null
        var cur = 1
        var start = 0
        val len = text.length
        if (lineNum == 1) {
            val end = text.indexOf('\n').let { if (it == -1) len - 1 else it - 1 } + 1
            return start..end
        }
        while (true) {
            val nl = text.indexOf('\n', start)
            if (nl == -1) {
                return if (lineNum == cur) start..(len - 1) else null
            }
            if (cur + 1 == lineNum) {
                val s = nl + 1
                val e = text.indexOf('\n', s).let { if (it == -1) len - 1 else it - 1 } + 1
                return s..e
            }
            start = nl + 1
            cur++
        }
    }
}

// ---------- Custom logical-line gutter ----------
class LogicalLineGutterView(context: Context) : View(context) {
    private var editor: CodeView? = null
    private var scrollListener: ViewTreeObserver.OnScrollChangedListener? = null
    private var textWatcher: android.text.TextWatcher? = null

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val bgPaint = Paint()
    private val dividerPaint = Paint().apply { strokeWidth = dp(1f) }
    private val errorDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = "#EF4444".toColorInt() }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private val padEnd = dp(8f)

    private var errorLines: Set<Int> = emptySet()
    fun setErrorLines(lines: Set<Int>) {
        errorLines = lines
        invalidate()
    }

    fun setTheme(dark: Boolean, matchTextSizePx: Float) {
        bgPaint.color = if (dark) "#121212".toColorInt() else "#FFFFFF".toColorInt()
        dividerPaint.color = if (dark) "#202020".toColorInt() else "#E5E7EB".toColorInt()
        textPaint.color = if (dark) "#B0B0B0".toColorInt() else "#4B5563".toColorInt()
        textPaint.textSize = matchTextSizePx * 0.9f
        invalidate()
    }

    fun setEditor(newEditor: CodeView?) {
        if (editor === newEditor) return
        editor?.let { e ->
            scrollListener?.let { e.viewTreeObserver.removeOnScrollChangedListener(it) }
            textWatcher?.let { e.removeTextChangedListener(it) }
        }
        editor = newEditor
        newEditor?.let { e ->
            scrollListener = ViewTreeObserver.OnScrollChangedListener { invalidate() }.also {
                e.viewTreeObserver.addOnScrollChangedListener(it)
            }
            textWatcher = object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { invalidate() }
                override fun afterTextChanged(s: android.text.Editable?) {}
            }.also { e.addTextChangedListener(it) }
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val e = editor ?: return
        val layout = e.layout ?: return
        val txt = e.text ?: return

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        canvas.drawLine(width - dp(0.5f), 0f, width - dp(0.5f), height.toFloat(), dividerPaint)

        val contentTop = e.totalPaddingTop
        the@ run {
            val contentBottom = e.height - e.totalPaddingBottom
            val scrollY = e.scrollY
            val startLine = layout.getLineForVertical(scrollY)
            val endLine = layout.getLineForVertical((scrollY + contentBottom - contentTop).coerceAtLeast(0))

            for (line in startLine..endLine) {
                val startOffset = layout.getLineStart(line)
                val isLogicalStart = startOffset == 0 || (startOffset - 1 in txt.indices && txt[startOffset - 1] == '\n')
                if (!isLogicalStart) continue

                val logicalNum = 1 + countNewlines(txt, startOffset)
                val baseline = layout.getLineBaseline(line)
                val y = (baseline - scrollY + contentTop).toFloat()
                canvas.drawText(logicalNum.toString(), width - padEnd, y, textPaint)
                if (errorLines.contains(logicalNum)) {
                    val cx = width - padEnd - dp(14f)
                    val cy = y - textPaint.textSize * 0.7f
                    canvas.drawCircle(cx, cy, dp(3.5f), errorDotPaint)
                }
            }
        }
    }

    private fun countNewlines(cs: CharSequence, upTo: Int): Int {
        var c = 0
        for (i in 0 until upTo) if (cs[i] == '\n') c++
        return c
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorApp() {
    val ctx = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val findFocusRequester = remember { FocusRequester() }

    // ---- Compile Settings ----
    val prefs = ctx.getSharedPreferences("compile_settings", Context.MODE_PRIVATE)
    var serverUrl by remember { mutableStateOf(prefs.getString("server_url", "http://127.0.0.1:8081/compile") ?: "") }
    fun saveServerUrl(newUrl: String) {
        prefs.edit().putString("server_url", newUrl).apply()
        serverUrl = newUrl
    }
    var showSettingsDialog by remember { mutableStateOf(false) }

    // Theme toggle (user-controlled; does not follow system)
    var isDark by remember { mutableStateOf(true) }

    // Word wrap ON by default
    var wrapEnabled by remember { mutableStateOf(true) }

    var currentSyntaxCfg by remember { mutableStateOf<SyntaxConfig?>(null) }

    // Results sheet state
    var showResults by remember { mutableStateOf(false) }
    var resultsText by remember { mutableStateOf("") }
    var resultsTitle by remember { mutableStateOf("Results") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboard = LocalClipboardManager.current

    // Error visualization state
    var errorLines by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val errorHighlighter = remember { ErrorHighlighter() }

    // File + content state
    var currentText by remember { mutableStateOf("") }
    var lastManualSavedText by remember { mutableStateOf("") }   // manual saves only
    var currentUri by remember { mutableStateOf<Uri?>(null) }
    val isDirty = currentText != lastManualSavedText
    var status by remember { mutableStateOf("Ready") }
    var codeViewRef by remember { mutableStateOf<CodeView?>(null) }
    var gutterRef by remember { mutableStateOf<LogicalLineGutterView?>(null) }
    val undoMgr = remember { UndoRedoManager() }

    // Find/Replace state
    var showFind by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var replaceText by remember { mutableStateOf("") }
    var matchCase by remember { mutableStateOf(false) }
    var wholeWord by remember { mutableStateOf(false) }

    // Jump buttons visibility
    var showGoTop by remember { mutableStateOf(false) }
    var showGoBottom by remember { mutableStateOf(false) }

    // ---- Selection helper ----
    fun selectAndReveal(start: Int, end: Int) {
        val cv = codeViewRef ?: return
        cv.isFocusable = true
        cv.isFocusableInTouchMode = true
        cv.requestFocus()
        val s = start.coerceIn(0, (cv.text?.length ?: 0))
        val e = end.coerceIn(0, (cv.text?.length ?: 0)).coerceAtLeast(s)
        cv.setSelection(s, e)
        cv.post { runCatching { cv.bringPointIntoView(s) }.getOrNull() }
    }

    /* ---- Line/Column helper (1-based) ---- */
    data class LineCol(val line: Int, val col: Int)
    fun indexToLineCol(text: String, index: Int): LineCol {
        val idx = index.coerceIn(0, text.length)
        val before = text.substring(0, idx)
        val line = before.count { it == '\n' } + 1
        val lastNl = before.lastIndexOf('\n')
        val col = idx - lastNl
        return LineCol(line, col)
    }

    // Scroll helper for jump buttons
    fun updateJumpButtons(cv: CodeView) {
        val canUp = cv.scrollY > 0
        val contentHeight = cv.layout?.height ?: 0
        val visibleHeight = cv.height - cv.totalPaddingTop - cv.totalPaddingBottom
        val maxScrollY = (contentHeight - visibleHeight).coerceAtLeast(0)
        val canDown = cv.scrollY < maxScrollY
        showGoTop = canUp
        showGoBottom = canDown
    }
    fun scrollToStart() {
        codeViewRef?.let { cv ->
            cv.post {
                cv.scrollTo(0, 0)
                cv.bringPointIntoView(0)
                updateJumpButtons(cv)
            }
        }
    }
    fun scrollToEnd() {
        codeViewRef?.let { cv ->
            val len = cv.text?.length ?: 0
            cv.post {
                val target = (len - 1).coerceAtLeast(0)
                cv.bringPointIntoView(target)
                updateJumpButtons(cv)
            }
        }
    }

    // SAF launchers
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) { }
            val text = readTextFromUri(ctx.contentResolver, it)
            currentText = text
            lastManualSavedText = text
            currentUri = it
            undoMgr.setInitial(text)
            // Reset errors on new file
            errorLines = emptySet()
            codeViewRef?.text?.let { ed -> errorHighlighter.clear(ed) }
            gutterRef?.setErrorLines(emptySet())

            status = "Opened: ${displayName(ctx.contentResolver, it)}"
            applyDefaultSyntaxForExtension(codeViewRef, it.toString(), isDark)
        }
    }

    val saveAsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        uri?.let {
            writeTextToUri(ctx.contentResolver, it, currentText)
            currentUri = it
            lastManualSavedText = currentText
            status = "Saved: ${displayName(ctx.contentResolver, it)}"
        }
    }

    // Auto-save
    LaunchedEffect(currentText) {
        currentUri?.let {
            writeTextToUri(ctx.contentResolver, it, currentText)
            status = "Auto-saved"
        }
    }

    // Load Syntax JSON
    val loadSyntaxLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val json = readTextFromUri(ctx.contentResolver, it)
            runCatching {
                val cfg = parseSyntaxConfig(json)
                currentSyntaxCfg = cfg
                codeViewRef?.let { cv -> applySyntaxFromConfig(cv, cfg) }
                status = "Applied syntax: ${cfg.language}"
            }.onFailure { status = "Invalid syntax JSON" }
        }
    }

    val fileName = currentUri?.let { displayName(ctx.contentResolver, it) } ?: "untitled"
    val titleText = (if (isDirty) "*" else "") + fileName
    var menuOpen by remember { mutableStateOf(false) }

    // Back dismiss for the Find panel
    BackHandler(enabled = showFind) { showFind = false }

    // Focus handling for find panel
    LaunchedEffect(showFind) {
        if (showFind) {
            focusManager.clearFocus(force = true)
            codeViewRef?.clearFocus()
            findFocusRequester.requestFocus()
            keyboard?.show()
        } else {
            codeViewRef?.requestFocus()
        }
    }

    // Observe CodeView scroll to toggle jump buttons
    DisposableEffect(codeViewRef) {
        val vto = codeViewRef?.viewTreeObserver
        val listener = ViewTreeObserver.OnScrollChangedListener {
            codeViewRef?.let { updateJumpButtons(it) }
        }
        vto?.addOnScrollChangedListener(listener)
        onDispose { vto?.removeOnScrollChangedListener(listener) }
    }

    // IME visibility (no nullable lambdas in Scaffold)
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.Menu, contentDescription = "Menu")
                    }
                },
                title = { Text(titleText, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    IconButton(
                        onClick = {
                            if (currentUri == null) {
                                saveAsLauncher.launch(suggestFileNameForLang(codeViewRef))
                            } else if (fileName.endsWith(".kt", true)) {
                                compileThroughBridge(currentText, fileName, serverUrl) { ok, errors, output ->
                                    if (ok) {
                                        errorLines = emptySet()
                                        codeViewRef?.text?.let { ed -> errorHighlighter.clear(ed) }
                                        gutterRef?.setErrorLines(emptySet())

                                        resultsTitle = "Program Output"
                                        resultsText = if (output.isBlank()) "(no output)" else output
                                        showResults = true
                                        status = "Compiled successfully"
                                    } else if (errors.isNotEmpty()) {
                                        val lines = errors.map { it.line.coerceAtLeast(1) }.toSet()
                                        errorLines = lines
                                        codeViewRef?.let { cv ->
                                            val ed = cv.text
                                            if (ed != null) {
                                                errorHighlighter.applyToLines(ed, ed.toString(), lines)
                                            }
                                        }
                                        gutterRef?.setErrorLines(lines)

                                        val sb = buildString {
                                            errors.forEach { e ->
                                                append("Line ${e.line}: ${e.message}\n")
                                            }
                                        }
                                        resultsTitle = "Compilation Errors"
                                        resultsText = sb.trimEnd()
                                        showResults = true
                                        status = "Compile errors (${lines.size} line(s))"
                                    } else {
                                        resultsTitle = "Compile Failed"
                                        resultsText = output.ifBlank { "Unknown error" }
                                        showResults = true
                                        status = "Compile failed"
                                    }
                                }
                            }
                        },
                        enabled = fileName.endsWith(".kt", true)
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Compile")
                    }

                    IconButton(onClick = { showFind = !showFind }) {
                        Icon(Icons.Filled.Search, contentDescription = "Find & Replace")
                    }
                    TextButton(
                        onClick = {
                            if (undoMgr.canUndo()) {
                                currentText = undoMgr.undo()
                                codeViewRef?.setText(currentText)
                                status = "Undo"
                                codeViewRef?.text?.let { ed ->
                                    errorHighlighter.applyToLines(ed, ed.toString(), errorLines)
                                }
                            }
                        },
                        enabled = undoMgr.canUndo()
                    ) { Text("Undo") }
                    TextButton(
                        onClick = {
                            if (undoMgr.canRedo()) {
                                currentText = undoMgr.redo()
                                codeViewRef?.setText(currentText)
                                status = "Redo"
                                codeViewRef?.text?.let { ed ->
                                    errorHighlighter.applyToLines(ed, ed.toString(), errorLines)
                                }
                            }
                        },
                        enabled = undoMgr.canRedo()
                    ) { Text("Redo") }

                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = isDark, onCheckedChange = null)
                                    Spacer(Modifier.width(8.dp)); Text("Dark mode")
                                }
                            },
                            onClick = { isDark = !isDark; menuOpen = false }
                        )
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = wrapEnabled, onCheckedChange = null)
                                    Spacer(Modifier.width(8.dp)); Text("Word wrap")
                                }
                            },
                            onClick = { wrapEnabled = !wrapEnabled; menuOpen = false }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("New") }, onClick = {
                            currentText = ""
                            currentUri = null
                            lastManualSavedText = ""
                            undoMgr.setInitial("")
                            codeViewRef?.setText("")
                            codeViewRef?.resetSyntaxPatternList()
                            applyKotlinSyntax(codeViewRef, isDark)
                            // Clear errors
                            errorLines = emptySet()
                            codeViewRef?.text?.let { ed -> errorHighlighter.clear(ed) }
                            gutterRef?.setErrorLines(emptySet())

                            status = "New file"
                            menuOpen = false
                        })
                        DropdownMenuItem(text = { Text("Open…") }, onClick = {
                            openLauncher.launch(arrayOf("*/*")); menuOpen = false
                        })
                        DropdownMenuItem(text = { Text("Save") }, onClick = {
                            if (currentUri == null) {
                                saveAsLauncher.launch(suggestFileNameForLang(codeViewRef))
                            } else {
                                writeTextToUri(ctx.contentResolver, currentUri!!, currentText)
                                lastManualSavedText = currentText
                                status = "Saved"
                            }
                            menuOpen = false
                        })
                        DropdownMenuItem(text = { Text("Save As…") }, onClick = {
                            saveAsLauncher.launch(suggestFileNameForLang(codeViewRef)); menuOpen = false
                        })
                        DropdownMenuItem(text = { Text("Load Syntax…") }, onClick = {
                            loadSyntaxLauncher.launch(arrayOf("application/json", "text/plain")); menuOpen = false
                        })
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Compile Settings") },
                            onClick = {
                                menuOpen = false
                                showSettingsDialog = true
                            }
                        )
                    }
                }
            )
        },
        // ✅ Always pass a lambda; render nothing when IME is visible
        bottomBar = {
            if (!imeVisible) {
                val words = remember(currentText) { Regex("\\b\\w+\\b").findAll(currentText).count() }
                val chars = currentText.length
                BottomAppBar {
                    Text(
                        "Status: $status    |    Words: $words   Chars: $chars",
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    ) { pv ->
        Column(
            Modifier
                .padding(pv)
                .fillMaxSize()
                .imePadding()
        ) {
            // ---- Find & Replace panel (ABOVE the editor; pushes content down)
            AnimatedVisibility(visible = showFind) {
                Surface(
                    tonalElevation = 6.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Column(Modifier.fillMaxWidth().padding(8.dp)) {
                        // Title row + checkboxes (right side)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Find & Replace", fontWeight = FontWeight.Bold)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = matchCase, onCheckedChange = { matchCase = it })
                                    Text("Match case")
                                }
                                Spacer(Modifier.width(12.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = wholeWord, onCheckedChange = { wholeWord = it })
                                    Text("Whole word")
                                }
                            }
                        }

                        Spacer(Modifier.height(4.dp))

                        // Row: Find + Replace inputs
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = findQuery,
                                onValueChange = { findQuery = it },
                                label = { Text("Find") },
                                singleLine = true,
                                modifier = Modifier.weight(1f).focusRequester(findFocusRequester)
                            )
                            OutlinedTextField(
                                value = replaceText,
                                onValueChange = { replaceText = it },
                                label = { Text("Replace") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(Modifier.height(4.dp))

                        // Buttons
                        Row(
                            Modifier
                                .horizontalScroll(rememberHScrollState())
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TextButton(
                                enabled = findQuery.isNotEmpty(),
                                onClick = {
                                    val prev = findPrevIndex(
                                        currentText, findQuery, matchCase, wholeWord,
                                        startBefore = codeViewRef?.selectionStart ?: currentText.length
                                    )
                                    if (prev != null) {
                                        val (s, e) = prev
                                        selectAndReveal(s, e)
                                        val lc = indexToLineCol(currentText, s)
                                        status = "Found at L${lc.line}, C${lc.col}"
                                    } else status = "No previous match"
                                }
                            ) { Text("Find Prev") }

                            TextButton(
                                enabled = findQuery.isNotEmpty(),
                                onClick = {
                                    val next = findNextIndex(
                                        currentText, findQuery, matchCase, wholeWord,
                                        startAfter = codeViewRef?.selectionEnd ?: 0
                                    )
                                    if (next != null) {
                                        val (s, e) = next
                                        selectAndReveal(s, e)
                                        val lc = indexToLineCol(currentText, s)
                                        status = "Found at L${lc.line}, C${lc.col}"
                                    } else status = "No more matches"
                                }
                            ) { Text("Find Next") }

                            TextButton(
                                enabled = findQuery.isNotEmpty(),
                                onClick = {
                                    val selStart = codeViewRef?.selectionStart ?: 0
                                    val selEnd = codeViewRef?.selectionEnd ?: 0
                                    val selected = if (selEnd > selStart) currentText.substring(selStart, selEnd) else ""
                                    val match = matches(selected, findQuery, matchCase, wholeWord)
                                    if (match) {
                                        val newText = currentText.replaceRange(selStart, selEnd, replaceText)
                                        currentText = newText
                                        codeViewRef?.setText(newText)
                                        val caret = selStart + replaceText.length
                                        selectAndReveal(caret, caret)
                                        val lc = indexToLineCol(newText, caret)
                                        undoMgr.onTextChange(newText)
                                        status = "Replaced at L${lc.line}, C${lc.col}"
                                    } else {
                                        val next = findNextIndex(
                                            currentText, findQuery, matchCase, wholeWord,
                                            startAfter = selEnd
                                        )
                                        if (next != null) {
                                            val (s, e) = next
                                            selectAndReveal(s, e)
                                            val lc = indexToLineCol(currentText, s)
                                            status = "Found at L${lc.line}, C${lc.col}"
                                        } else status = "No match"
                                    }
                                }
                            ) { Text("Replace") }

                            TextButton(
                                enabled = findQuery.isNotEmpty(),
                                onClick = {
                                    val regex = buildFindRegex(findQuery, matchCase, wholeWord).toRegex()
                                    val count = regex.findAll(currentText).count()
                                    if (count > 0) {
                                        val newText = currentText.replace(regex, replaceText)
                                        currentText = newText
                                        codeViewRef?.setText(newText)
                                        val caret = codeViewRef?.selectionStart ?: 0
                                        selectAndReveal(caret, caret)
                                        undoMgr.onTextChange(newText)
                                    }
                                    status = "Replaced $count occurrence(s)"
                                }
                            ) { Text("Replace All") }
                        }
                    }
                }
            }

            // ---- Editor area with overlayed jump buttons
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                Row(Modifier.fillMaxSize()) {
                    // Gutter
                    AndroidView(
                        modifier = Modifier
                            .width(48.dp)
                            .fillMaxHeight(),
                        factory = { context ->
                            LogicalLineGutterView(context).apply { gutterRef = this }
                        },
                        update = { g ->
                            g.setEditor(codeViewRef)
                            g.setTheme(isDark, (codeViewRef?.textSize ?: 16f))
                            g.setErrorLines(errorLines)
                        }
                    )

                    // Editor
                    AndroidView(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        factory = { context ->
                            CodeView(context).apply {
                                setEnableAutoIndentation(true)
                                setTabLength(4)
                                setEnableLineNumber(false)
                                gravity = Gravity.TOP or Gravity.START
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                                setLineSpacing(0f, 1.0f)
                                setIncludeFontPadding(true)
                                setPadding(16, 24, 24, 24)
                                isVerticalScrollBarEnabled = true
                                isHorizontalScrollBarEnabled = true
                                scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
                                isFocusable = true
                                isFocusableInTouchMode = true

                                setHorizontallyScrolling(!wrapEnabled)
                                runCatching {
                                    if (android.os.Build.VERSION.SDK_INT >= 23) {
                                        this.breakStrategy = LineBreaker.BREAK_STRATEGY_SIMPLE
                                        this.hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
                                        if (android.os.Build.VERSION.SDK_INT >= 26) {
                                            this.setJustificationMode(LineBreaker.JUSTIFICATION_MODE_NONE)
                                        }
                                    }
                                }

                                applyEditorPalette(this, isDark)
                                if (currentSyntaxCfg != null) {
                                    applySyntaxFromConfig(this, currentSyntaxCfg!!)
                                } else {
                                    applyKotlinSyntax(this, isDark)
                                }

                                addTextChangedListener {
                                    val txt = this.text?.toString() ?: ""
                                    if (txt != currentText) {
                                        currentText = txt
                                        undoMgr.onTextChange(txt)
                                        // Re-apply syntax + error highlights after any edit
                                        this.reHighlightSyntax()
                                        this.text?.let { ed ->
                                            errorHighlighter.applyToLines(ed, ed.toString(), errorLines)
                                        }
                                    }
                                }

                                codeViewRef = this
                                gutterRef?.setEditor(this)
                                gutterRef?.setTheme(isDark, this.textSize)
                                gutterRef?.setErrorLines(errorLines)

                                // initialize jump button visibility
                                post { updateJumpButtons(this) }
                            }
                        },
                        update = { cv ->
                            cv.setHorizontallyScrolling(!wrapEnabled)
                            runCatching {
                                if (android.os.Build.VERSION.SDK_INT >= 23) {
                                    cv.breakStrategy = LineBreaker.BREAK_STRATEGY_SIMPLE
                                    cv.hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
                                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                                        cv.setJustificationMode(LineBreaker.JUSTIFICATION_MODE_NONE)
                                    }
                                }
                            }
                            cv.isVerticalScrollBarEnabled = true
                            cv.isHorizontalScrollBarEnabled = true
                            cv.scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
                            cv.isFocusable = true
                            cv.isFocusableInTouchMode = true

                            applyEditorPalette(cv, isDark)
                            if (currentSyntaxCfg != null) {
                                applySyntaxFromConfig(cv, currentSyntaxCfg!!)
                            } else {
                                applyKotlinSyntax(cv, isDark)
                            }

                            if (cv.text.toString() != currentText) cv.setText(currentText)

                            // Re-apply error highlights after palette/syntax/updates
                            cv.text?.let { ed -> errorHighlighter.applyToLines(ed, ed.toString(), errorLines) }

                            gutterRef?.setEditor(cv)
                            gutterRef?.setTheme(isDark, cv.textSize)
                            gutterRef?.setErrorLines(errorLines)

                            cv.post { updateJumpButtons(cv) }
                        }
                    )
                }

                // Floating jump buttons
                androidx.compose.animation.AnimatedVisibility(
                    visible = showGoTop,
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    FloatingActionButton(
                        onClick = { scrollToStart() },
                        modifier = Modifier.padding(12.dp),
                        containerColor = MaterialTheme.colorScheme.primary
                    ) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Go to start") }
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = showGoBottom,
                    modifier = Modifier.align(Alignment.BottomEnd)
                ) {
                    FloatingActionButton(
                        onClick = { scrollToEnd() },
                        modifier = Modifier.padding(12.dp),
                        containerColor = MaterialTheme.colorScheme.primary
                    ) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Go to end") }
                }
            }
        }
    }

    // ⚙️ Compile Settings dialog
    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("Compile Settings") },
            text = {
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("Server URL") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    saveServerUrl(serverUrl)
                    showSettingsDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showSettingsDialog = false }) { Text("Cancel") }
            }
        )
    }

    // ---- Results bottom sheet ----
    if (showResults) {
        ModalBottomSheet(
            onDismissRequest = { showResults = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(resultsTitle, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 420.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp)
                ) {
                    Text(
                        resultsText.ifBlank { "(no output)" },
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    val clipboard = LocalClipboardManager.current
                    TextButton(onClick = { clipboard.setText(AnnotatedString(resultsText)) }) {
                        Text("Copy Output")
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { showResults = false }) { Text("Close") }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/* -------------------- Helpers -------------------- */

private fun applyEditorPalette(cv: CodeView, dark: Boolean) {
    cv.setBackgroundColor(if (dark) "#121212".toColorInt() else "#FFF7FB".toColorInt())
    cv.setTextColor      (if (dark) "#E6E6E6".toColorInt() else "#111827".toColorInt())
}

private fun applyKotlinSyntax(cv: CodeView?, dark: Boolean) {
    if (cv == null) return
    cv.resetSyntaxPatternList()

    val kwColor       = if (dark) "#C792EA".toColorInt() else "#C678DD".toColorInt()
    val commentColor  = if (dark) "#6A737D".toColorInt() else "#5C6370".toColorInt()
    val stringColor   = if (dark) "#C3E88D".toColorInt() else "#98C379".toColorInt()
    val numberColor   = if (dark) "#F78C6C".toColorInt() else "#D19A66".toColorInt()
    val annotationCol = if (dark) "#82AAFF".toColorInt() else "#61AFEF".toColorInt()
    val callColor     = if (dark) "#82AAFF".toColorInt() else "#61AFEF".toColorInt()

    val kw = listOf(
        "package","import","class","object","interface","fun","val","var","if","else","try","catch","finally",
        "for","while","do","return","in","is","when","as","break","continue","throw","this","super",
        "true","false","null","data","sealed","enum","companion","override","open","abstract","private",
        "public","protected","internal","lateinit","suspend","inline","noinline","crossinline","reified","typealias"
    )

    cv.addSyntaxPattern(Pattern.compile("\\b(${kw.joinToString("|")})\\b"), kwColor)
    cv.addSyntaxPattern(Pattern.compile("//.*"), commentColor)
    cv.addSyntaxPattern(Pattern.compile("/\\*[\\s\\S]*?\\*/"), commentColor)
    cv.addSyntaxPattern(Pattern.compile("\"\"\"[\\s\\S]*?\"\"\""), stringColor)
    cv.addSyntaxPattern(Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\""), stringColor)
    cv.addSyntaxPattern(Pattern.compile("'(?:\\\\.|[^'\\\\])'"), stringColor)
    cv.addSyntaxPattern(Pattern.compile("\\b\\d+(?:_\\d+)*(?:\\.\\d+(?:_\\d+)*)?(?:[eE][+-]?\\d+)?[fFdDlL]?\\b"), numberColor)
    cv.addSyntaxPattern(Pattern.compile("@[A-Za-z_][A-Za-z0-9_]*"), annotationCol)
    cv.addSyntaxPattern(Pattern.compile("\\b[A-Za-z_][A-Za-z0-9_]*\\s*(?=\\()"), callColor)

    cv.reHighlightSyntax()
}

private fun applySyntaxFromConfig(cv: CodeView, cfg: SyntaxConfig) {
    cv.resetSyntaxPatternList()
    val kwColor      = "#C792EA".toColorInt()
    val commentColor = "#6A737D".toColorInt()
    val stringColor  = "#98C379".toColorInt()

    if (cfg.keywords.isNotEmpty()) {
        val kwRegex = "\\b(${cfg.keywords.joinToString("|") { Pattern.quote(it) }})\\b"
        cv.addSyntaxPattern(Pattern.compile(kwRegex), kwColor)
    }
    cfg.singleLineComment?.let { sl ->
        val esc = Pattern.quote(sl)
        cv.addSyntaxPattern(Pattern.compile("$esc.*"), commentColor)
    }
    if (cfg.multiLineCommentStart != null && cfg.multiLineCommentEnd != null) {
        val start = Pattern.quote(cfg.multiLineCommentStart)
        val end = Pattern.quote(cfg.multiLineCommentEnd)
        cv.addSyntaxPattern(Pattern.compile("$start[\\s\\S]*?$end"), commentColor)
    }
    val delims = if (cfg.stringDelims.isEmpty()) listOf("\"","'") else cfg.stringDelims
    for (d in delims) {
        val q = Pattern.quote(d)
        cv.addSyntaxPattern(Pattern.compile("$q(?:\\\\.|(?!$q).)*$q"), stringColor)
    }
    cv.reHighlightSyntax()
}

fun applyDefaultSyntaxForExtension(cv: CodeView?, path: String, dark: Boolean) {
    when {
        path.endsWith(".kt", true) -> applyKotlinSyntax(cv, dark)
        path.endsWith(".java", true) -> {
            cv?.resetSyntaxPatternList()
            val kwColor      = if (dark) "#C792EA".toColorInt() else "#C678DD".toColorInt()
            val commentColor = if (dark) "#6A737D".toColorInt() else "#5C6370".toColorInt()
            val stringColor  = if (dark) "#C3E88D".toColorInt() else "#98C379".toColorInt()
            val kw = listOf("class","interface","enum","extends","implements","public","private","protected",
                "static","final","void","int","double","boolean","new","return","if","else","for","while",
                "try","catch","finally","switch","case","break","continue","this","super","null","true","false")
            cv?.addSyntaxPattern(Pattern.compile("\\b(${kw.joinToString("|")})\\b"), kwColor)
            cv?.addSyntaxPattern(Pattern.compile("//.*"), commentColor)
            cv?.addSyntaxPattern(Pattern.compile("/\\*[\\s\\S]*?\\*/"), commentColor)
            cv?.addSyntaxPattern(Pattern.compile("\"(?:\\\\.|[^\"])*\"|'(?:\\\\.|[^'])*'"), stringColor)
            cv?.reHighlightSyntax()
        }
        path.endsWith(".py", true) -> {
            cv?.resetSyntaxPatternList()
            val kwColor      = if (dark) "#C792EA".toColorInt() else "#C678DD".toColorInt()
            val commentColor = if (dark) "#6A737D".toColorInt() else "#5C6370".toColorInt()
            val stringColor  = if (dark) "#C3E88D".toColorInt() else "#98C379".toColorInt()
            val kw = listOf("def","class","return","if","elif","else","for","while","try","except","finally",
                "import","from","as","in","is","and","or","not","True","False","None","with","yield","lambda",
                "pass","break","continue","global","nonlocal","assert","raise")
            cv?.addSyntaxPattern(Pattern.compile("\\b(${kw.joinToString("|")})\\b"), kwColor)
            cv?.addSyntaxPattern(Pattern.compile("#.*"), commentColor)
            cv?.addSyntaxPattern(Pattern.compile("\"\"\"[\\s\\S]*?\"\"\"|'''[\\s\\S]*?'''"), commentColor)
            cv?.addSyntaxPattern(Pattern.compile("\"(?:\\\\.|[^\"])*\"|'(?:\\\\.|[^'])*'"), stringColor)
            cv?.reHighlightSyntax()
        }
        else -> { cv?.resetSyntaxPatternList(); cv?.reHighlightSyntax() }
    }
}

fun parseSyntaxConfig(json: String): SyntaxConfig {
    val o = JSONObject(json)
    val kws = when (val arr = o.opt("keywords")) {
        is JSONArray -> (0 until arr.length()).map { i -> arr.getString(i) }
        else -> emptyList()
    }
    val sl: String? = if (o.has("singleLineComment") && !o.isNull("singleLineComment")) o.getString("singleLineComment") else null
    val mlStart: String? = if (o.has("multiLineCommentStart") && !o.isNull("multiLineCommentStart")) o.getString("multiLineCommentStart") else null
    val mlEnd: String? = if (o.has("multiLineCommentEnd") && !o.isNull("multiLineCommentEnd")) o.getString("multiLineCommentEnd") else null
    val delims = when (val arr = o.opt("stringDelims")) {
        is JSONArray -> (0 until arr.length()).map { i -> arr.getString(i) }
        else -> listOf("\"","'")
    }
    return SyntaxConfig(
        language = if (o.has("language") && !o.isNull("language")) o.getString("language") else "custom",
        keywords = kws,
        singleLineComment = sl,
        multiLineCommentStart = mlStart,
        multiLineCommentEnd = mlEnd,
        stringDelims = delims
    )
}

fun readTextFromUri(cr: ContentResolver, uri: Uri): String =
    cr.openInputStream(uri)?.use { ins -> BufferedReader(InputStreamReader(ins)).readText() } ?: ""

fun writeTextToUri(cr: ContentResolver, uri: Uri, text: String) {
    val mode = try { "rwt" } catch (_: Exception) { "w" }
    cr.openOutputStream(uri, mode)?.use { outs -> OutputStreamWriter(outs).use { it.write(text) } }
}

fun displayName(cr: ContentResolver, uri: Uri): String =
    try {
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else uri.lastPathSegment ?: "untitled"
        } ?: "untitled"
    } catch (_: Exception) {
        uri.lastPathSegment ?: "untitled"
    }

// Default suggestion changed to .txt
fun suggestFileNameForLang(cv: CodeView?): String = "untitled.txt"

/* ---------- Find/Replace helpers ---------- */
fun buildFindRegex(q: String, matchCase: Boolean, wholeWord: Boolean): String {
    if (q.isEmpty()) return "(?!)"
    val esc = Pattern.quote(q)
    val body = if (wholeWord) "\\b$esc\\b" else esc
    return if (matchCase) body else "(?i)$body"
}

fun findNextIndex(text: String, q: String, matchCase: Boolean, wholeWord: Boolean, startAfter: Int): Pair<Int, Int>? {
    if (q.isEmpty()) return null
    val regex = buildFindRegex(q, matchCase, wholeWord).toRegex()
    val begin = if (startAfter in 0..text.length) startAfter else 0
    val sub = text.substring(begin)
    val m = regex.find(sub) ?: return null
    val start = begin + m.range.first
    val end = begin + m.range.last + 1
    return start to end
}

fun findPrevIndex(text: String, q: String, matchCase: Boolean, wholeWord: Boolean, startBefore: Int): Pair<Int, Int>? {
    if (q.isEmpty()) return null
    val regex = buildFindRegex(q, matchCase, wholeWord).toRegex()
    val end = startBefore.coerceIn(0, text.length)
    val sub = text.substring(0, end)
    var last: MatchResult? = null
    regex.findAll(sub).forEach { last = it }
    return last?.let { it.range.first to (it.range.last + 1) }
}

fun matches(sample: String, q: String, matchCase: Boolean, wholeWord: Boolean): Boolean {
    if (q.isEmpty()) return false
    val regex = buildFindRegex(q, matchCase, wholeWord).toRegex()
    return regex.matches(sample)
}

/* ---------- Compile bridge ---------- */
data class CompileError(val line: Int, val message: String)

fun compileThroughBridge(
    code: String,
    fileName: String,
    serverUrl: String,
    onResult: (ok: Boolean, errors: List<CompileError>, output: String) -> Unit
) {
    val client = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        .build()

    val bodyJson = JSONObject().apply {
        put("fileName", fileName)
        put("code", code)
    }.toString()
    val media = "application/json; charset=utf-8".toMediaType()
    val req = Request.Builder()
        .url(serverUrl)
        .post(bodyJson.toRequestBody(media))
        .build()

    client.newCall(req).enqueue(object : okhttp3.Callback {
        override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
            onResult(false, emptyList(), "Bridge error: ${e.message}")
        }
        override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
            response.use {
                val text = it.body?.string().orEmpty()
                runCatching {
                    val o = JSONObject(text)
                    val ok = o.optBoolean("ok", false)
                    val out = o.optString("output", "")
                    val errsArr = o.optJSONArray("errors") ?: JSONArray()
                    val errs = buildList {
                        for (i in 0 until errsArr.length()) {
                            val e = errsArr.getJSONObject(i)
                            add(CompileError(e.optInt("line", 0), e.optString("message", "")))
                        }
                    }
                    onResult(ok, errs, out)
                }.onFailure {
                    onResult(false, emptyList(), text.ifEmpty { "Invalid bridge response" })
                }
            }
        }
    })
}
// --------------------DONE--------------------
// - Fix: bottomBar always a lambda (no null); hides when keyboard is visible
// - This removes white bar and fixes “Composable invocation / type mismatch” errors
