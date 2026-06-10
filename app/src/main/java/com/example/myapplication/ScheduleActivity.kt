package com.example.myapplication

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class ScheduleActivity : AppCompatActivity() {

    private lateinit var timeTableCanvas: FrameLayout
    private lateinit var etSubjectCodeInput: EditText
    private lateinit var btnAddSubject: TextView

    private lateinit var cardCurrentSubject: CardView
    private lateinit var currentClassEmptyCard: CardView
    private lateinit var tvCurrentSubjectName: TextView
    private lateinit var tvCurrentProfessor: TextView
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvCurrentRoom: TextView
    private lateinit var tvCurrentSubjectCode: TextView

    private val database = FirebaseDatabase.getInstance().reference

    private var studentId: String = ""
    private var allCourses: List<Course> = emptyList()
    private var selectedCourseCodes: MutableSet<String> = mutableSetOf()
    private var selectedCourses: List<Course> = emptyList()

    private val dayList = listOf("월", "화", "수", "목", "금")

    private val blockColors = listOf(
        "#7E92C1",
        "#BDA59B",
        "#7DBDC6",
        "#A5A6C8",
        "#C2A5A0",
        "#8DA7BE"
    )

    data class Course(
        val code: String,
        val name: String,
        val professor: String,
        val semester: String,
        val room: String,
        val meetings: List<Meeting>
    )

    data class Meeting(
        val day: String,
        val startMinute: Int,
        val endMinute: Int,
        val room: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.schedule_1)

        bindViews()

        studentId = getStudentId()

        loadSavedCodesFromLocal()
        listenFirebaseSchedule()

        btnAddSubject.setOnClickListener {
            addSubjectByCode()
        }

        etSubjectCodeInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addSubjectByCode()
                true
            } else {
                false
            }
        }

        findViewById<ImageButton>(R.id.btnBottomRefresh).setOnClickListener {
            listenFirebaseSchedule()
            Toast.makeText(this, "시간표를 새로고침했습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindViews() {
        timeTableCanvas = findViewById(R.id.timeTableCanvas)
        etSubjectCodeInput = findViewById(R.id.etSubjectCodeInput)
        btnAddSubject = findViewById(R.id.btnAddSubject)

        cardCurrentSubject = findViewById(R.id.cardCurrentSubject)
        currentClassEmptyCard = findViewById(R.id.currentClassEmptyCard)
        tvCurrentSubjectName = findViewById(R.id.tvCurrentSubjectName)
        tvCurrentProfessor = findViewById(R.id.tvCurrentProfessor)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvCurrentRoom = findViewById(R.id.tvCurrentRoom)
        tvCurrentSubjectCode = findViewById(R.id.tvCurrentSubjectCode)
    }

    private fun getStudentId(): String {
        val fromIntent = intent.getStringExtra("studentId")
        if (!fromIntent.isNullOrBlank()) return fromIntent

        val prefs = getSharedPreferences("login", MODE_PRIVATE)
        val fromPrefs = prefs.getString("studentId", null)
        if (!fromPrefs.isNullOrBlank()) return fromPrefs

        return "202234920"
    }

    private fun loadSavedCodesFromLocal() {
        val prefs = getSharedPreferences("schedule", MODE_PRIVATE)
        val saved = prefs.getStringSet("selectedCourseCodes_$studentId", emptySet()) ?: emptySet()
        selectedCourseCodes = saved.toMutableSet()
    }

    private fun saveCodesToLocal() {
        getSharedPreferences("schedule", MODE_PRIVATE)
            .edit()
            .putStringSet("selectedCourseCodes_$studentId", selectedCourseCodes)
            .apply()
    }

    private fun listenFirebaseSchedule() {
        database.get().addOnSuccessListener { root ->
            allCourses = extractCoursesFromFirebase(root)

            val firebaseCodes = extractSelectedCodes(root, studentId)
            selectedCourseCodes.addAll(firebaseCodes)

            selectedCourses = allCourses
                .filter { selectedCourseCodes.contains(it.code) }
                .distinctBy { it.code }

            renderTimeTable()
            renderUpcomingClass()
        }.addOnFailureListener {
            Toast.makeText(this, "시간표 데이터를 불러오지 못했습니다.", Toast.LENGTH_SHORT).show()
            renderTimeTable()
            renderUpcomingClass()
        }
    }

    private fun addSubjectByCode() {
        val inputCode = etSubjectCodeInput.text.toString().trim()

        if (inputCode.isBlank()) {
            Toast.makeText(this, "과목코드를 입력해주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        val course = allCourses.firstOrNull { it.code == inputCode }

        if (course == null) {
            Toast.makeText(this, "JSON 데이터에 없는 과목코드입니다.", Toast.LENGTH_SHORT).show()
            return
        }

        selectedCourseCodes.add(course.code)
        saveCodesToLocal()

        database.child("students")
            .child(studentId)
            .child("selectedCourses")
            .child(course.code)
            .setValue(true)

        selectedCourses = allCourses
            .filter { selectedCourseCodes.contains(it.code) }
            .distinctBy { it.code }

        etSubjectCodeInput.text.clear()

        renderTimeTable()
        renderUpcomingClass()

        Toast.makeText(this, "${course.name} 수업이 추가되었습니다.", Toast.LENGTH_SHORT).show()
    }

    private fun renderTimeTable() {
        timeTableCanvas.removeAllViews()

        if (selectedCourses.isEmpty()) {
            drawEmptyTimeTable()
            return
        }

        val maxEndMinute = selectedCourses
            .flatMap { it.meetings }
            .maxOfOrNull { it.endMinute } ?: 18 * 60

        val startHour = 9
        val endHour = max(ceil(maxEndMinute / 60.0).toInt(), 15)
        val hourCount = endHour - startHour

        val leftWidth = dp(24)
        val headerHeight = dp(20)
        val rowHeight = dp(32)
        val totalHeight = headerHeight + hourCount * rowHeight

        val lp = timeTableCanvas.layoutParams
        lp.width = dp(250)
        lp.height = totalHeight + dp(6)
        timeTableCanvas.layoutParams = lp

        timeTableCanvas.post {
            val totalWidth = timeTableCanvas.width
            val gridWidth = totalWidth - leftWidth
            val colWidth = gridWidth / 5

            drawGrid(startHour, endHour, leftWidth, headerHeight, rowHeight, colWidth)

            selectedCourses.forEachIndexed { index, course ->
                course.meetings.forEach { meeting ->
                    val dayIndex = dayList.indexOf(meeting.day)
                    if (dayIndex >= 0) {
                        drawClassBlock(
                            course = course,
                            meeting = meeting,
                            color = blockColors[index % blockColors.size],
                            startHour = startHour,
                            leftWidth = leftWidth,
                            headerHeight = headerHeight,
                            rowHeight = rowHeight,
                            colWidth = colWidth,
                            dayIndex = dayIndex
                        )
                    }
                }
            }
        }
    }

    private fun drawEmptyTimeTable() {
        val startHour = 9
        val endHour = 15
        val hourCount = endHour - startHour

        val leftWidth = dp(24)
        val headerHeight = dp(20)
        val rowHeight = dp(32)
        val totalHeight = headerHeight + hourCount * rowHeight

        val lp = timeTableCanvas.layoutParams
        lp.width = dp(250)
        lp.height = totalHeight + dp(6)
        timeTableCanvas.layoutParams = lp

        timeTableCanvas.post {
            val totalWidth = timeTableCanvas.width
            val gridWidth = totalWidth - leftWidth
            val colWidth = gridWidth / 5
            drawGrid(startHour, endHour, leftWidth, headerHeight, rowHeight, colWidth)
        }
    }

    private fun drawGrid(
        startHour: Int,
        endHour: Int,
        leftWidth: Int,
        headerHeight: Int,
        rowHeight: Int,
        colWidth: Int
    ) {
        timeTableCanvas.removeAllViews()

        dayList.forEachIndexed { index, day ->
            val tv = TextView(this)
            tv.text = day
            tv.textSize = 8f
            tv.setTextColor(Color.parseColor("#A3A3A3"))
            tv.gravity = Gravity.CENTER
            tv.includeFontPadding = false

            val params = FrameLayout.LayoutParams(colWidth, headerHeight)
            params.leftMargin = leftWidth + index * colWidth
            params.topMargin = 0
            timeTableCanvas.addView(tv, params)
        }

        for (hour in startHour until endHour) {
            val y = headerHeight + (hour - startHour) * rowHeight

            val hourTv = TextView(this)
            hourTv.text = if (hour <= 12) hour.toString() else (hour - 12).toString()
            hourTv.textSize = 8f
            hourTv.setTextColor(Color.parseColor("#A8A8A8"))
            hourTv.gravity = Gravity.TOP or Gravity.RIGHT
            hourTv.includeFontPadding = false

            val hourParams = FrameLayout.LayoutParams(leftWidth - dp(4), rowHeight)
            hourParams.leftMargin = 0
            hourParams.topMargin = y + dp(2)
            timeTableCanvas.addView(hourTv, hourParams)

            val horizontal = View(this)
            horizontal.setBackgroundColor(Color.parseColor("#EFEFEF"))

            val hParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            hParams.leftMargin = leftWidth
            hParams.topMargin = y
            timeTableCanvas.addView(horizontal, hParams)
        }

        for (i in 0..5) {
            val vertical = View(this)
            vertical.setBackgroundColor(Color.parseColor("#EFEFEF"))

            val vParams = FrameLayout.LayoutParams(dp(1), (endHour - startHour) * rowHeight)
            vParams.leftMargin = leftWidth + i * colWidth
            vParams.topMargin = headerHeight
            timeTableCanvas.addView(vertical, vParams)
        }

        val border = GradientDrawable()
        border.setColor(Color.TRANSPARENT)
        border.setStroke(dp(1), Color.parseColor("#E5E5E5"))
        border.cornerRadius = dp(8).toFloat()

        val borderView = View(this)
        borderView.background = border

        val borderParams = FrameLayout.LayoutParams(
            leftWidth + colWidth * 5,
            headerHeight + (endHour - startHour) * rowHeight
        )
        borderParams.leftMargin = 0
        borderParams.topMargin = 0
        timeTableCanvas.addView(borderView, borderParams)
    }

    private fun drawClassBlock(
        course: Course,
        meeting: Meeting,
        color: String,
        startHour: Int,
        leftWidth: Int,
        headerHeight: Int,
        rowHeight: Int,
        colWidth: Int,
        dayIndex: Int
    ) {
        val startOffsetMinute = meeting.startMinute - startHour * 60
        val durationMinute = meeting.endMinute - meeting.startMinute

        val top = headerHeight + (startOffsetMinute * rowHeight / 60.0).toInt()
        val height = max(dp(28), (durationMinute * rowHeight / 60.0).toInt())

        val block = TextView(this)
        block.text = makeBlockText(course, meeting)
        block.textSize = 7.5f
        block.setTextColor(Color.WHITE)
        block.gravity = Gravity.CENTER
        block.includeFontPadding = false
        block.setPadding(dp(2), dp(2), dp(2), dp(2))
        block.maxLines = 5
        block.typeface = Typeface.DEFAULT_BOLD

        val bg = GradientDrawable()
        bg.setColor(Color.parseColor(color))
        bg.cornerRadius = dp(2).toFloat()
        block.background = bg

        block.setOnClickListener {
            showSelectedCourse(course, meeting)
        }

        val params = FrameLayout.LayoutParams(colWidth - dp(4), height)
        params.leftMargin = leftWidth + dayIndex * colWidth + dp(2)
        params.topMargin = top
        timeTableCanvas.addView(block, params)
    }

    private fun makeBlockText(course: Course, meeting: Meeting): String {
        val room = meeting.room.ifBlank { course.room }
        val lectureType = when {
            room.contains("화상") -> "화상강의"
            course.name.contains("영어") -> "영어강의"
            else -> room
        }

        return "${course.name}\n($lectureType)"
    }

    private fun renderUpcomingClass() {
        val now = LocalDateTime.now()
        val today = now.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)
            .replace("요일", "")
        val nowMinute = now.hour * 60 + now.minute

        val upcoming = selectedCourses
            .flatMap { course ->
                course.meetings.map { meeting -> course to meeting }
            }
            .filter { (_, meeting) ->
                meeting.day == today &&
                        nowMinute >= meeting.startMinute - 60 &&
                        nowMinute <= meeting.endMinute + 60
            }
            .minByOrNull { (_, meeting) ->
                kotlin.math.abs(meeting.startMinute - nowMinute)
            }

        if (upcoming == null) {
            cardCurrentSubject.visibility = View.GONE
            currentClassEmptyCard.visibility = View.VISIBLE
            return
        }

        val course = upcoming.first
        val meeting = upcoming.second

        showSelectedCourse(course, meeting)
    }

    private fun showSelectedCourse(course: Course, meeting: Meeting) {
        currentClassEmptyCard.visibility = View.GONE
        cardCurrentSubject.visibility = View.VISIBLE

        tvCurrentSubjectName.text = course.name
        tvCurrentProfessor.text = course.professor.ifBlank { "교수 정보 없음" }

        val timeText = "${meeting.day} ${formatMinute(meeting.startMinute)} ~ ${formatMinute(meeting.endMinute)}"
        tvCurrentTime.text = timeText

        val roomText = meeting.room.ifBlank { course.room }.ifBlank { "강의실 정보 없음" }
        tvCurrentRoom.text = roomText

        tvCurrentSubjectCode.text = course.code
    }

    private fun extractCoursesFromFirebase(root: DataSnapshot): List<Course> {
        val result = mutableListOf<Course>()

        fun scan(node: DataSnapshot) {
            val code = getString(node, listOf("code", "courseCode", "subjectCode", "lectureCode", "과목코드"))
            val name = getString(node, listOf("name", "courseName", "subjectName", "lectureName", "title", "과목명"))

            if (!code.isNullOrBlank() && !name.isNullOrBlank()) {
                val professor = getString(
                    node,
                    listOf("professor", "professorName", "teacher", "instructor", "교수", "교수명")
                ) ?: ""

                val semester = getString(
                    node,
                    listOf("semester", "term", "학기")
                ) ?: ""

                val room = getString(
                    node,
                    listOf("room", "classroom", "lectureRoom", "location", "place", "강의실", "장소")
                ) ?: ""

                val rawTime = getString(
                    node,
                    listOf("time", "times", "schedule", "dayTime", "classTime", "요일/시간", "시간")
                ) ?: ""

                val meetings = extractMeetings(node, rawTime, room)

                if (meetings.isNotEmpty()) {
                    result.add(
                        Course(
                            code = code,
                            name = name,
                            professor = professor,
                            semester = semester,
                            room = room,
                            meetings = meetings
                        )
                    )
                }
            }

            node.children.forEach { scan(it) }
        }

        scan(root)

        return result.distinctBy { it.code }
    }

    private fun extractMeetings(node: DataSnapshot, rawTime: String, defaultRoom: String): List<Meeting> {
        val meetings = mutableListOf<Meeting>()

        if (rawTime.isNotBlank()) {
            meetings.addAll(parseTimeString(rawTime, defaultRoom))
        }

        val schedulesNode = node.child("schedules")
        if (schedulesNode.exists()) {
            schedulesNode.children.forEach { child ->
                val day = getString(child, listOf("day", "요일")) ?: ""
                val start = getString(child, listOf("start", "startTime", "시작")) ?: ""
                val end = getString(child, listOf("end", "endTime", "종료")) ?: ""
                val room = getString(child, listOf("room", "classroom", "lectureRoom", "강의실", "장소")) ?: defaultRoom

                if (day.isNotBlank() && start.isNotBlank() && end.isNotBlank()) {
                    meetings.add(
                        Meeting(
                            day = normalizeDay(day),
                            startMinute = parseClock(start),
                            endMinute = parseClock(end),
                            room = room
                        )
                    )
                }
            }
        }

        val day = getString(node, listOf("day", "요일")) ?: ""
        val start = getString(node, listOf("start", "startTime", "시작")) ?: ""
        val end = getString(node, listOf("end", "endTime", "종료")) ?: ""

        if (day.isNotBlank() && start.isNotBlank() && end.isNotBlank()) {
            meetings.add(
                Meeting(
                    day = normalizeDay(day),
                    startMinute = parseClock(start),
                    endMinute = parseClock(end),
                    room = defaultRoom
                )
            )
        }

        return meetings
            .filter { it.day in dayList && it.startMinute < it.endMinute }
            .distinctBy { "${it.day}_${it.startMinute}_${it.endMinute}_${it.room}" }
    }

    private fun parseTimeString(raw: String, defaultRoom: String): List<Meeting> {
        val result = mutableListOf<Meeting>()

        val normalized = raw
            .replace("，", ",")
            .replace("/", ",")
            .replace("~", "-")

        val parts = normalized.split(",").map { it.trim() }.filter { it.isNotBlank() }

        val regex = Regex("(월|화|수|목|금)\\s*(\\d{1,2}:\\d{2})\\s*-\\s*(\\d{1,2}:\\d{2})")

        parts.forEach { part ->
            val match = regex.find(part)
            if (match != null) {
                val day = match.groupValues[1]
                val start = match.groupValues[2]
                val end = match.groupValues[3]

                result.add(
                    Meeting(
                        day = day,
                        startMinute = parseClock(start),
                        endMinute = parseClock(end),
                        room = defaultRoom
                    )
                )
            }
        }

        return result
    }

    private fun extractSelectedCodes(root: DataSnapshot, targetStudentId: String): Set<String> {
        val result = mutableSetOf<String>()

        val studentNode = findStudentNode(root, targetStudentId)

        if (studentNode != null) {
            fun scanStudent(node: DataSnapshot) {
                val code = getString(
                    node,
                    listOf("code", "courseCode", "subjectCode", "lectureCode", "과목코드")
                )

                if (!code.isNullOrBlank()) {
                    result.add(code)
                }

                if (node.key == "selectedCourses") {
                    node.children.forEach { child ->
                        if (child.value == true || child.value.toString() == "true") {
                            child.key?.let { result.add(it) }
                        }
                    }
                }

                node.children.forEach { scanStudent(it) }
            }

            scanStudent(studentNode)
        }

        return result
    }

    private fun findStudentNode(root: DataSnapshot, targetStudentId: String): DataSnapshot? {
        var found: DataSnapshot? = null

        fun scan(node: DataSnapshot) {
            if (found != null) return

            if (node.key == targetStudentId) {
                found = node
                return
            }

            val idValue = getString(node, listOf("studentId", "id", "학번"))
            if (idValue == targetStudentId) {
                found = node
                return
            }

            node.children.forEach { scan(it) }
        }

        scan(root)

        return found
    }

    private fun getString(node: DataSnapshot, keys: List<String>): String? {
        keys.forEach { key ->
            val value = node.child(key).value
            if (value != null && value.toString().isNotBlank()) {
                return value.toString()
            }
        }
        return null
    }

    private fun normalizeDay(day: String): String {
        return day
            .replace("요일", "")
            .replace("Monday", "월")
            .replace("Tuesday", "화")
            .replace("Wednesday", "수")
            .replace("Thursday", "목")
            .replace("Friday", "금")
            .trim()
            .take(1)
    }

    private fun parseClock(text: String): Int {
        val clean = text.trim()
        val parts = clean.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return hour * 60 + minute
    }

    private fun formatMinute(minute: Int): String {
        val h = minute / 60
        val m = minute % 60
        return "%02d:%02d".format(h, m)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}