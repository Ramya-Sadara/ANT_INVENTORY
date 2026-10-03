package com.ant.inventory

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class InventoryEntry(
    val id: String,
    val orderNo: String,
    val partNo: String,
    val quantity: String,
    val location: String,
    val timestamp: String
)

class MainActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("inventory", Context.MODE_PRIVATE) }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    // Form fields
    private lateinit var etOrder: EditText
    private lateinit var etPart: EditText
    private lateinit var etQty: EditText
    private lateinit var spinnerLocation: Spinner
    private lateinit var btnSave: Button
    private lateinit var btnExport: Button

    // List area
    private lateinit var etSearch: EditText
    private lateinit var tvEntryCount: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var tvEmpty: TextView

    private val allEntries = mutableListOf<InventoryEntry>()
    private var searchQuery = ""

    private val locations = arrayOf(
        "RZD1", "RZD2", "RZD3", "RZD4",
        "RZD5", "RZD6", "RZD7", "RZD8", "Other"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setupSpinner()
        setupListeners()
        loadEntries()
        refreshList()
    }

    private fun bindViews() {
        etOrder = findViewById(R.id.etOrder)
        etPart = findViewById(R.id.etPart)
        etQty = findViewById(R.id.etQty)
        spinnerLocation = findViewById(R.id.spinnerLocation)
        btnSave = findViewById(R.id.btnSave)
        btnExport = findViewById(R.id.btnExport)
        etSearch = findViewById(R.id.etSearch)
        tvEntryCount = findViewById(R.id.tvEntryCount)
        listContainer = findViewById(R.id.listContainer)
        tvEmpty = findViewById(R.id.tvEmpty)
    }

    private fun setupSpinner() {
        spinnerLocation.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            locations
        )
    }

    private fun setupListeners() {
        btnSave.setOnClickListener { saveEntry() }
        btnExport.setOnClickListener { exportCsv() }
        btnClearAll.setOnClickListener { confirmClearAll() }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString()?.trim() ?: ""
                refreshList()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private val btnClearAll: Button get() = findViewById(R.id.btnClearAll)

    private fun saveEntry() {
        val orderNo = etOrder.text.toString().trim()
        val partNo  = etPart.text.toString().trim()
        val qty     = etQty.text.toString().trim()
        val loc     = spinnerLocation.selectedItem.toString()

        if (orderNo.isEmpty() || partNo.isEmpty() || qty.isEmpty()) {
            Toast.makeText(this, "Order No, Part No and Quantity are required", Toast.LENGTH_SHORT).show()
            return
        }

        val entry = InventoryEntry(
            id        = UUID.randomUUID().toString(),
            orderNo   = orderNo,
            partNo    = partNo,
            quantity  = qty,
            location  = loc,
            timestamp = dateFormat.format(Date())
        )

        val serialized = "${entry.id}\t${entry.orderNo}\t${entry.partNo}\t${entry.quantity}\t${entry.location}\t${entry.timestamp}"
        val existing = prefs.getString("rows", "")!!
        val updated  = if (existing.isEmpty()) serialized else "$existing\n$serialized"
        prefs.edit().putString("rows", updated).apply()

        allEntries.add(0, entry)
        etOrder.text.clear()
        etPart.text.clear()
        etQty.text.clear()
        refreshList()
        Toast.makeText(this, "Saved ✓", Toast.LENGTH_SHORT).show()
    }

    private fun deleteEntry(entry: InventoryEntry) {
        AlertDialog.Builder(this)
            .setTitle("Delete Entry")
            .setMessage("Delete entry for Part No: ${entry.partNo}?")
            .setPositiveButton("Delete") { _, _ ->
                allEntries.removeAll { it.id == entry.id }
                persistEntries()
                refreshList()
                Toast.makeText(this, "Entry deleted", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmClearAll() {
        if (allEntries.isEmpty()) {
            Toast.makeText(this, "No entries to clear", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Clear All")
            .setMessage("Delete all ${allEntries.size} entries? This cannot be undone.")
            .setPositiveButton("Clear All") { _, _ ->
                allEntries.clear()
                prefs.edit().remove("rows").apply()
                refreshList()
                Toast.makeText(this, "All entries cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadEntries() {
        allEntries.clear()
        prefs.getString("rows", "")!!.lines()
            .filter { it.isNotBlank() }
            .mapNotNull { parseLine(it) }
            .reversed()
            .forEach { allEntries.add(it) }
    }

    private fun parseLine(line: String): InventoryEntry? {
        val parts = line.split("\t")
        return when {
            parts.size == 6 -> InventoryEntry(parts[0], parts[1], parts[2], parts[3], parts[4], parts[5])
            // Legacy 5-field format (no id): generate one
            parts.size == 5 -> InventoryEntry(UUID.randomUUID().toString(), parts[0], parts[1], parts[2], parts[3], parts[4])
            else -> null
        }
    }

    private fun persistEntries() {
        val rows = allEntries.reversed().joinToString("\n") {
            "${it.id}\t${it.orderNo}\t${it.partNo}\t${it.quantity}\t${it.location}\t${it.timestamp}"
        }
        prefs.edit().putString("rows", rows).apply()
    }

    private fun refreshList() {
        listContainer.removeAllViews()
        val filtered = if (searchQuery.isEmpty()) allEntries
        else allEntries.filter { e ->
            e.orderNo.contains(searchQuery, ignoreCase = true) ||
            e.partNo.contains(searchQuery, ignoreCase = true) ||
            e.location.contains(searchQuery, ignoreCase = true)
        }

        tvEntryCount.text = "${filtered.size} / ${allEntries.size} entries"
        tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE

        filtered.forEach { entry ->
            listContainer.addView(buildEntryCard(entry))
        }
    }

    private fun buildEntryCard(entry: InventoryEntry): View {
        val inflater = layoutInflater
        val card = inflater.inflate(R.layout.item_entry, listContainer, false)
        card.findViewById<TextView>(R.id.tvOrderNo).text   = "Order: ${entry.orderNo}"
        card.findViewById<TextView>(R.id.tvPartNo).text    = "Part:  ${entry.partNo}"
        card.findViewById<TextView>(R.id.tvQty).text       = "Qty:   ${entry.quantity}"
        card.findViewById<TextView>(R.id.tvLocation).text  = "Loc:   ${entry.location}"
        card.findViewById<TextView>(R.id.tvTimestamp).text = entry.timestamp
        card.findViewById<ImageButton>(R.id.btnDelete).setOnClickListener { deleteEntry(entry) }
        return card
    }

    private fun exportCsv() {
        if (allEntries.isEmpty()) {
            Toast.makeText(this, "No entries to export", Toast.LENGTH_SHORT).show()
            return
        }
        val header = "Order No,Part No,Physical Quantity,Location,Date\n"
        val rows   = allEntries.reversed().joinToString("\n") { e ->
            listOf(e.orderNo, e.partNo, e.quantity, e.location, e.timestamp)
                .joinToString(",") { v -> "\"${v.replace("\"", "\"\"")}\"" }
        }
        val csv = header + rows

        try {
            val file = File(cacheDir, "ant_inventory_${System.currentTimeMillis()}.csv")
            file.writeText(csv)
            val uri: Uri = FileProvider.getUriForFile(
                this, "${packageName}.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "ANT INVENTORY Export")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Export CSV"))
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
