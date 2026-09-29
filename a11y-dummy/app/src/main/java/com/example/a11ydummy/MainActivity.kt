package com.example.a11ydummy

import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : android.app.Activity() {
    private lateinit var recycler: RecyclerView
    private lateinit var counter: TextView
    private lateinit var popupCounter: TextView
    private lateinit var adapter: PersonAdapter
    private var checked = 0
    private var dismissed = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 0) }
        val title = TextView(this).apply { text = "A11y Checkbox Stress Test"; textSize = 22f; setTextIsSelectable(true) }
        root.addView(title, LinearLayout.LayoutParams(-1, -2))
        counter = TextView(this).apply { textSize = 16f; contentDescription = "Checked 0 of 15" }
        popupCounter = TextView(this).apply { textSize = 14f }
        root.addView(counter, LinearLayout.LayoutParams(-1, -2))
        root.addView(popupCounter, LinearLayout.LayoutParams(-1, -2))

        recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            isNestedScrollingEnabled = true
            contentDescription = "People scroll list"
        }
        adapter = PersonAdapter((1..15).map { Person(it, "Dummy Person $it", if (it % 2 == 0) "Female" else "Male", "${it.toString().padStart(2,'0')}/09/200${it % 10}", 20 + it, "XXXX-XXXX-${it.toString().padStart(4,'0')}") })
        recycler.adapter = adapter
        root.addView(recycler, LinearLayout.LayoutParams(-1, 0, 1f))

        val reset = Button(this).apply { text = "Reset all"; contentDescription = "Reset all checkboxes"; setOnClickListener { resetAll() } }
        root.addView(reset, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        updateCounters()
    }

    private fun showPopup(position: Int) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 16, 32, 16) }
        val msg = TextView(this).apply { text = "Dummy popup after checking Person ${position + 1}"; textSize = 18f; gravity = Gravity.CENTER; setPadding(0, 12, 0, 20) }
        val close = ImageButton(this).apply {
            contentDescription = "purple_cross_icon"
            id = View.generateViewId()
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        box.addView(msg, LinearLayout.LayoutParams(-1, -2))
        box.addView(close, LinearLayout.LayoutParams(-1, 72))
        val dialog = AlertDialog.Builder(this).setView(box).setCancelable(false).create()
        close.setOnClickListener {
            dismissed++
            popupCounter.text = "Popups dismissed: $dismissed / 15"
            dialog.dismiss()
        }
        dialog.setOnShowListener { close.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) }
        dialog.show()
    }

    private fun updateCounters() { counter.text = "Checked: $checked / 15"; counter.contentDescription = "Checked $checked of 15"; popupCounter.text = "Popups dismissed: $dismissed / 15" }
    private fun resetAll() { checked = 0; dismissed = 0; adapter.reset(); updateCounters(); recycler.scrollToPosition(0) }

    private inner class PersonAdapter(private val people: List<Person>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val states = BooleanArray(people.size)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(makeCard()) {}
        override fun getItemCount() = people.size
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) =
            bindCard(holder.itemView as LinearLayout, people[position], position)
        fun reset() { java.util.Arrays.fill(states, false); notifyDataSetChanged() }

        private fun makeCard(): LinearLayout = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 48)
            minimumHeight = (resources.displayMetrics.heightPixels * 0.92).toInt()
        }

        private fun bindCard(card: LinearLayout, p: Person, position: Int) {
            card.removeAllViews()
            val heading = TextView(this@MainActivity).apply { text = "Person ${p.index} of 15"; textSize = 24f; setPadding(0, 0, 0, 24) }
            card.addView(heading)
            listOf("Name: ${p.name}", "Gender: ${p.gender}", "DOB: ${p.dob}", "Age: ${p.age}", "ID: ${p.id}").forEach { s -> card.addView(TextView(this@MainActivity).apply { text=s; textSize=18f; setPadding(0,10,0,10) }) }
            val check = CheckBox(this@MainActivity).apply {
                id = View.generateViewId()
                contentDescription = "dummy_checkbox_${p.index.toString().padStart(2,'0')}"
                text = "I confirm Person ${p.index}"
                textSize = 18f
                isChecked = states[position]
                isEnabled = !states[position]
                setOnClickListener {
                    if (!states[position]) {
                        states[position] = true; checked++; isEnabled = false; updateCounters(); showPopup(position)
                    }
                }
            }
            card.addView(check, LinearLayout.LayoutParams(-1, 72))
            card.addView(TextView(this@MainActivity).apply { text="Scroll down after closing the popup to load the next person."; textSize=14f; setPadding(0,24,0,0) })
        }
    }

    data class Person(val index:Int,val name:String,val gender:String,val dob:String,val age:Int,val id:String)
}
