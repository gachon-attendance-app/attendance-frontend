package com.example.myapplication

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.GridLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import org.json.JSONObject
import kotlin.math.roundToInt

class AllAttendanceActivity : ComponentActivity() {

    private val defaultStudentId = "202234920"

    private lateinit var gridAttendanceRate: GridLayout
    private lateinit var tvSelectedClassName: TextView
    private lateinit var tvLectureProgress: TextView
    private lateinit var tvTotalAttendanceRate: TextView
    private lateinit var tvTotalLateRate: TextView
    private lateinit var tvTotalAbsentRate: TextView

    private var loginInputId: String = ""
    private var loginPortalId: String = ""
    private var targetStudentId: String = ""

    private var rootJson: JSONObject? = null
    private var rootListener: ValueEventListener? = null

    private val attendanceBlue = Color.parseColor("#0281F6")
    private val absentRed = Color.parseColor("#E53935")
    private val latePurple = Color.parseColor("#9C27B0")
    private val mainBlue = Color.parseColor("#004B83")
    private val grayTextColor = Color.parseColor("#777777")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.all_attendance)

        bindViews()
        readLoginInfo()
        initButtons()
        startRealtimeDatabaseListener()
    }

    override fun onDestroy() {
        super.onDestroy()

        rootListener?.let {
            FirebaseDatabase.getInstance().reference.removeEventListener(it)
        }
    }

    private fun bindViews() {
        gridAttendanceRate = findViewById(R.id.gridAttendanceRate)
        tvSelectedClassName = findViewById(R.id.tvSelectedClassName)
        tvLectureProgress = findViewById(R.id.tvLectureProgress)
        tvTotalAttendanceRate = findViewById(R.id.tvTotalAttendanceRate)
        tvTotalLateRate = findViewById(R.id.tvTotalLateRate)
        tvTotalAbsentRate = findViewById(R.id.tvTotalAbsentRate)
    }

    private fun readLoginInfo() {
        val pref = getSharedPreferences("LOGIN_INFO", MODE_PRIVATE)
        val loginPref = getSharedPreferences("login_pref", MODE_PRIVATE)

        loginInputId = intent.getStringExtra("studentId")
            ?: pref.getString("userId", "")
                    ?: loginPref.getString("userId", "")
                    ?: ""

        loginPortalId = pref.getString("portalID", "")
            ?: pref.getString("portalId", "")
                    ?: pref.getString("loginId", "")
                    ?: loginPref.getString("portalID", "")
                    ?: loginPref.getString("portalId", "")
                    ?: loginPref.getString("loginId", "")
                    ?: ""

        targetStudentId = loginInputId.ifBlank { defaultStudentId }
    }

    private fun initButtons() {
        findViewById<View?>(R.id.btnBottomHome)?.setOnClickListener {
            finish()
        }

        findViewById<View?>(R.id.btnBottomRefresh)?.setOnClickListener {
            startRealtimeDatabaseListener()
            Toast.makeText(this, "새로고침되었습니다", Toast.LENGTH_SHORT).show()
        }

        findViewById<View?>(R.id.btnBottomNotice)?.setOnClickListener {
            finish()
        }

        findViewById<View?>(R.id.btnBottomSchedule)?.setOnClickListener {
            finish()
        }

        findViewById<View?>(R.id.btnBottomLogout)?.setOnClickListener {
            getSharedPreferences("LOGIN_INFO", MODE_PRIVATE)
                .edit()
                .clear()
                .apply()

            getSharedPreferences("login_pref", MODE_PRIVATE)
                .edit()
                .clear()
                .apply()

            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            finish()
        }
    }

    private fun startRealtimeDatabaseListener() {
        rootListener?.let {
            FirebaseDatabase.getInstance().reference.removeEventListener(it)
        }

        clearScreen()
        showEmptyGridMessage("데이터를 불러오는 중입니다.")

        rootListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val newRootJson = snapshotToJSONObject(snapshot)
                rootJson = newRootJson

                targetStudentId = resolveStudentId(newRootJson)

                val enrollmentJson = findEnrollmentObject(newRootJson, targetStudentId)
                val subjectsJson = newRootJson.optJSONObject("Subjects")
                val attendanceRecordsJson = newRootJson.optJSONObject("Attendance_Records")

                val enrolledSubjectIds = getEnrolledSubjectIds(
                    enrollmentJson = enrollmentJson,
                    attendanceRecordsJson = attendanceRecordsJson,
                    studentId = targetStudentId
                )

                val subjectItems = enrolledSubjectIds.mapNotNull { subjectId ->
                    val subjectJson = subjectsJson?.optJSONObject(subjectId)

                    val subjectName = cleanSubjectName(
                        subjectJson?.optString("subjectName", "")
                            ?: subjectJson?.optString("name", "")
                            ?: subjectJson?.optString("courseName", "")
                            ?: subjectId
                    )

                    if (subjectName.isBlank()) {
                        null
                    } else {
                        val stat = getSubjectAttendanceStat(
                            subjectId = subjectId,
                            studentId = targetStudentId,
                            attendanceRecordsJson = attendanceRecordsJson
                        )

                        SubjectAttendanceUiItem(
                            subjectId = subjectId,
                            subjectName = subjectName,
                            stat = stat
                        )
                    }
                }

                renderSubjectGrid(subjectItems)
                renderTotalSummary(subjectItems)
            }

            override fun onCancelled(error: DatabaseError) {
                clearScreen()
                showEmptyGridMessage("출결 데이터를 불러오지 못했습니다.")

                Toast.makeText(
                    this@AllAttendanceActivity,
                    "Firebase 오류: ${error.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        FirebaseDatabase.getInstance().reference.addValueEventListener(rootListener!!)
    }

    private fun snapshotToJSONObject(snapshot: DataSnapshot): JSONObject {
        val jsonObject = JSONObject()

        for (child in snapshot.children) {
            val value = snapshotValueToAny(child)
            if (value != null) {
                jsonObject.put(child.key ?: "", value)
            }
        }

        return jsonObject
    }

    private fun snapshotValueToAny(snapshot: DataSnapshot): Any? {
        if (!snapshot.hasChildren()) {
            return snapshot.value
        }

        val obj = JSONObject()

        for (child in snapshot.children) {
            val value = snapshotValueToAny(child)
            if (value != null) {
                obj.put(child.key ?: "", value)
            }
        }

        return obj
    }

    private fun resolveStudentId(root: JSONObject?): String {
        if (root == null) return loginInputId.ifBlank { defaultStudentId }

        val enrollmentRoot = root.optJSONObject("Enrollment")
        val usersRoot = root.optJSONObject("Users")

        if (loginInputId.isNotBlank()) {
            if (enrollmentRoot?.has(loginInputId) == true) {
                return loginInputId
            }

            if (hasAttendanceRecordForStudent(root, loginInputId)) {
                return loginInputId
            }
        }

        if (usersRoot != null) {
            val keys = usersRoot.keys()

            while (keys.hasNext()) {
                val key = keys.next()
                val userObject = usersRoot.optJSONObject(key) ?: continue

                val dbUserId = userObject.optString("userId", key)
                val dbPortalId = userObject.optString("portalID", "")
                val dbLoginId = userObject.optString("loginId", "")
                val userType = userObject.optString("userType", "")

                val isStudent = userType.equals("student", true) ||
                        userType.equals("학생", true) ||
                        userType.isBlank()

                val matched =
                    loginInputId == key ||
                            loginInputId == dbUserId ||
                            loginInputId == dbPortalId ||
                            loginInputId == dbLoginId ||
                            loginPortalId == dbPortalId ||
                            loginPortalId == dbLoginId

                if (isStudent && matched) {
                    return dbUserId.ifBlank { key }
                }
            }
        }

        if (loginInputId.isNotBlank()) {
            return loginInputId
        }

        return defaultStudentId
    }

    private fun hasAttendanceRecordForStudent(root: JSONObject, studentId: String): Boolean {
        val recordsRoot = root.optJSONObject("Attendance_Records") ?: return false
        val subjectKeys = recordsRoot.keys()

        while (subjectKeys.hasNext()) {
            val subjectCode = subjectKeys.next()
            val subjectObject = recordsRoot.optJSONObject(subjectCode) ?: continue
            val dateKeys = subjectObject.keys()

            while (dateKeys.hasNext()) {
                val dateKey = dateKeys.next()
                val dateObject = subjectObject.optJSONObject(dateKey) ?: continue

                if (dateObject.has(studentId)) {
                    return true
                }
            }
        }

        return false
    }

    private fun findEnrollmentObject(root: JSONObject?, studentId: String): JSONObject? {
        if (root == null) return null

        val enrollmentRoot = root.optJSONObject("Enrollment")
        val directEnrollment = enrollmentRoot?.optJSONObject(studentId)
        if (directEnrollment != null) return directEnrollment

        val usersRoot = root.optJSONObject("Users")
        val userObject = usersRoot?.optJSONObject(studentId)

        return userObject?.optJSONObject("Enrollment")
            ?: userObject?.optJSONObject("enrollment")
            ?: userObject?.optJSONObject("enrollments")
            ?: userObject?.optJSONObject("subjects")
    }

    private fun clearScreen() {
        gridAttendanceRate.removeAllViews()

        tvSelectedClassName.text = ""
        tvLectureProgress.text = ""
        tvTotalAttendanceRate.text = ""
        tvTotalLateRate.text = ""
        tvTotalAbsentRate.text = ""
    }

    private fun getEnrolledSubjectIds(
        enrollmentJson: JSONObject?,
        attendanceRecordsJson: JSONObject?,
        studentId: String
    ): List<String> {
        val result = mutableSetOf<String>()

        if (enrollmentJson != null) {
            val keys = enrollmentJson.keys()

            while (keys.hasNext()) {
                val subjectId = keys.next()
                val value = enrollmentJson.opt(subjectId)

                val isEnrolled = when (value) {
                    is Boolean -> value
                    is JSONObject -> true
                    else -> value?.toString()?.equals("true", ignoreCase = true) == true ||
                            value?.toString()?.equals("1", ignoreCase = true) == true
                }

                if (isEnrolled) {
                    result.add(subjectId)
                }
            }
        }

        if (attendanceRecordsJson != null) {
            val subjectKeys = attendanceRecordsJson.keys()

            while (subjectKeys.hasNext()) {
                val subjectId = subjectKeys.next()
                val subjectObject = attendanceRecordsJson.optJSONObject(subjectId) ?: continue
                val dateKeys = subjectObject.keys()

                while (dateKeys.hasNext()) {
                    val dateKey = dateKeys.next()
                    val dateObject = subjectObject.optJSONObject(dateKey) ?: continue

                    if (dateObject.has(studentId)) {
                        result.add(subjectId)
                    }
                }
            }
        }

        return result.sorted()
    }

    private fun getSubjectAttendanceStat(
        subjectId: String,
        studentId: String,
        attendanceRecordsJson: JSONObject?
    ): AttendanceStat {
        if (attendanceRecordsJson == null) return AttendanceStat()

        val subjectRecordJson = attendanceRecordsJson.optJSONObject(subjectId) ?: return AttendanceStat()

        var attendance = 0
        var late = 0
        var absent = 0

        val dateKeys = subjectRecordJson.keys()

        while (dateKeys.hasNext()) {
            val dateKey = dateKeys.next()
            val dateObject = subjectRecordJson.optJSONObject(dateKey) ?: continue
            val studentRecordObject = dateObject.optJSONObject(studentId) ?: continue

            val status = studentRecordObject.optString(
                "finalStatus",
                studentRecordObject.optString("status", "")
            )

            when (normalizeStatus(status)) {
                "출석" -> attendance++
                "지각" -> late++
                "결석" -> absent++
            }
        }

        return AttendanceStat(
            attendance = attendance,
            late = late,
            absent = absent
        )
    }

    private fun renderSubjectGrid(items: List<SubjectAttendanceUiItem>) {
        gridAttendanceRate.removeAllViews()

        when {
            items.isEmpty() -> {
                showEmptyGridMessage("등록된 수업이 없습니다.")
                return
            }

            items.all { it.stat.totalCount <= 0 } -> {
                showEmptyGridMessage("출결 기록이 없습니다.")
            }
        }

        val inflater = LayoutInflater.from(this)

        for (item in items) {
            val view = inflater.inflate(R.layout.all_attendance_rate, gridAttendanceRate, false)

            val tvClassName = view.findViewById<TextView>(R.id.tvClassName)
            val tvAttendanceRate = view.findViewById<TextView>(R.id.tvAttendanceRate)
            val donutChart = view.findViewById<DonutChartView>(R.id.donutChart)

            tvClassName.text = item.subjectName

            if (item.stat.totalCount > 0) {
                tvAttendanceRate.text = "${item.stat.attendanceRate}%"
                tvAttendanceRate.setTextColor(mainBlue)
                donutChart.setData(
                    attendance = item.stat.attendance,
                    late = item.stat.late,
                    absent = item.stat.absent
                )
            } else {
                tvAttendanceRate.text = ""
                donutChart.clearData()
            }

            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = dpToPx(170)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(0, 0, 0, 0)
            }

            view.layoutParams = params
            gridAttendanceRate.addView(view)
        }
    }

    private fun showEmptyGridMessage(message: String) {
        gridAttendanceRate.removeAllViews()

        val emptyText = TextView(this).apply {
            text = message
            textSize = 15f
            setTextColor(grayTextColor)
            gravity = Gravity.CENTER
            setPadding(0, dpToPx(60), 0, dpToPx(60))
            layoutParams = GridLayout.LayoutParams().apply {
                width = GridLayout.LayoutParams.MATCH_PARENT
                height = GridLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(0, 3)
            }
        }

        gridAttendanceRate.addView(emptyText)
    }

    private fun renderTotalSummary(items: List<SubjectAttendanceUiItem>) {
        val totalAttendance = items.sumOf { it.stat.attendance }
        val totalLate = items.sumOf { it.stat.late }
        val totalAbsent = items.sumOf { it.stat.absent }
        val totalCount = totalAttendance + totalLate + totalAbsent

        val firstSubjectWithRecord = items.firstOrNull { it.stat.totalCount > 0 }
        val firstSubject = firstSubjectWithRecord ?: items.firstOrNull()

        tvSelectedClassName.text = firstSubject?.subjectName.orEmpty()

        tvLectureProgress.text = if (items.isEmpty()) {
            ""
        } else {
            "${items.count { it.stat.totalCount > 0 }}/${items.size}"
        }

        if (totalCount <= 0) {
            tvTotalAttendanceRate.text = ""
            tvTotalLateRate.text = ""
            tvTotalAbsentRate.text = ""
            return
        }

        val attendanceRate = percent(totalAttendance, totalCount)
        val lateRate = percent(totalLate, totalCount)
        val absentRate = percent(totalAbsent, totalCount)

        tvTotalAttendanceRate.text = "$attendanceRate%"
        tvTotalAttendanceRate.setTextColor(attendanceBlue)

        tvTotalLateRate.text = "$lateRate%"
        tvTotalLateRate.setTextColor(latePurple)

        tvTotalAbsentRate.text = "$absentRate%"
        tvTotalAbsentRate.setTextColor(absentRed)
    }

    private fun normalizeStatus(status: String): String {
        return when (status.trim().uppercase()) {
            "출석", "출석 완료", "PRESENT", "ATTENDANCE", "ATTENDED" -> "출석"
            "지각", "LATE" -> "지각"
            "결석", "ABSENT", "ABSENCE" -> "결석"
            else -> ""
        }
    }

    private fun percent(value: Int, total: Int): Int {
        if (total <= 0) return 0
        return ((value.toFloat() / total.toFloat()) * 100f).roundToInt()
    }

    private fun cleanSubjectName(name: String): String {
        return name
            .replace(" (영어강의)", "")
            .replace(" (실시간화상강의)", "")
            .replace("(영어강의)", "")
            .replace("(실시간화상강의)", "")
            .trim()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    data class SubjectAttendanceUiItem(
        val subjectId: String,
        val subjectName: String,
        val stat: AttendanceStat
    )

    data class AttendanceStat(
        val attendance: Int = 0,
        val late: Int = 0,
        val absent: Int = 0
    ) {
        val totalCount: Int
            get() = attendance + late + absent

        val attendanceRate: Int
            get() {
                if (totalCount <= 0) return 0
                return ((attendance.toFloat() / totalCount.toFloat()) * 100f).roundToInt()
            }
    }
}