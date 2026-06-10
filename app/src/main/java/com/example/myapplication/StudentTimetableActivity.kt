package com.example.myapplication

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.example.myapplication.model.domain.model.Subject
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class StudentTimetableActivity : AppCompatActivity() {

    // ── UI 요소 ────────────────────────────────────────────────────
    private lateinit var classBlockLayer: FrameLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var dotsContainer: LinearLayout

    // ── 데이터 ─────────────────────────────────────────────────────
    private val database = FirebaseDatabase.getInstance().reference
    private var userId: String = ""

    private val enrolledSubjects = mutableListOf<Subject>()

    /** subjectCode → 해당 블록 View 목록 */
    private val blockViewMap = mutableMapOf<String, MutableList<View>>()

    private var selectedSubjectCode: String? = null

    private val blockColors = listOf(
        "#8FA2C7", "#B9AAA5", "#79B2B8", "#A7B58D", "#C39DA4",
        "#9BB5A0", "#C4A882", "#7D9BB5", "#B5A89B", "#8EB5B5"
    )

    /** 그리드 시작 시각: 9시 */
    private val GRID_START_HOUR = 9

    /** 한 시간 높이(dp) — XML TableRow height(48dp)와 동일 */
    private val ONE_HOUR_DP = 48f

    // ── onCreate ───────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_student_timetable)

        classBlockLayer = findViewById(R.id.classBlockLayer)
        viewPager       = findViewById(R.id.viewPagerSubjectCards)
        dotsContainer   = findViewById(R.id.dotsContainer)

        // SharedPreferences에서 userId 읽기
        userId = getSharedPreferences("LOGIN_INFO", Context.MODE_PRIVATE)
            .getString("userId", "") ?: ""

        loadEnrollment()

        // 카드 스와이프 → 해당 블록 하이라이트
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (position in enrolledSubjects.indices) {
                    highlightSubject(enrolledSubjects[position].subjectCode)
                    updateDots(position)
                }
            }
        })
    }

    // ── Firebase: Enrollment 실시간 구독 ──────────────────────────
    private fun loadEnrollment() {
        if (userId.isBlank()) return

        database.child("Enrollment").child(userId)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val codes = snapshot.children
                        .filter { it.getValue(Boolean::class.java) == true }
                        .mapNotNull { it.key }
                    loadSubjects(codes)
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    // ── Firebase: Subjects 단건 조회 ──────────────────────────────
    private fun loadSubjects(codes: List<String>) {
        if (codes.isEmpty()) { enrolledSubjects.clear(); refreshUI(); return }

        val loaded = mutableListOf<Subject>()
        var pending = codes.size

        codes.forEach { code ->
            database.child("Subjects").child(code)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snap: DataSnapshot) {
                        snap.getValue(Subject::class.java)?.let { loaded.add(it) }
                        if (--pending == 0) finishLoading(loaded)
                    }
                    override fun onCancelled(error: DatabaseError) {
                        if (--pending == 0) finishLoading(loaded)
                    }
                })
        }
    }

    private fun finishLoading(loaded: List<Subject>) {
        enrolledSubjects.clear()
        enrolledSubjects.addAll(loaded.sortedWith(subjectOrder))
        refreshUI()
    }

    // ── UI 전체 갱신 ───────────────────────────────────────────────
    private fun refreshUI() {
        classBlockLayer.removeAllViews()
        blockViewMap.clear()

        // 레이아웃 측정 완료 후 블록 그리기
        classBlockLayer.post {
            enrolledSubjects.forEachIndexed { idx, subject ->
                drawBlocks(subject, idx)
            }
        }

        // ViewPager2 어댑터
        viewPager.adapter = CardAdapter()
        viewPager.offscreenPageLimit = 3

        buildDots(enrolledSubjects.size)
        if (enrolledSubjects.isNotEmpty()) {
            highlightSubject(enrolledSubjects[0].subjectCode)
            updateDots(0)
        }
    }

    // ── 시간표 블록 그리기 ─────────────────────────────────────────
    private fun drawBlocks(subject: Subject, colorIdx: Int) {
        val colorHex   = blockColors[colorIdx % blockColors.size]
        val cleanName  = subject.subjectName
            .replace(" (영어강의)", "")
            .replace(" (실시간화상강의)", "")

        val totalW    = classBlockLayer.width
        if (totalW <= 0) return
        val colW      = totalW / 5
        val oneHourPx = (ONE_HOUR_DP * resources.displayMetrics.density).toInt()

        for ((_, day) in subject.schedule) {
            val colIdx = dayToCol(day.dayOfWeek) ?: continue
            val loc    = day.location.ifBlank { "" }

            for (period in day.periods) {
                if (period == null) continue
                val startH = period.startTime.substringBefore(":").toIntOrNull() ?: continue
                val endH   = period.endTime.substringBefore(":").toIntOrNull()   ?: continue
                val dur    = (endH - startH).coerceAtLeast(1)

                val block = TextView(this).apply {
                    text = if (loc.isNotEmpty()) "$cleanName\n$loc" else cleanName
                    setTextColor(Color.WHITE)
                    textSize = 8.5f
                    gravity  = Gravity.CENTER
                    setBackgroundColor(Color.parseColor(colorHex))
                    setPadding(3, 2, 3, 2)
                    setOnClickListener {
                        val pos = enrolledSubjects.indexOfFirst { it.subjectCode == subject.subjectCode }
                        if (pos >= 0) viewPager.currentItem = pos
                    }
                }

                classBlockLayer.addView(block, FrameLayout.LayoutParams(
                    colW - 2,
                    dur * oneHourPx - 2
                ).also { lp ->
                    lp.leftMargin = colIdx * colW + 1
                    lp.topMargin  = (startH - GRID_START_HOUR) * oneHourPx + 1
                })

                blockViewMap.getOrPut(subject.subjectCode) { mutableListOf() }.add(block)
            }
        }
    }

    // ── 블록 하이라이트 ────────────────────────────────────────────
    private fun highlightSubject(code: String) {
        // 이전 블록 원래 색상 복원
        selectedSubjectCode?.let { prev ->
            val prevIdx = enrolledSubjects.indexOfFirst { it.subjectCode == prev }
            val prevColor = if (prevIdx >= 0) blockColors[prevIdx % blockColors.size] else "#8FA2C7"
            blockViewMap[prev]?.forEach { v ->
                (v as? TextView)?.setBackgroundColor(Color.parseColor(prevColor))
            }
        }
        // 선택 블록 어둡게
        val newIdx = enrolledSubjects.indexOfFirst { it.subjectCode == code }
        if (newIdx >= 0) {
            val base     = Color.parseColor(blockColors[newIdx % blockColors.size])
            val darkened = darken(base, 0.80f)
            blockViewMap[code]?.forEach { v ->
                (v as? TextView)?.setBackgroundColor(darkened)
            }
        }
        selectedSubjectCode = code
    }

    private fun darken(color: Int, factor: Float): Int {
        val r = (Color.red(color)   * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color)  * factor).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    // ── 도트 인디케이터 ────────────────────────────────────────────
    private fun buildDots(count: Int) {
        dotsContainer.removeAllViews()
        repeat(count) {
            val dot  = View(this)
            val size = (8 * resources.displayMetrics.density).toInt()
            dot.layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginStart = (4 * resources.displayMetrics.density).toInt()
                marginEnd   = (4 * resources.displayMetrics.density).toInt()
            }
            dot.setBackgroundResource(R.drawable.dot_inactive)
            dotsContainer.addView(dot)
        }
    }

    private fun updateDots(active: Int) {
        for (i in 0 until dotsContainer.childCount) {
            dotsContainer.getChildAt(i).setBackgroundResource(
                if (i == active) R.drawable.dot_active else R.drawable.dot_inactive
            )
        }
    }

    // ── 요일 → 열 인덱스 ──────────────────────────────────────────
    private fun dayToCol(day: String): Int? = when (day.trim().lowercase()) {
        "monday",    "mon", "월", "월요일" -> 0
        "tuesday",   "tue", "화", "화요일" -> 1
        "wednesday", "wed", "수", "수요일" -> 2
        "thursday",  "thu", "목", "목요일" -> 3
        "friday",    "fri", "금", "금요일" -> 4
        else -> null
    }

    // ── 과목 정렬: 요일 → 시작시각 ────────────────────────────────
    private val subjectOrder = Comparator<Subject> { a, b ->
        val aDay = a.schedule.values.minOfOrNull { dayToCol(it.dayOfWeek) ?: 99 } ?: 99
        val bDay = b.schedule.values.minOfOrNull { dayToCol(it.dayOfWeek) ?: 99 } ?: 99
        if (aDay != bDay) return@Comparator aDay - bDay

        val aTime = a.schedule.values.flatMap { it.periods }.filterNotNull()
            .minOfOrNull { it.startTime } ?: "99:99"
        val bTime = b.schedule.values.flatMap { it.periods }.filterNotNull()
            .minOfOrNull { it.startTime } ?: "99:99"
        aTime.compareTo(bTime)
    }

    // ── 학기 계산 (DB에 없으므로 날짜 기준 자동 계산) ─────────────
    private fun currentSemester(): String {
        val cal      = java.util.Calendar.getInstance()
        val year     = cal.get(java.util.Calendar.YEAR)
        val month    = cal.get(java.util.Calendar.MONTH) + 1
        val semester = if (month in 1..8) 1 else 2
        return "${year}년 ${semester}학기"
    }

    // ── ViewPager2 Adapter ─────────────────────────────────────────
    inner class CardAdapter : RecyclerView.Adapter<CardAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName      = v.findViewById<TextView>(R.id.tvCardSubjectName)
            val tvProf      = v.findViewById<TextView>(R.id.tvCardProfessor)
            val tvSemester  = v.findViewById<TextView>(R.id.tvCardSemester)
            val tvTime      = v.findViewById<TextView>(R.id.tvCardTime)
            val tvLocation  = v.findViewById<TextView>(R.id.tvCardLocation)
            val tvCode      = v.findViewById<TextView>(R.id.tvCardCode)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context)
                .inflate(R.layout.item_subject_card, parent, false))

        override fun getItemCount() = enrolledSubjects.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val s = enrolledSubjects[position]

            holder.tvName.text     = s.subjectName
            holder.tvProf.text     = s.professorName
            holder.tvSemester.text = currentSemester()

            // 시간 문자열: "화 14:00-15:00, 목 13:00-15:00"
            val timeStr = s.schedule.entries
                .sortedBy { dayToCol(it.value.dayOfWeek) ?: 99 }
                .mapNotNull { (_, day) ->
                    val dayKr = when (day.dayOfWeek.trim().lowercase()) {
                        "monday",    "mon", "월", "월요일" -> "월"
                        "tuesday",   "tue", "화", "화요일" -> "화"
                        "wednesday", "wed", "수", "수요일" -> "수"
                        "thursday",  "thu", "목", "목요일" -> "목"
                        "friday",    "fri", "금", "금요일" -> "금"
                        else -> return@mapNotNull null
                    }
                    val valid = day.periods.filterNotNull()
                    if (valid.isEmpty()) return@mapNotNull null
                    "$dayKr ${valid.minOf { it.startTime }}-${valid.maxOf { it.endTime }}"
                }.joinToString(", ")
            holder.tvTime.text = timeStr

            // 장소: 첫 번째 요일 기준
            holder.tvLocation.text = s.schedule.values
                .firstOrNull { it.location.isNotBlank() }?.location ?: ""

            holder.tvCode.text = s.subjectCode

            // 카드 클릭 → 해당 블록 하이라이트
            holder.itemView.setOnClickListener {
                highlightSubject(s.subjectCode)
            }
        }
    }
}