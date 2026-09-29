@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.campustimetable

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.time.*
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit


private const val SCHOOL_HOME_URL = "https://www.xttc.edu.cn/"
private const val SCHOOL_LOGIN_URL = "https://cas-xttc-edu-cn.i.xttc.edu.cn/cas/login"
private const val SCHOOL_PORTAL_URL = "https://myportal-xttc-edu-cn-s.i.xttc.edu.cn/"
private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
private const val PREFERENCES_NAME = "campus_timetable_preferences"
private const val DARK_MODE_KEY = "dark_mode_enabled"

private val LightColors = lightColorScheme(
    primary = Color(0xFF006A60),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF74F8E5),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4A635E),
    secondaryContainer = Color(0xFFCDE8E1),
    onSecondaryContainer = Color(0xFF06201C),
    tertiary = Color(0xFF456179),
    tertiaryContainer = Color(0xFFCBE6FF),
    onTertiaryContainer = Color(0xFF001E30),
    background = Color(0xFFF7FAF8),
    onBackground = Color(0xFF191C1B),
    surface = Color(0xFFF7FAF8),
    onSurface = Color(0xFF191C1B),
    surfaceVariant = Color(0xFFDAE5E1),
    onSurfaceVariant = Color(0xFF3F4946),
    outline = Color(0xFF6F7976)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF52DBC8),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005047),
    onPrimaryContainer = Color(0xFF74F8E5),
    secondary = Color(0xFFB1CCC5),
    secondaryContainer = Color(0xFF334B47),
    onSecondaryContainer = Color(0xFFCDE8E1),
    tertiary = Color(0xFFADCBE5),
    tertiaryContainer = Color(0xFF2D4961),
    onTertiaryContainer = Color(0xFFCBE6FF),
    background = Color(0xFF101413),
    onBackground = Color(0xFFE0E3E1),
    surface = Color(0xFF101413),
    onSurface = Color(0xFFE0E3E1),
    surfaceVariant = Color(0xFF3F4946),
    onSurfaceVariant = Color(0xFFBEC9C5),
    outline = Color(0xFF89938F)
)

enum class CourseSource { WEB_SYNC, MANUAL }
enum class AdjustmentType { WHOLE_ADJUSTMENT, PARTIAL_ADJUSTMENT }

@androidx.room.Entity(tableName = "courses")
data class Course(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val semesterId: String = "default",
    val name: String,
    val teacher: String? = null,
    val classroom: String? = null,
    val weekday: Int,
    val startSection: Int,
    val endSection: Int,
    val startWeek: Int,
    val endWeek: Int,
    val adjustmentType: AdjustmentType? = null,
    val rawText: String? = null,
    val reminderEnabled: Boolean = true,
    val source: CourseSource = CourseSource.WEB_SYNC
)

@androidx.room.Entity(tableName = "semesters")
data class Semester(@PrimaryKey val id: String = "default", val name: String = "当前学期", val firstMonday: String = "")

class Converters {
    @TypeConverter fun source(v: CourseSource?) = v?.name
    @TypeConverter fun source(v: String?) = v?.let { CourseSource.valueOf(it) }
    @TypeConverter fun adjustment(v: AdjustmentType?) = v?.name
    @TypeConverter fun adjustment(v: String?) = v?.let { AdjustmentType.valueOf(it) }
}

@Dao interface CourseDao {
    @Query("SELECT * FROM courses ORDER BY weekday, startSection") fun all(): Flow<List<Course>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(course: Course): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(courses: List<Course>)
    @Delete suspend fun delete(course: Course)
    @Query("DELETE FROM courses WHERE semesterId=:semester AND source='WEB_SYNC'") suspend fun deleteSynced(semester: String)
    @Query("UPDATE courses SET reminderEnabled=:enabled WHERE id=:id") suspend fun setReminder(id: Long, enabled: Boolean)
}

@Dao interface SemesterDao {
    @Query("SELECT * FROM semesters WHERE id=:id") fun get(id: String = "default"): Flow<Semester?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(s: Semester)
}

