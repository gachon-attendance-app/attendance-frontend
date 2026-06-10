package com.example.myapplication

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.example.myapplication.model.domain.model.Subject
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var contentFrame: FrameLayout

    private var currentPageResId: Int = R.layout.main1

    private var userId: String = ""
    private var userName: String = ""
    private var userRole: String = "student"
    private var portalId: String = ""

    private val defaultStudentId = "202234920"

    private val attendanceBlue = Color.parseColor("#0281F6")
    private val absentRed = Color.parseColor("#E53935")
    private val latePurple = Color.parseColor("#9C27B0")
    private val mainBlue = Color.parseColor("#004B83")

    private val blockColors = listOf(
        "#8FA2C7", "#B9AAA5", "#79B2B8", "#A7B58D", "#C39DA4",
        "#9BB5A0", "#C4A882", "#7D9BB5", "#B5A89B", "#8EB5B5"
    )

    private var scheduleSubjects = mutableListOf<Subject>()
    private var scheduleUpcomingSubjects = mutableListOf<Subject>()
    private val scheduleBlockMap = mutableMapOf<String, MutableList<View>>()
    private var scheduleSelectedCode: String? = null
    private var scheduleEnrollmentListener: ValueEventListener? = null
    private val firebaseDb = FirebaseDatabase.getInstance().reference

    // 데모용 임시 과목 코드 (앱 재시작 시 Firebase에서 자동 삭제)
    private val DEMO_SUBJECT_CODE = "99001001"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        readLoginInfoFromPrefs()

        setContentView(R.layout.activity_drawer_host)

        drawerLayout = findViewById(R.id.drawerLayout)
        contentFrame = findViewById(R.id.contentFrame)

        setupDrawerMenuClick()
        registerDemoSubjectToFirebase()   // 데모용 자료구조 과목 Firebase에 등록
        resolveLoginUserAndLoadFirstPage()
    }

    // 앱 시작 시: 이전 세션 잔여 데모 데이터 삭제 후 새로 등록
    private fun registerDemoSubjectToFirebase() {
        // Subjects에 자료구조 과목 데이터 등록
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

        // Enrollment에서 데모 과목 제거 (이전 세션 잔여 삭제)
        firebaseDb.child("Enrollment").child(defaultStudentId).child(DEMO_SUBJECT_CODE).removeValue()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 앱 종료 시 데모 과목 Enrollment에서 삭제
        firebaseDb.child("Enrollment").child(userId.ifBlank { defaultStudentId }).child(DEMO_SUBJECT_CODE).removeValue()
    }

    private fun readLoginInfoFromPrefs() {
        val pref = getSharedPreferences("LOGIN_INFO", MODE_PRIVATE)
        val loginPref = getSharedPreferences("login_pref", MODE_PRIVATE)

        userId = pref.getString("userId", "")
            ?: loginPref.getString("userId", "")
                    ?: ""

        portalId = pref.getString("portalID", "")
            ?: pref.getString("portalId", "")
                    ?: pref.getString("loginId", "")
                    ?: loginPref.getString("portalID", "")
                    ?: loginPref.getString("portalId", "")
                    ?: loginPref.getString("loginId", "")
                    ?: ""

        userName = pref.getString("userName", "")
            ?: loginPref.getString("userName", "")
                    ?: ""

        userRole = pref.getString("userRole", "")
            ?: pref.getString("userType", "")
                    ?: loginPref.getString("userRole", "")
                    ?: loginPref.getString("userType", "")
                    ?: "student"

        userRole = normalizeRole(userRole)

        if (portalId.isBlank() && userId.isNotBlank() && !isRealFirebaseUserId(userId)) {
            portalId = userId
        }

        if (userId.isBlank()) {
            userId = defaultStudentId
        }
    }

    private fun resolveLoginUserAndLoadFirstPage() {
        val currentInputId = userId.trim()
        val currentPortalId = portalId.trim()

        FirebaseClient.get("Users") { usersJson ->
            var resolvedUserId = ""
            var resolvedName = ""
            var resolvedRole = ""

            if (usersJson != null) {
                val keys = usersJson.keys()

                while (keys.hasNext()) {
                    val key = keys.next()
                    val userJson = usersJson.optJSONObject(key) ?: continue

                    val dbUserId = userJson.optString("userId", key)
                    val dbPortalId = userJson.optString("portalID", "")
                    val dbName = userJson.optString("name", "")
                    val dbUserType = userJson.optString("userType", "")

                    val matchedByRealUserId = currentInputId == key || currentInputId == dbUserId
                    val matchedByPortalId = currentInputId == dbPortalId || currentPortalId == dbPortalId

                    if (matchedByRealUserId || matchedByPortalId) {
                        resolvedUserId = dbUserId.ifBlank { key }
                        resolvedName = dbName
                        resolvedRole = normalizeRole(dbUserType)
                        break
                    }
                }
            }

            if (resolvedUserId.isBlank()) {
                resolvedUserId = if (isRealFirebaseUserId(userId)) userId else defaultStudentId
                resolvedRole = if (userRole.isBlank()) "student" else userRole
            }

            userId = resolvedUserId
            userName = resolvedName.ifBlank { userName }
            userRole = resolvedRole.ifBlank { userRole }

            saveResolvedLoginInfo()

            runOnUiThread {
                if (userRole == "professor") {
                    loadPage(R.layout.main_p_1)
                } else {
                    loadPage(R.layout.main1)
                }
            }
        }
    }

    private fun saveResolvedLoginInfo() {
        getSharedPreferences("LOGIN_INFO", MODE_PRIVATE).edit()
            .putString("userId", userId)
            .putString("userName", userName)
            .putString("userRole", userRole)
            .putString("portalID", portalId)
            .apply()
    }

    private fun isRealFirebaseUserId(value: String): Boolean {
        return value.matches(Regex("\\d{6,}")) || value == "testUser"
    }

    private fun normalizeRole(role: String): String {
        return when (role.trim().uppercase()) {
            "PROFESSOR", "PROF", "교수" -> "professor"
            "STUDENT", "학생" -> "student"
            else -> role.trim().lowercase().ifBlank { "student" }
        }
    }

    private fun loadPage(layoutResId: Int) {
        if (layoutResId != R.layout.schedule_1) {
            detachScheduleListener()
        }

        currentPageResId = layoutResId
        contentFrame.removeAllViews()

        val pageView = LayoutInflater.from(this).inflate(layoutResId, contentFrame, false)
        contentFrame.addView(pageView)

        connectTopMenuButton(pageView)
        connectBottomMenu(pageView)
        loadDataByPage(layoutResId, pageView)
    }

    private fun loadDataByPage(layoutResId: Int, pageView: View) {
        when (layoutResId) {
            R.layout.main1 -> loadStudentMainPage(pageView)
            R.layout.main_p_1 -> loadProfessorMainPage(pageView)
            R.layout.schedule_1 -> loadSchedulePage(pageView)
            R.layout.mypage -> loadMyPage(pageView)
            R.layout.week_1 -> loadWeekPage(pageView)
            R.layout.all_attendance -> loadAttendanceSummary(pageView)
            R.layout.notice_1,
            R.layout.notice_2 -> loadNoticePage(pageView)
            R.layout.cancel_1,
            R.layout.cancel_2 -> loadCancelPage(pageView)
            R.layout.confirm_1,
            R.layout.confirm_2 -> loadConfirmPage(pageView)
        }
    }

    private fun connectTopMenuButton(pageView: View) {
        pageView.findViewById<View?>(R.id.btnMenu)?.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.END)
        }
    }

    private fun connectBottomMenu(pageView: View) {
        pageView.findViewById<View?>(R.id.btnBottomHome)?.setOnClickListener {
            if (userRole == "professor") {
                loadPage(R.layout.main_p_1)
            } else {
                loadPage(R.layout.main1)
            }
        }

        pageView.findViewById<View?>(R.id.btnBottomRefresh)?.setOnClickListener {
            loadPage(currentPageResId)
            Toast.makeText(this, "새로고침되었습니다", Toast.LENGTH_SHORT).show()
        }

        pageView.findViewById<View?>(R.id.btnBottomNotice)?.setOnClickListener {
            if (userRole == "professor") {
                loadPage(R.layout.notice_2)
            } else {
                loadPage(R.layout.notice_1)
            }
        }

        pageView.findViewById<View?>(R.id.btnBottomSchedule)?.setOnClickListener {
            loadPage(R.layout.schedule_1)
        }

        pageView.findViewById<View?>(R.id.btnBottomLogout)?.setOnClickListener {
            logout()
        }
    }

    private fun setupDrawerMenuClick() {
        findViewById<View?>(R.id.menuMyPage)?.setOnClickListener {
            moveTo(R.layout.mypage)
        }

        findViewById<View?>(R.id.menuSchedule)?.setOnClickListener {
            moveTo(R.layout.schedule_1)
        }

        findViewById<View?>(R.id.menuWeekAttendance)?.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.END)
            loadPage(R.layout.week_1)
        }

        findViewById<View?>(R.id.menuAllAttendance)?.setOnClickListener {
            moveTo(R.layout.all_attendance)
        }

        findViewById<View?>(R.id.menuConfirmPeriod)?.setOnClickListener {
            moveTo(R.layout.confirm_1)
        }

        findViewById<View?>(R.id.menuConfirmOfficial)?.setOnClickListener {
            moveTo(R.layout.confirm_2)
        }

        findViewById<View?>(R.id.menuNotice)?.setOnClickListener {
            if (userRole == "professor") {
                moveTo(R.layout.notice_2)
            } else {
                moveTo(R.layout.notice_1)
            }
        }

        findViewById<View?>(R.id.menuCancel)?.setOnClickListener {
            if (userRole == "professor") {
                moveTo(R.layout.cancel_2)
            } else {
                moveTo(R.layout.cancel_1)
            }
        }
    }

    private fun moveTo(layoutResId: Int) {
        drawerLayout.closeDrawer(GravityCompat.END)
        loadPage(layoutResId)
    }

    private fun logout() {
        getSharedPreferences("LOGIN_INFO", MODE_PRIVATE).edit().clear().apply()
        getSharedPreferences("login_pref", MODE_PRIVATE).edit().clear().apply()
        Toast.makeText(this, "로그아웃되었습니다", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun loadStudentMainPage(pageView: View) {
        val cal = Calendar.getInstance(Locale.KOREA)
        val nowMinute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val todayDow = when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SUNDAY    -> "Sunday"
            Calendar.MONDAY    -> "Monday"
            Calendar.TUESDAY   -> "Tuesday"
            Calendar.WEDNESDAY -> "Wednesday"
            Calendar.THURSDAY  -> "Thursday"
            Calendar.FRIDAY    -> "Friday"
            Calendar.SATURDAY  -> "Saturday"
            else -> ""
        }
        val todayDowKr = when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SUNDAY -> "일"; Calendar.MONDAY -> "월"; Calendar.TUESDAY -> "화"
            Calendar.WEDNESDAY -> "수"; Calendar.THURSDAY -> "목"
            Calendar.FRIDAY -> "금"; Calendar.SATURDAY -> "토"; else -> ""
        }
        val dateFmt = SimpleDateFormat("yyyy.MM.dd", Locale.KOREA)
        pageView.findViewById<TextView?>(R.id.tvDate)?.text =
            "${dateFmt.format(cal.time)} $todayDowKr"

        firebaseDb.child("Enrollment").child(userId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(enrollSnap: DataSnapshot) {
                    val codes = enrollSnap.children
                        .filter { it.getValue(Boolean::class.java) == true }
                        .mapNotNull { it.key }

                    if (codes.isEmpty()) {
                        showMainNoClass(pageView)
                        return
                    }

                    val loaded = mutableListOf<DataSnapshot>()
                    var pending = codes.size

                    for (code in codes) {
                        firebaseDb.child("Subjects").child(code)
                            .addListenerForSingleValueEvent(object : ValueEventListener {
                                override fun onDataChange(snap: DataSnapshot) {
                                    loaded.add(snap)
                                    pending--
                                    if (pending > 0) return

                                    data class TodayClass(
                                        val subjectCode: String,
                                        val name: String, val prof: String,
                                        val startTime: String, val endTime: String,
                                        val room: String,
                                        val startMinute: Int, val endMinute: Int
                                    )

                                    // 오늘 요일 수업 전체 수집
                                    val todayClasses = mutableListOf<TodayClass>()
                                    for (snap2 in loaded) {
                                        val subCode = snap2.child("subjectCode").getValue(String::class.java) ?: snap2.key ?: ""
                                        val rawName = snap2.child("subjectName").getValue(String::class.java) ?: ""
                                        val prof = snap2.child("professorName").getValue(String::class.java) ?: ""
                                        snap2.child("schedule").children.forEach { daySnap ->
                                            val dow = daySnap.child("dayOfWeek").getValue(String::class.java) ?: ""
                                            if (!dow.equals(todayDow, ignoreCase = true)) return@forEach
                                            val location = daySnap.child("location").getValue(String::class.java) ?: ""
                                            var st = ""; var et = ""
                                            daySnap.child("periods").children.forEach { p ->
                                                val s = p.child("startTime").getValue(String::class.java) ?: ""
                                                val e = p.child("endTime").getValue(String::class.java) ?: ""
                                                if (s.isNotBlank() && st.isBlank()) st = s
                                                if (e.isNotBlank()) et = e
                                            }
                                            if (st.isBlank()) return@forEach
                                            todayClasses.add(TodayClass(
                                                subCode, cleanSubjectName(rawName), prof,
                                                st, et, location,
                                                scheduleTimeToMinute(st), scheduleTimeToMinute(et)
                                            ))
                                        }
                                    }

                                    todayClasses.sortBy { it.startMinute }

                                    // 현재 진행 중이거나 시작 1시간 이내 수업 찾기
                                    // 시작 60분 전 ~ 종료 시각: 출결 대상 수업
                                    val current = todayClasses.firstOrNull { c ->
                                        nowMinute >= c.startMinute - 60 && nowMinute <= c.endMinute
                                    }

                                    // 완전히 끝난 수업 중 가장 최근
                                    val lastFinished = todayClasses
                                        .filter { nowMinute > it.endMinute }
                                        .maxByOrNull { it.endMinute }

                                    val target = current ?: lastFinished

                                    runOnUiThread {
                                        if (target == null) {
                                            // 오늘 수업 없거나 아직 1시간 이상 남음
                                            showMainNoClass(pageView)
                                        } else {
                                            val timeText = "${target.startTime} ~ ${target.endTime}"
                                            // 상단 카드: 수업명 + 시간
                                            setTextIfExists(pageView, R.id.tvClassName, target.name)
                                            setTextIfExists(pageView, R.id.tvClassTime, timeText)
                                            setTextIfExists(pageView, R.id.tvPeriod, "")
                                            // 하단 카드: 상세 정보
                                            setTextIfExists(pageView, R.id.tvDetailClassName, target.name)
                                            setTextIfExists(pageView, R.id.tvDetailProfessor, target.prof)
                                            setTextIfExists(pageView, R.id.tvDetailTime, timeText)
                                            setTextIfExists(pageView, R.id.tvDetailRoom, target.room)

                                            // 출결 상태 조회 (완전히 끝난 수업만)
                                            if (nowMinute > target.endMinute) {
                                                val today2 = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date())
                                                firebaseDb.child("Attendance_Records")
                                                    .child(target.subjectCode).child(today2).child(userId)
                                                    .addListenerForSingleValueEvent(object : ValueEventListener {
                                                        override fun onDataChange(recSnap: DataSnapshot) {
                                                            val raw = recSnap.child("finalStatus").getValue(String::class.java) ?: ""
                                                            val status = normalizeAttendanceStatus(raw)
                                                            runOnUiThread {
                                                                val (iconRes, statusText, statusColor) = when (status) {
                                                                    "출석" -> Triple(R.drawable.mainblue, "출석 완료", Color.parseColor("#004B83"))
                                                                    "지각" -> Triple(R.drawable.maingray, "지각", Color.parseColor("#9C27B0"))
                                                                    "결석" -> Triple(R.drawable.maingray, "미출석", Color.parseColor("#9E9EA4"))
                                                                    else   -> Triple(R.drawable.maingray, "미출석", Color.parseColor("#9E9EA4"))
                                                                }
                                                                pageView.findViewById<android.widget.ImageView?>(R.id.ivCheckIcon)?.setImageResource(iconRes)
                                                                setTextIfExists(pageView, R.id.tvAttendanceStatus, statusText)
                                                                pageView.findViewById<TextView?>(R.id.tvAttendanceStatus)?.setTextColor(statusColor)
                                                            }
                                                        }
                                                        override fun onCancelled(e: DatabaseError) {}
                                                    })
                                            } else {
                                                // 진행 중 수업 - 아직 출결 미확정
                                                pageView.findViewById<android.widget.ImageView?>(R.id.ivCheckIcon)
                                                    ?.setImageResource(R.drawable.maingray)
                                                setTextIfExists(pageView, R.id.tvAttendanceStatus, "출석 대기")
                                            }
                                        }
                                        pageView.findViewById<Button?>(R.id.btnAttendance)?.setOnClickListener {
                                            Toast.makeText(this@MainActivity, "출석체크는 교수님 출석 시작 후 가능합니다", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                                override fun onCancelled(e: DatabaseError) { pending-- }
                            })
                    }
                }
                override fun onCancelled(e: DatabaseError) {}
            })
    }

    private fun loadProfessorMainPage(pageView: View) {
        val targetSubjectCode = "14454001"

        FirebaseClient.get("Subjects/$targetSubjectCode") { subjectJson ->
            val subjectName = cleanSubjectName(subjectJson?.optString("subjectName", "").orEmpty())

            runOnUiThread {
                setTextByName(pageView, "tvClassName", subjectName)
                setTextByName(pageView, "tvSubjectName", subjectName)
            }
        }

        pageView.findViewById<View?>(R.id.btnProfessorAttendanceCheck)?.setOnClickListener {
            Toast.makeText(this, "출석체크가 시작되었습니다", Toast.LENGTH_SHORT).show()
            showProfessorPin(pageView, "1234")
        }

        pageView.findViewById<View?>(R.id.btnRollCallAttendance)?.setOnClickListener {
            Toast.makeText(this, "호명출석 기능입니다", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showProfessorPin(pageView: View, pinCode: String) {
        val pin = pinCode.padEnd(4, ' ')

        setTextByName(pageView, "tvPinDigit1", pin[0].toString())
        setTextByName(pageView, "tvPinDigit2", pin[1].toString())
        setTextByName(pageView, "tvPinDigit3", pin[2].toString())
        setTextByName(pageView, "tvPinDigit4", pin[3].toString())
    }

    private fun loadSchedulePage(pageView: View) {
        // classBlockLayer 또는 timeTableCanvas 둘 다 지원
        val classBlockLayer: FrameLayout? =
            pageView.findViewById<FrameLayout?>(R.id.classBlockLayer)
                ?: pageView.findViewById(R.id.timeTableCanvas)

        if (classBlockLayer == null) {
            Toast.makeText(this, "시간표 View를 찾을 수 없습니다(classBlockLayer/timeTableCanvas).", Toast.LENGTH_SHORT).show()
            return
        }

        // viewPager / dotsContainer 없어도 동작
        val viewPager: ViewPager2? = pageView.findViewById(R.id.viewPagerSubjectCards)
        val dotsContainer: LinearLayout? = pageView.findViewById(R.id.dotsContainer)
        val emptyCard: View? = pageView.findViewById(R.id.currentClassEmptyCard)
        val subjectCard: View? = pageView.findViewById(R.id.cardCurrentSubject)

        val tvUpcomingName: TextView? =
            pageView.findViewById<TextView?>(R.id.tvCardSubjectName)
                ?: pageView.findViewById(R.id.tvCurrentSubjectName)
        val tvUpcomingProf: TextView? =
            pageView.findViewById<TextView?>(R.id.tvCardProfessor)
                ?: pageView.findViewById(R.id.tvCurrentProfessor)
        val tvUpcomingTime: TextView? =
            pageView.findViewById<TextView?>(R.id.tvCardTime)
                ?: pageView.findViewById(R.id.tvCurrentTime)
        val tvUpcomingRoom: TextView? =
            pageView.findViewById<TextView?>(R.id.tvCardLocation)
                ?: pageView.findViewById(R.id.tvCurrentRoom)
        val tvUpcomingCode: TextView? =
            pageView.findViewById<TextView?>(R.id.tvCardCode)
                ?: pageView.findViewById(R.id.tvCurrentSubjectCode)

        // EditText: etSubjectCodeInput 또는 etCourseCode
        val etCode: android.widget.EditText? =
            pageView.findViewById<android.widget.EditText?>(R.id.etSubjectCodeInput)
                ?: pageView.findViewById(R.id.etCourseCode)

        // 추가 버튼: btnAddSubject 또는 btnAddClass
        val btnAdd: View? =
            pageView.findViewById<View?>(R.id.btnAddSubject)
                ?: pageView.findViewById(R.id.btnAddClass)

        btnAdd?.let { btn ->
            btn.isEnabled = true
            btn.isClickable = true
            btn.alpha = 1f
            btn.setOnClickListener {
                val code = etCode?.text?.toString()?.trim().orEmpty()
                if (code.isBlank()) {
                    Toast.makeText(this, "과목코드를 입력해주세요.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                addSubjectToSchedule(code, etCode)
            }
        }

        etCode?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                btnAdd?.performClick()
                true
            } else false
        }

        viewPager?.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (position in scheduleUpcomingSubjects.indices) {
                    scheduleHighlight(scheduleUpcomingSubjects[position].subjectCode)
                    dotsContainer?.let { scheduleUpdateDots(it, position) }
                }
            }
        })

        detachScheduleListener()

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val codes = snapshot.children
                    .filter { it.getValue(Boolean::class.java) == true }
                    .mapNotNull { it.key }

                loadScheduleSubjects(
                    codes, classBlockLayer, viewPager, dotsContainer, emptyCard,
                    subjectCard, tvUpcomingName, tvUpcomingProf, tvUpcomingTime, tvUpcomingRoom, tvUpcomingCode
                )
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@MainActivity, "시간표 데이터를 불러오지 못했습니다.", Toast.LENGTH_SHORT).show()
            }
        }

        scheduleEnrollmentListener = listener
        firebaseDb.child("Enrollment").child(userId).addValueEventListener(listener)
    }

    private fun addSubjectToSchedule(
        code: String,
        etCode: android.widget.EditText?
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

                    if (scheduleSubjects.any { it.subjectCode == finalCode }) {
                        Toast.makeText(this@MainActivity, "이미 추가된 과목입니다.", Toast.LENGTH_SHORT).show()
                        return
                    }

                    firebaseDb.child("Enrollment")
                        .child(userId)
                        .child(finalCode)
                        .setValue(true)
                        .addOnSuccessListener {
                            etCode?.text?.clear()
                            Toast.makeText(this@MainActivity, "${cleanSubjectName(subject.subjectName)} 추가됨", Toast.LENGTH_SHORT).show()
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

    private fun loadScheduleSubjects(
        codes: List<String>,
        classBlockLayer: FrameLayout,
        viewPager: ViewPager2?,
        dotsContainer: LinearLayout?,
        emptyCard: View?,
        subjectCard: View? = null,
        tvName: TextView? = null,
        tvProf: TextView? = null,
        tvTime: TextView? = null,
        tvRoom: TextView? = null,
        tvCode: TextView? = null
    ) {
        if (codes.isEmpty()) {
            scheduleSubjects.clear()
            scheduleUpcomingSubjects.clear()
            runOnUiThread {
                scheduleRefreshUI(classBlockLayer, viewPager, dotsContainer, emptyCard, subjectCard, tvName, tvProf, tvTime, tvRoom, tvCode)
            }
            return
        }

        val loaded = mutableListOf<Subject>()
        var pending = codes.size

        codes.forEach { code ->
            firebaseDb.child("Subjects").child(code)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snap: DataSnapshot) {
                        snap.getValue(Subject::class.java)?.let { loaded.add(it) }
                        pending--
                        if (pending == 0) {
                            scheduleSubjects.clear()
                            scheduleSubjects.addAll(loaded.sortedWith(scheduleSubjectOrder))
                            runOnUiThread {
                                scheduleRefreshUI(classBlockLayer, viewPager, dotsContainer, emptyCard, subjectCard, tvName, tvProf, tvTime, tvRoom, tvCode)
                            }
                        }
                    }
                    override fun onCancelled(error: DatabaseError) {
                        pending--
                        if (pending == 0) {
                            scheduleSubjects.clear()
                            scheduleSubjects.addAll(loaded.sortedWith(scheduleSubjectOrder))
                            runOnUiThread {
                                scheduleRefreshUI(classBlockLayer, viewPager, dotsContainer, emptyCard, subjectCard, tvName, tvProf, tvTime, tvRoom, tvCode)
                            }
                        }
                    }
                })
        }
    }


    private fun scheduleRefreshUI(
        classBlockLayer: FrameLayout,
        viewPager: ViewPager2?,
        dotsContainer: LinearLayout?,
        emptyCard: View?,
        subjectCard: View? = null,
        tvName: TextView? = null,
        tvProf: TextView? = null,
        tvTime: TextView? = null,
        tvRoom: TextView? = null,
        tvCode: TextView? = null
    ) {
        classBlockLayer.removeAllViews()
        classBlockLayer.setPadding(0, 0, 0, 0)
        classBlockLayer.setBackgroundColor(Color.WHITE)
        scheduleBlockMap.clear()
        scheduleSelectedCode = null

        val startHour = 9
        val endHour = getScheduleMaxEndHour().coerceAtLeast(15)
        val leftWidth = dpToPx(20)
        val headerHeight = dpToPx(20)
        val rowHeight = dpToPx(34)
        val hourCount = endHour - startHour
        val targetHeight = headerHeight + hourCount * rowHeight + dpToPx(2)

        classBlockLayer.layoutParams = classBlockLayer.layoutParams.apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = targetHeight
        }

        classBlockLayer.post {
            classBlockLayer.removeAllViews()
            drawScheduleGrid(classBlockLayer, startHour, endHour, leftWidth, headerHeight, rowHeight)

            if (scheduleSubjects.isNotEmpty()) {
                scheduleSubjects.forEachIndexed { index, subject ->
                    scheduleDrawBlocks(
                        subject = subject,
                        colorIdx = index,
                        classBlockLayer = classBlockLayer,
                        viewPager = viewPager,
                        startHour = startHour,
                        leftWidth = leftWidth,
                        headerHeight = headerHeight,
                        rowHeight = rowHeight
                    )
                }
            }

            scheduleUpcomingSubjects.clear()
            scheduleUpcomingSubjects.addAll(getSubjectsNearCurrentTime())

            if (scheduleUpcomingSubjects.isEmpty()) {
                emptyCard?.visibility = View.VISIBLE
                subjectCard?.visibility = View.GONE
                viewPager?.visibility = View.GONE
                dotsContainer?.visibility = View.GONE
            } else {
                emptyCard?.visibility = View.GONE
                val upcoming = scheduleUpcomingSubjects[0]

                // ViewPager 방식 (schedule_1.xml에 viewPager 있을 때)
                if (viewPager != null && dotsContainer != null) {
                    viewPager.visibility = View.VISIBLE
                    dotsContainer.visibility = View.VISIBLE
                    viewPager.adapter = ScheduleCardAdapter(viewPager, dotsContainer)
                    viewPager.offscreenPageLimit = 3
                    scheduleBuildDots(dotsContainer, scheduleUpcomingSubjects.size)
                    scheduleUpdateDots(dotsContainer, 0)
                }

                // 직접 TextView 방식 (ScheduleActivity 레이아웃)
                if (tvName != null) {
                    subjectCard?.visibility = View.VISIBLE
                    tvName.text = cleanSubjectName(upcoming.subjectName)
                    tvProf?.text = upcoming.professorName

                    val cal = java.util.Calendar.getInstance(Locale.KOREA)
                    val todayCol = when (cal.get(java.util.Calendar.DAY_OF_WEEK)) {
                        java.util.Calendar.MONDAY    -> 0
                        java.util.Calendar.TUESDAY   -> 1
                        java.util.Calendar.WEDNESDAY -> 2
                        java.util.Calendar.THURSDAY  -> 3
                        java.util.Calendar.FRIDAY    -> 4
                        else -> -1
                    }
                    val todayDay = upcoming.schedule.values
                        .firstOrNull { scheduleDayToCol(it.dayOfWeek) == todayCol }
                        ?: upcoming.schedule.values.firstOrNull()

                    if (todayDay != null) {
                        val valid = todayDay.periods.filterNotNull()
                            .filter { it.startTime.isNotBlank() && it.endTime.isNotBlank() }
                            .sortedBy { scheduleTimeToMinute(it.startTime) }
                        if (valid.isNotEmpty()) {
                            tvTime?.text = "${valid.first().startTime} ~ ${valid.last().endTime}"
                        }
                        tvRoom?.text = todayDay.location
                    }
                    tvCode?.text = upcoming.subjectCode
                }

                scheduleHighlight(upcoming.subjectCode)
            }
        }
    }


    private fun drawScheduleGrid(
        layer: FrameLayout,
        startHour: Int,
        endHour: Int,
        leftWidth: Int,
        headerHeight: Int,
        rowHeight: Int
    ) {
        val days = listOf("월", "화", "수", "목", "금")
        val totalWidth = layer.width
        if (totalWidth <= 0) return

        val gridWidth = totalWidth - leftWidth
        val colWidth = gridWidth / 5

        days.forEachIndexed { index, day ->
            val tv = TextView(this).apply {
                text = day
                textSize = 8f
                setTextColor(Color.parseColor("#999999"))
                gravity = Gravity.CENTER
                includeFontPadding = false
            }

            layer.addView(
                tv,
                FrameLayout.LayoutParams(colWidth, headerHeight).apply {
                    leftMargin = leftWidth + index * colWidth
                    topMargin = 0
                }
            )
        }

        for (hour in startHour until endHour) {
            val y = headerHeight + (hour - startHour) * rowHeight
            val hourText = if (hour <= 12) hour.toString() else (hour - 12).toString()

            val hourView = TextView(this).apply {
                text = hourText
                textSize = 8f
                setTextColor(Color.parseColor("#B8B8B8"))
                gravity = Gravity.TOP or Gravity.RIGHT
                includeFontPadding = false
                setPadding(0, dpToPx(2), dpToPx(5), 0)
            }

            layer.addView(
                hourView,
                FrameLayout.LayoutParams(leftWidth, rowHeight).apply {
                    leftMargin = 0
                    topMargin = y
                }
            )

            val horizontal = View(this).apply {
                setBackgroundColor(Color.parseColor("#EEEEEE"))
            }

            layer.addView(
                horizontal,
                FrameLayout.LayoutParams(gridWidth, dpToPx(1)).apply {
                    leftMargin = leftWidth
                    topMargin = y
                }
            )
        }

        val bottomLine = View(this).apply {
            setBackgroundColor(Color.parseColor("#EEEEEE"))
        }

        layer.addView(
            bottomLine,
            FrameLayout.LayoutParams(gridWidth, dpToPx(1)).apply {
                leftMargin = leftWidth
                topMargin = headerHeight + (endHour - startHour) * rowHeight
            }
        )

        for (i in 0..5) {
            val vertical = View(this).apply {
                setBackgroundColor(Color.parseColor("#EEEEEE"))
            }

            layer.addView(
                vertical,
                FrameLayout.LayoutParams(dpToPx(1), (endHour - startHour) * rowHeight).apply {
                    leftMargin = leftWidth + i * colWidth
                    topMargin = headerHeight
                }
            )
        }
    }

    private fun scheduleDrawBlocks(
        subject: Subject,
        colorIdx: Int,
        classBlockLayer: FrameLayout,
        viewPager: ViewPager2?,
        startHour: Int,
        leftWidth: Int,
        headerHeight: Int,
        rowHeight: Int
    ) {
        val colorHex = blockColors[colorIdx % blockColors.size]
        val cleanName = cleanSubjectName(subject.subjectName)
        val totalWidth = classBlockLayer.width
        if (totalWidth <= 0) return

        val gridWidth = totalWidth - leftWidth
        val colWidth = gridWidth / 5

        for ((_, day) in subject.schedule) {
            val colIndex = scheduleDayToCol(day.dayOfWeek) ?: continue
            val location = day.location.ifBlank { "" }

            // 유효한 period만 시작시간 오름차순 정렬
            val validPeriods = day.periods
                .filterNotNull()
                .filter { it.startTime.isNotBlank() && it.endTime.isNotBlank() }
                .sortedBy { scheduleTimeToMinute(it.startTime) }

            if (validPeriods.isEmpty()) continue

            // 연속 period 묶기: endTime 이 다음 period startTime 과 같거나 연속이면 병합
            data class TimeBlock(val startMinute: Int, val endMinute: Int)

            val merged = mutableListOf<TimeBlock>()
            var curStart = scheduleTimeToMinute(validPeriods[0].startTime)
            var curEnd = scheduleTimeToMinute(validPeriods[0].endTime)

            for (i in 1 until validPeriods.size) {
                val nextStart = scheduleTimeToMinute(validPeriods[i].startTime)
                val nextEnd = scheduleTimeToMinute(validPeriods[i].endTime)
                // 10분 이내 gap은 연속으로 간주 (예: 10:50 끝 → 11:00 시작)
                if (nextStart - curEnd <= 10) {
                    curEnd = nextEnd
                } else {
                    merged.add(TimeBlock(curStart, curEnd))
                    curStart = nextStart
                    curEnd = nextEnd
                }
            }
            merged.add(TimeBlock(curStart, curEnd))

            for (block in merged) {
                if (block.startMinute < 0 || block.endMinute <= block.startMinute) continue

                val top = headerHeight + ((block.startMinute - startHour * 60) * rowHeight / 60f).toInt()
                val blockHeight = ((block.endMinute - block.startMinute) * rowHeight / 60f).toInt()
                    .coerceAtLeast(dpToPx(24))

                // 표시할 텍스트: 과목명 + 강의실
                val displayText = if (location.isNotBlank()) "$cleanName\n$location" else cleanName

                val blockView = TextView(this).apply {
                    text = displayText
                    setTextColor(Color.WHITE)
                    textSize = 7.5f
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    setPadding(dpToPx(3), dpToPx(3), dpToPx(3), dpToPx(3))
                    maxLines = 6
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setBackgroundColor(Color.parseColor(colorHex))

                    setOnClickListener {
                        val position = scheduleUpcomingSubjects.indexOfFirst { it.subjectCode == subject.subjectCode }
                        if (position >= 0) {
                            viewPager?.currentItem = position
                        }
                        scheduleHighlight(subject.subjectCode)
                    }
                }

                classBlockLayer.addView(
                    blockView,
                    FrameLayout.LayoutParams(colWidth - dpToPx(3), blockHeight - dpToPx(2)).apply {
                        leftMargin = leftWidth + colIndex * colWidth + dpToPx(2)
                        topMargin = top + dpToPx(1)
                    }
                )

                scheduleBlockMap.getOrPut(subject.subjectCode) { mutableListOf() }.add(blockView)
            }
        }
    }

    private fun scheduleTimeToMinute(time: String): Int {
        val clean = time.trim()
        val hour = clean.substringBefore(":").toIntOrNull() ?: return -1
        val minute = clean.substringAfter(":", "0").toIntOrNull() ?: 0
        return hour * 60 + minute
    }

    private fun getScheduleMaxEndHour(): Int {
        var maxMinute = 15 * 60

        scheduleSubjects.forEach { subject ->
            subject.schedule.values.forEach { day ->
                day.periods.filterNotNull().forEach { period ->
                    val end = scheduleTimeToMinute(period.endTime)
                    if (end > maxMinute) maxMinute = end
                }
            }
        }

        val hour = maxMinute / 60
        val minute = maxMinute % 60
        return if (minute == 0) hour else hour + 1
    }

    private fun getSubjectsNearCurrentTime(): List<Subject> {
        if (scheduleSubjects.isEmpty()) return emptyList()

        val cal = java.util.Calendar.getInstance(Locale.KOREA)
        val todayIndex = cal.get(java.util.Calendar.DAY_OF_WEEK)
        val today = when (todayIndex) {
            java.util.Calendar.MONDAY    -> 0
            java.util.Calendar.TUESDAY   -> 1
            java.util.Calendar.WEDNESDAY -> 2
            java.util.Calendar.THURSDAY  -> 3
            java.util.Calendar.FRIDAY    -> 4
            else -> -1
        }
        if (today == -1) return emptyList()

        val nowMinute = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)

        data class TodayEntry(val subject: Subject, val classStart: Int, val classEnd: Int)

        val todayEntries = mutableListOf<TodayEntry>()
        scheduleSubjects.forEach { subject ->
            subject.schedule.values.forEach { day ->
                val dayCol = scheduleDayToCol(day.dayOfWeek) ?: return@forEach
                if (dayCol != today) return@forEach

                val validPeriods = day.periods.filterNotNull()
                    .filter { it.startTime.isNotBlank() && it.endTime.isNotBlank() }
                if (validPeriods.isEmpty()) return@forEach

                val classStart = validPeriods.minOf { scheduleTimeToMinute(it.startTime) }
                val classEnd   = validPeriods.maxOf { scheduleTimeToMinute(it.endTime) }
                if (classStart < 0 || classEnd <= classStart) return@forEach

                todayEntries.add(TodayEntry(subject, classStart, classEnd))
            }
        }

        if (todayEntries.isEmpty()) return emptyList()

        // 1순위: 지금 진행 중인 수업
        val inProgress = todayEntries
            .filter { nowMinute >= it.classStart && nowMinute <= it.classEnd }
            .minByOrNull { it.classStart }
        if (inProgress != null) return listOf(inProgress.subject)

        // 2순위: 앞으로 시작할 수업 중 가장 빠른 것
        val nextClass = todayEntries
            .filter { nowMinute < it.classStart }
            .minByOrNull { it.classStart }
        if (nextClass != null) return listOf(nextClass.subject)

        // 오늘 수업 모두 끝남
        return emptyList()
    }


    private fun scheduleHighlight(code: String) {
        scheduleSelectedCode?.let { prev ->
            val prevIdx = scheduleSubjects.indexOfFirst { it.subjectCode == prev }
            val prevColor = if (prevIdx >= 0) blockColors[prevIdx % blockColors.size] else "#8FA2C7"

            scheduleBlockMap[prev]?.forEach { view ->
                (view as? TextView)?.setBackgroundColor(Color.parseColor(prevColor))
            }
        }

        val newIdx = scheduleSubjects.indexOfFirst { it.subjectCode == code }
        if (newIdx >= 0) {
            val base = Color.parseColor(blockColors[newIdx % blockColors.size])
            scheduleBlockMap[code]?.forEach { view ->
                (view as? TextView)?.setBackgroundColor(darkenColor(base, 0.80f))
            }
        }

        scheduleSelectedCode = code
    }

    private fun darkenColor(color: Int, factor: Float): Int {
        val r = (Color.red(color) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun scheduleBuildDots(container: LinearLayout, count: Int) {
        container.removeAllViews()

        repeat(count) {
            val dot = View(this)
            val size = dpToPx(8)

            dot.layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginStart = dpToPx(4)
                marginEnd = dpToPx(4)
            }

            dot.setBackgroundResource(R.drawable.dot_inactive)
            container.addView(dot)
        }
    }

    private fun scheduleUpdateDots(container: LinearLayout, active: Int) {
        for (i in 0 until container.childCount) {
            container.getChildAt(i).setBackgroundResource(
                if (i == active) R.drawable.dot_active else R.drawable.dot_inactive
            )
        }
    }

    private fun scheduleDayToCol(day: String): Int? {
        return when (day.trim().lowercase()) {
            "monday", "mon", "월", "월요일" -> 0
            "tuesday", "tue", "화", "화요일" -> 1
            "wednesday", "wed", "수", "수요일" -> 2
            "thursday", "thu", "목", "목요일" -> 3
            "friday", "fri", "금", "금요일" -> 4
            else -> null
        }
    }

    private val scheduleSubjectOrder = Comparator<Subject> { a, b ->
        val aDay = a.schedule.values.minOfOrNull { scheduleDayToCol(it.dayOfWeek) ?: 99 } ?: 99
        val bDay = b.schedule.values.minOfOrNull { scheduleDayToCol(it.dayOfWeek) ?: 99 } ?: 99

        if (aDay != bDay) return@Comparator aDay - bDay

        val aTime = a.schedule.values.flatMap { it.periods }.filterNotNull().minOfOrNull { it.startTime } ?: "99:99"
        val bTime = b.schedule.values.flatMap { it.periods }.filterNotNull().minOfOrNull { it.startTime } ?: "99:99"
        aTime.compareTo(bTime)
    }

    private fun detachScheduleListener() {
        scheduleEnrollmentListener?.let {
            firebaseDb.child("Enrollment").child(userId).removeEventListener(it)
            scheduleEnrollmentListener = null
        }

        scheduleSubjects.clear()
        scheduleUpcomingSubjects.clear()
        scheduleBlockMap.clear()
        scheduleSelectedCode = null
    }

    private fun currentSemester(): String {
        val cal = java.util.Calendar.getInstance()
        val year = cal.get(java.util.Calendar.YEAR)
        val month = cal.get(java.util.Calendar.MONTH) + 1
        val semester = if (month in 1..8) 1 else 2
        return "${year}년 ${semester}학기"
    }

    inner class ScheduleCardAdapter(
        private val viewPager: ViewPager2?,
        private val dotsContainer: LinearLayout?
    ) : RecyclerView.Adapter<ScheduleCardAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tvCardSubjectName)
            val tvProf: TextView = v.findViewById(R.id.tvCardProfessor)
            val tvSemester: TextView = v.findViewById(R.id.tvCardSemester)
            val tvTime: TextView = v.findViewById(R.id.tvCardTime)
            val tvLocation: TextView = v.findViewById(R.id.tvCardLocation)
            val tvCode: TextView = v.findViewById(R.id.tvCardCode)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_subject_card, parent, false)
            )
        }

        override fun getItemCount(): Int {
            return scheduleUpcomingSubjects.size
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val subject = scheduleUpcomingSubjects[position]

            holder.tvName.text = cleanSubjectName(subject.subjectName)
            holder.tvProf.text = subject.professorName
            holder.tvSemester.text = currentSemester()

            // 오늘 요일 기준으로 해당 day의 시간 표시 (전체 요일 아닌 오늘 것만)
            val cal = java.util.Calendar.getInstance()
            val todayIndex = cal.get(java.util.Calendar.DAY_OF_WEEK)
            val todayCol = when (todayIndex) {
                java.util.Calendar.MONDAY    -> 0
                java.util.Calendar.TUESDAY   -> 1
                java.util.Calendar.WEDNESDAY -> 2
                java.util.Calendar.THURSDAY  -> 3
                java.util.Calendar.FRIDAY    -> 4
                else -> -1
            }

            // 오늘 수업 day 찾기 → 없으면 첫 번째 day 사용
            val todayDay = subject.schedule.values
                .firstOrNull { scheduleDayToCol(it.dayOfWeek) == todayCol }
                ?: subject.schedule.values.firstOrNull()

            val timeText: String
            val locationText: String

            if (todayDay != null) {
                val validPeriods = todayDay.periods.filterNotNull()
                    .filter { it.startTime.isNotBlank() && it.endTime.isNotBlank() }
                    .sortedBy { scheduleTimeToMinute(it.startTime) }

                timeText = if (validPeriods.isNotEmpty()) {
                    val st = validPeriods.first().startTime
                    val et = validPeriods.last().endTime
                    "$st ~ $et"
                } else ""

                locationText = todayDay.location.ifBlank {
                    subject.schedule.values.firstOrNull { it.location.isNotBlank() }?.location.orEmpty()
                }
            } else {
                // fallback: 전체 요일 시간 나열
                timeText = subject.schedule.entries
                    .sortedBy { scheduleDayToCol(it.value.dayOfWeek) ?: 99 }
                    .mapNotNull { (_, day) ->
                        val dayKr = when (day.dayOfWeek.trim().lowercase()) {
                            "monday", "mon", "월", "월요일" -> "월"
                            "tuesday", "tue", "화", "화요일" -> "화"
                            "wednesday", "wed", "수", "수요일" -> "수"
                            "thursday", "thu", "목", "목요일" -> "목"
                            "friday", "fri", "금", "금요일" -> "금"
                            else -> return@mapNotNull null
                        }
                        val valid = day.periods.filterNotNull()
                            .filter { it.startTime.isNotBlank() && it.endTime.isNotBlank() }
                        if (valid.isEmpty()) return@mapNotNull null
                        "$dayKr ${valid.minOf { it.startTime }} ~ ${valid.maxOf { it.endTime }}"
                    }
                    .joinToString(", ")
                locationText = subject.schedule.values.firstOrNull { it.location.isNotBlank() }?.location.orEmpty()
            }

            holder.tvTime.text = timeText
            holder.tvLocation.text = locationText
            holder.tvCode.text = subject.subjectCode

            holder.itemView.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    scheduleHighlight(subject.subjectCode)
                    viewPager?.currentItem = pos
                    dotsContainer?.let { scheduleUpdateDots(it, pos) }
                }
            }
        }
    }

    private fun loadMyPage(pageView: View) {
        FirebaseClient.get("Users/$userId") { userJson ->
            val name = userJson?.optString("name", "").orEmpty()
            val id = userJson?.optString("userId", userId).orEmpty()
            val portal = userJson?.optString("portalID", portalId).orEmpty()
            val type = userJson?.optString("userType", userRole).orEmpty()

            runOnUiThread {
                setTextByName(pageView, "tvUserName", name)
                setTextByName(pageView, "tvUserId", id)
                setTextByName(pageView, "tvPortalId", portal)
                setTextByName(pageView, "tvUserType", type)
                setTextByName(pageView, "tvStudentName", name)
                setTextByName(pageView, "tvStudentInfo", id)
                setTextByName(pageView, "tvProfessorName", name)
            }
        }
    }

    // ───────── 주간 출석 페이지 상태 ─────────
    private var weekCalendar: Calendar = Calendar.getInstance(Locale.KOREA)
    private var weekSelectedDateStr: String = ""   // "yyyy-MM-dd"

    private fun loadWeekPage(pageView: View) {
        // 오늘 날짜를 기준으로 초기화
        weekCalendar = Calendar.getInstance(Locale.KOREA)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(weekCalendar.time)
        weekSelectedDateStr = today

        setupWeekCalendar(pageView)
    }

    /**
     * 달력 버튼(이전주/다음주)과 요일셀 클릭을 연결하고
     * 현재 weekCalendar 기준 주를 렌더링한다.
     */
    private fun setupWeekCalendar(pageView: View) {
        renderWeekDates(pageView)

        pageView.findViewById<View?>(R.id.btnPrevWeek)?.setOnClickListener {
            weekCalendar.add(Calendar.WEEK_OF_YEAR, -1)
            renderWeekDates(pageView)
            // 이전 주 이동 후 선택 날짜가 이번 주에 없으면 월요일로 리셋
            loadAttendanceForDate(pageView, weekSelectedDateStr)
        }

        pageView.findViewById<View?>(R.id.btnNextWeek)?.setOnClickListener {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date())
            // 오늘 포함 이전 주만 이동 가능
            val cal = Calendar.getInstance(Locale.KOREA)
            cal.time = weekCalendar.time
            cal.add(Calendar.WEEK_OF_YEAR, 1)
            val nextWeekSunday = cal.apply {
                set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
            }.time
            val nextSundayStr = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(nextWeekSunday)
            if (nextSundayStr > today) {
                // 미래 주 이동 불가
                return@setOnClickListener
            }
            weekCalendar.add(Calendar.WEEK_OF_YEAR, 1)
            renderWeekDates(pageView)
            loadAttendanceForDate(pageView, weekSelectedDateStr)
        }
    }

    /**
     * weekCalendar 기준으로 일~토 날짜를 셀에 표시하고 클릭 리스너 등록.
     * 오늘 이후 날짜는 미래이므로 클릭 불가(흐리게).
     */
    private fun renderWeekDates(pageView: View) {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date())
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)
        val dayFmt = SimpleDateFormat("d", Locale.KOREA)
        val monthFmt = SimpleDateFormat("yyyy년 M월", Locale.KOREA)

        val cal = Calendar.getInstance(Locale.KOREA)
        cal.time = weekCalendar.time
        cal.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)

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
            val dayNum = dayFmt.format(cal.time)
            val isFuture = dateStr > today
            val isToday = dateStr == today
            val isSelected = dateStr == weekSelectedDateStr

            val container = pageView.findViewById<LinearLayout?>(containerId)
            val tv = pageView.findViewById<TextView?>(tvId)

            tv?.text = dayNum

            when {
                isSelected && isToday -> {
                    tv?.background = resources.getDrawable(R.drawable.bg_date_selected_blue, null)
                    tv?.setTextColor(Color.parseColor("#004B83"))
                }
                isSelected -> {
                    tv?.background = resources.getDrawable(R.drawable.bg_date_selected_blue, null)
                    tv?.setTextColor(Color.parseColor("#004B83"))
                }
                isToday -> {
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

    /**
     * 선택된 날짜(dateStr "yyyy-MM-dd")에 해당하는 요일의 수업 목록을 JSON에서 구하고,
     * 각 수업의 출결 기록을 Attendance_Records에서 확인해 표시한다.
     * 출결 기록이 없으면 "결석"으로 처리한다.
     */
    private fun loadAttendanceForDate(pageView: View, dateStr: String) {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date())
        val cal = Calendar.getInstance(Locale.KOREA)
        cal.time = fmt.parse(dateStr) ?: Date()
        val isToday = dateStr == today
        val nowMinute = Calendar.getInstance(Locale.KOREA).let {
            it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
        }

        val dayOfWeekEng = when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SUNDAY    -> "Sunday"
            Calendar.MONDAY    -> "Monday"
            Calendar.TUESDAY   -> "Tuesday"
            Calendar.WEDNESDAY -> "Wednesday"
            Calendar.THURSDAY  -> "Thursday"
            Calendar.FRIDAY    -> "Friday"
            Calendar.SATURDAY  -> "Saturday"
            else -> ""
        }
        val dayOfWeekKr = when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SUNDAY    -> "일"
            Calendar.MONDAY    -> "월"
            Calendar.TUESDAY   -> "화"
            Calendar.WEDNESDAY -> "수"
            Calendar.THURSDAY  -> "목"
            Calendar.FRIDAY    -> "금"
            Calendar.SATURDAY  -> "토"
            else -> ""
        }

        val displayFmt = SimpleDateFormat("M월 d일", Locale.KOREA)
        pageView.findViewById<TextView?>(R.id.tvSelectedDate)?.text =
            "${displayFmt.format(cal.time)} ($dayOfWeekKr)"

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

                    for (code in enrolledCodes) {
                        firebaseDb.child("Subjects").child(code)
                            .addListenerForSingleValueEvent(object : ValueEventListener {
                                override fun onDataChange(subjectSnap: DataSnapshot) {
                                    subjectCache[code] = subjectSnap
                                    subjectDone++
                                    if (subjectDone < enrolledCodes.size) return

                                    data class ClassEntry(
                                        val code: String, val name: String,
                                        val timeRange: String, val startTime: String,
                                        val endMinute: Int
                                    )
                                    val classesOnDay = mutableListOf<ClassEntry>()

                                    for (c in enrolledCodes) {
                                        val snap = subjectCache[c] ?: continue
                                        val rawName = snap.child("subjectName").getValue(String::class.java) ?: ""
                                        val name = cleanSubjectName(rawName)
                                        snap.child("schedule").children.forEach { daySnap ->
                                            val dow = daySnap.child("dayOfWeek").getValue(String::class.java) ?: ""
                                            if (!dow.equals(dayOfWeekEng, ignoreCase = true)) return@forEach
                                            var startTime = ""; var endTime = ""
                                            daySnap.child("periods").children.forEach { p ->
                                                val st = p.child("startTime").getValue(String::class.java) ?: ""
                                                val et = p.child("endTime").getValue(String::class.java) ?: ""
                                                if (st.isNotBlank() && startTime.isBlank()) startTime = st
                                                if (et.isNotBlank()) endTime = et
                                            }
                                            if (startTime.isBlank()) return@forEach
                                            val endMin = scheduleTimeToMinute(endTime)
                                            // 오늘이면 완전히 끝난 수업만, 과거면 모두 표시
                                            if (isToday && nowMinute <= endMin) return@forEach
                                            classesOnDay.add(ClassEntry(c, name, "$startTime - $endTime", startTime, endMin))
                                        }
                                    }

                                    classesOnDay.sortBy { it.startTime }

                                    if (classesOnDay.isEmpty()) {
                                        showWeekMessage(pageView, "해당 날짜에 수업이 없습니다")
                                        return
                                    }

                                    // 출결 + UWB 병렬 fetch
                                    val statusCache = mutableMapOf<String, String>()
                                    val uwbCache = mutableMapOf<String, List<Pair<String, Boolean>>>()
                                    var recordDone = 0
                                    val recordTotal = classesOnDay.size * 2  // 출결 + UWB 각각

                                    fun tryRenderAll() {
                                        if (recordDone < recordTotal) return
                                        // 결석 있으면 해당 날짜 TV에 빨간 테두리
                                        val hasAbsent = statusCache.values.any { it == "결석" }
                                        updateDateCellBorder(pageView, dateStr, hasAbsent)
                                        renderWeekListWithUwb(pageView, classesOnDay.map {
                                            Pair(
                                                Triple(it.name, it.timeRange, statusCache[it.code] ?: "결석"),
                                                uwbCache[it.code] ?: emptyList()
                                            )
                                        })
                                    }

                                    for (entry in classesOnDay) {
                                        // 출결 기록
                                        firebaseDb.child("Attendance_Records")
                                            .child(entry.code).child(dateStr).child(userId)
                                            .addListenerForSingleValueEvent(object : ValueEventListener {
                                                override fun onDataChange(snap: DataSnapshot) {
                                                    val raw = snap.child("finalStatus").getValue(String::class.java) ?: ""
                                                    statusCache[entry.code] = if (raw.isBlank()) "결석"
                                                    else normalizeAttendanceStatus(raw).ifBlank { "결석" }
                                                    recordDone++
                                                    tryRenderAll()
                                                }
                                                override fun onCancelled(e: DatabaseError) {
                                                    statusCache[entry.code] = "결석"
                                                    recordDone++
                                                    tryRenderAll()
                                                }
                                            })
                                        // UWB 로그
                                        firebaseDb.child("UWB_Logs")
                                            .child(entry.code).child(dateStr).child(userId)
                                            .addListenerForSingleValueEvent(object : ValueEventListener {
                                                override fun onDataChange(snap: DataSnapshot) {
                                                    val logs = snap.children.mapNotNull { entry2 ->
                                                        val time = entry2.child("timestamp").getValue(String::class.java) ?: ""
                                                        val detected = entry2.child("isDetected").getValue(Boolean::class.java) ?: false
                                                        if (time.isNotBlank()) Pair(time, detected) else null
                                                    }.sortedBy { it.first }
                                                    uwbCache[entry.code] = logs
                                                    recordDone++
                                                    tryRenderAll()
                                                }
                                                override fun onCancelled(e: DatabaseError) {
                                                    uwbCache[entry.code] = emptyList()
                                                    recordDone++
                                                    tryRenderAll()
                                                }
                                            })
                                    }
                                }
                                override fun onCancelled(e: DatabaseError) { subjectDone++ }
                            })
                    }
                }
                override fun onCancelled(e: DatabaseError) {
                    showWeekMessage(pageView, "데이터를 불러오지 못했습니다")
                }
            })
    }

    /** 날짜 셀 테두리 업데이트 (결석=빨강, 오늘=파랑) */
    private fun updateDateCellBorder(pageView: View, dateStr: String, hasAbsent: Boolean) {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date())
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)
        val cal = Calendar.getInstance(Locale.KOREA)
        cal.time = weekCalendar.time
        cal.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)

        val tvIds = listOf(R.id.tvSunDate, R.id.tvMonDate, R.id.tvTueDate,
            R.id.tvWedDate, R.id.tvThuDate, R.id.tvFriDate, R.id.tvSatDate)

        for (tvId in tvIds) {
            val d = fmt.format(cal.time)
            if (d == dateStr) {
                val tv = pageView.findViewById<TextView?>(tvId)
                val isToday = d == today
                when {
                    hasAbsent && isToday -> tv?.background = resources.getDrawable(R.drawable.bg_date_warning_red, null)
                    hasAbsent -> tv?.background = resources.getDrawable(R.drawable.bg_date_warning_red, null)
                    isToday   -> tv?.background = resources.getDrawable(R.drawable.bg_date_selected_blue, null)
                    else      -> tv?.background = resources.getDrawable(R.drawable.bg_date_selected_blue, null)
                }
                break
            }
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
    }

    /** UWB 로그 포함 출결 리스트 렌더링 (슬라이드 애니메이션) */
    private fun renderWeekListWithUwb(
        pageView: View,
        items: List<Pair<Triple<String, String, String>, List<Pair<String, Boolean>>>>
    ) {
        val container = pageView.findViewById<LinearLayout?>(R.id.listContainer) ?: return
        while (container.childCount > 1) container.removeViewAt(1)

        for ((info, uwbLogs) in items) {
            val (name, timeRange, status) = info
            val color = when (status) {
                "출석" -> attendanceBlue
                "지각" -> latePurple
                else   -> absentRed
            }
            val iconRes = when (status) {
                "출석" -> R.drawable.attendanceweek
                "지각" -> R.drawable.lateweek
                else   -> R.drawable.absentweek
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
            item.findViewById<android.widget.ImageView?>(R.id.ivStatusIcon)?.apply {
                setImageResource(iconRes)
                visibility = View.VISIBLE
            }

            val detailArea = item.findViewById<LinearLayout?>(R.id.detailArea)
            val detailRows = item.findViewById<LinearLayout?>(R.id.detailRowsContainer)
            val rowMain = item.findViewById<LinearLayout?>(R.id.rowMain)
            val btnCollapse = item.findViewById<TextView?>(R.id.btnCollapse)

            if (detailRows != null && detailArea != null) {
                detailRows.removeAllViews()

                if (uwbLogs.isEmpty()) {
                    // UWB 실행 안 됨
                    detailRows.addView(TextView(this@MainActivity).apply {
                        text = "UWB 실행이 되지 않았습니다"
                        textSize = 12f
                        setTextColor(Color.parseColor("#888888"))
                        setPadding(0, 4, 0, 4)
                    })
                } else {
                    // UWB 실행됨 - 각 로그 표시
                    for ((time, detected) in uwbLogs) {
                        val row = LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            setPadding(0, 3, 0, 3)
                        }
                        val tvTime = TextView(this@MainActivity).apply {
                            text = time
                            textSize = 12f
                            setTextColor(Color.parseColor("#444444"))
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        }
                        val tvDetected = TextView(this@MainActivity).apply {
                            // UWB 감지됨 = 출석, 미감지 = 미출석
                            text = if (detected) "출석" else "미출석"
                            textSize = 12f
                            setTextColor(
                                if (detected) Color.parseColor("#004B83")
                                else Color.parseColor("#888888")
                            )
                        }
                        row.addView(tvTime)
                        row.addView(tvDetected)
                        detailRows.addView(row)
                    }
                }

                // 슬라이드 토글
                rowMain?.setOnClickListener {
                    if (detailArea.visibility == View.GONE) {
                        // 펼치기: 슬라이드 다운
                        detailArea.visibility = View.VISIBLE
                        detailArea.measure(
                            View.MeasureSpec.makeMeasureSpec(detailArea.width, View.MeasureSpec.AT_MOST),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                        )
                        val targetH = detailArea.measuredHeight
                        detailArea.layoutParams.height = 0
                        detailArea.requestLayout()
                        val anim = android.animation.ValueAnimator.ofInt(0, targetH).apply {
                            duration = 220
                            addUpdateListener {
                                detailArea.layoutParams.height = it.animatedValue as Int
                                detailArea.requestLayout()
                            }
                            addListener(object : android.animation.AnimatorListenerAdapter() {
                                override fun onAnimationEnd(a: android.animation.Animator) {
                                    detailArea.layoutParams.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                                }
                            })
                        }
                        anim.start()
                    } else {
                        // 접기: 슬라이드 업
                        val initH = detailArea.height
                        val anim = android.animation.ValueAnimator.ofInt(initH, 0).apply {
                            duration = 180
                            addUpdateListener {
                                detailArea.layoutParams.height = it.animatedValue as Int
                                detailArea.requestLayout()
                            }
                            addListener(object : android.animation.AnimatorListenerAdapter() {
                                override fun onAnimationEnd(a: android.animation.Animator) {
                                    detailArea.visibility = View.GONE
                                    detailArea.layoutParams.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                                }
                            })
                        }
                        anim.start()
                    }
                }
                btnCollapse?.setOnClickListener { rowMain?.performClick() }
            }

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

    private fun showMainNoClass(pageView: View) {
        // 상단 카드: "출결 해당 수업 없음"
        setTextIfExists(pageView, R.id.tvClassName, "출결 해당 수업 없음")
        setTextIfExists(pageView, R.id.tvClassTime, "")
        setTextIfExists(pageView, R.id.tvAttendanceStatus, "현재 시간에 해당하는 수업이 없습니다")
        pageView.findViewById<android.widget.ImageView?>(R.id.ivCheckIcon)?.setImageResource(R.drawable.maingray)
        // 하단 카드
        setTextIfExists(pageView, R.id.tvDetailClassName, "출결 해당 수업 없음")
        setTextIfExists(pageView, R.id.tvDetailProfessor, "")
        setTextIfExists(pageView, R.id.tvDetailTime, "현재 시간에 해당하는 수업 정보가 이곳에 표시됩니다")
        setTextIfExists(pageView, R.id.tvDetailRoom, "")
        setTextIfExists(pageView, R.id.tvPeriod, "")
    }


    private fun loadNoticePage(pageView: View) {
        FirebaseClient.get("Class_Notices") { noticesRoot ->
            var title = ""
            var content = ""
            var date = ""

            if (noticesRoot != null) {
                val subjectKeys = noticesRoot.keys()

                while (subjectKeys.hasNext()) {
                    val subjectCode = subjectKeys.next()
                    val noticesBySubject = noticesRoot.optJSONObject(subjectCode) ?: continue
                    val noticeKeys = noticesBySubject.keys()

                    while (noticeKeys.hasNext()) {
                        val noticeObject = noticesBySubject.optJSONObject(noticeKeys.next()) ?: continue

                        title = noticeObject.optString("title", "")
                        content = noticeObject.optString("content", "")
                        date = noticeObject.optString("targetDate", "")

                        break
                    }

                    if (title.isNotBlank() || content.isNotBlank()) {
                        break
                    }
                }
            }

            runOnUiThread {
                setTextByName(pageView, "tvNoticeTitle", title)
                setTextByName(pageView, "tvNoticeContent", content)
                setTextByName(pageView, "tvNoticeDate", date)
                setTextByName(pageView, "tvTitle", title)
                setTextByName(pageView, "tvContent", content)
            }
        }
    }

    private fun loadCancelPage(pageView: View) {
        FirebaseClient.get("Class_Notices") { noticesRoot ->
            var title = ""
            var content = ""
            var date = ""

            if (noticesRoot != null) {
                val subjectKeys = noticesRoot.keys()

                while (subjectKeys.hasNext()) {
                    val subjectCode = subjectKeys.next()
                    val noticesBySubject = noticesRoot.optJSONObject(subjectCode) ?: continue
                    val noticeKeys = noticesBySubject.keys()

                    while (noticeKeys.hasNext()) {
                        val noticeObject = noticesBySubject.optJSONObject(noticeKeys.next()) ?: continue
                        val type = noticeObject.optString("type", "")

                        if (type == "CANCELED" || type == "CANCEL" || type == "휴강") {
                            title = noticeObject.optString("title", "")
                            content = noticeObject.optString("content", "")
                            date = noticeObject.optString("targetDate", "")
                            break
                        }
                    }

                    if (title.isNotBlank() || content.isNotBlank()) {
                        break
                    }
                }
            }

            runOnUiThread {
                setTextByName(pageView, "tvCancelTitle", title)
                setTextByName(pageView, "tvCancelContent", content)
                setTextByName(pageView, "tvCancelDate", date)
                setTextByName(pageView, "tvTitle", title)
                setTextByName(pageView, "tvContent", content)
            }
        }
    }

    private fun loadConfirmPage(pageView: View) {
        FirebaseClient.get("Absence_Requests") { absenceRoot ->
            var absenceType = ""
            var content = ""
            var status = ""
            var date = ""

            if (absenceRoot != null) {
                val subjectKeys = absenceRoot.keys()

                outer@ while (subjectKeys.hasNext()) {
                    val subjectCode = subjectKeys.next()
                    val dateObject = absenceRoot.optJSONObject(subjectCode) ?: continue
                    val dateKeys = dateObject.keys()

                    while (dateKeys.hasNext()) {
                        val dateKey = dateKeys.next()
                        val studentsObject = dateObject.optJSONObject(dateKey) ?: continue
                        val requestObject = studentsObject.optJSONObject(userId) ?: continue

                        date = dateKey
                        absenceType = requestObject.optString("absenceType", "")
                        content = requestObject.optString("content", "")
                        status = requestObject.optString("status", "")

                        break@outer
                    }
                }
            }

            runOnUiThread {
                setTextByName(pageView, "tvAbsenceType", absenceType)
                setTextByName(pageView, "tvAbsenceContent", content)
                setTextByName(pageView, "tvAbsenceStatus", status)
                setTextByName(pageView, "tvAbsenceDate", date)
            }
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
                        val rawSubjectName = subjectJson?.optString("subjectName", "").orEmpty()
                        val subjectName = cleanSubjectName(rawSubjectName)

                        if (subjectName.isNotBlank()) {
                            val stat = getAttendanceStatBySubject(recordsRoot, subjectCode, userId)
                            subjectItems.add(
                                SubjectAttendanceItem(
                                    subjectCode,
                                    subjectName,
                                    stat
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
        if (enrollmentJson == null) {
            return emptyList()
        }

        val result = mutableListOf<String>()
        val keys = enrollmentJson.keys()

        while (keys.hasNext()) {
            val code = keys.next()
            val isEnrolled = enrollmentJson.optBoolean(code, false)

            if (isEnrolled) {
                result.add(code)
            }
        }

        return result.sorted()
    }

    private fun getFirstEnrolledSubjectCode(enrollmentJson: JSONObject?): String {
        return getEnrolledSubjectCodes(enrollmentJson).firstOrNull().orEmpty()
    }

    private fun getAttendanceStatBySubject(
        recordsRoot: JSONObject?,
        subjectCode: String,
        targetUserId: String
    ): AttendanceStat {
        if (recordsRoot == null) {
            return AttendanceStat()
        }

        val subjectRecordJson = recordsRoot.optJSONObject(subjectCode) ?: return AttendanceStat()

        var present = 0
        var late = 0
        var absent = 0

        val dateKeys = subjectRecordJson.keys()

        while (dateKeys.hasNext()) {
            val dateObject = subjectRecordJson.optJSONObject(dateKeys.next()) ?: continue
            val userRecord = dateObject.optJSONObject(targetUserId) ?: continue

            when (normalizeAttendanceStatus(userRecord.optString("finalStatus", ""))) {
                "출석" -> present++
                "지각" -> late++
                "결석" -> absent++
            }
        }

        return AttendanceStat(present, late, absent)
    }

    private fun getTotalAttendanceStat(
        recordsRoot: JSONObject?,
        targetUserId: String
    ): AttendanceStat {
        if (recordsRoot == null) {
            return AttendanceStat()
        }

        var present = 0
        var late = 0
        var absent = 0

        val subjectKeys = recordsRoot.keys()

        while (subjectKeys.hasNext()) {
            val subjectObject = recordsRoot.optJSONObject(subjectKeys.next()) ?: continue
            val dateKeys = subjectObject.keys()

            while (dateKeys.hasNext()) {
                val dateObject = subjectObject.optJSONObject(dateKeys.next()) ?: continue
                val userRecord = dateObject.optJSONObject(targetUserId) ?: continue

                when (normalizeAttendanceStatus(userRecord.optString("finalStatus", ""))) {
                    "출석" -> present++
                    "지각" -> late++
                    "결석" -> absent++
                }
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
                tvRate.setTextColor(mainBlue)
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

        val firstSubject = subjectItems.firstOrNull { it.stat.total > 0 }
            ?: subjectItems.firstOrNull()

        pageView.findViewById<TextView?>(R.id.tvSelectedClassName)?.text =
            firstSubject?.subjectName.orEmpty()

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
        tvTotalRate?.setTextColor(attendanceBlue)

        tvTotalLate?.text = "${calculateRate(totalLate, total)}%"
        tvTotalLate?.setTextColor(latePurple)

        tvTotalAbsent?.text = "${calculateRate(totalAbsent, total)}%"
        tvTotalAbsent?.setTextColor(absentRed)
    }

    private fun normalizeAttendanceStatus(status: String): String {
        return when (status.trim().uppercase()) {
            "출석", "출석 완료", "PRESENT", "ATTENDANCE", "ATTENDED" -> "출석"
            "지각", "LATE" -> "지각"
            "결석", "ABSENT", "ABSENCE" -> "결석"
            else -> ""
        }
    }

    private fun calculateRate(value: Int, total: Int): Int {
        if (total <= 0) {
            return 0
        }

        return ((value.toFloat() / total.toFloat()) * 100f).roundToInt()
    }

    private fun cleanSubjectName(subjectName: String): String {
        return subjectName
            .replace(" (영어강의)", "")
            .replace(" (실시간화상강의)", "")
            .replace("(영어강의)", "")
            .replace("(실시간화상강의)", "")
            .trim()
    }

    private fun makeScheduleText(subjectJson: JSONObject?): String {
        val scheduleObj = subjectJson?.optJSONObject("schedule") ?: return ""
        val result = mutableListOf<String>()
        val dayKeys = scheduleObj.keys()

        while (dayKeys.hasNext()) {
            val dayObj = scheduleObj.optJSONObject(dayKeys.next()) ?: continue

            val dayKr = when (dayObj.optString("dayOfWeek", "").uppercase()) {
                "MONDAY" -> "월"
                "TUESDAY" -> "화"
                "WEDNESDAY" -> "수"
                "THURSDAY" -> "목"
                "FRIDAY" -> "금"
                "SATURDAY" -> "토"
                "SUNDAY" -> "일"
                else -> dayObj.optString("dayOfWeek", "")
            }

            val periodsArr = dayObj.optJSONArray("periods")

            var startTime = ""
            var endTime = ""

            if (periodsArr != null) {
                for (i in 0 until periodsArr.length()) {
                    val period = periodsArr.optJSONObject(i) ?: continue
                    val st = period.optString("startTime", "")
                    val ed = period.optString("endTime", "")

                    if (st.isNotBlank() && startTime.isBlank()) {
                        startTime = st
                    }

                    if (ed.isNotBlank()) {
                        endTime = ed
                    }
                }
            }

            if (dayKr.isNotBlank() && startTime.isNotBlank() && endTime.isNotBlank()) {
                result.add("$dayKr $startTime-$endTime")
            }
        }

        return result.joinToString(", ")
    }

    private fun getFirstLocation(subjectJson: JSONObject?): String {
        val scheduleObj = subjectJson?.optJSONObject("schedule") ?: return ""
        val dayKeys = scheduleObj.keys()

        while (dayKeys.hasNext()) {
            val location = scheduleObj.optJSONObject(dayKeys.next())
                ?.optString("location", "")
                ?: continue

            if (location.isNotBlank()) {
                return location
            }
        }

        return ""
    }

    private fun getTodayText(): String {
        return SimpleDateFormat("yyyy.MM.dd", Locale.KOREA).format(Date())
    }

    private fun setTextIfExists(pageView: View, id: Int, value: String) {
        pageView.findViewById<TextView?>(id)?.text = value
    }

    private fun setTextByName(pageView: View, idName: String, value: String) {
        val id = resources.getIdentifier(idName, "id", packageName)

        if (id != 0) {
            pageView.findViewById<TextView?>(id)?.text = value
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    data class SubjectAttendanceItem(
        val subjectCode: String,
        val subjectName: String,
        val stat: AttendanceStat
    )

    data class AttendanceStat(
        val present: Int = 0,
        val late: Int = 0,
        val absent: Int = 0
    ) {
        val total: Int
            get() = present + late + absent

        val presentRate: Int
            get() = if (total <= 0) 0 else ((present.toFloat() / total) * 100f).roundToInt()

        val lateRate: Int
            get() = if (total <= 0) 0 else ((late.toFloat() / total) * 100f).roundToInt()

        val absentRate: Int
            get() = if (total <= 0) 0 else ((absent.toFloat() / total) * 100f).roundToInt()
    }
}