package com.example.signsearch

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Animatable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.signsearch.Board
import com.ccg.slrcore.common.Empties
import com.ccg.slrcore.common.FocusSublistFilter
import com.ccg.slrcore.common.ImageMPResultWrapper
import com.ccg.slrcore.common.Thresholder
import com.ccg.slrcore.engine.SimpleExecutionEngine
import com.ccg.slrcore.preview.ComposeCanvasPainterInterface
import com.ccg.slrcore.preview.HandPreviewPainter
import com.ccg.slrcore.preview.PainterMode
import com.ccg.slrcore.system.NoTrigger
import com.google.mediapipe.framework.image.BitmapExtractor
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.LinkedList
import kotlin.concurrent.thread
import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager.PERMISSION_GRANTED
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity: AppCompatActivity() {
    private val board = MutableStateFlow(Board(0, 0))
    private val hintFocused = MutableStateFlow(false)
    private val PERMISSIONS_REQUIRED = arrayOf(Manifest.permission.CAMERA)

    private fun requestAppPermissions() {
        PERMISSIONS_REQUIRED.forEach {
            Log.d("perms", "Camera")
            if (this.checkSelfPermission(
                    it
                ) != PERMISSION_GRANTED
            ) {
                registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(it)
            }
        }
    }

    private lateinit var engine: SimpleExecutionEngine
    private val loadingModel = MutableStateFlow(true)
    private val loadingGame = MutableStateFlow(false)
    private val guessedSet = MutableStateFlow(HashSet<String>())
    private val solution = MutableStateFlow<List<String>>(LinkedList())
    private var points = MutableStateFlow(0)
    private val currGuess = MutableStateFlow<List<Node>>(LinkedList())
    private val cameraVisible = MutableStateFlow(false)
    private val showHint = MutableStateFlow("")
    private val currResult: MutableStateFlow<ImageMPResultWrapper<HandLandmarkerResult>> =
        MutableStateFlow(
            ImageMPResultWrapper(
                Empties.EMPTY_HANDMARKER_RESULTS, Empties.EMPTY_BITMAP
            )
        )

    private val focusedWord = MutableStateFlow("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        this.requestAppPermissions()
        loadingModel.value = true
        engine = SimpleExecutionEngine(this, {
            newGame(this.signPredictor.mapping)
            loadingModel.value = false
        }) {
                sign ->
            Log.d("Guessed", sign)
            if (sign !in guessedSet.value) {
                guessedSet.value = (guessedSet.value.toMutableSet()  + sign).toHashSet()
                val path = board.value.search(sign)
                Log.d("animation", "$path, ${board.value.grid}")
                if (path.isNotEmpty()) {
                    // draw on board
                    points.value += Board.lenPoints(path)
                    currGuess.value = path
                }
            }
        }
        engine.buffer.trigger = NoTrigger()
        engine.isInterpolating = true

        engine.posePredictor.addCallback("ui-result-update") { mpResult ->
            currResult.value = mpResult
        }

        setContent {
            LevelView()
        }
    }

    private fun newGame(board: Board) {
        this.currGuess.value = LinkedList()
        this.points.value = 0
        this.guessedSet.value = HashSet()
        this.solution.value = board.foundList(this.engine.signPredictor.mapping)
        this.focusedWord.value = this.solution.value.sortedWith(String.CASE_INSENSITIVE_ORDER).first()
        Log.d("Word List", this.solution.value.toString())
        loadingGame.value = false
        Log.d("game", "new game done")
        this.engine.signPredictor.outputFilters = mutableListOf(
            Thresholder(0.9F),
            FocusSublistFilter(this.solution.value),
        )
        this.board.value = board
    }

    private fun newGame(words: List<String>) {
        loadingGame.value = true
        Log.d("game", "new game")
        if (this::engine.isInitialized) engine.pause()
        thread {
            Log.d("game", "generating")
//            val board = Board.genBoard(5, 5, words, 10, 0.3)
            val board = Board(5, 5, "AFRBM TEEOI DROCE ANCRC DEEAM")
            newGame(board)
            this.board.value = board
        }
    }

    fun win() {
        Log.d("Win", "${points.value}");
    }

    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    @Composable
    fun LevelView() {
        val _loadingGame by loadingGame.collectAsState()
        val _loadingModel by loadingModel.collectAsState()

        val solution by solution.collectAsState()
        val guesses by guessedSet.collectAsState()


        LaunchedEffect(guesses, solution) {
            if (guesses.size == solution.size && guesses.isNotEmpty() && solution.isNotEmpty()) win()
        }
        Scaffold {
            Box (
                modifier = Modifier.background(
                    MaterialTheme.colorScheme.onSurface
//                    Color(0xFF00BFA5)
                )
            ) {
                if (_loadingModel || _loadingGame) Loading(what = if (_loadingGame) "Creating Board..." else "Starting Sign Recognizer...")
                else {
                    CameraPreview()
                    BoardFocus()
                    HintFocus()
                    HintVideo()
                }
            }
        }
    }

    @Composable
    fun Loading(what: String) {
        Box(
            modifier = Modifier
                .fillMaxSize()
        ) {
            Column (
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxSize()
            ) {
                CircularProgressIndicator()
                Text(
                    what,
                    style = TextStyle(
                        color = MaterialTheme.colorScheme.surface
                    )
                )
            }
        }
    }

    @Composable
    fun CameraPreview () {
        val isCameraVisisble by this.cameraVisible.collectAsState()
        val mpResult by this.currResult.collectAsState()

        LaunchedEffect(isCameraVisisble) {
            if (isCameraVisisble) {
                engine.poll()
            } else {
                if (engine.buffer.trigger is NoTrigger) {
                    engine.buffer.triggerCallbacks()
                }
                engine.pause()
            }
        }

        Box (
            modifier = Modifier
                .fillMaxWidth()

        ) {
            Row (
                horizontalArrangement = Arrangement.End,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                if (isCameraVisisble) {
                    Canvas(
                        modifier = Modifier
                            .height(80.dp)
                            .width(80.dp)
//                            .fillMaxSize()
//                            .align(Alignment.Center)
                    ) {
                        val resultBmp = BitmapExtractor.extract(mpResult.image)
                        val targetAR =
                            maxOf(size.width / resultBmp.width, size.height / resultBmp.height)
                        val img = Bitmap.createBitmap(
                            resultBmp,
                            0,
                            0,
                            resultBmp.width,
                            resultBmp.height,
                            Matrix().also {
                                it.setRectToRect(
                                    RectF(
                                        0f,
                                        0f,
                                        resultBmp.width.toFloat(),
                                        resultBmp.height.toFloat()
                                    ),
                                    RectF(
                                        0f,
                                        0f,
                                        resultBmp.width * targetAR,
                                        resultBmp.height * targetAR
                                    ),
                                    Matrix.ScaleToFit.FILL
                                )
                            },
                            true
                        )
                        HandPreviewPainter(
                            ComposeCanvasPainterInterface(
                                this
                            ),
                            PainterMode.IMAGE_AND_SKELETON
                        ).paint(
                            img,
                            mpResult.result,
                            img.width.toFloat(),
                            img.height.toFloat()
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun BoardFocus() {
        val board: Board by this.board.collectAsState()
        Column (
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxHeight()
//                .background(MaterialTheme.colorScheme.onBackground)
                .padding(10.dp)
        ) {
            PointsBar()
            BoardGrid(board)
            HintsBar()
        }
    }

    // https://github.com/SimformSolutionsPvtLtd/SSComposeShowCaseView?tab=readme-ov-file
    @Composable
    fun PointsBar() {
        Row (
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
        ) {
            PointsBanner()
//            StarsBanner()
//            TimeBanner()
        }
    }

    @Composable
    fun TimeBanner() {
        Column (
            horizontalAlignment = Alignment.End
        ) {
            Text(
                text="32",
                textAlign = TextAlign.Right,
                style = TextStyle(
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Text(
                "Seconds",
            )
        }
    }

    @Composable
    fun PointsBanner() {
        val points by points.collectAsState()
        Column(
            horizontalAlignment = Alignment.Start,
            modifier = Modifier
        ) {
            Text(
                text="$points",
                textAlign = TextAlign.Left,
                style = TextStyle(
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.background
                )
            )
            Text(
                "Points",
                style = TextStyle(
                    color = MaterialTheme.colorScheme.background
                )
            )
        }
    }

    @Composable
    fun StarsBanner() {
        Row (
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Star,
                contentDescription = "First Star",
                tint = Color.Gray,
                modifier = Modifier.size(48.dp)
            )
            Icon(
                imageVector = Icons.Outlined.Star,
                contentDescription = "Second Star",
                tint = Color.Gray,
                modifier = Modifier.size(48.dp)
            )
            Icon(
                imageVector = Icons.Outlined.Star,
                contentDescription = "Third Star",
                tint = Color.LightGray,
                modifier = Modifier.size(48.dp)
            )
        }
    }

    @Composable
    fun HintsBar() {
        val focusedWord by focusedWord.collectAsState()
        val guessedSet by guessedSet.collectAsState()
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.onSurfaceVariant,
                contentColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    hintFocused.value = true
                }
        ) {
            Row (
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Column (
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier
                ) {
                    Text(
                        text=guessedSet.filter { it.lowercase() < focusedWord.lowercase() }.maxByOrNull { it.lowercase() } ?: "AAAAA",
                        style = TextStyle(
                            fontSize = 24.sp,
                        )
                    )
                    GuessWord(word = focusedWord, guessed = focusedWord in guessedSet) { hintFocused.value = true }
                    Text(
                        text=guessedSet.filter { it.lowercase() > focusedWord.lowercase() }.minByOrNull { it.lowercase() } ?: "ZZZZZ",
                        style = TextStyle(
                            fontSize = 24.sp,
                        )
                    )
                }
                val activity = (LocalContext.current as? Activity)
                IconButton(
                    onClick = {
                        newGame(engine.signPredictor.mapping)
                    },
                    modifier = Modifier
                        .padding(10.dp)
                        .size(96.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "New Board",
                        modifier = Modifier
                            .size(48.dp)
                    )
                }
            }
        }
    }

    @Composable
    fun BoardGrid(board: Board) {
        val isCameraVisible by cameraVisible.collectAsState()
        val guess by currGuess.collectAsState()
        LazyColumn (
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            Log.d("tap", "test")
                            cameraVisible.value = !isCameraVisible
                        }
                    )
                }
        ) {
            itemsIndexed(
                board.getCharGrid(),
                { row, _ -> row },
                { _, _ -> null },
                { row, rowItem ->
                    LazyRow  {
                        itemsIndexed(
                            rowItem,
                            { col, _ -> col },
                            { _, _ -> null },
                            { col, char ->
                                val animationPosition = if (char.lowercase() in guess.map { it.char.lowercase() })  guess.indexOf(
                                    Node(char, row, col)
                                ) else -1
                                Tile(char, animationPosition)
                            }
                        )
                    }
                }
            )
        }
    }

    @Composable
    fun Tile(char: Char, animatePosition: Int = -1,
             animationContainerColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
             animationContentColor: Color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        val cardContainerColor = remember {Animatable(animationContainerColor)}
        val cardContentColor = remember {Animatable(animationContentColor)}
        if (animatePosition >= 0) {
            AnimateEffect(
                cardContainerColor,
                MaterialTheme.colorScheme.inverseOnSurface,
                animatePosition,
                1000,
                500,
                true
            )
            AnimateEffect(
                cardContentColor,
                MaterialTheme.colorScheme.inverseSurface,
                animatePosition,
                1000,
                500,
                true
            )
        } else {
            AnimateEffect(
                cardContainerColor,
                MaterialTheme.colorScheme.onSurfaceVariant,
                animatePosition,
                1000,
                500,
                false
            )
            AnimateEffect(
                cardContentColor,
                MaterialTheme.colorScheme.surfaceVariant,
                animatePosition,
                1000,
                500,
                false
            )
        }
        Card(
            colors = CardDefaults.cardColors(
                containerColor= cardContainerColor.value,
                contentColor = cardContentColor.value
            ),
            modifier = Modifier
                .padding(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .background(Brush.linearGradient(
                        colors=listOf(
                            Color(1f, 1f, 1f, 0f),
                            Color(1f, 1f, 1f, 0.0f),
                            Color(1f, 1f, 1f, 0.0f),
                            Color(1f, 1f, 1f, 0.025f),
                            Color(1f, 1f, 1f, 0.05f),
                            Color(0f, 0f, 0f, 0.15f),
                            Color(0f, 0f, 0f, 0.2f)
                        ),
                        start = Offset.Zero,
                        end = Offset.Infinite,

                        ))
            ) {
                Box(
                    modifier = Modifier
                        .padding(4.dp)
                        .width(54.dp)
                        .align(Alignment.Center)
                        .aspectRatio(1f)
                        .clip(
                            RoundedCornerShape(10.dp)
                        )
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color(0x00, 0x00, 0x00, 0x00),
                                    Color(0xbb, 0xbb, 0xbb, 0x55),
                                )
                            )
                        )
                )
                Text(
                    text = char.toString(),
                    textAlign = TextAlign.Center,
                    style = TextStyle(
                        fontSize = 32.sp,
                        fontWeight = FontWeight.ExtraBold
                    ),
                    modifier = Modifier
                        .width(32.dp)
                        .align(Alignment.Center)
                        .aspectRatio(1f)
                        .wrapContentHeight()
                        .background(Color.Transparent)
                )
            }
        }
    }

    @Composable
    fun HintFocus() {
        val sheetState = rememberModalBottomSheetState()
        val coroutineScope = rememberCoroutineScope()
        val hintFocus by this.hintFocused.collectAsState()
        val solution by this.solution.collectAsState()
        val guessedSet by this.guessedSet.collectAsState()
        if (hintFocus)
            ModalBottomSheet(
                sheetState = sheetState,
                onDismissRequest = { this.hintFocused.value = false }
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(30.dp)
                ) {
                    item {
                        Text(
                            text = "Words List",
                            style = TextStyle(
                                fontSize = 48.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                    items(solution.sortedWith(String.CASE_INSENSITIVE_ORDER)) {
                        GuessWord(
                            word = it,
                            guessed = it in guessedSet,
                            listGuessClick = {
                                focusedWord.value = it
                                hintFocused.value = false
                            }
                        ) {
                            focusedWord.value = it
                            hintFocused.value = false
                            coroutineScope.launch {  sheetState.hide() }
                        }
                    }
                }
            }
    }


    @Composable
    fun GuessWord(word: String, guessed: Boolean, listGuessClick: (() -> Unit)?=null, hintGuessClick: (() -> Unit)?=null) {
        if (word.isEmpty()) return
        Row (
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .clickable {
                    listGuessClick?.invoke()
                }
        ) {
            if (guessed) {
                Text(
                    word,
                    style = TextStyle(
                        fontSize = 40.sp
                    )
                )
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    for (c in word) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = Color.Gray
                            ),
                            modifier = Modifier
                                .width(16.dp)
                                .aspectRatio(1.0f)
                        ) {}
                    }
//                    Text(
//                        word,
//                        style = TextStyle(
//                            fontSize = 40.sp,
//                            color=Color.Blue
//                        )
//                    )
                    Text(
                        "(${word.length})",
                        modifier = Modifier
                    )
                }
            }
            IconButton(
                onClick = {
                    showHint.value = word
                    guessedSet.value = (guessedSet.value.toMutableSet()  + word).toHashSet()
                    currGuess.value = board.value.search(word)
                    hintGuessClick?.invoke()
                },
                modifier = Modifier
            ) {
                Icon (
                    imageVector = Icons.Filled.Info,
                    contentDescription = "Hint"
                )
            }
        }
    }

    @Composable
    fun <T, U : AnimationVector> AnimateEffect(
        prop: Animatable<T, U>,
        newValue: T,
        position: Int,
        duration: Int,
        delay: Int,
        offset: Boolean
    ) {
        LaunchedEffect(Unit) {
            prop.animateTo(
                newValue,
                animationSpec = tween(
                    durationMillis = duration,
                    delayMillis = delay * (if (offset) position else 1)
                )
            )
        }
    }

    @Composable
    fun HintVideo () {
        val _sign by showHint.collectAsState()
        var sign = _sign
        if (sign.isEmpty()) return
        if (sign == "for" || sign == "if") sign = "_$sign"
        val exoPlayer = remember { ExoPlayer.Builder(baseContext).build() }
        Column (
            verticalArrangement = Arrangement.SpaceAround,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
        ) {
            Column (
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
            ) {
                Text(
                    sign.filter { it != '_' }.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.displayLarge
                )
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                ) {
                    DisposableEffect(Unit) {
                        exoPlayer.setMediaItem(
                            MediaItem.fromUri(
                                Uri.Builder().scheme(ContentResolver.SCHEME_ANDROID_RESOURCE).path(
                                    resources.getIdentifier("${sign.lowercase()}", "raw", packageName)
                                        .toString()
                                ).build()
                            )
                        )
                        exoPlayer.playWhenReady = true
                        exoPlayer.repeatMode = ExoPlayer.REPEAT_MODE_ONE
                        exoPlayer.prepare()
                        onDispose {
                            exoPlayer.release()
                        }
                    }
                    AndroidView(factory = {
                        PlayerView(it).apply {
                            player = exoPlayer
                            useController = false
                        }
                    })
                }
                Text(
                    "Video provided by the Deaf Professional Arts Network.",
                    Modifier.background(MaterialTheme.colorScheme.surface)
                )
            }
            IconButton(onClick = { showHint.value = "" }, modifier = Modifier.size(128.dp)) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "Got it!",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(128.dp)
                )
            }
        }
    }
}