@Database(entities = [Course::class, Semester::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun courseDao(): CourseDao
    abstract fun semesterDao(): SemesterDao
    companion object { fun create(c: Context) = Room.databaseBuilder(c, AppDatabase::class.java, "timetable.db").build() }
}

object TimetableParser {
    private val weekRange = Regex("(?:第\\s*)?(\\d+)\\s*[-－—~～至]\\s*(\\d+)\\s*周")
    private val singleWeek = Regex("(?:第\\s*)?(\\d+)\\s*周")
    private val sectionRange = Regex("(?:第\\s*)?[\\[【(（]?\\s*(\\d+)\\s*[-－—~～至]\\s*(\\d+)\\s*节")
    private val singleSection = Regex("(?:第\\s*)?[\\[【(（]?\\s*(\\d+)\\s*节")
    private val compactPeriod = Regex("(?<!\\d)(\\d+)\\s*[-－—~～至]\\s*(\\d+)\\s*[\\[【(（]\\s*(\\d+)\\s*[-－—~～至]\\s*(\\d+)\\s*[\\]】)）]")
    private val compactSingleWeek = Regex("(?<!\\d)(\\d+)\\s*[\\[【(（]\\s*(\\d+)\\s*[-－—~～至]\\s*(\\d+)\\s*[\\]】)）]")
    private val metadataLine = Regex(
        "^(?:周次|节次|教师|老师|上课地点|地点|教室|校区|课程性质|课程代码)(?:\\s*[：:]|\\s+)"
    )

    fun parse(json: String): List<Course> {
        val decoded = runCatching { org.json.JSONTokener(json).nextValue() as? String }.getOrNull() ?: json
        val rows = JSONArray(decoded)
        val result = mutableListOf<Course>()
        for (i in 0 until rows.length()) {
            val item = rows.optJSONObject(i)
            if (item != null) {
                parseCell(item.optInt("weekday"), item.optString("text"), result)
                continue
            }

            val row = rows.optJSONArray(i) ?: continue
            for (j in 1 until row.length()) parseCell(j, row.optString(j), result)
        }
        return result
    }

    private fun parseCell(weekday: Int, raw: String, result: MutableList<Course>) {
        if (weekday !in 1..7) return
        raw.split("\u001e").forEach { block ->
            val text = block.replace("\\n", "\n").replace('\u00a0', ' ').trim()
            if (text.isBlank()) return@forEach

            val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
            val compactLineIndexes = lines.indices.filter { index ->
                compactPeriod.containsMatchIn(lines[index]) || compactSingleWeek.containsMatchIn(lines[index])
            }
            if (compactLineIndexes.size > 1) {
                compactLineIndexes.forEach { markerIndex ->
                    val start = (markerIndex - 2).coerceAtLeast(0)
                    val endExclusive = (markerIndex + 2).coerceAtMost(lines.size)
                    parseCell(weekday, lines.subList(start, endExclusive).joinToString("\n"), result)
                }
                return@forEach
            }

            val compactMatch = compactPeriod.find(text)
            val compactSingleMatch = if (compactMatch == null) compactSingleWeek.find(text) else null
            val weekMatch = if (compactMatch == null && compactSingleMatch == null) weekRange.find(text) else null
            val singleWeekMatch = if (weekMatch == null && compactMatch == null && compactSingleMatch == null) singleWeek.find(text) else null
            val sectionMatch = if (compactMatch == null && compactSingleMatch == null) sectionRange.find(text) else null
            val singleSectionMatch = if (sectionMatch == null && compactMatch == null && compactSingleMatch == null) singleSection.find(text) else null
            if (compactMatch == null && compactSingleMatch == null && weekMatch == null && singleWeekMatch == null) return@forEach
            if (compactMatch == null && compactSingleMatch == null && sectionMatch == null && singleSectionMatch == null) return@forEach

            val startWeek = compactMatch?.groupValues?.get(1)?.toIntOrNull()
                ?: compactSingleMatch?.groupValues?.get(1)?.toIntOrNull()
                ?: weekMatch?.groupValues?.get(1)?.toIntOrNull()
                ?: singleWeekMatch?.groupValues?.get(1)?.toIntOrNull()
                ?: return@forEach
            val endWeek = compactMatch?.groupValues?.get(2)?.toIntOrNull()
                ?: compactSingleMatch?.groupValues?.get(1)?.toIntOrNull()
                ?: weekMatch?.groupValues?.get(2)?.toIntOrNull() ?: startWeek
            val rawStart = compactMatch?.groupValues?.get(3)?.toIntOrNull()
                ?: compactSingleMatch?.groupValues?.get(2)?.toIntOrNull()
                ?: sectionMatch?.groupValues?.get(1)?.toIntOrNull()
                ?: singleSectionMatch?.groupValues?.get(1)?.toIntOrNull()
                ?: return@forEach
            val rawEnd = compactMatch?.groupValues?.get(4)?.toIntOrNull()
                ?: compactSingleMatch?.groupValues?.get(3)?.toIntOrNull()
                ?: sectionMatch?.groupValues?.get(2)?.toIntOrNull() ?: rawStart

            val marker = when { text.contains(" P") || text.endsWith("P") -> AdjustmentType.PARTIAL_ADJUSTMENT; text.contains(" O") || text.endsWith("O") -> AdjustmentType.WHOLE_ADJUSTMENT; else -> null }
            val nameLine = lines.firstOrNull { line ->
                !metadataLine.containsMatchIn(line) &&
                    !compactPeriod.containsMatchIn(line) && !compactSingleWeek.containsMatchIn(line) &&
                    !weekRange.containsMatchIn(line) && !singleWeek.containsMatchIn(line) &&
                    !sectionRange.containsMatchIn(line) && !singleSection.containsMatchIn(line)
            }
            val name = nameLine?.let { line ->
                when {
                    line.startsWith("课程名称") -> line.removePrefix("课程名称").trimStart('：', ':', ' ')
                    else -> line
                }
            }?.replace(Regex("\\s+[OP]$"), "")?.ifBlank { null } ?: "未命名课程"
            val teacher = labeledValue(lines, "教师", "老师")
                ?: nameLine?.let { courseLine ->
                    lines.dropWhile { it != courseLine }.drop(1).firstOrNull { looksLikePerson(it) }
                }
            val classroom = labeledValue(lines, "上课地点", "地点", "教室")
                ?: lines.firstOrNull { looksLikeClassroom(it) }
            val parsed = Course(
                name = name,
                teacher = teacher,
                classroom = classroom,
                weekday = weekday,
                startSection = rawStart.coerceIn(1, 10),
                endSection = rawEnd.coerceIn(1, 10),
                startWeek = startWeek,
                endWeek = endWeek,
                adjustmentType = marker,
                rawText = text
            )

            val duplicateIndex = result.indexOfFirst { old ->
                old.weekday == parsed.weekday && old.name == parsed.name && old.teacher == parsed.teacher &&
                    old.classroom == parsed.classroom && old.startWeek == parsed.startWeek && old.endWeek == parsed.endWeek &&
                    old.adjustmentType == parsed.adjustmentType &&
                    parsed.startSection <= old.endSection && old.startSection <= parsed.endSection
            }
            if (duplicateIndex >= 0) {
                val old = result[duplicateIndex]
                result[duplicateIndex] = old.copy(
                    startSection = minOf(old.startSection, parsed.startSection),
                    endSection = maxOf(old.endSection, parsed.endSection),
                    rawText = old.rawText ?: parsed.rawText
                )
            } else result += parsed
        }
    }

    private fun labeledValue(lines: List<String>, vararg labels: String): String? =
        lines.firstNotNullOfOrNull { line ->
            val label = labels.firstOrNull { candidate ->
                line.startsWith(candidate) &&
                    line.drop(candidate.length).firstOrNull() in listOf('：', ':', ' ')
            } ?: return@firstNotNullOfOrNull null
            line.removePrefix(label).trimStart('：', ':', ' ').ifBlank { null }
        }

    private fun looksLikePerson(value: String): Boolean =
        value.length in 2..12 && !metadataLine.containsMatchIn(value) &&
            !compactPeriod.containsMatchIn(value) && !compactSingleWeek.containsMatchIn(value) &&
            !weekRange.containsMatchIn(value) && !singleWeek.containsMatchIn(value) &&
            !sectionRange.containsMatchIn(value) && !singleSection.containsMatchIn(value) &&
            !looksLikeClassroom(value)

    private fun looksLikeClassroom(value: String): Boolean =
        value.contains(Regex("教室|校区|楼|馆|场|室$|机房|\\d{3,4}$")) &&
            !value.startsWith("课程")
}


class TimetableViewModel(private val db: AppDatabase, private val context: Context) : ViewModel() {
    val courses = db.courseDao().all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val semester = db.semesterDao().get().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    var selectedWeek by mutableStateOf(1); private set
    var message by mutableStateOf<String?>(null); private set
    fun setWeek(w: Int) { selectedWeek = w.coerceAtLeast(1) }
    fun saveSemester(date: LocalDate) = viewModelScope.launch { db.semesterDao().save(Semester(firstMonday = date.toString())); selectedWeek = 1 }
    fun add(c: Course) = viewModelScope.launch { db.courseDao().insert(c.copy(source = CourseSource.MANUAL)); schedule(c) }
    fun delete(c: Course) = viewModelScope.launch { db.courseDao().delete(c); cancel(c) }
    fun toggleReminder(c: Course) = viewModelScope.launch { db.courseDao().setReminder(c.id, !c.reminderEnabled); if (c.reminderEnabled) cancel(c) else schedule(c.copy(reminderEnabled = true)) }
    fun syncFromJson(json: String) = viewModelScope.launch {
        val parsed = runCatching { TimetableParser.parse(json) }.getOrDefault(emptyList())
        if (parsed.isEmpty()) message = "未识别到课程，请先登录并进入课表查询页面"
        else { db.courseDao().deleteSynced("default"); db.courseDao().insertAll(parsed); parsed.filter { it.reminderEnabled }.forEach(::schedule); message = "已同步 ${parsed.size} 门课程" }
    }
    fun clearMessage() { message = null }
    private fun schedule(c: Course) {
        if (!c.reminderEnabled) return
        val times = listOf(
            LocalTime.of(8, 30), LocalTime.of(9, 20),
            LocalTime.of(10, 15), LocalTime.of(11, 5),
            LocalTime.of(14, 30), LocalTime.of(15, 20),
            LocalTime.of(16, 15), LocalTime.of(17, 5),
            LocalTime.of(19, 0), LocalTime.of(19, 50)
        )
        val now = ZonedDateTime.now(); var next = now.with(java.time.DayOfWeek.of(c.weekday)).with(times[(c.startSection - 1).coerceIn(0, 9)]).withSecond(0).withNano(0).minusMinutes(10)
        if (!next.isAfter(now)) next = next.plusWeeks(1)
        val intent = Intent(context, CourseReminderReceiver::class.java).putExtra("name", c.name).putExtra("teacher", c.teacher).putExtra("room", c.classroom)
        val pending = PendingIntent.getBroadcast(context, (c.id.hashCode() xor c.name.hashCode()), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), pending)
    }
    private fun cancel(c: Course) { val pending = PendingIntent.getBroadcast(context, (c.id.hashCode() xor c.name.hashCode()), Intent(context, CourseReminderReceiver::class.java), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE); if (pending != null) (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending) }
}

class CourseReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel("course", "课程提醒", NotificationManager.IMPORTANCE_DEFAULT))
        val notification = androidx.core.app.NotificationCompat.Builder(context, "course").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(intent.getStringExtra("name") ?: "课程提醒").setContentText(listOfNotNull(intent.getStringExtra("teacher"), intent.getStringExtra("room")).joinToString(" · ").ifBlank { "即将上课" }).setAutoCancel(true).build()
        manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var db: AppDatabase
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = AppDatabase.create(this)
        if (android.os.Build.VERSION.SDK_INT >= 33) notificationPermission.launch("android.permission.POST_NOTIFICATIONS")
        setContent {
            val preferences = remember { getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE) }
            var darkModeEnabled by remember {
                mutableStateOf(preferences.getBoolean(DARK_MODE_KEY, false))
            }
            CampusTimetableTheme(darkModeEnabled) {
                AppRoot(
                    db = db,
                    context = this,
                    darkModeEnabled = darkModeEnabled,
                    onDarkModeChange = { enabled ->
                        darkModeEnabled = enabled
                        preferences.edit().putBoolean(DARK_MODE_KEY, enabled).apply()
                    }
                )
            }
        }
    }
}

@Composable
fun CampusTimetableTheme(darkModeEnabled: Boolean, content: @Composable () -> Unit) {
    val colorScheme = if (darkModeEnabled) DarkColors else LightColors
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        window.statusBarColor = colorScheme.surface.toArgb()
        window.navigationBarColor = colorScheme.surface.toArgb()
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !darkModeEnabled
            isAppearanceLightNavigationBars = !darkModeEnabled
        }
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

