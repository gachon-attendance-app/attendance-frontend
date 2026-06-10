package com.example.myapplication

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.launcher.AttendanceServiceLauncher
import com.example.myapplication.model.data.repository.AttendanceRepositoryImpl
import com.example.myapplication.model.domain.model.Subject
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var contentFrame: FrameLayout

    private val repository = AttendanceRepositoryImpl()

    private var currentPageResId: Int = R.layout.main1
    private var userId: String = ""
    private var userName: String = ""
    private var userRole: String = "student"
    private var currentSubjectCode: String = ""

    private var currentClassName: String = "모바일 프로그래밍"
    private var currentClassTime: String = "10:00 ~ 10:50"
    private var currentClassStartTime: String = "10:00"
    private var weekCalendar: Calendar = Calendar.getInstance(Locale.KOREA)
    private var weekSelectedDateStr: String = ""

    private val defaultStudentId = "202234920"
    private val firebaseDb = FirebaseDatabase.getInstance().reference

    private val handler = Handler(Looper.getMainLooper())
    private var pinPopupShowing = false
    private var uwbRunnable: Runnable? = null

    private var attendanceRecordListener: ValueEventListener? = null
    private var attendanceSessionListener: ValueEventListener? = null
    private var activeRecordRef: DatabaseReference? = null
    private var activeSessionRef: DatabaseReference? = null

    /** 출석 Service trigger + 권한 흐름 + Service→Activity broadcast 수신 헬퍼. */
    private lateinit var launcher: AttendanceServiceLauncher

    /** 페이즈 UI 전환(15분 후 After15+UWB 카드) 클라이언트 timer. */
    private var phaseTransitionRunnable: Runnable? = null

    companion object {
        private const val DEFAULT_SUBJECT_CODE = "14454001"
        private const val BLUE_ACTIVE = "#0281F6"
        private const val GRAY_INACTIVE = "#9E9EA4"
        private const val FIVE_MINUTES = 5 * 60 * 1000L
        private const val TEN_MINUTES = 10 * 60 * 1000L
        private const val FIFTEEN_MINUTES = 15 * 60 * 1000L
        private const val DEMO_SUBJECT_CODE = "99001001"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        readLoginInfo()
        if (userId.isNotBlank()) {
            com.example.myapplication.schedule.work.ScheduleSyncWorker.enqueueOnce(this, userId)
            com.example.myapplication.schedule.work.ScheduleSyncWorker.enqueuePeriodic(this, userId)
        }

        setContentView(R.layout.activity_drawer_host)

        drawerLayout = findViewById(R.id.drawerLayout)
        contentFrame = findViewById(R.id.contentFrame)

        launcher = AttendanceServiceLauncher(this)
        launcher.setListener(sessionListener)
        launcher.requestStartupPermissions()

        registerDemoSubjectToFirebase()

        if (userRole == "professor") {
            loadPage(R.layout.main_p_1)
        } else {
            loadPage(R.layout.main1)
        }

        setupDrawerMenuClick()
    }

    override fun onResume() {
        super.onResume()
        launcher.registerReceiver()
    }

    override fun onPause() {
        super.onPause()
        launcher.unregisterReceiver()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        launcher.handlePermissionResult(requestCode, grantResults)
    }

    override fun onDestroy() {
        super.onDestroy()
        uwbRunnable?.let { handler.removeCallbacks(it) }
        phaseTransitionRunnable?.let { handler.removeCallbacks(it) }
        removeRecordListener()
        removeSessionListener()
        firebaseDb.child("Enrollment").child(userId.ifBlank { defaultStudentId }).child(DEMO_SUBJECT_CODE).removeValue()
    }

    private fun registerDemoSubjectToFirebase() {
        val demoData = mapOf(
            "subjectCode" to DEMO_SUBJECT_CODE,
            "subjectName" to "자료구조 및 실습 (영어강의)",
            "professorName" to "안종현",
            "schedule" to mapOf(
                "day1" to mapOf(
                    "dayOfWeek" to "Tuesday",
                    "location" to "AI관-412",
                    "periods" to listOf(
                        null,
                        mapOf("startTime" to "10:00", "endTime" to "10:50"),
                        mapOf("startTime" to "11:00", "endTime" to "11:50")
                    )
                ),
                "day2" to mapOf(
                    "dayOfWeek" to "Thursday",
                    "location" to "AI관-511",
                    "periods" to listOf(
                        null,
                        mapOf("startTime" to "10:00", "endTime" to "10:50"),
                        mapOf("startTime" to "11:00", "endTime" to "11:50"),
                        mapOf("startTime" to "12:00", "endTime" to "12:50")
                    )
                )
            )
        )

        firebaseDb.child("Subjects").child(DEMO_SUBJECT_CODE).setValue(demoData)
        firebaseDb.child("Enrollment").child(defaultStudentId).child(DEMO_SUBJECT_CODE).removeValue()
        if (userId.isNotBlank() && userId != defaultStudentId) {
            firebaseDb.child("Enrollment").child(userId).child(DEMO_SUBJECT_CODE).removeValue()
        }
    }

    private val sessionListener = object : AttendanceServiceLauncher.SessionEventsListener {
        override fun onSessionStarted(sessionCode: String?, lectureSessionId: String?) {
            val pageView = contentFrame.getChildAt(0) ?: return
            showPin(pageView, sessionCode ?: "")
            Toast.makeText(this@MainActivity, "출석체크가 시작되었습니다 (PIN: $sessionCode)", Toast.LENGTH_SHORT).show()
        }

        override fun onSessionFailed(reason: String?) {
            Toast.makeText(this@MainActivity, "출석 시작 실패: $reason", Toast.LENGTH_LONG).show()
        }

        override fun onSessionExpired() {
            Toast.makeText(this@MainActivity, "BLE 광고 종료 (PIN 수동 입력 계속 가능)", Toast.LENGTH_SHORT).show()
        }

        override fun onAttendanceConfirmed(sessionCode: String?) {
            val pageView = contentFrame.getChildAt(0) ?: return
            updateStudentAttendanceUi(pageView, "출석 완료", true)
            Toast.makeText(this@MainActivity, "출석 처리되었습니다", Toast.LENGTH_SHORT).show()
        }

        override fun onAttendanceFailed(reason: String?) {
            Toast.makeText(this@MainActivity, "출석 실패: $reason", Toast.LENGTH_LONG).show()
        }

        override fun onAttendanceAbsent(attendanceId: String?) {
            val pageView = contentFrame.getChildAt(0) ?: return
            updateStudentAttendanceUi(pageView, "결석", false)
            AlertDialog.Builder(this@MainActivity)
                .setTitle("결석 처리")
                .setMessage("UWB 재실 검증에 3회 연속 실패하여 결석 처리되었습니다.")
                .setPositiveButton("확인", null)
                .show()
        }
    }

    private fun readLoginInfo() {
        val pref = getSharedPreferences("LOGIN_INFO", MODE_PRIVATE)
        userId = pref.getString("userId", "") ?: ""
        userName = pref.getString("userName", "") ?: ""
        userRole = pref.getString("userRole", "student") ?: "student"
        if (userId.isBlank()) {
            userId = defaultStudentId
        }
    }

    private fun loadPage(layoutResId: Int) {
        currentPageResId = layoutResId

        // 페이지가 바뀔 때마다 기존에 걸어둔 실시간 감지 리스너 해제 (메모리 누수 방지)
        removeRecordListener()
        removeSessionListener()
        contentFrame.removeAllViews()

        val pageView = LayoutInflater.from(this).inflate(layoutResId, contentFrame, false)
        contentFrame.addView(pageView)

        connectTopMenuButton(pageView)
        connectBottomMenu(pageView)
        loadJsonDataForPage(layoutResId, pageView)
    }

    private fun loadJsonDataForPage(layoutResId: Int, pageView: View) {
        when (layoutResId) {
            R.layout.main1 -> {
                loadCurrentClass(pageView)
            }
            R.layout.main_p_1 -> {
                loadProfessorPage(pageView)
                pageView.findViewById<View?>(R.id.btnProfessorAttendanceCheck)?.setOnClickListener {
                    startAttendanceSession(pageView)
                }
                pageView.findViewById<View?>(R.id.btnRollCallAttendance)?.setOnClickListener {
                    Toast.makeText(this, "호명출석 기능은 출석체크 시작 전만 사용할 수 있습니다", Toast.LENGTH_SHORT).show()
                }
            }
            R.layout.schedule_1 -> loadSchedule(pageView)
            R.layout.mypage -> {
                loadMyPage(pageView)
                loadSchedule(pageView)
            }
            R.layout.week_1, R.layout.week_2 -> loadWeekPage(pageView)
            R.layout.all_attendance -> loadAttendanceSummary(pageView)
        }
    }

    private fun connectTopMenuButton(pageView: View) {
        pageView.findViewById<View?>(R.id.btnMenu)?.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.END)
        }
    }

    private fun connectBottomMenu(pageView: View) {
        val btnHome = pageView.findViewById<View?>(R.id.btnBottomHome)
        val btnRefresh = pageView.findViewById<View?>(R.id.btnBottomRefresh)
        val btnNotice = pageView.findViewById<View?>(R.id.btnBottomNotice)
        val btnSchedule = pageView.findViewById<View?>(R.id.btnBottomSchedule)
        val btnLogout = pageView.findViewById<View?>(R.id.btnBottomLogout)

        btnHome?.setOnClickListener { if (userRole == "professor") loadPage(R.layout.main_p_1) else loadPage(R.layout.main1) }
        btnRefresh?.setOnClickListener {
            loadPage(currentPageResId)
            Toast.makeText(this, "새로고침되었습니다", Toast.LENGTH_SHORT).show()
        }
        btnNotice?.setOnClickListener { if (userRole == "professor") loadPage(R.layout.notice_2) else loadPage(R.layout.notice_1) }
        btnSchedule?.setOnClickListener { loadPage(R.layout.schedule_1) }
        btnLogout?.setOnClickListener { logout() }
    }

    private fun setupDrawerMenuClick() {
        findViewById<View?>(R.id.menuMyPage)?.setOnClickListener { moveTo(R.layout.mypage) }
        findViewById<View?>(R.id.menuSchedule)?.setOnClickListener { moveTo(R.layout.schedule_1) }
        findViewById<View?>(R.id.menuWeekAttendance)?.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.END)
            loadPage(R.layout.week_1)
        }
        findViewById<View?>(R.id.menuAllAttendance)?.setOnClickListener { moveTo(R.layout.all_attendance) }
        findViewById<View?>(R.id.menuConfirmPeriod)?.setOnClickListener { moveTo(R.layout.confirm_1) }
        findViewById<View?>(R.id.menuConfirmOfficial)?.setOnClickListener { moveTo(R.layout.confirm_2) }
        findViewById<View?>(R.id.menuNotice)?.setOnClickListener {
            if (userRole == "professor") moveTo(R.layout.notice_2) else moveTo(R.layout.notice_1)
        }
        findViewById<View?>(R.id.menuCancel)?.setOnClickListener {
            if (userRole == "professor") moveTo(R.layout.cancel_2) else moveTo(R.layout.cancel_1)
        }
    }

    private fun moveTo(layoutResId: Int) {
        drawerLayout.closeDrawer(GravityCompat.END)
        loadPage(layoutResId)
    }

    private fun loadCurrentClass(pageView: View) {
        val calendar = Calendar.getInstance(Locale.KOREA)
        val currentDayInt = when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 1; Calendar.TUESDAY -> 2; Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4; Calendar.FRIDAY -> 5; Calendar.SATURDAY -> 6; Calendar.SUNDAY -> 7; else -> 1
        }
        val nowStr = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date())

        fun timeToMinutes(timeStr: String): Int {
            val parts = timeStr.split(":")
            val h = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
            return h * 60 + m
        }

        // 위쪽 UI 초기화
        pageView.findViewById<TextView>(R.id.tvDate)?.text = todayText()
        pageView.findViewById<TextView>(R.id.tvClassName)?.text = "시간표 분석 중..."
        pageView.findViewById<TextView>(R.id.tvClassTime)?.text = "-"
        pageView.findViewById<TextView>(R.id.tvPeriod)?.text = "-"

        // 아래쪽 UI 초기화
        pageView.findViewById<TextView>(R.id.tvDetailClassName)?.text = "분석 중..."
        pageView.findViewById<TextView>(R.id.tvDetailProfessor)?.text = "-"
        pageView.findViewById<TextView>(R.id.tvDetailTime)?.text = "-"
        pageView.findViewById<TextView>(R.id.tvDetailRoom)?.text = "-"

        FirebaseClient.get("Enrollment/$userId") { enrollmentJson ->
            val subjectCodes = mutableListOf<String>()
            val keys = enrollmentJson?.keys()
            if (keys != null) {
                while (keys.hasNext()) subjectCodes.add(keys.next())
            }

            if (subjectCodes.isEmpty()) {
                runOnUiThread {
                    pageView.findViewById<TextView>(R.id.tvClassName)?.text = "수강 신청 내역 없음"
                    updateStudentAttendanceUi(pageView, "미출석", false)
                }
                return@get
            }

            class ClassInstance(
                val subjectCode: String, val subjectName: String, val profName: String,
                val location: String, val fullScheduleStr: String,
                val dayOfWeekInt: Int, val startTime: String, val endTime: String
            )

            val allClasses = mutableListOf<ClassInstance>()
            var fetchCount = 0
            val lock = Any()

            subjectCodes.forEach { subjectCode ->
                FirebaseClient.get("Subjects/$subjectCode") { subjectJson ->
                    synchronized(lock) {
                        fetchCount++
                        if (subjectJson != null) {
                            val subCode = subjectJson.optString("subjectCode", subjectCode)
                            val subName = subjectJson.optString("subjectName", "알 수 없는 과목")
                            val profName = subjectJson.optString("professorName", "미정")
                            val scheduleObj = subjectJson.optJSONObject("schedule")

                            if (scheduleObj != null) {
                                val dayKeys = scheduleObj.keys()
                                val allSchedulesForThisSubject = mutableListOf<String>()
                                var representLocation = "미정"

                                // 첫 번째 루프: 이 과목의 전체 시간표 문자열 만들기 (예: 월 10:00-11:50, 수 10:00-11:50)
                                val dayKeysList = scheduleObj.keys().asSequence().toList()
                                for (dayKey in dayKeysList) {
                                    val dayObj = scheduleObj.optJSONObject(dayKey) ?: continue
                                    val dayKr = when (dayObj.optString("dayOfWeek", "").uppercase()) {
                                        "MONDAY"->"월"; "TUESDAY"->"화"; "WEDNESDAY"->"수"
                                        "THURSDAY"->"목"; "FRIDAY"->"금"; "SATURDAY"->"토"; "SUNDAY"->"일"
                                        else -> dayObj.optString("dayOfWeek", "")
                                    }
                                    if (representLocation == "미정") representLocation = dayObj.optString("location", "미정")

                                    val periodsArr = dayObj.optJSONArray("periods")
                                    var sTime = ""; var eTime = ""
                                    if (periodsArr != null) {
                                        for (i in 0 until periodsArr.length()) {
                                            val p = periodsArr.optJSONObject(i) ?: continue
                                            if (sTime.isEmpty()) sTime = p.optString("startTime", "")
                                            eTime = p.optString("endTime", "")
                                        }
                                    }
                                    if (sTime.isNotEmpty() && eTime.isNotEmpty()) {
                                        allSchedulesForThisSubject.add("$dayKr $sTime-$eTime")
                                    }
                                }
                                val fullScheduleStr = allSchedulesForThisSubject.joinToString(", ")

                                // 두 번째 루프: 가장 임박한 시간을 찾기 위해 요일별로 쪼개서 리스트에 담기
                                for (dayKey in dayKeysList) {
                                    val dayObj = scheduleObj.optJSONObject(dayKey) ?: continue
                                    val dayOfWeekStr = dayObj.optString("dayOfWeek", "")
                                    val dayInt = when (dayOfWeekStr.uppercase()) {
                                        "MONDAY", "월" -> 1; "TUESDAY", "화" -> 2; "WEDNESDAY", "수" -> 3
                                        "THURSDAY", "목" -> 4; "FRIDAY", "금" -> 5; "SATURDAY", "토" -> 6; "SUNDAY", "일" -> 7; else -> -1
                                    }

                                    if (dayInt != -1) {
                                        val periodsArr = dayObj.optJSONArray("periods")
                                        if (periodsArr != null) {
                                            var firstStart: String? = null
                                            var lastEnd: String? = null
                                            for (i in 0 until periodsArr.length()) {
                                                val p = periodsArr.optJSONObject(i) ?: continue
                                                val st = p.optString("startTime", "")
                                                val ed = p.optString("endTime", "")
                                                if (st.isNotEmpty() && ed.isNotEmpty()) {
                                                    if (firstStart == null) firstStart = st
                                                    lastEnd = ed
                                                }
                                            }
                                            if (firstStart != null && lastEnd != null) {
                                                allClasses.add(ClassInstance(subCode, subName, profName, representLocation, fullScheduleStr, dayInt, firstStart, lastEnd))
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (fetchCount == subjectCodes.size) {
                            runOnUiThread {
                                if (allClasses.isEmpty()) {
                                    pageView.findViewById<TextView>(R.id.tvClassName)?.text = "등록된 수업 없음"
                                    pageView.findViewById<TextView>(R.id.tvDetailClassName)?.text = "등록된 수업 없음"
                                    return@runOnUiThread
                                }

                                val ongoingClass = allClasses.firstOrNull {
                                    it.dayOfWeekInt == currentDayInt && nowStr >= it.startTime && nowStr <= it.endTime
                                }

                                val currentTotalMinutes = currentDayInt * 24 * 60 + timeToMinutes(nowStr)
                                val upcomingClass = allClasses.minByOrNull {
                                    var classMins = it.dayOfWeekInt * 24 * 60 + timeToMinutes(it.startTime)
                                    if (classMins < currentTotalMinutes) classMins += 7 * 24 * 60
                                    classMins
                                }

                                val targetClass = ongoingClass ?: upcomingClass
                                if (targetClass == null) return@runOnUiThread

                                currentSubjectCode = targetClass.subjectCode
                                currentClassName = targetClass.subjectName
                                currentClassStartTime = targetClass.startTime
                                currentClassTime = "${targetClass.startTime} ~ ${targetClass.endTime}"

                                val startHour = currentClassStartTime.substringBefore(":").toIntOrNull() ?: 10
                                val periodNumber = when (startHour) {
                                    9->"1교시"; 10->"2교시"; 11->"3교시"; 12->"4교시"; 13->"5교시"; 14->"6교시"; 15->"7교시"; 16->"8교시"; else->"1교시"
                                }

                                val dayKr = when (targetClass.dayOfWeekInt) { 1->"월"; 2->"화"; 3->"수"; 4->"목"; 5->"금"; 6->"토"; 7->"일"; else->"" }
                                val periodText = if (ongoingClass != null) "수업 중" else "($dayKr) $periodNumber 예정"

                                pageView.findViewById<TextView>(R.id.tvClassName)?.text = currentClassName
                                pageView.findViewById<TextView>(R.id.tvDate)?.text = dateTextForScheduleDay(targetClass.dayOfWeekInt)
                                pageView.findViewById<TextView>(R.id.tvClassTime)?.text = currentClassTime
                                pageView.findViewById<TextView>(R.id.tvPeriod)?.text = periodText

                                pageView.findViewById<TextView>(R.id.tvDetailClassName)?.text = currentClassName
                                pageView.findViewById<TextView>(R.id.tvDetailProfessor)?.text = targetClass.profName
                                pageView.findViewById<TextView>(R.id.tvDetailTime)?.text = currentClassTime
                                pageView.findViewById<TextView>(R.id.tvDetailRoom)?.text = targetClass.location

                                updateStudentAttendanceUi(
                                    pageView,
                                    if (ongoingClass != null) "출석대기" else "미출석",
                                    false
                                )
                                setupStudentAttendanceButton(pageView)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun setupStudentAttendanceButton(pageView: View) {
        val btnAttendance = pageView.findViewById<Button?>(R.id.btnAttendance) ?: return
        setAttendanceButtonInactive(btnAttendance)

        val today = apiDateText()
        if (currentSubjectCode.isBlank()) currentSubjectCode = DEFAULT_SUBJECT_CODE

        val database = FirebaseDatabase.getInstance().reference
        activeRecordRef = database.child("Attendance_Records").child(currentSubjectCode).child(today).child(userId)

        attendanceRecordListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (currentPageResId != R.layout.main1) return

                val currentStatus = snapshot.child("finalStatus").getValue(String::class.java) ?: ""

                when (currentStatus) {
                    "출석", "출석 완료", "異쒖꽍" -> {
                        setAttendanceButtonCompleted(btnAttendance)
                        updateStudentAttendanceUi(pageView, "출석 완료", true)
                        btnAttendance.setOnClickListener { Toast.makeText(this@MainActivity, "이미 출석 처리되었습니다", Toast.LENGTH_SHORT).show() }
                        removeSessionListener() // 출석 완료 시 더 이상 세션 관찰 안함
                    }
                    "결석", "寃곗꽍" -> {
                        setAttendanceButtonInactive(btnAttendance)
                        updateStudentAttendanceUi(pageView, "결석", false)
                        btnAttendance.setOnClickListener { Toast.makeText(this@MainActivity, "결석 처리되었습니다", Toast.LENGTH_SHORT).show() }
                        removeSessionListener()
                    }
                    "지각" -> {
                        setAttendanceButtonInactive(btnAttendance)
                        updateStudentAttendanceUi(pageView, "지각", false)
                        btnAttendance.setOnClickListener { Toast.makeText(this@MainActivity, "지각 처리되었습니다", Toast.LENGTH_SHORT).show() }
                        removeSessionListener()
                    }
                    else -> {
                        updateStudentAttendanceUi(pageView, "미출석", false)
                        observeSessionStatus(pageView, btnAttendance, today)
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        activeRecordRef?.addValueEventListener(attendanceRecordListener!!)
    }

    private fun observeSessionStatus(pageView: View, btnAttendance: Button, today: String) {
        if (activeSessionRef != null) return // 이미 리스너가 작동 중이면 패스

        val database = FirebaseDatabase.getInstance().reference
        activeSessionRef = database.child("Attendance_Session").child(currentSubjectCode).child(today)

        attendanceSessionListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (currentPageResId != R.layout.main1) return

                if (!snapshot.exists()) {
                    updateStudentAttendanceUi(pageView, "미출석", false)
                    setAttendanceButtonInactive(btnAttendance)
                    btnAttendance.setOnClickListener { Toast.makeText(this@MainActivity, "출석체크 시간이 아닙니다", Toast.LENGTH_SHORT).show() }
                    return
                }

                val status = snapshot.child("status").getValue(String::class.java) ?: "READY"
                val bluetoothEndAt = snapshot.child("bluetoothEndAt").getValue(Long::class.java) ?: 0L
                val pinEndAt = snapshot.child("pinEndAt").getValue(Long::class.java) ?: 0L
                val classStartAt = snapshot.child("classStartAt").getValue(Long::class.java) ?: 0L
                val now = System.currentTimeMillis()

                if (status == "BLUETOOTH_ACTIVE" && now <= bluetoothEndAt) {
                    updateStudentAttendanceUi(pageView, "출석대기", false)
                    setAttendanceButtonActive(btnAttendance)
                    btnAttendance.setOnClickListener { startBluetoothAttendanceScan() }
                } else if (now in (bluetoothEndAt + 1)..pinEndAt) {
                    updateStudentAttendanceUi(pageView, "출석대기", false)
                    setAttendanceButtonInactive(btnAttendance)

                    val sessionJson = JSONObject().apply {
                        put("status", status)
                        put("classStartAt", classStartAt)
                        put("pinEndAt", pinEndAt)
                    }
                    btnAttendance.setOnClickListener { checkStudentPinEligibilityAndShow(pageView, sessionJson) }
                } else {
                    updateStudentAttendanceUi(pageView, "미출석", false)
                    setAttendanceButtonInactive(btnAttendance)
                    btnAttendance.setOnClickListener { Toast.makeText(this@MainActivity, "출석체크 시간이 아닙니다", Toast.LENGTH_SHORT).show() }
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        activeSessionRef?.addValueEventListener(attendanceSessionListener!!)
    }

    private fun removeRecordListener() {
        attendanceRecordListener?.let { activeRecordRef?.removeEventListener(it) }
        attendanceRecordListener = null
        activeRecordRef = null
    }

    private fun removeSessionListener() {
        attendanceSessionListener?.let { activeSessionRef?.removeEventListener(it) }
        attendanceSessionListener = null
        activeSessionRef = null
    }

    private fun setAttendanceButtonActive(button: Button) {
        button.setBackgroundResource(R.drawable.bg_attendance_button_blue)
        button.text = "출석\n체크"
        button.isEnabled = true
        button.alpha = 1.0f
    }

    private fun setAttendanceButtonInactive(button: Button) {
        button.setBackgroundResource(R.drawable.bg_attendance_button_gray)
        button.text = "출석\n체크"
        button.isEnabled = true
        button.alpha = 1.0f
    }

    private fun setAttendanceButtonCompleted(button: Button) {
        button.setBackgroundResource(R.drawable.bg_attendance_button_gray)
        button.text = "출석\n완료"
        button.isEnabled = true
        button.alpha = 1.0f
    }

    private fun startBluetoothAttendanceScan() {
        if (userId.isBlank()) {
            Toast.makeText(this, "로그인 정보가 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "BLE 출석 신호를 찾는 중...", Toast.LENGTH_SHORT).show()
        launcher.startStudent(userId)
    }

    private fun checkStudentPinEligibilityAndShow(pageView: View, sessionJson: JSONObject) {
        if (pinPopupShowing) return
        val today = apiDateText()

        FirebaseClient.get("Attendance_Records/$currentSubjectCode/$today/$userId") { recordJson ->
            val currentStatus = recordJson?.optString("finalStatus", "결석") ?: "결석"

            if (currentStatus == "출석" || currentStatus == "출석 완료") {
                runOnUiThread {
                    Toast.makeText(this, "이미 출석 처리되었습니다", Toast.LENGTH_SHORT).show()
                }
                return@get
            }

            runOnUiThread {
                showPinDialog(pageView, sessionJson)
            }
        }
    }

    private fun showPinDialog(pageView: View, sessionJson: JSONObject) {
        pinPopupShowing = true

        val dialogView = LayoutInflater.from(this).inflate(R.layout.pin, null)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        val etPin1 = dialogView.findViewById<EditText>(R.id.etPin1)
        val etPin2 = dialogView.findViewById<EditText>(R.id.etPin2)
        val etPin3 = dialogView.findViewById<EditText>(R.id.etPin3)
        val etPin4 = dialogView.findViewById<EditText>(R.id.etPin4)

        val tvPinClassName = dialogView.findViewById<TextView>(R.id.tvPinClassName)
        val tvPinClassTime = dialogView.findViewById<TextView>(R.id.tvPinClassTime)
        val tvPinRemainTime = dialogView.findViewById<TextView>(R.id.tvPinRemainTime)
        val tvPinStatusGuide = dialogView.findViewById<TextView>(R.id.tvPinStatusGuide)
        val tvPinResultMessage = dialogView.findViewById<TextView>(R.id.tvPinResultMessage)

        val btnPinCancel = dialogView.findViewById<Button>(R.id.btnPinCancel)
        val btnPinConfirm = dialogView.findViewById<Button>(R.id.btnPinConfirm)

        val now = System.currentTimeMillis()
        val classStartAt = sessionJson.optLong("classStartAt", todayMillisFromTime(currentClassStartTime))
        val pinEndAt = sessionJson.optLong("pinEndAt", classStartAt + FIFTEEN_MINUTES)

        val remainMs = (pinEndAt - now).coerceAtLeast(0L)
        val remainMinute = remainMs / 1000 / 60
        val remainSecond = remainMs / 1000 % 60

        tvPinClassName.text = currentClassName
        tvPinClassTime.text = currentClassTime
        tvPinRemainTime.text = "PIN 입력 가능 시간 %02d:%02d".format(remainMinute, remainSecond)

        if (now < classStartAt + TEN_MINUTES) {
            tvPinStatusGuide.text = "현재 PIN 인증 시 출석 처리됩니다."
        } else {
            tvPinStatusGuide.text = "현재 PIN 인증 시 결석 처리됩니다."
        }

        btnPinCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnPinConfirm.setOnClickListener {
            val inputPin = etPin1.text.toString() +
                    etPin2.text.toString() +
                    etPin3.text.toString() +
                    etPin4.text.toString()

            if (System.currentTimeMillis() > pinEndAt) {
                tvPinResultMessage.visibility = View.VISIBLE
                tvPinResultMessage.text = "PIN 입력 시간이 종료되었습니다."
                return@setOnClickListener
            }

            if (inputPin.length != 4) {
                tvPinResultMessage.visibility = View.VISIBLE
                tvPinResultMessage.text = "PIN 4자리를 모두 입력해주세요."
                return@setOnClickListener
            }

            launcher.submitPin(userId, inputPin)
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            pinPopupShowing = false
        }

        dialog.show()
    }

    private fun startAttendanceSession(pageView: View) {
        if (currentSubjectCode.isBlank()) {
            currentSubjectCode = DEFAULT_SUBJECT_CODE
        }
        val now = System.currentTimeMillis()
        val classStartAt = todayMillisFromTime(currentClassStartTime)

        val pinEndAt = now + FIFTEEN_MINUTES

        launcher.startProfessor(currentSubjectCode, userId, classStartAt)

        val delayToAfter15 = (pinEndAt - now).coerceAtLeast(0L)
        phaseTransitionRunnable?.let { handler.removeCallbacks(it) }
        phaseTransitionRunnable = Runnable { transitionToAfter15Phase(pageView) }
        handler.postDelayed(phaseTransitionRunnable!!, delayToAfter15)
    }

    private fun transitionToAfter15Phase(pageView: View) {
        findChildByIdName<View>(pageView, "cardProfessorControlBefore15")?.visibility = View.GONE
        findChildByIdName<View>(pageView, "cardProfessorControlAfter15")?.visibility = View.VISIBLE
        findChildByIdName<View>(pageView, "cardUwbMiddleCheck")?.visibility = View.VISIBLE
        val btnRollCall = findChildByIdName<View>(pageView, "btnRollCallAttendance")
        val btnProfessorAttendanceCheck = findChildByIdName<View>(pageView, "btnProfessorAttendanceCheck")
        btnRollCall?.isEnabled = false
        btnRollCall?.alpha = 0.4f
        btnProfessorAttendanceCheck?.isEnabled = false
        btnProfessorAttendanceCheck?.alpha = 0.4f
    }

    private fun loadProfessorPage(pageView: View) {
        findChildByIdName<View>(pageView, "layoutClassInfoContent")?.visibility = View.GONE
        findChildByIdName<View>(pageView, "tvNoClassInfo")?.visibility = View.VISIBLE
        setText(pageView, "tvDate", todayText())
        setText(pageView, "tvPeriod", "-")

        val calendar = Calendar.getInstance(Locale.KOREA)
        val currentDayInt = when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 5
            Calendar.SATURDAY -> 6
            Calendar.SUNDAY -> 7
            else -> 1
        }
        val nowMinutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        val currentTotalMinutes = currentDayInt * 24 * 60 + nowMinutes

        data class ProfessorClassInstance(
            val subjectCode: String,
            val subjectName: String,
            val dayOfWeekInt: Int,
            val startTime: String,
            val endTime: String,
            val location: String
        )

        FirebaseClient.get("Enrollment/$userId") { enrollmentJson ->
            val subjectCodes = mutableListOf<String>()
            val keys = enrollmentJson?.keys()
            if (keys != null) {
                while (keys.hasNext()) {
                    val code = keys.next()
                    if (enrollmentJson.optBoolean(code, false)) subjectCodes.add(code)
                }
            }

            if (subjectCodes.isEmpty()) {
                runOnUiThread {
                    setText(pageView, "tvNoClassInfo", "담당 등록 과목이 없습니다.")
                    updateProfessorSessionUi(pageView, null)
                    loadProfessorRows(pageView, null)
                }
                return@get
            }

            val allClasses = mutableListOf<ProfessorClassInstance>()
            var fetchCount = 0
            val lock = Any()

            subjectCodes.forEach { subjectCode ->
                FirebaseClient.get("Subjects/$subjectCode") { subjectJson ->
                    synchronized(lock) {
                        fetchCount++
                        if (subjectJson != null) {
                            val subCode = subjectJson.optString("subjectCode", subjectCode)
                            val subName = subjectJson.optString("subjectName", "알 수 없는 과목")
                            val scheduleObj = subjectJson.optJSONObject("schedule")
                            val dayKeys = scheduleObj?.keys()

                            if (dayKeys != null) {
                                while (dayKeys.hasNext()) {
                                    val dayObj = scheduleObj.optJSONObject(dayKeys.next()) ?: continue
                                    val dayInt = scheduleDayToInt(dayObj.optString("dayOfWeek", ""))
                                    if (dayInt <= 0) continue

                                    val periods = dayObj.optJSONArray("periods") ?: continue
                                    var firstStart: String? = null
                                    var lastEnd: String? = null
                                    for (i in 0 until periods.length()) {
                                        val period = periods.optJSONObject(i) ?: continue
                                        val start = period.optString("startTime", "")
                                        val end = period.optString("endTime", "")
                                        if (start.isNotBlank() && end.isNotBlank()) {
                                            if (firstStart == null) firstStart = start
                                            lastEnd = end
                                        }
                                    }

                                    if (firstStart != null && lastEnd != null) {
                                        allClasses.add(
                                            ProfessorClassInstance(
                                                subjectCode = subCode,
                                                subjectName = subName,
                                                dayOfWeekInt = dayInt,
                                                startTime = firstStart,
                                                endTime = lastEnd,
                                                location = dayObj.optString("location", "미정")
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        if (fetchCount == subjectCodes.size) {
                            if (allClasses.isEmpty()) {
                                runOnUiThread {
                                    setText(pageView, "tvNoClassInfo", "등록된 수업 시간이 없습니다.")
                                    updateProfessorSessionUi(pageView, null)
                                    loadProfessorRows(pageView, null)
                                }
                                return@get
                            }

                            val ongoingClass = allClasses.firstOrNull {
                                it.dayOfWeekInt == currentDayInt &&
                                    nowMinutes in scheduleTimeToMinutes(it.startTime)..scheduleTimeToMinutes(it.endTime)
                            }
                            val upcomingClass = allClasses.minByOrNull {
                                var classMinutes = it.dayOfWeekInt * 24 * 60 + scheduleTimeToMinutes(it.startTime)
                                if (classMinutes < currentTotalMinutes) classMinutes += 7 * 24 * 60
                                classMinutes
                            }
                            val targetClass = ongoingClass ?: upcomingClass ?: return@get

                            currentSubjectCode = targetClass.subjectCode
                            currentClassName = cleanSubjectNameForDisplay(targetClass.subjectName)
                            currentClassStartTime = targetClass.startTime
                            currentClassTime = "${targetClass.startTime} ~ ${targetClass.endTime}"

                            val startHour = currentClassStartTime.substringBefore(":").toIntOrNull() ?: 10
                            val periodNumber = when (startHour) {
                                9 -> "1교시"
                                10 -> "2교시"
                                11 -> "3교시"
                                12 -> "4교시"
                                13 -> "5교시"
                                14 -> "6교시"
                                15 -> "7교시"
                                16 -> "8교시"
                                else -> "1교시"
                            }
                            val periodText = if (ongoingClass != null) {
                                "수업 중"
                            } else {
                                "(${scheduleDayText(targetClass.dayOfWeekInt)}) $periodNumber 예정"
                            }

                            runOnUiThread {
                                findChildByIdName<View>(pageView, "layoutClassInfoContent")?.visibility = View.VISIBLE
                                findChildByIdName<View>(pageView, "tvNoClassInfo")?.visibility = View.GONE
                                setText(pageView, "tvDate", dateTextForScheduleDay(targetClass.dayOfWeekInt))
                                setText(pageView, "tvPeriod", periodText)
                                setText(pageView, "tvClassName", currentClassName)
                                setText(pageView, "tvClassTime", "${scheduleDayText(targetClass.dayOfWeekInt)} $currentClassTime")
                                setText(pageView, "tvAfter15ClassName", currentClassName)
                            }

                            val today = apiDateText()
                            FirebaseClient.get("Attendance_Session/$currentSubjectCode/$today") { sessionJson ->
                                runOnUiThread {
                                    updateProfessorSessionUi(pageView, sessionJson)
                                }
                            }

                            FirebaseClient.get("Attendance_Records/$currentSubjectCode") { recordsJson ->
                                runOnUiThread {
                                    loadProfessorRows(pageView, recordsJson)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun updateProfessorSessionUi(pageView: View, sessionJson: JSONObject?) {
        val cardBefore15 = findChildByIdName<View>(pageView, "cardProfessorControlBefore15")
        val cardAfter15 = findChildByIdName<View>(pageView, "cardProfessorControlAfter15")
        val cardUwb = findChildByIdName<View>(pageView, "cardUwbMiddleCheck")
        val btnRollCall = findChildByIdName<View>(pageView, "btnRollCallAttendance")
        val btnProfessorAttendanceCheck = findChildByIdName<View>(pageView, "btnProfessorAttendanceCheck")

        if (sessionJson == null) {
            cardBefore15?.visibility = View.VISIBLE
            cardAfter15?.visibility = View.GONE
            cardUwb?.visibility = View.GONE

            showPin(pageView, "")
            btnRollCall?.isEnabled = true
            btnRollCall?.alpha = 1.0f
            btnRollCall?.setBackgroundResource(R.drawable.bg_attendance_button_blue)
            btnProfessorAttendanceCheck?.isEnabled = true
            btnProfessorAttendanceCheck?.alpha = 1.0f
            btnProfessorAttendanceCheck?.setBackgroundResource(R.drawable.bg_attendance_button_blue)
            return
        }

        val now = System.currentTimeMillis()
        val pinEndAt = sessionJson.optLong("pinEndAt", todayMillisFromTime(currentClassStartTime) + FIFTEEN_MINUTES)
        val status = sessionJson.optString("status", "READY")
        val pinCode = sessionJson.optString("pinCode", "")
        val uwbCheckCount = sessionJson.optInt("uwbCheckCount", 0)
        setText(pageView, "tvUwbCheckCount", "${uwbCheckCount}회")

        if (now >= pinEndAt || status == "UWB_ACTIVE") {
            cardBefore15?.visibility = View.GONE
            cardAfter15?.visibility = View.VISIBLE
            cardUwb?.visibility = View.VISIBLE
            btnRollCall?.isEnabled = false
            btnRollCall?.alpha = 0.4f
            btnRollCall?.setBackgroundResource(R.drawable.bg_attendance_button_gray)
            btnProfessorAttendanceCheck?.isEnabled = false
            btnProfessorAttendanceCheck?.alpha = 0.4f
            btnProfessorAttendanceCheck?.setBackgroundResource(R.drawable.bg_attendance_button_gray)
        } else {
            cardBefore15?.visibility = View.VISIBLE
            cardAfter15?.visibility = View.GONE
            cardUwb?.visibility = View.GONE

            showPin(pageView, pinCode)

            btnRollCall?.isEnabled = false
            btnRollCall?.alpha = 0.4f
            btnRollCall?.setBackgroundResource(R.drawable.bg_attendance_button_gray)

            btnProfessorAttendanceCheck?.isEnabled = false
            btnProfessorAttendanceCheck?.alpha = 0.6f
            btnProfessorAttendanceCheck?.setBackgroundResource(R.drawable.bg_attendance_button_gray)
        }
    }

    private fun loadProfessorRows(pageView: View, recordsJson: JSONObject?) {
        val rows = findChildByIdName<LinearLayout>(pageView, "layoutStudentAttendanceRows")
        rows?.removeAllViews()

        FirebaseClient.get("Users") { usersJson ->
            val keys = usersJson?.keys()
            var total = 0
            var present = 0
            var late = 0
            var absent = 0

            val studentList = mutableListOf<Triple<String, String, String>>()

            if (keys != null) {
                while (keys.hasNext()) {
                    val key = keys.next()
                    val user = FirebaseParsers.user(usersJson.optJSONObject(key), key) ?: continue
                    if (user.userType != "STUDENT") continue

                    val status = findLatestAttendanceStatus(recordsJson, user.userId)

                    total++

                    when (status) {
                        "출석", "출석 완료" -> present++
                        "지각" -> late++
                        "결석", "미출석" -> absent++
                    }

                    studentList.add(Triple(user.userId, user.name, status))
                }
            }

            if (total == 0) total = 1

            val finalTotal = total
            val finalPresent = present
            val finalLate = late
            val finalAbsent = absent

            runOnUiThread {
                studentList.forEach { (studentId, name, status) ->
                    addStudentRow(pageView, studentId, name, status)
                }
                setText(pageView, "tvStudentCount", "총 ${finalTotal}명")
                setText(pageView, "tvAttendanceRate", "${finalPresent * 100 / finalTotal}%")
                setText(pageView, "tvLateRate", "${finalLate * 100 / finalTotal}%")
                setText(pageView, "tvAbsentRate", "${finalAbsent * 100 / finalTotal}%")
            }
        }
    }

    private fun findLatestAttendanceStatus(recordsJson: JSONObject?, targetUserId: String): String {
        if (recordsJson == null) return "미출석"
        val dateKeys = recordsJson.keys()
        var result = "미출석"
        while (dateKeys.hasNext()) {
            val dateKey = dateKeys.next()
            val dateObject = recordsJson.optJSONObject(dateKey) ?: continue
            val userObject = dateObject.optJSONObject(targetUserId) ?: continue
            result = userObject.optString("finalStatus", "미출석")
        }
        return result
    }

    private fun addStudentRow(pageView: View, studentId: String, name: String, status: String) {
        val parent = findChildByIdName<LinearLayout>(pageView, "layoutStudentAttendanceRows") ?: return
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(0), dpToPx(8), dpToPx(0), dpToPx(8))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(38))
        }
        row.addView(makeRowText(studentId, 1.45f))
        row.addView(makeRowText(name, 1.0f))
        row.addView(makeStatusIcon(status == "출석" || status == "출석 완료", R.drawable.attendanceweek, 0.75f))
        row.addView(makeStatusIcon(status == "결석" || status == "미출석", R.drawable.absentweek, 0.75f))
        row.addView(makeStatusIcon(status == "지각", R.drawable.lateweek, 0.75f))
        parent.addView(row)
    }

    private fun makeRowText(value: String, weight: Float): TextView {
        return TextView(this).apply {
            text = value
            textSize = 12f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(Color.parseColor("#222222"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight)
        }
    }

    private fun makeStatusIcon(isVisible: Boolean, drawableResId: Int, weight: Float): FrameLayout {
        return FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight)
            val icon = ImageView(this@MainActivity).apply {
                setImageResource(drawableResId)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                visibility = if (isVisible) View.VISIBLE else View.INVISIBLE
                layoutParams = FrameLayout.LayoutParams(dpToPx(18), dpToPx(18), Gravity.CENTER)
            }
            addView(icon)
        }
    }

    private fun showPin(pageView: View, pinCode: String) {
        val pin = pinCode.padEnd(4, ' ')
        setText(pageView, "tvPinDigit1", pin[0].toString())
        setText(pageView, "tvPinDigit2", pin[1].toString())
        setText(pageView, "tvPinDigit3", pin[2].toString())
        setText(pageView, "tvPinDigit4", pin[3].toString())
    }

    private fun loadSchedule(pageView: View) {
        val etSubjectCodeInput = pageView.findViewById<EditText>(R.id.etSubjectCodeInput)
        val btnAddSubject = pageView.findViewById<TextView>(R.id.btnAddSubject)

        btnAddSubject?.setOnClickListener {
            val inputCode = etSubjectCodeInput?.text.toString().trim()

            if (inputCode.isEmpty()) {
                Toast.makeText(this@MainActivity, "과목코드를 입력해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            addSubjectToSchedule(inputCode, etSubjectCodeInput, pageView)
        }

        val parent = findChildByIdName<FrameLayout>(pageView, "classBlockLayer")
            ?: findChildByIdName(pageView, "timeTableCanvas")
        runOnUiThread { parent?.removeAllViews() }

        FirebaseClient.get("Enrollment/$userId") { enrollmentJson ->
            val subjectCodes = mutableListOf<String>()
            val keys = enrollmentJson?.keys()
            if (keys != null) {
                while (keys.hasNext()) subjectCodes.add(keys.next())
            }

            if (subjectCodes.isEmpty()) {
                runOnUiThread {
                    setText(pageView, "tvCurrentClassName", "수강 신청 내역 없음")
                    findChildByIdName<View>(pageView, "currentClassEmptyCard")?.visibility = View.VISIBLE
                }
                return@get
            }

            val subjects = mutableListOf<com.example.myapplication.model.domain.model.Subject>()
            var fetchCount = 0
            val lock = Any()

            subjectCodes.forEach { subjectCode ->
                FirebaseClient.get("Subjects/$subjectCode") { subjectJson ->
                    synchronized(lock) {
                        fetchCount++
                        if (subjectJson != null) {
                            val subCode = subjectJson.optString("subjectCode", subjectCode)
                            val subName = subjectJson.optString("subjectName", "알 수 없음")
                            val profName = subjectJson.optString("professorName", "미정")
                            val scheduleObj = subjectJson.optJSONObject("schedule")

                            val scheduleMap = mutableMapOf<String, com.example.myapplication.model.domain.model.DaySchedule>()

                            if (scheduleObj != null) {
                                val dayKeys = scheduleObj.keys()
                                while (dayKeys.hasNext()) {
                                    val dayName = dayKeys.next()
                                    val dayObj = scheduleObj.optJSONObject(dayName) ?: continue
                                    val dayOfWeekStr = dayObj.optString("dayOfWeek", dayName)
                                    val locationStr = dayObj.optString("location", "미정")

                                    val periodsList = mutableListOf<com.example.myapplication.model.domain.model.Period>()
                                    val periodsArr = dayObj.optJSONArray("periods")
                                    if (periodsArr != null) {
                                        for (i in 0 until periodsArr.length()) {
                                            val p = periodsArr.optJSONObject(i) ?: continue
                                            val st = p.optString("startTime", "")
                                            val ed = p.optString("endTime", "")
                                            if (st.isNotEmpty() && ed.isNotEmpty()) {
                                                periodsList.add(com.example.myapplication.model.domain.model.Period(st, ed))
                                            }
                                        }
                                    }
                                    if (periodsList.isNotEmpty()) {
                                        scheduleMap[dayOfWeekStr] = com.example.myapplication.model.domain.model.DaySchedule(
                                            dayOfWeek = dayOfWeekStr, location = locationStr, periods = periodsList
                                        )
                                    }
                                }
                            }
                            subjects.add(
                                com.example.myapplication.model.domain.model.Subject(
                                    subjectCode = subCode, subjectName = subName, professorName = profName, schedule = scheduleMap
                                )
                            )
                        }
                        if (fetchCount == subjectCodes.size) {
                            runOnUiThread { renderScheduleSubjects(pageView, parent, subjects) }
                        }
                    }
                }
            }
        }
    }

    private fun loadStudentScheduleFromRest(pageView: View) {
        val etSubjectCodeInput = pageView.findViewById<EditText>(R.id.etSubjectCodeInput)
        val btnAddSubject = pageView.findViewById<TextView>(R.id.btnAddSubject)

        btnAddSubject?.setOnClickListener {
            val inputCode = etSubjectCodeInput?.text.toString().trim()

            if (inputCode.isEmpty()) {
                Toast.makeText(this@MainActivity, "과목코드를 입력해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val database: DatabaseReference = FirebaseDatabase.getInstance().reference

            database.child("Subjects").child(inputCode).get().addOnSuccessListener { snapshot ->
                if (snapshot.exists()) {
                    database.child("Enrollment").child(userId).child(inputCode).setValue(true)
                        .addOnSuccessListener {
                            Toast.makeText(this@MainActivity, "과목($inputCode)이 추가되었습니다!", Toast.LENGTH_SHORT).show()
                            etSubjectCodeInput?.text?.clear()
                            loadStudentScheduleFromRest(pageView)
                        }
                        .addOnFailureListener {
                            Toast.makeText(this@MainActivity, "과목 추가 실패. 다시 시도해주세요.", Toast.LENGTH_SHORT).show()
                        }
                } else {
                    Toast.makeText(this@MainActivity, "존재하지 않는 과목코드입니다.", Toast.LENGTH_SHORT).show()
                }
            }.addOnFailureListener {
                Toast.makeText(this@MainActivity, "과목 확인 중 오류가 발생했습니다.", Toast.LENGTH_SHORT).show()
            }
        }

        lifecycleScope.launch {
                val parent = findChildByIdName<FrameLayout>(pageView, "classBlockLayer")
                    ?: findChildByIdName(pageView, "timeTableCanvas")

            runOnUiThread {
                parent?.removeAllViews()
            }

            try {
                val subjects = repository.getEnrolledSubjects(userId)

                runOnUiThread {
                    renderScheduleSubjects(pageView, parent, subjects)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun loadProfessorScheduleFromRest(pageView: View) {
        lifecycleScope.launch {
            val parent = findChildByIdName<FrameLayout>(pageView, "classBlockLayer")
                ?: findChildByIdName(pageView, "timeTableCanvas")
            parent?.removeAllViews()

            try {
                val subjects = repository.getSubjects()
                renderScheduleSubjects(pageView, parent, subjects)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun renderScheduleSubjects(pageView: View, parent: FrameLayout?, subjects: List<Subject>) {
        if (parent == null) return
        parent.post {
            parent.removeAllViews()
            parent.setBackgroundColor(Color.WHITE)
            if (subjects.isEmpty()) {
                setText(pageView, "tvCurrentClassName", "등록된 시간표 없음")
                findChildByIdName<View>(pageView, "cardCurrentSubject")?.visibility = View.GONE
                findChildByIdName<View>(pageView, "currentClassEmptyCard")?.visibility = View.VISIBLE
                return@post
            }

            val startHour = 9
            val endHour = getScheduleMaxEndHour(subjects).coerceAtLeast(15)
            val leftWidth = dpToPx(20)
            val headerHeight = dpToPx(20)
            val rowHeight = dpToPx(34)
            val targetHeight = headerHeight + (endHour - startHour) * rowHeight + dpToPx(2)
            parent.layoutParams = parent.layoutParams.apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = targetHeight
            }

            drawScheduleGrid(parent, startHour, endHour, leftWidth, headerHeight, rowHeight)

            subjects.forEachIndexed { index, subject ->
                addSubjectBlock(parent, subject, index, startHour, leftWidth, headerHeight, rowHeight)
            }

            renderUpcomingScheduleCard(pageView, subjects)
        }
    }

    private fun addSubjectToSchedule(
        code: String,
        etCode: EditText?,
        pageView: View
    ) {
        firebaseDb.child("Subjects").child(code)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snap: DataSnapshot) {
                    if (!snap.exists()) {
                        Toast.makeText(this@MainActivity, "등록되지 않은 과목코드입니다.", Toast.LENGTH_SHORT).show()
                        return
                    }

                    val subject = snap.getValue(Subject::class.java)
                    if (subject == null) {
                        Toast.makeText(this@MainActivity, "과목 정보를 읽을 수 없습니다.", Toast.LENGTH_SHORT).show()
                        return
                    }

                    val finalCode = subject.subjectCode.ifBlank { code }
                    firebaseDb.child("Enrollment").child(userId).child(finalCode)
                        .addListenerForSingleValueEvent(object : ValueEventListener {
                            override fun onDataChange(enrollmentSnap: DataSnapshot) {
                                if (enrollmentSnap.getValue(Boolean::class.java) == true) {
                                    Toast.makeText(this@MainActivity, "이미 추가된 과목입니다.", Toast.LENGTH_SHORT).show()
                                    return
                                }

                                firebaseDb.child("Enrollment")
                                    .child(userId)
                                    .child(finalCode)
                                    .setValue(true)
                                    .addOnSuccessListener {
                                        etCode?.text?.clear()
                                        Toast.makeText(
                                            this@MainActivity,
                                            "${cleanSubjectNameForDisplay(subject.subjectName)} 추가됨",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        loadSchedule(pageView)
                                    }
                                    .addOnFailureListener {
                                        Toast.makeText(this@MainActivity, "수업 추가에 실패했습니다.", Toast.LENGTH_SHORT).show()
                                    }
                            }

                            override fun onCancelled(error: DatabaseError) {
                                Toast.makeText(this@MainActivity, "오류가 발생했습니다.", Toast.LENGTH_SHORT).show()
                            }
                        })
                }

                override fun onCancelled(error: DatabaseError) {
                    Toast.makeText(this@MainActivity, "오류가 발생했습니다.", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun renderUpcomingScheduleCard(pageView: View, subjects: List<Subject>) {
        val upcoming = findCurrentOrNextSchedule(subjects)
        val subjectCard = findChildByIdName<View>(pageView, "cardCurrentSubject")
        val emptyCard = findChildByIdName<View>(pageView, "currentClassEmptyCard")

        if (upcoming == null) {
            subjectCard?.visibility = View.GONE
            emptyCard?.visibility = View.VISIBLE
            return
        }

        emptyCard?.visibility = View.GONE
        subjectCard?.visibility = View.VISIBLE
        setText(pageView, "tvCurrentSubjectName", cleanSubjectNameForDisplay(upcoming.subject.subjectName))
        setText(pageView, "tvCurrentProfessor", upcoming.subject.professorName)
        setText(pageView, "tvCurrentTime", "${scheduleDayText(upcoming.dayOfWeekInt)} ${upcoming.startTime} ~ ${upcoming.endTime}")
        setText(pageView, "tvCurrentRoom", upcoming.day.location.ifBlank { "\uBBF8\uC815" })
        setText(pageView, "tvCurrentSubjectCode", upcoming.subject.subjectCode)
    }

    private fun findCurrentOrNextSchedule(subjects: List<Subject>): ScheduleCardTarget? {
        val calendar = Calendar.getInstance(Locale.KOREA)
        val currentDayInt = when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 5
            Calendar.SATURDAY -> 6
            Calendar.SUNDAY -> 7
            else -> 1
        }
        val nowMinutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        val currentWeekMinutes = currentDayInt * 24 * 60 + nowMinutes

        val targets = subjects.flatMap { subject ->
            subject.schedule.values.mapNotNull { day ->
                val periods = day.periods
                    .filterNotNull()
                    .filter { it.startTime.isNotBlank() && it.endTime.isNotBlank() }
                    .sortedBy { scheduleTimeToMinutes(it.startTime) }

                if (periods.isEmpty()) {
                    null
                } else {
                    ScheduleCardTarget(
                        subject = subject,
                        day = day,
                        dayOfWeekInt = scheduleDayToInt(day.dayOfWeek),
                        startTime = periods.first().startTime,
                        endTime = periods.last().endTime
                    )
                }
            }
        }.filter { it.dayOfWeekInt > 0 }

        val ongoing = targets.firstOrNull {
            it.dayOfWeekInt == currentDayInt &&
                nowMinutes in scheduleTimeToMinutes(it.startTime)..scheduleTimeToMinutes(it.endTime)
        }
        if (ongoing != null) return ongoing

        return targets.minByOrNull {
            var classMinutes = it.dayOfWeekInt * 24 * 60 + scheduleTimeToMinutes(it.startTime)
            if (classMinutes < currentWeekMinutes) classMinutes += 7 * 24 * 60
            classMinutes
        }
    }

    private fun scheduleDayToInt(dayOfWeek: String): Int {
        return when (dayOfWeek.uppercase()) {
            "MONDAY", "\uC6D4" -> 1
            "TUESDAY", "\uD654" -> 2
            "WEDNESDAY", "\uC218" -> 3
            "THURSDAY", "\uBAA9" -> 4
            "FRIDAY", "\uAE08" -> 5
            "SATURDAY", "\uD1A0" -> 6
            "SUNDAY", "\uC77C" -> 7
            else -> -1
        }
    }

    private fun scheduleDayText(dayOfWeekInt: Int): String {
        return when (dayOfWeekInt) {
            1 -> "\uC6D4"
            2 -> "\uD654"
            3 -> "\uC218"
            4 -> "\uBAA9"
            5 -> "\uAE08"
            6 -> "\uD1A0"
            7 -> "\uC77C"
            else -> ""
        }
    }

    private fun scheduleTimeToMinutes(time: String): Int {
        val parts = time.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return hour * 60 + minute
    }

    private fun cleanSubjectNameForDisplay(subjectName: String): String {
        return subjectName
            .replace(" (\uC601\uC5B4\uAC15\uC758)", "")
            .replace(" (\uC2E4\uC2DC\uAC04\uD654\uC0C1\uAC15\uC758)", "")
            .replace("(\uC601\uC5B4\uAC15\uC758)", "")
            .replace("(\uC2E4\uC2DC\uAC04\uD654\uC0C1\uAC15\uC758)", "")
            .trim()
    }

    private data class ScheduleCardTarget(
        val subject: Subject,
        val day: com.example.myapplication.model.domain.model.DaySchedule,
        val dayOfWeekInt: Int,
        val startTime: String,
        val endTime: String
    )

    private fun loadMyPage(pageView: View) {
        FirebaseClient.get("Users/$userId") { userJson ->
            val user = FirebaseParsers.user(userJson, userId)
            if (userRole == "professor") {
                setText(pageView, "tvProfessorName", user?.name ?: userName)
                setText(pageView, "tvProfessorMajor", "소프트웨어학과")
            } else {
                setText(pageView, "tvStudentName", user?.name ?: userName)
                setText(pageView, "tvStudentMajor", "소프트웨어학과")
                setText(pageView, "tvStudentInfo", user?.userId ?: userId)
            }
        }
    }

    private fun loadWeekPage(pageView: View) {
        weekCalendar = Calendar.getInstance(Locale.KOREA)
        weekSelectedDateStr = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(weekCalendar.time)
        setupWeekCalendar(pageView)
        loadAttendanceForDate(pageView, weekSelectedDateStr)
    }

    private fun setupWeekCalendar(pageView: View) {
        renderWeekDates(pageView)

        pageView.findViewById<View?>(R.id.btnPrevWeek)?.setOnClickListener {
            weekCalendar.add(Calendar.WEEK_OF_YEAR, -1)
            renderWeekDates(pageView)
            loadAttendanceForDate(pageView, weekSelectedDateStr)
        }

        pageView.findViewById<View?>(R.id.btnNextWeek)?.setOnClickListener {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date())
            val nextWeek = Calendar.getInstance(Locale.KOREA).apply {
                time = weekCalendar.time
                add(Calendar.WEEK_OF_YEAR, 1)
                set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
            }
            if (SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(nextWeek.time) > today) {
                return@setOnClickListener
            }
            weekCalendar.add(Calendar.WEEK_OF_YEAR, 1)
            renderWeekDates(pageView)
            loadAttendanceForDate(pageView, weekSelectedDateStr)
        }
    }

    private fun renderWeekDates(pageView: View) {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date())
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)
        val dayFmt = SimpleDateFormat("d", Locale.KOREA)
        val monthFmt = SimpleDateFormat("yyyy년 M월", Locale.KOREA)

        val cal = Calendar.getInstance(Locale.KOREA).apply {
            time = weekCalendar.time
            set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
        }
        val mondayCal = cal.clone() as Calendar
        mondayCal.add(Calendar.DAY_OF_WEEK, 1)
        pageView.findViewById<TextView?>(R.id.tvMonth)?.text = monthFmt.format(mondayCal.time)

        val dayIds = listOf(
            R.id.dateSun to R.id.tvSunDate,
            R.id.dateMon to R.id.tvMonDate,
            R.id.dateTue to R.id.tvTueDate,
            R.id.dateWed to R.id.tvWedDate,
            R.id.dateThu to R.id.tvThuDate,
            R.id.dateFri to R.id.tvFriDate,
            R.id.dateSat to R.id.tvSatDate
        )

        for ((containerId, tvId) in dayIds) {
            val dateStr = fmt.format(cal.time)
            val isFuture = dateStr > today
            val isToday = dateStr == today
            val isSelected = dateStr == weekSelectedDateStr
            val container = pageView.findViewById<LinearLayout?>(containerId)
            val tv = pageView.findViewById<TextView?>(tvId)

            tv?.text = dayFmt.format(cal.time)
            when {
                isSelected || isToday -> {
                    tv?.background = resources.getDrawable(R.drawable.bg_date_selected_blue, null)
                    tv?.setTextColor(Color.parseColor("#004B83"))
                }
                isFuture -> {
                    tv?.background = null
                    tv?.setTextColor(Color.parseColor("#BBBBBB"))
                }
                else -> {
                    tv?.background = null
                    tv?.setTextColor(Color.parseColor("#111111"))
                }
            }

            if (!isFuture) {
                val clickDate = dateStr
                container?.isClickable = true
                container?.setOnClickListener {
                    weekSelectedDateStr = clickDate
                    renderWeekDates(pageView)
                    loadAttendanceForDate(pageView, clickDate)
                }
            } else {
                container?.setOnClickListener(null)
                container?.isClickable = false
            }
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
    }

    private fun loadAttendanceForDate(pageView: View, dateStr: String) {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date())
        val cal = Calendar.getInstance(Locale.KOREA).apply {
            time = fmt.parse(dateStr) ?: Date()
        }
        val isToday = dateStr == today
        val nowMinute = Calendar.getInstance(Locale.KOREA).let {
            it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
        }

        val dayOfWeekEng = when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SUNDAY -> "Sunday"
            Calendar.MONDAY -> "Monday"
            Calendar.TUESDAY -> "Tuesday"
            Calendar.WEDNESDAY -> "Wednesday"
            Calendar.THURSDAY -> "Thursday"
            Calendar.FRIDAY -> "Friday"
            Calendar.SATURDAY -> "Saturday"
            else -> ""
        }
        val dayOfWeekKr = when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SUNDAY -> "일"
            Calendar.MONDAY -> "월"
            Calendar.TUESDAY -> "화"
            Calendar.WEDNESDAY -> "수"
            Calendar.THURSDAY -> "목"
            Calendar.FRIDAY -> "금"
            Calendar.SATURDAY -> "토"
            else -> ""
        }

        pageView.findViewById<TextView?>(R.id.tvSelectedDate)?.text =
            "${SimpleDateFormat("M월 d일", Locale.KOREA).format(cal.time)} ($dayOfWeekKr)"

        firebaseDb.child("Enrollment").child(userId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(enrollSnap: DataSnapshot) {
                    val enrolledCodes = enrollSnap.children
                        .filter { it.getValue(Boolean::class.java) == true }
                        .mapNotNull { it.key }

                    if (enrolledCodes.isEmpty()) {
                        showWeekMessage(pageView, "수강 중인 과목이 없습니다")
                        return
                    }

                    val subjectCache = mutableMapOf<String, DataSnapshot>()
                    var subjectDone = 0

                    enrolledCodes.forEach { code ->
                        firebaseDb.child("Subjects").child(code)
                            .addListenerForSingleValueEvent(object : ValueEventListener {
                                override fun onDataChange(subjectSnap: DataSnapshot) {
                                    subjectCache[code] = subjectSnap
                                    subjectDone++
                                    if (subjectDone < enrolledCodes.size) return

                                    data class ClassEntry(
                                        val code: String,
                                        val name: String,
                                        val timeRange: String,
                                        val startTime: String,
                                        val endMinute: Int
                                    )

                                    val classesOnDay = mutableListOf<ClassEntry>()
                                    enrolledCodes.forEach { subjectCode ->
                                        val snap = subjectCache[subjectCode] ?: return@forEach
                                        val rawName = snap.child("subjectName").getValue(String::class.java).orEmpty()
                                        val name = cleanSubjectNameForDisplay(rawName)
                                        snap.child("schedule").children.forEach { daySnap ->
                                            val dow = daySnap.child("dayOfWeek").getValue(String::class.java).orEmpty()
                                            if (!dow.equals(dayOfWeekEng, ignoreCase = true)) return@forEach

                                            var startTime = ""
                                            var endTime = ""
                                            daySnap.child("periods").children.forEach { periodSnap ->
                                                val st = periodSnap.child("startTime").getValue(String::class.java).orEmpty()
                                                val et = periodSnap.child("endTime").getValue(String::class.java).orEmpty()
                                                if (st.isNotBlank() && startTime.isBlank()) startTime = st
                                                if (et.isNotBlank()) endTime = et
                                            }
                                            if (startTime.isBlank() || endTime.isBlank()) return@forEach

                                            val endMinute = scheduleTimeToMinutes(endTime)
                                            if (isToday && nowMinute <= endMinute) return@forEach
                                            classesOnDay.add(
                                                ClassEntry(
                                                    subjectCode,
                                                    name.ifBlank { subjectCode },
                                                    "$startTime - $endTime",
                                                    startTime,
                                                    endMinute
                                                )
                                            )
                                        }
                                    }

                                    classesOnDay.sortBy { it.startTime }
                                    if (classesOnDay.isEmpty()) {
                                        showWeekMessage(pageView, "해당 날짜에 수업이 없습니다")
                                        return
                                    }

                                    val statusCache = mutableMapOf<String, String>()
                                    val uwbCache = mutableMapOf<String, List<Pair<String, Boolean>>>()
                                    var recordDone = 0
                                    val recordTotal = classesOnDay.size * 2

                                    fun tryRenderAll() {
                                        if (recordDone < recordTotal) return
                                        val hasAbsent = statusCache.values.any { it == "결석" }
                                        updateDateCellBorder(pageView, dateStr, hasAbsent)
                                        renderWeekListWithUwb(
                                            pageView,
                                            classesOnDay.map {
                                                Pair(
                                                    Triple(it.name, it.timeRange, statusCache[it.code] ?: "결석"),
                                                    uwbCache[it.code] ?: emptyList()
                                                )
                                            }
                                        )
                                    }

                                    classesOnDay.forEach { entry ->
                                        firebaseDb.child("Attendance_Records").child(entry.code).child(dateStr).child(userId)
                                            .addListenerForSingleValueEvent(object : ValueEventListener {
                                                override fun onDataChange(snap: DataSnapshot) {
                                                    val raw = snap.child("finalStatus").getValue(String::class.java).orEmpty()
                                                    statusCache[entry.code] = normalizeAttendanceStatus(raw).ifBlank { "결석" }
                                                    recordDone++
                                                    tryRenderAll()
                                                }

                                                override fun onCancelled(error: DatabaseError) {
                                                    statusCache[entry.code] = "결석"
                                                    recordDone++
                                                    tryRenderAll()
                                                }
                                            })

                                        firebaseDb.child("UWB_Logs").child(entry.code).child(dateStr).child(userId)
                                            .addListenerForSingleValueEvent(object : ValueEventListener {
                                                override fun onDataChange(snap: DataSnapshot) {
                                                    uwbCache[entry.code] = snap.children.mapNotNull { logSnap ->
                                                        val time = logSnap.child("timestamp").getValue(String::class.java).orEmpty()
                                                        val detected = logSnap.child("isDetected").getValue(Boolean::class.java) ?: false
                                                        if (time.isBlank()) null else Pair(time, detected)
                                                    }.sortedBy { it.first }
                                                    recordDone++
                                                    tryRenderAll()
                                                }

                                                override fun onCancelled(error: DatabaseError) {
                                                    uwbCache[entry.code] = emptyList()
                                                    recordDone++
                                                    tryRenderAll()
                                                }
                                            })
                                    }
                                }

                                override fun onCancelled(error: DatabaseError) {
                                    subjectDone++
                                    if (subjectDone == enrolledCodes.size) {
                                        showWeekMessage(pageView, "데이터를 불러오지 못했습니다")
                                    }
                                }
                            })
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    showWeekMessage(pageView, "데이터를 불러오지 못했습니다")
                }
            })
    }

    private fun updateDateCellBorder(pageView: View, dateStr: String, hasAbsent: Boolean) {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date())
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)
        val cal = Calendar.getInstance(Locale.KOREA).apply {
            time = weekCalendar.time
            set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
        }
        val tvIds = listOf(
            R.id.tvSunDate,
            R.id.tvMonDate,
            R.id.tvTueDate,
            R.id.tvWedDate,
            R.id.tvThuDate,
            R.id.tvFriDate,
            R.id.tvSatDate
        )

        for (tvId in tvIds) {
            val currentDate = fmt.format(cal.time)
            if (currentDate == dateStr) {
                val tv = pageView.findViewById<TextView?>(tvId)
                val isToday = currentDate == today
                tv?.background = resources.getDrawable(
                    if (hasAbsent) R.drawable.bg_date_warning_red else R.drawable.bg_date_selected_blue,
                    null
                )
                tv?.setTextColor(if (hasAbsent && !isToday) Color.WHITE else Color.parseColor("#004B83"))
                break
            }
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
    }

    private fun renderWeekListWithUwb(
        pageView: View,
        items: List<Pair<Triple<String, String, String>, List<Pair<String, Boolean>>>>
    ) {
        val container = pageView.findViewById<LinearLayout?>(R.id.listContainer) ?: return
        while (container.childCount > 1) container.removeViewAt(1)

        items.forEach { (info, uwbLogs) ->
            val (name, timeRange, status) = info
            val color = when (status) {
                "출석" -> Color.parseColor("#0281F6")
                "지각" -> Color.parseColor("#9C27B0")
                else -> Color.parseColor("#E53935")
            }
            val iconRes = when (status) {
                "출석" -> R.drawable.attendanceweek
                "지각" -> R.drawable.lateweek
                else -> R.drawable.absentweek
            }

            val item = LayoutInflater.from(this@MainActivity)
                .inflate(R.layout.item_attendance, container, false)

            item.findViewById<View?>(R.id.viewSideBar)?.setBackgroundColor(color)
            item.findViewById<TextView?>(R.id.tvSubjectName)?.text = name
            item.findViewById<TextView?>(R.id.tvTimeRange)?.text = timeRange
            item.findViewById<TextView?>(R.id.tvStatus)?.apply {
                text = status
                setTextColor(color)
            }
            item.findViewById<ImageView?>(R.id.ivStatusIcon)?.apply {
                setImageResource(iconRes)
                visibility = View.VISIBLE
            }

            val detailArea = item.findViewById<LinearLayout?>(R.id.detailArea)
            val detailRows = item.findViewById<LinearLayout?>(R.id.detailRowsContainer)
            val rowMain = item.findViewById<LinearLayout?>(R.id.rowMain)
            val btnCollapse = item.findViewById<TextView?>(R.id.btnCollapse)

            detailRows?.removeAllViews()
            if (detailRows != null) {
                val rows = if (uwbLogs.isEmpty()) listOf("UWB 실행이 되지 않았습니다" to "") else {
                    uwbLogs.map { (time, detected) -> time to if (detected) "출석" else "미출석" }
                }
                rows.forEach { (time, detectedText) ->
                    val row = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, 3, 0, 3)
                    }
                    row.addView(TextView(this@MainActivity).apply {
                        text = time
                        textSize = 12f
                        setTextColor(Color.parseColor("#444444"))
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    row.addView(TextView(this@MainActivity).apply {
                        text = detectedText
                        textSize = 12f
                        setTextColor(Color.parseColor("#888888"))
                    })
                    detailRows.addView(row)
                }
            }

            rowMain?.setOnClickListener {
                detailArea ?: return@setOnClickListener
                detailArea.visibility = if (detailArea.visibility == View.GONE) View.VISIBLE else View.GONE
            }
            btnCollapse?.setOnClickListener { rowMain?.performClick() }
            container.addView(item)
        }
    }

    private fun showWeekMessage(pageView: View, msg: String) {
        val container = pageView.findViewById<LinearLayout?>(R.id.listContainer) ?: return
        while (container.childCount > 1) container.removeViewAt(1)
        container.addView(TextView(this).apply {
            text = msg
            textSize = 14f
            setTextColor(Color.parseColor("#888888"))
            setPadding(8, 24, 8, 8)
        })
    }

    private fun normalizeAttendanceStatus(status: String): String {
        return when (status.trim().uppercase()) {
            "출석", "출석 완료", "PRESENT", "ATTENDANCE", "ATTENDED" -> "출석"
            "지각", "LATE" -> "지각"
            "결석", "ABSENT", "ABSENCE" -> "결석"
            else -> ""
        }
    }

    private fun loadAttendanceSummary(pageView: View) {
        clearAttendanceSummaryScreen(pageView)

        FirebaseClient.get("Enrollment/$userId") { enrollmentJson ->
            FirebaseClient.get("Subjects") { subjectsJson ->
                FirebaseClient.get("Attendance_Records") { recordsRoot ->
                    val enrolledSubjectCodes = getEnrolledSubjectCodes(enrollmentJson)
                    val subjectItems = mutableListOf<SubjectAttendanceItem>()

                    enrolledSubjectCodes.forEach { subjectCode ->
                        val subjectJson = subjectsJson?.optJSONObject(subjectCode)
                        val subjectName = cleanSubjectNameForDisplay(
                            subjectJson?.optString("subjectName", "").orEmpty()
                        )

                        if (subjectName.isNotBlank()) {
                            subjectItems.add(
                                SubjectAttendanceItem(
                                    subjectCode = subjectCode,
                                    subjectName = subjectName,
                                    stat = getAttendanceStatBySubject(recordsRoot, subjectCode, userId)
                                )
                            )
                        }
                    }

                    runOnUiThread {
                        renderAttendanceSubjectGrid(pageView, subjectItems)
                        renderAttendanceTotalSummary(pageView, subjectItems)
                    }
                }
            }
        }
    }

    private fun clearAttendanceSummaryScreen(pageView: View) {
        pageView.findViewById<GridLayout?>(R.id.gridAttendanceRate)?.removeAllViews()
        pageView.findViewById<TextView?>(R.id.tvSelectedClassName)?.text = ""
        pageView.findViewById<TextView?>(R.id.tvLectureProgress)?.text = ""
        pageView.findViewById<TextView?>(R.id.tvTotalAttendanceRate)?.text = ""
        pageView.findViewById<TextView?>(R.id.tvTotalLateRate)?.text = ""
        pageView.findViewById<TextView?>(R.id.tvTotalAbsentRate)?.text = ""
    }

    private fun getEnrolledSubjectCodes(enrollmentJson: JSONObject?): List<String> {
        if (enrollmentJson == null) return emptyList()

        val result = mutableListOf<String>()
        val keys = enrollmentJson.keys()
        while (keys.hasNext()) {
            val code = keys.next()
            if (enrollmentJson.optBoolean(code, false)) {
                result.add(code)
            }
        }
        return result.sorted()
    }

    private fun getAttendanceStatBySubject(
        recordsRoot: JSONObject?,
        subjectCode: String,
        targetUserId: String
    ): AttendanceStat {
        val subjectRecordJson = recordsRoot?.optJSONObject(subjectCode) ?: return AttendanceStat()

        var present = 0
        var late = 0
        var absent = 0
        val dateKeys = subjectRecordJson.keys()
        while (dateKeys.hasNext()) {
            val userRecord = subjectRecordJson
                .optJSONObject(dateKeys.next())
                ?.optJSONObject(targetUserId)
                ?: continue

            when (normalizeAttendanceStatus(userRecord.optString("finalStatus", ""))) {
                "출석" -> present++
                "지각" -> late++
                "결석" -> absent++
            }
        }

        return AttendanceStat(present, late, absent)
    }

    private fun renderAttendanceSubjectGrid(
        pageView: View,
        subjectItems: List<SubjectAttendanceItem>
    ) {
        val grid = pageView.findViewById<GridLayout?>(R.id.gridAttendanceRate) ?: return
        grid.removeAllViews()

        subjectItems.forEach { item ->
            val itemView = LayoutInflater.from(this)
                .inflate(R.layout.all_attendance_rate, grid, false)

            itemView.findViewById<TextView>(R.id.tvClassName).text = item.subjectName
            val tvRate = itemView.findViewById<TextView>(R.id.tvAttendanceRate)
            val donut = itemView.findViewById<DonutChartView>(R.id.donutChart)

            if (item.stat.total > 0) {
                tvRate.text = "${item.stat.presentRate}%"
                tvRate.setTextColor(Color.parseColor("#004B83"))
                donut.setData(item.stat.present, item.stat.late, item.stat.absent)
            } else {
                tvRate.text = ""
                donut.clearData()
            }

            itemView.layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                height = dpToPx(170)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(0, 0, 0, 0)
            }
            grid.addView(itemView)
        }
    }

    private fun renderAttendanceTotalSummary(
        pageView: View,
        subjectItems: List<SubjectAttendanceItem>
    ) {
        val totalPresent = subjectItems.sumOf { it.stat.present }
        val totalLate = subjectItems.sumOf { it.stat.late }
        val totalAbsent = subjectItems.sumOf { it.stat.absent }
        val total = totalPresent + totalLate + totalAbsent

        val firstSubject = subjectItems.firstOrNull { it.stat.total > 0 } ?: subjectItems.firstOrNull()
        pageView.findViewById<TextView?>(R.id.tvSelectedClassName)?.text = firstSubject?.subjectName.orEmpty()
        pageView.findViewById<TextView?>(R.id.tvLectureProgress)?.text = ""

        val tvTotalRate = pageView.findViewById<TextView?>(R.id.tvTotalAttendanceRate)
        val tvTotalLate = pageView.findViewById<TextView?>(R.id.tvTotalLateRate)
        val tvTotalAbsent = pageView.findViewById<TextView?>(R.id.tvTotalAbsentRate)

        if (total <= 0) {
            tvTotalRate?.text = ""
            tvTotalLate?.text = ""
            tvTotalAbsent?.text = ""
            return
        }

        tvTotalRate?.text = "${calculateRate(totalPresent, total)}%"
        tvTotalRate?.setTextColor(Color.parseColor("#0281F6"))
        tvTotalLate?.text = "${calculateRate(totalLate, total)}%"
        tvTotalLate?.setTextColor(Color.parseColor("#9C27B0"))
        tvTotalAbsent?.text = "${calculateRate(totalAbsent, total)}%"
        tvTotalAbsent?.setTextColor(Color.parseColor("#E53935"))
    }

    private fun calculateRate(value: Int, total: Int): Int {
        if (total <= 0) return 0
        return ((value.toFloat() / total.toFloat()) * 100f).roundToInt()
    }

    private data class SubjectAttendanceItem(
        val subjectCode: String,
        val subjectName: String,
        val stat: AttendanceStat
    )

    private data class AttendanceStat(
        val present: Int = 0,
        val late: Int = 0,
        val absent: Int = 0
    ) {
        val total: Int
            get() = present + late + absent

        val presentRate: Int
            get() = if (total <= 0) 0 else ((present.toFloat() / total) * 100f).roundToInt()
    }

    private fun drawScheduleGrid(
        parent: FrameLayout,
        startHour: Int,
        endHour: Int,
        leftWidth: Int,
        headerHeight: Int,
        rowHeight: Int
    ) {
        val totalWidth = parent.width
        if (totalWidth <= 0) return

        val gridWidth = totalWidth - leftWidth
        val colWidth = gridWidth / 5
        val days = listOf("월", "화", "수", "목", "금")

        days.forEachIndexed { index, day ->
            val cellLeft = leftWidth + index * colWidth
            val cellWidth = if (index == days.lastIndex) {
                totalWidth - cellLeft
            } else {
                colWidth
            }
            val dayView = TextView(this).apply {
                text = day
                textSize = 8f
                setTextColor(Color.parseColor("#999999"))
                gravity = Gravity.CENTER
                includeFontPadding = false
            }
            parent.addView(
                dayView,
                FrameLayout.LayoutParams(cellWidth, headerHeight).apply {
                    leftMargin = cellLeft
                    topMargin = 0
                }
            )
        }

        for (hour in startHour until endHour) {
            val y = headerHeight + (hour - startHour) * rowHeight
            val hourView = TextView(this).apply {
                text = if (hour <= 12) hour.toString() else (hour - 12).toString()
                textSize = 8f
                setTextColor(Color.parseColor("#B8B8B8"))
                gravity = Gravity.TOP or Gravity.RIGHT
                includeFontPadding = false
                setPadding(0, dpToPx(2), dpToPx(5), 0)
            }
            parent.addView(
                hourView,
                FrameLayout.LayoutParams(leftWidth, rowHeight).apply {
                    leftMargin = 0
                    topMargin = y
                }
            )

            parent.addView(
                View(this).apply { setBackgroundColor(Color.parseColor("#EEEEEE")) },
                FrameLayout.LayoutParams(gridWidth, dpToPx(1)).apply {
                    leftMargin = leftWidth
                    topMargin = y
                }
            )
        }

        parent.addView(
            View(this).apply { setBackgroundColor(Color.parseColor("#EEEEEE")) },
            FrameLayout.LayoutParams(gridWidth, dpToPx(1)).apply {
                leftMargin = leftWidth
                topMargin = headerHeight + (endHour - startHour) * rowHeight
            }
        )

        for (i in 0..5) {
            val x = if (i == 5) totalWidth - dpToPx(1) else leftWidth + i * colWidth
            parent.addView(
                View(this).apply { setBackgroundColor(Color.parseColor("#EEEEEE")) },
                FrameLayout.LayoutParams(dpToPx(1), (endHour - startHour) * rowHeight).apply {
                    leftMargin = x
                    topMargin = headerHeight
                }
            )
        }
    }

    private fun addSubjectBlock(
        parent: FrameLayout,
        subject: Subject,
        index: Int,
        startHour: Int,
        leftWidth: Int,
        headerHeight: Int,
        rowHeight: Int
    ) {
        val colors = listOf("#8FA2C7", "#B9AAA5", "#79B2B8", "#A7B58D", "#C39DA4")
        val color = colors[index % colors.size]
        val cleanName = cleanSubjectNameForDisplay(subject.subjectName)

        val usableWidth = parent.width - leftWidth
        val columnWidth = if (usableWidth > 0) usableWidth / 5 else dpToPx(46)

        subject.schedule.values.forEach { daySchedule ->
            val location = daySchedule.location.ifEmpty { "미정" }
            val dayIndex = scheduleDayToInt(daySchedule.dayOfWeek) - 1
            if (dayIndex !in 0..4) return@forEach

            val periods = daySchedule.periods
                .filterNotNull()
                .filter { it.startTime.isNotBlank() && it.endTime.isNotBlank() }
                .sortedBy { scheduleTimeToMinutes(it.startTime) }

            data class TimeBlock(val startMinute: Int, val endMinute: Int)

            val merged = mutableListOf<TimeBlock>()
            var currentStart = scheduleTimeToMinutes(periods.first().startTime)
            var currentEnd = scheduleTimeToMinutes(periods.first().endTime)

            for (i in 1 until periods.size) {
                val nextStart = scheduleTimeToMinutes(periods[i].startTime)
                val nextEnd = scheduleTimeToMinutes(periods[i].endTime)
                if (nextStart - currentEnd <= 10) {
                    currentEnd = nextEnd
                } else {
                    merged.add(TimeBlock(currentStart, currentEnd))
                    currentStart = nextStart
                    currentEnd = nextEnd
                }
            }
            merged.add(TimeBlock(currentStart, currentEnd))

            merged.forEach { blockTime ->
                if (blockTime.endMinute <= blockTime.startMinute) return@forEach

                val top = headerHeight + ((blockTime.startMinute - startHour * 60) * rowHeight / 60f).toInt()
                val height = ((blockTime.endMinute - blockTime.startMinute) * rowHeight / 60f).toInt()
                    .coerceAtLeast(dpToPx(24))

                val block = TextView(this@MainActivity).apply {
                    text = "$cleanName\n$location"
                    setTextColor(Color.WHITE)
                    textSize = 7.5f
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    setPadding(dpToPx(2), dpToPx(2), dpToPx(2), dpToPx(2))
                    setBackgroundColor(Color.parseColor(color))
                }

                val blockLeft = leftWidth + dayIndex * columnWidth
                val blockWidth = if (dayIndex == 4) {
                    parent.width - blockLeft
                } else {
                    columnWidth
                }
                val params = FrameLayout.LayoutParams(
                    blockWidth,
                    height
                ).apply {
                    leftMargin = blockLeft
                    topMargin = top
                }
                parent.addView(block, params)
            }
        }
    }

    private fun getScheduleMaxEndHour(subjects: List<Subject>): Int {
        var maxMinute = 15 * 60
        subjects.forEach { subject ->
            subject.schedule.values.forEach { day ->
                day.periods.filterNotNull().forEach { period ->
                    val end = scheduleTimeToMinutes(period.endTime)
                    if (end > maxMinute) maxMinute = end
                }
            }
        }
        val hour = maxMinute / 60
        val minute = maxMinute % 60
        return if (minute == 0) hour else hour + 1
    }

    private fun getTopMarginByTime(startTimeStr: String): Int {
        val parts = startTimeStr.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: 9
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val totalMinutesFromBase = (hour * 60 + minute) - (9 * 60)
        return dpToPx((totalMinutesFromBase * (52.0 / 60.0)).toInt())
    }

    private fun getBlockHeightByTime(startTimeStr: String, endTimeStr: String): Int {
        val startParts = startTimeStr.split(":")
        val startHour = startParts.getOrNull(0)?.toIntOrNull() ?: 9
        val startMin = startParts.getOrNull(1)?.toIntOrNull() ?: 0

        val endParts = endTimeStr.split(":")
        val endHour = endParts.getOrNull(0)?.toIntOrNull() ?: 10
        val endMin = endParts.getOrNull(1)?.toIntOrNull() ?: 0

        var durationMinutes = (endHour * 60 + endMin) - (startHour * 60 + startMin)

        if (durationMinutes % 60 == 50) {
            durationMinutes += 10
        } else if (durationMinutes % 60 == 45) {
            durationMinutes += 15
        }

        return dpToPx((durationMinutes * (52.0 / 60.0)).toInt()).coerceAtLeast(dpToPx(20))
    }

    private fun addSimpleText(pageView: View, parentIdName: String, value: String) {
        val parent = findChildByIdName<LinearLayout>(pageView, parentIdName) ?: return
        parent.removeAllViews()
        parent.addView(TextView(this).apply { text = value; textSize = 14f; setTextColor(Color.parseColor("#222222")); setPadding(16, 12, 16, 12) })
    }

    private fun todayMillisFromTime(time: String): Long {
        val parts = time.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: 10
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val calendar = Calendar.getInstance(Locale.KOREA)
        calendar.time = Date()
        calendar.set(Calendar.HOUR_OF_DAY, hour)
        calendar.set(Calendar.MINUTE, minute)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    private fun setText(pageView: View, idName: String, value: String) {
        findChildByIdName<TextView>(pageView, idName)?.text = value
    }

    private fun updateStudentAttendanceUi(pageView: View, statusText: String, isCompleted: Boolean) {
        val ivCheckIcon = pageView.findViewById<ImageView?>(R.id.ivCheckIcon)
        val tvAttendanceStatus = pageView.findViewById<TextView?>(R.id.tvAttendanceStatus)
        tvAttendanceStatus?.text = statusText
        if (isCompleted) {
            ivCheckIcon?.setImageResource(R.drawable.mainblue)
            tvAttendanceStatus?.setTextColor(Color.parseColor(BLUE_ACTIVE))
        } else {
            ivCheckIcon?.setImageResource(R.drawable.maingray)
            tvAttendanceStatus?.setTextColor(Color.parseColor(GRAY_INACTIVE))
        }
    }

    private inline fun <reified T> findChildByIdName(pageView: View, idName: String): T? {
        val id = resources.getIdentifier(idName, "id", packageName)
        return if (id != 0) pageView.findViewById(id) else null
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun todayText(): String = SimpleDateFormat("yyyy.MM.dd", Locale.KOREA).format(Date())

    private fun dateTextForScheduleDay(dayOfWeekInt: Int): String {
        val calendar = Calendar.getInstance(Locale.KOREA)
        val todayInt = when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 5
            Calendar.SATURDAY -> 6
            Calendar.SUNDAY -> 7
            else -> 1
        }
        val dayOffset = (dayOfWeekInt - todayInt + 7) % 7
        calendar.add(Calendar.DAY_OF_MONTH, dayOffset)

        val dayKr = when (dayOfWeekInt) {
            1 -> "\uC6D4"
            2 -> "\uD654"
            3 -> "\uC218"
            4 -> "\uBAA9"
            5 -> "\uAE08"
            6 -> "\uD1A0"
            7 -> "\uC77C"
            else -> ""
        }
        val date = SimpleDateFormat("yyyy.MM.dd", Locale.KOREA).format(calendar.time)
        return if (dayKr.isNotEmpty()) "$date ($dayKr)" else date
    }

    private fun apiDateText(): String = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date())

    private fun logout() {
        getSharedPreferences("LOGIN_INFO", MODE_PRIVATE).edit().clear().apply()
        getSharedPreferences("login_pref", MODE_PRIVATE).edit().clear().apply()
        Toast.makeText(this, "로그아웃되었습니다", Toast.LENGTH_SHORT).show()
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