@Composable fun AppRoot(
    db: AppDatabase,
    context: Context,
    darkModeEnabled: Boolean,
    onDarkModeChange: (Boolean) -> Unit
) {
    val vm = remember { TimetableViewModel(db, context) }
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(topBar = { TopAppBar(title = { Text("我的课程表") }, actions = { TextButton(onClick = { tab = 1 }) { Text("教务同步") } }) }, bottomBar = { NavigationBar { NavigationBarItem(tab == 0, { tab = 0 }, label = { Text("课表") }, icon = {}); NavigationBarItem(tab == 1, { tab = 1 }, label = { Text("设置") }, icon = {}) } }) { p -> if (tab == 0) TimetableScreen(vm, Modifier.padding(p)) else SettingsScreen(vm, darkModeEnabled, onDarkModeChange, Modifier.padding(p)) }
}

@Composable fun TimetableScreen(vm: TimetableViewModel, modifier: Modifier = Modifier) {
    val courses by vm.courses.collectAsState(); val semester by vm.semester.collectAsState()
    val todayWeek = semester?.firstMonday?.takeIf { it.isNotBlank() }?.let { (ChronoUnit.WEEKS.between(LocalDate.parse(it), LocalDate.now()) + 1).toInt().coerceAtLeast(1) } ?: 1
    val weekStart = semester?.firstMonday?.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it).plusWeeks((vm.selectedWeek - 1).toLong()) }
    var addDialog by remember { mutableStateOf(false) }; var selectedCourse by remember { mutableStateOf<Course?>(null) }
    Box(modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) { Text("第 ${vm.selectedWeek} 周", style = MaterialTheme.typography.titleMedium); Row { TextButton({ vm.setWeek(vm.selectedWeek - 1) }) { Text("上一周") }; TextButton({ vm.setWeek(todayWeek) }) { Text("本周") }; TextButton({ vm.setWeek(vm.selectedWeek + 1) }) { Text("下一周") } } }
        if (semester?.firstMonday.isNullOrBlank()) Text("请先在设置中填写本学期第一周周一日期", color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(6.dp))
        TimetableGrid(courses, vm.selectedWeek, weekStart) { selectedCourse = it }
    }; FloatingActionButton(onClick = { addDialog = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) { Text("+") } }
    if (addDialog) AddCourseDialog({ addDialog = false }) { vm.add(it); addDialog = false }
    selectedCourse?.let { c -> CourseDetailDialog(c, { selectedCourse = null }, { vm.toggleReminder(c) }, { vm.delete(c); selectedCourse = null }) }
}

@Composable
fun TimetableGrid(
    courses: List<Course>,
    selectedWeek: Int,
    weekStart: LocalDate?,
    onCourseClick: (Course) -> Unit
) {
    val horizontalState = rememberScrollState()
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val timeColumnWidth = 58.dp
        val dayWidth = (maxWidth - timeColumnWidth) / 5
        Row(
            Modifier
                .fillMaxWidth()
                .border(0.75.dp, colors.outlineVariant.copy(alpha = 0.42f))
        ) {
            TimeColumn(Modifier.width(timeColumnWidth))
            Row(Modifier.weight(1f).horizontalScroll(horizontalState)) {
                (1..7).forEach { day ->
                    DayColumn(
                        day = day,
                        date = weekStart?.plusDays((day - 1).toLong()),
                        courses = courses,
                        selectedWeek = selectedWeek,
                        modifier = Modifier.width(dayWidth),
                        onCourseClick = onCourseClick
                    )
                }
            }
        }
    }
}

@Composable
private fun TimeColumn(modifier: Modifier = Modifier) {
    Column(modifier.background(MaterialTheme.colorScheme.surface)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(58.dp)
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)),
            contentAlignment = Alignment.Center
        ) {
            Text("节次", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        (1..10).forEach { section ->
            SectionLabel(section)
        }
    }
}

@Composable
private fun DayColumn(
    day: Int,
    date: LocalDate?,
    courses: List<Course>,
    selectedWeek: Int,
    modifier: Modifier = Modifier,
    onCourseClick: (Course) -> Unit
) {
    Column(modifier) {
        DayHeader(day, date)
        var section = 1
        while (section <= 10) {
            val item = courses.firstOrNull {
                it.weekday == day &&
                    selectedWeek in it.startWeek..it.endWeek &&
                    it.startSection == section
            }
            val sectionSpan = item
                ?.let { (it.endSection - section + 1).coerceIn(1, 11 - section) }
                ?: 1
            CourseCell(item, sectionSpan, onCourseClick)
            section += sectionSpan
        }
    }
}

@Composable
private fun DayHeader(day: Int, date: LocalDate?) {
    val isToday = date == LocalDate.now()
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val backgroundColor = if (darkTheme) Color(0xFF182329) else Color(0xFFE3F2FD)
    val foreground = if (darkTheme) Color(0xFF90CAF9) else Color(0xFF1976D2)
    Column(
        Modifier
            .fillMaxWidth()
            .height(58.dp)
            .background(if (isToday) backgroundColor else backgroundColor.copy(alpha = 0.58f))
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("周${"一二三四五六日"[day - 1]}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = foreground)
        Text(date?.format(DateTimeFormatter.ofPattern("M/d")) ?: "--/--", fontSize = 10.sp, color = foreground)
    }
}

@Composable
private fun SectionLabel(section: Int) {
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Column(
        Modifier
            .height(72.dp)
            .fillMaxWidth()
            .background(if (darkTheme) Color(0xFF151B1E) else Color(0xFFF1F8FE))
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(section.toString(), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CourseCell(c: Course?, sectionSpan: Int, onClick: (Course) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(5.dp)
    val darkTheme = colors.background.luminance() < 0.5f
    val containerColor = if (darkTheme) Color(0xFF17231B) else Color(0xFFE8F5E9)
    val contentColor = colors.onSurface
    Box(
        Modifier
            .height((72 * sectionSpan).dp)
            .fillMaxWidth()
            .border(0.5.dp, colors.outlineVariant.copy(alpha = 0.42f))
            .padding(2.dp)
            .then(
                if (c == null) Modifier.background(colors.surface)
                else Modifier
                    .background(containerColor, shape)
                    .border(1.dp, contentColor.copy(alpha = 0.2f), shape)
                    .clickable { onClick(c) }
            )
            .padding(horizontal = 6.dp, vertical = 7.dp),
        contentAlignment = Alignment.TopStart
    ) {
        if (c != null) {
            Column {
                Text(
                    c.name,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                    maxLines = if (sectionSpan == 1) 2 else 5,
                    overflow = TextOverflow.Ellipsis
                )
                c.classroom?.let {
                    Text(
                        "@$it",
                        fontSize = 9.sp,
                        lineHeight = 12.sp,
                        color = contentColor.copy(alpha = 0.78f)
                    )
                }
                c.teacher?.let {
                    Text(it, fontSize = 9.sp, color = contentColor.copy(alpha = 0.78f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable fun CourseDetailDialog(c: Course, onDismiss: () -> Unit, onToggle: () -> Unit, onDelete: () -> Unit) { AlertDialog(onDismissRequest = onDismiss, title = { Text(c.name) }, text = { Column { c.teacher?.let { Text("教师：$it") }; c.classroom?.let { Text("教室：$it") }; Text("第${c.startWeek}-${c.endWeek}周 · 第${c.startSection}-${c.endSection}节"); Text(if (c.reminderEnabled) "提醒：已开启（提前10分钟）" else "提醒：已关闭") } }, confirmButton = { TextButton(onClick = onToggle) { Text(if (c.reminderEnabled) "关闭提醒" else "开启提醒") } }, dismissButton = { Row { TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }; TextButton(onClick = onDismiss) { Text("关闭") } } }) }

@Composable fun AddCourseDialog(onDismiss: () -> Unit, onSave: (Course) -> Unit) {
    var name by remember { mutableStateOf("") }; var teacher by remember { mutableStateOf("") }; var room by remember { mutableStateOf("") }; var day by remember { mutableStateOf("1") }; var section by remember { mutableStateOf("1") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("添加课程") }, text = { Column { OutlinedTextField(name, { name = it }, label = { Text("课程名称") }); OutlinedTextField(teacher, { teacher = it }, label = { Text("教师（可选）") }); OutlinedTextField(room, { room = it }, label = { Text("教室（可选）") }); Row { OutlinedTextField(day, { day = it }, label = { Text("星期1-7") }, modifier = Modifier.weight(1f)); Spacer(Modifier.width(8.dp)); OutlinedTextField(section, { section = it }, label = { Text("第几节（1-10）") }, modifier = Modifier.weight(1f)) } } }, confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onSave(Course(name = name, teacher = teacher.ifBlank { null }, classroom = room.ifBlank { null }, weekday = day.toIntOrNull()?.coerceIn(1, 7) ?: 1, startSection = section.toIntOrNull()?.coerceIn(1, 10) ?: 1, endSection = section.toIntOrNull()?.coerceIn(1, 10) ?: 1, startWeek = 1, endWeek = 30)) }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
fun SettingsScreen(
    vm: TimetableViewModel,
    darkModeEnabled: Boolean,
    onDarkModeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var date by remember { mutableStateOf("") }
    var showWeb by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    Column(modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("外观", style = MaterialTheme.typography.titleLarge)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onDarkModeChange(!darkModeEnabled) }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text("夜间模式", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "使用深色界面",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = darkModeEnabled, onCheckedChange = null)
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("学期设置", style = MaterialTheme.typography.titleLarge)
        Text("第一周周一日期（YYYY-MM-DD）", modifier = Modifier.padding(top = 16.dp))
        OutlinedTextField(date, { date = it }, singleLine = true, placeholder = { Text("例如 2026-09-07") })
        Button(
            onClick = { runCatching { vm.saveSemester(LocalDate.parse(date)) } },
            modifier = Modifier.padding(top = 8.dp)
        ) { Text("保存日期") }
        HorizontalDivider(Modifier.padding(vertical = 24.dp))
        Text("邢台学院教务", style = MaterialTheme.typography.titleLarge)
        Text(
            "通过邢台学院统一身份认证登录，进入学生课表页面后即可同步。",
            modifier = Modifier.padding(vertical = 8.dp)
        )
        Button(onClick = { showWeb = true }) { Text("登录并同步课表") }
        vm.message?.let {
            Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
        }
        HorizontalDivider(Modifier.padding(vertical = 24.dp))
        Text("关于", style = MaterialTheme.typography.titleLarge)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showAbout = true }
                .padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text("关于我的课程表", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "软件信息与版本",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "版本 1.1",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    if (showWeb) WebViewDialog(vm) { showWeb = false }
    if (showAbout) AboutDialog { showAbout = false }
}

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("我的课程表") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("版本 1.1", color = MaterialTheme.colorScheme.primary)
                Text("支持邢台学院教务系统的个人课程表工具")
                Text("“我的课程表”不会读取任何隐私数据（相册，通讯录等），课程与设置数据保存在本机")
                Text("bug反馈：抖音ent1701")

                Text(
                    "技术支持与鸣谢：10+v",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("Copyright © 2026 Sherlock. All rights reserved.")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable fun WebViewDialog(vm: TimetableViewModel, onClose: () -> Unit) {
    var web by remember { mutableStateOf<WebView?>(null) }
    var status by remember { mutableStateOf("正在从邢台学院官网进入智慧校园…") }
    var portalUrl by remember { mutableStateOf<String?>(null) }
    val extractScript = """
        (() => {
          const docs = [];
          const visited = new Set();
          const frameUrls = [];
          const collectDocs = doc => {
            if (!doc || visited.has(doc)) return;
            visited.add(doc);
            docs.push(doc);
            [...doc.querySelectorAll('iframe,frame')].forEach(frame => {
              const frameUrl = frame.src || frame.getAttribute('src') || '';
              if (frameUrl && !frameUrls.includes(frameUrl)) frameUrls.push(frameUrl);
              try {
                collectDocs(frame.contentDocument || frame.contentWindow?.document);
              } catch (_) {}
            });
          };
          collectDocs(document);
          const clean = value => (value || '').replace(/\u00a0/g, ' ').replace(/\r/g, '').trim();
          const dayNumber = value => {
            const match = clean(value).match(/(?:星期|周)\s*([一二三四五六日天])/);
            return match ? ({'一':1,'二':2,'三':3,'四':4,'五':5,'六':6,'日':7,'天':7})[match[1]] : 0;
          };
          const sectionRange = value => {
            const compact = clean(value).match(/\d+\s*[-－—~～至]\s*\d+\s*[\[【(（]\s*(\d+)\s*[-－—~～至]\s*(\d+)\s*[\]】)）]/);
            if (compact) return [Number(compact[1]), Number(compact[2])];
            const range = clean(value).match(/(?:第\s*)?[\[【(（]?\s*(\d+)\s*[-－—~～至]\s*(\d+)\s*节/);
            if (range) return [Number(range[1]), Number(range[2])];
            const single = clean(value).match(/(?:第\s*)?(\d+)\s*节/);
            return single ? [Number(single[1]), Number(single[1])] : null;
          };
          const hasCompactPeriod = value => /(?:^|\D)\d+\s*(?:[-－—~～至]\s*\d+\s*)?[\[【(（]\s*\d+\s*[-－—~～至]\s*\d+\s*[\]】)）]/.test(value);
          const hasWeek = value => /(?:第\s*)?\d+\s*(?:[-－—~～至]\s*\d+\s*)?周/.test(value) || hasCompactPeriod(value);
          const hasSection = value => /(?:第\s*)?[\[【(（]?\s*\d+\s*(?:[-－—~～至]\s*\d+\s*)?节/.test(value) || hasCompactPeriod(value);
          const blockSelector = '.timetable_con,.kbcontent,.course-item,.courseInfo,[data-course]';

          const inspectTable = table => {
            const occupied = [];
            const placements = [];
            [...table.rows].forEach((tr, rowIndex) => {
              occupied[rowIndex] = occupied[rowIndex] || [];
              let column = 0;
              [...tr.cells].forEach(cell => {
                while (occupied[rowIndex][column]) column += 1;
                const rowSpan = Math.max(Number(cell.rowSpan) || 1, 1);
                const colSpan = Math.max(Number(cell.colSpan) || 1, 1);
                placements.push({cell, rowIndex, column, rowSpan, colSpan, text: clean(cell.innerText)});
                for (let r = rowIndex; r < rowIndex + rowSpan; r += 1) {
                  occupied[r] = occupied[r] || [];
                  for (let c = column; c < column + colSpan; c += 1) occupied[r][c] = true;
                }
                column += colSpan;
              });
            });

            const dayColumns = {};
            placements.forEach(item => {
              const day = dayNumber(item.text);
              if (day) for (let c = item.column; c < item.column + item.colSpan; c += 1) dayColumns[c] = day;
            });
            const sectionRows = {};
            placements.filter(item => item.column <= 1).forEach(item => {
              const section = sectionRange(item.text);
              if (section) sectionRows[item.rowIndex] = section;
            });

            const entries = [];
            placements.forEach(item => {
              const day = dayColumns[item.column];
              if (!day || dayNumber(item.text)) return;
              const descendants = [...item.cell.querySelectorAll(blockSelector)];
              const blocks = descendants.filter(element =>
                !descendants.some(other => other !== element && element.contains(other))
              );
              const texts = (blocks.length ? blocks : [item.cell]).map(element => clean(element.innerText));
              texts.forEach(originalText => {
                if (!originalText || !hasWeek(originalText)) return;
                let text = originalText;
                if (!hasSection(text)) {
                  const start = sectionRows[item.rowIndex];
                  const end = sectionRows[item.rowIndex + item.rowSpan - 1] || start;
                  if (start) text += '\n[' + start[0] + '-' + (end ? end[1] : start[1]) + '节]';
                }
                if (hasSection(text)) entries.push({weekday: day, text});
              });
            });
            return {entries, score: entries.length * 10 + Object.keys(dayColumns).length};
          };

          const tables = docs.flatMap(doc => [...doc.querySelectorAll('table')]);
          const candidates = tables.map(inspectTable);
          candidates.sort((a, b) => b.score - a.score);
          const rows = candidates.length ? candidates[0].entries : [];
          const pageText = docs.map(doc => clean(doc.body && doc.body.innerText)).join('\n');
          const eduElement = docs.flatMap(doc =>
            [...doc.querySelectorAll('a,[data-url],[data-href]')]
          ).find(element => /教务|课表/.test(clean(
            element.innerText || element.title || element.getAttribute('aria-label')
          )));
          const eduUrl = eduElement ? clean(
            eduElement.href || eduElement.getAttribute('data-url') || eduElement.getAttribute('data-href')
          ) : '';
          const login = /\/cas\/login/i.test(location.pathname) || docs.some(doc =>
            !!doc.querySelector('#fm1,#username,#password') || clean(doc.title).includes('统一身份认证')
          );
          const systemError = pageText.includes('请先登录系统') || pageText.includes('登录状态已失效');
          return JSON.stringify({
            login, systemError, rows, empty: !pageText, eduUrl,
            frameUrls,
            diagnostics: {documents: docs.length, tables: tables.length, bestScore: candidates[0]?.score || 0}
          });
        })()
    """.trimIndent()
    val openEduScript = """
        (() => {
          const docs = [document];
          [...document.querySelectorAll('iframe,frame')].forEach(frame => {
            try { if (frame.contentDocument) docs.push(frame.contentDocument); } catch (_) {}
          });
          const clean = value => (value || '').replace(/\s+/g, ' ').trim();
          const candidates = docs.flatMap(doc => [...doc.querySelectorAll('body *')])
            .filter(element => {
              const text = clean(element.innerText || element.title || element.getAttribute('aria-label'));
              if (!/教务/.test(text) || text.length > 30) return false;
              return ![...element.children].some(child => /教务/.test(clean(child.innerText)));
            })
            .sort((a, b) => clean(a.innerText).length - clean(b.innerText).length);
          const target = candidates[0];
          if (!target) return false;
          const clickable = target.closest(
            'a,button,[role="button"],[onclick],[data-url],[data-href],li'
          ) || target;
          clickable.scrollIntoView({block: 'center', inline: 'center'});
          clickable.click();
          return true;
        })()
    """.trimIndent()
    fun handlePage(raw: String, pageUrl: String) {
        val decoded = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull() ?: raw
        val obj = runCatching { JSONObject(decoded) }.getOrNull()
        if (obj == null) { status = "页面读取失败，请点击重新登录"; return }
        val host = runCatching { android.net.Uri.parse(pageUrl).host }.getOrNull().orEmpty()
        when {
            obj.optBoolean("login") -> status = "请登录，然后从智慧校园进入教务系统并打开学生课表"
            obj.optBoolean("systemError") -> status = "登录状态已失效，请点击重新登录"
            obj.optJSONArray("rows")?.length() ?: 0 > 0 -> status = "已检测到课表页面，可以点击同步"
            obj.optBoolean("empty") -> status = "${host.ifBlank { "当前页面" }} 返回了空页面"
            host.contains("myportal-xttc-edu-cn-s.i.xttc.edu.cn", ignoreCase = true) ->
                status = "登录成功，请点击顶部“打开教务”"
            else -> status = "已打开 ${host.ifBlank { "网页" }}，请进入学生课表页面"
        }
    }
    fun openEduFromPortal() {
        val view = web ?: return
        val currentHost = runCatching { android.net.Uri.parse(view.url).host }.getOrNull().orEmpty()
        if (!currentHost.contains("myportal-xttc-edu-cn-s.i.xttc.edu.cn", ignoreCase = true)) {
            status = "正在返回智慧校园，请再次点击“打开教务”"
            view.loadUrl(portalUrl ?: SCHOOL_PORTAL_URL)
            return
        }
        status = "正在从智慧校园打开教务系统…"
        view.evaluateJavascript(openEduScript) { result ->
            if (result != "true") {
                status = "门户中暂未找到教务入口，请等待页面加载后重试"
            }
        }
    }
    fun restartLogin() {
        status = "正在清理会话并从邢台学院官网重新登录…"
        web?.let { view ->
            view.stopLoading()
            view.clearHistory()
            CookieManager.getInstance().removeAllCookies {
                CookieManager.getInstance().flush()
                view.post { view.loadUrl(SCHOOL_HOME_URL) }
            }
        }
    }
    fun syncCurrentPage() {
        web?.evaluateJavascript(extractScript) { raw ->
            val decoded = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull() ?: raw
            val obj = runCatching { JSONObject(decoded) }.getOrNull()
            when {
                obj?.optBoolean("login") == true ->
                    status = "请先在网页中完成登录，再进入课表查询页面"
                obj?.optBoolean("systemError") == true ->
                    status = "登录状态无效，请点击重新登录"
                else -> {
                    val rows = obj?.optJSONArray("rows")
                    if (rows == null || rows.length() == 0) {
                        val frameUrls = obj?.optJSONArray("frameUrls")
                        val timetableFrameUrl = (0 until (frameUrls?.length() ?: 0))
                            .mapNotNull { frameUrls?.optString(it) }
                            .firstOrNull { url ->
                                url.startsWith("http") && Regex("xskb|kbcx|course|timetable|grkb", RegexOption.IGNORE_CASE).containsMatchIn(url)
                            }
                        if (timetableFrameUrl != null) {
                            status = "已定位课表内页，正在打开；加载完成后请再次点击同步"
                            web?.loadUrl(timetableFrameUrl)
                        } else {
                            val diagnostics = obj?.optJSONObject("diagnostics")
                            val documents = diagnostics?.optInt("documents") ?: 0
                            val tables = diagnostics?.optInt("tables") ?: 0
                            val score = diagnostics?.optInt("bestScore") ?: 0
                            status = "未读取到课程（页面 $documents 层、表格 $tables 个、匹配 $score），请将此提示截图发给开发者"
                        }
                    } else {
                        vm.syncFromJson(rows.toString())
                        web?.destroy()
                        onClose()
                    }
                }
            }
        }
    }
    Dialog(onDismissRequest = { web?.destroy(); onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("登录教务系统", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { web?.destroy(); onClose() }) { Text("关闭") }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { restartLogin() }) { Text("重新登录") }
                    TextButton(onClick = { openEduFromPortal() }) { Text("打开教务") }
                    Button(onClick = { syncCurrentPage() }) { Text("同步当前页面") }
                }
                Text(
                    status,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
                AndroidView(factory = { c ->
                    WebView(c).apply {
                        var desktopModeEnabled = false
                        var mainLoadError = false
                        val mobileUserAgent = settings.userAgentString
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        settings.setSupportMultipleWindows(true)
                        settings.useWideViewPort = false
                        settings.loadWithOverviewMode = false
                        settings.setSupportZoom(false)
                        settings.builtInZoomControls = false
                        settings.textZoom = 100
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webChromeClient = object : WebChromeClient() {
                            override fun onCreateWindow(
                                view: WebView,
                                isDialog: Boolean,
                                isUserGesture: Boolean,
                                resultMsg: android.os.Message
                            ): Boolean {
                                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                                val popup = WebView(view.context)
                                popup.settings.javaScriptEnabled = true
                                popup.webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(
                                        popupView: WebView,
                                        url: String,
                                        favicon: android.graphics.Bitmap?
                                    ) {
                                        if (url != "about:blank") {
                                            view.loadUrl(url)
                                            popupView.stopLoading()
                                            popupView.destroy()
                                        }
                                    }
                                }
                                transport.webView = popup
                                resultMsg.sendToTarget()
                                return true
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(
                                view: WebView,
                                url: String,
                                favicon: android.graphics.Bitmap?
                            ) {
                                val isCasPage = url.contains("/cas/", ignoreCase = true)
                                val isPortalPage = url.contains("myportal-xttc-edu-cn-s.i.xttc.edu.cn", ignoreCase = true)
                                val isSchoolHomePage = runCatching {
                                    android.net.Uri.parse(url).host.equals("www.xttc.edu.cn", ignoreCase = true)
                                }.getOrDefault(false)
                                if (isPortalPage) portalUrl = url
                                mainLoadError = false
                                if ((isCasPage || isSchoolHomePage) && desktopModeEnabled) {
                                    desktopModeEnabled = false
                                    view.stopLoading()
                                    view.settings.userAgentString = mobileUserAgent
                                    view.settings.useWideViewPort = false
                                    view.settings.loadWithOverviewMode = false
                                    view.settings.setSupportZoom(false)
                                    view.settings.builtInZoomControls = false
                                    view.loadUrl(url)
                                    return
                                }
                                if (!isCasPage && !isPortalPage && !isSchoolHomePage && !desktopModeEnabled && url.startsWith("http")) {
                                    desktopModeEnabled = true
                                    view.stopLoading()
                                    view.settings.userAgentString = DESKTOP_USER_AGENT
                                    view.settings.useWideViewPort = true
                                    view.settings.loadWithOverviewMode = true
                                    view.settings.setSupportZoom(true)
                                    view.settings.builtInZoomControls = true
                                    view.settings.displayZoomControls = false
                                    status = "登录成功，正在加载智慧校园…"
                                    view.loadUrl(url)
                                    return
                                }
                                status = if (isCasPage) {
                                    "正在加载邢台学院统一身份认证…"
                                 } else if (isPortalPage) {
                                    "登录成功，请点击顶部“打开教务”"
                                } else {
                                    "正在加载智慧校园或教务系统…"
                                }
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                if (mainLoadError) return
                                val host = runCatching { android.net.Uri.parse(url).host }.getOrNull().orEmpty()
                                if (host.equals("www.xttc.edu.cn", ignoreCase = true)) {
                                    status = "正在从学校官网进入智慧校园…"
                                    view.evaluateJavascript(
                                        """(() => { const link = [...document.querySelectorAll('a')].find(a => /智慧校园/.test(a.innerText || '') && /cas-xttc-edu-cn/i.test(a.href || '')); if (link) { link.click(); return true; } return false; })()"""
                                    ) { clicked -> if (clicked != "true") view.loadUrl(SCHOOL_LOGIN_URL) }
                                    return
                                }
                                if (host.contains("myportal-xttc-edu-cn-s.i.xttc.edu.cn", ignoreCase = true) && !desktopModeEnabled) {
                                    desktopModeEnabled = true
                                    view.settings.userAgentString = DESKTOP_USER_AGENT
                                    view.settings.useWideViewPort = true
                                    view.settings.loadWithOverviewMode = true
                                    view.settings.setSupportZoom(true)
                                    view.settings.builtInZoomControls = true
                                    view.settings.displayZoomControls = false
                                    status = "登录成功，正在加载智慧校园…"
                                    view.reload()
                                    return
                                }
                                view.evaluateJavascript(extractScript) { raw -> handlePage(raw, url) }
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: WebResourceError
                            ) {
                                if (request.isForMainFrame) {
                                    mainLoadError = true
                                    status = "页面加载失败：${error.description}"
                                }
                            }

                            override fun onReceivedHttpError(
                                view: WebView,
                                request: WebResourceRequest,
                                errorResponse: WebResourceResponse
                            ) {
                                if (request.isForMainFrame) {
                                    mainLoadError = true
                                    status = "服务器返回错误 ${errorResponse.statusCode}，请重新登录"
                                }
                            }
                        }
                        loadUrl(SCHOOL_HOME_URL)
                        web = this
                    }
                }, modifier = Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}
