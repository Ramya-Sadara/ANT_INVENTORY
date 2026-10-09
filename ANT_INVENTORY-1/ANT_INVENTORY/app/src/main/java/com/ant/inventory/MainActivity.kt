package com.ant.inventory

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
    val timestamp: String,
    val remarks: String = ""
)

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CAMERA_ORDER = 1001
        private const val REQUEST_CAMERA_PART  = 1002
        private const val SCAN_ORDER_RC        = 2001
        private const val SCAN_PART_RC         = 2002
        // Separators tried (in order) when one barcode holds Order, Part and Qty.
        // "-" and "/" are not used because part numbers often contain them.
        private val BARCODE_DELIMITERS = listOf("|", ";", ",", "\t", "\n", "\u001D")
    }

    private val prefs by lazy { getSharedPreferences("inventory", Context.MODE_PRIVATE) }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    private lateinit var etOrder: EditText
    private lateinit var etPart: EditText
    private lateinit var etQty: EditText
    private lateinit var etRemarks: EditText
    private lateinit var spinnerLocation: Spinner
    private lateinit var btnSave: Button
    private lateinit var btnExport: Button
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
        etOrder         = findViewById(R.id.etOrder)
        etPart          = findViewById(R.id.etPart)
        etQty           = findViewById(R.id.etQty)
        etRemarks       = findViewById(R.id.etRemarks)
        spinnerLocation = findViewById(R.id.spinnerLocation)
        btnSave         = findViewById(R.id.btnSave)
        btnExport       = findViewById(R.id.btnExport)
        etSearch        = findViewById(R.id.etSearch)
        tvEntryCount    = findViewById(R.id.tvEntryCount)
        listContainer   = findViewById(R.id.listContainer)
        tvEmpty         = findViewById(R.id.tvEmpty)
        findViewById<ImageButton>(R.id.btnScanOrder).setOnClickListener {
            requestCameraAndScan(REQUEST_CAMERA_ORDER, SCAN_ORDER_RC)
        }
        findViewById<ImageButton>(R.id.btnScanPart).setOnClickListener {
            requestCameraAndScan(REQUEST_CAMERA_PART, SCAN_PART_RC)
        }
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
        findViewById<Button>(R.id.btnClearAll).setOnClickListener { confirmClearAll() }
        findViewById<Button>(R.id.btnLogout).setOnClickListener { logout() }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString()?.trim() ?: ""
                refreshList()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun logout() {
        AlertDialog.Builder(this)
            .setTitle("Lock App")
            .setMessage("Lock the app and return to PIN screen?")
            .setPositiveButton("Lock") { _, _ ->
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requestCameraAndScan(cameraRequestCode: Int, scanRequestCode: Int) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            launchScanner(scanRequestCode)
        } else {
            pendingScanRc = scanRequestCode
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), cameraRequestCode
            )
        }
    }

    private var pendingScanRc: Int = -1

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            launchScanner(pendingScanRc)
        } else {
            Toast.makeText(this, "Camera permission is required to scan barcodes", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchScanner(scanRequestCode: Int) {
        startActivityForResult(Intent(this, ScannerActivity::class.java), scanRequestCode)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val scanned = data?.getStringExtra(ScannerActivity.EXTRA_BARCODE)?.trim() ?: return
        if (scanned.isEmpty()) return
        if (requestCode != SCAN_ORDER_RC && requestCode != SCAN_PART_RC) return

        val parts = splitCombinedBarcode(scanned)
        if (parts != null) {
            etOrder.setText(parts.first)
            etPart.setText(parts.second)
            val qtyDigits = parts.third.filter { it.isDigit() }
            etQty.setText(qtyDigits)
            val msg = if (qtyDigits.isEmpty()) "Quantity in barcode is not a number – please enter it"
                      else "Order, Part and Qty filled from barcode"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } else {
            // Not a combined barcode: fill only the field that was scanned.
            when (requestCode) {
                SCAN_ORDER_RC -> etOrder.setText(scanned)
                SCAN_PART_RC  -> etPart.setText(scanned)
            }
        }
    }

    /** Splits "ORDER<sep>PART<sep>QTY": first = order, last = qty, middle = part. */
    private fun splitCombinedBarcode(raw: String): Triple<String, String, String>? {
        for (d in BARCODE_DELIMITERS) {
            val p = raw.split(d).map { it.trim() }.filter { it.isNotEmpty() }
            if (p.size >= 3) return Triple(p.first(), p.subList(1, p.size - 1).joinToString(d), p.last())
        }
        val ws = raw.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (ws.size >= 3) return Triple(ws.first(), ws.subList(1, ws.size - 1).joinToString(" "), ws.last())
        return null
    }

    /** Removes tabs/newlines, which would break the stored row format. */
    private fun clean(s: String) = s.replace(Regex("[\\t\\r\\n]+"), " ").trim()

    private fun saveEntry() {
        val orderNo = clean(etOrder.text.toString())
        val partNo  = clean(etPart.text.toString())
        val qty     = clean(etQty.text.toString())
        val remarks = clean(etRemarks.text.toString())
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
            timestamp = dateFormat.format(Date()),
            remarks   = remarks
        )
        allEntries.add(0, entry)
        persistEntries()
        etOrder.text.clear()
        etPart.text.clear()
        etQty.text.clear()
        etRemarks.text.clear()
        refreshList()
        Toast.makeText(this, "Saved ✓", Toast.LENGTH_SHORT).show()
    }

    private fun editEntry(entry: InventoryEntry) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        fun field(label: String, value: String, type: Int): EditText {
            form.addView(TextView(this).apply { text = label })
            return EditText(this).apply {
                inputType = type
                setText(value)
                form.addView(this)
            }
        }
        val etO = field("Order No", entry.orderNo, InputType.TYPE_CLASS_TEXT)
        val etP = field("Part No", entry.partNo, InputType.TYPE_CLASS_TEXT)
        val etQ = field("Physical Quantity", entry.quantity, InputType.TYPE_CLASS_NUMBER)
        val etR = field("Remarks", entry.remarks,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)

        form.addView(TextView(this).apply { text = "Location" })
        val locList = if (entry.location in locations) locations else locations + entry.location
        val sp = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, locList)
            setSelection(locList.indexOf(entry.location).coerceAtLeast(0))
        }
        form.addView(sp)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Edit Entry")
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val o = clean(etO.text.toString())
                val p = clean(etP.text.toString())
                val q = clean(etQ.text.toString())
                if (o.isEmpty() || p.isEmpty() || q.isEmpty()) {
                    Toast.makeText(this, "Order No, Part No and Quantity are required", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val idx = allEntries.indexOfFirst { it.id == entry.id }
                if (idx >= 0) {
                    allEntries[idx] = entry.copy(
                        orderNo  = o,
                        partNo   = p,
                        quantity = q,
                        remarks  = clean(etR.text.toString()),
                        location = sp.selectedItem.toString()
                    )
                    persistEntries()
                    refreshList()
                    Toast.makeText(this, "Entry updated", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
        }
        dialog.show()
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
        if (allEntries.isEmpty()) { Toast.makeText(this, "No entries to clear", Toast.LENGTH_SHORT).show(); return }
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
            parts.size >= 7 -> InventoryEntry(parts[0], parts[1], parts[2], parts[3], parts[4], parts[5], parts[6])
            parts.size == 6 -> InventoryEntry(parts[0], parts[1], parts[2], parts[3], parts[4], parts[5])
            parts.size == 5 -> InventoryEntry(UUID.randomUUID().toString(), parts[0], parts[1], parts[2], parts[3], parts[4])
            else -> null
        }
    }

    private fun persistEntries() {
        prefs.edit().putString("rows", allEntries.reversed().joinToString("\n") {
            "${it.id}\t${it.orderNo}\t${it.partNo}\t${it.quantity}\t${it.location}\t${it.timestamp}\t${it.remarks}"
        }).apply()
    }

    private fun refreshList() {
        listContainer.removeAllViews()
        val filtered = if (searchQuery.isEmpty()) allEntries
        else allEntries.filter { e ->
            e.orderNo.contains(searchQuery, ignoreCase = true) ||
            e.partNo.contains(searchQuery, ignoreCase = true) ||
            e.location.contains(searchQuery, ignoreCase = true) ||
            e.remarks.contains(searchQuery, ignoreCase = true)
        }
        tvEntryCount.text = "${filtered.size} / ${allEntries.size} entries"
        tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        filtered.forEach { listContainer.addView(buildEntryCard(it)) }
    }

    private fun buildEntryCard(entry: InventoryEntry): View {
        val card = layoutInflater.inflate(R.layout.item_entry, listContainer, false)
        card.findViewById<TextView>(R.id.tvOrderNo).text   = "Order: ${entry.orderNo}"
        card.findViewById<TextView>(R.id.tvPartNo).text    = "Part:  ${entry.partNo}"
        card.findViewById<TextView>(R.id.tvQty).text       = "Qty:   ${entry.quantity}"
        card.findViewById<TextView>(R.id.tvLocation).text  = "Loc:   ${entry.location}"
        val tvRemarks = card.findViewById<TextView>(R.id.tvRemarks)
        tvRemarks.text = "Remarks: ${entry.remarks}"
        tvRemarks.visibility = if (entry.remarks.isBlank()) View.GONE else View.VISIBLE
        card.findViewById<TextView>(R.id.tvTimestamp).text = entry.timestamp
        card.findViewById<ImageButton>(R.id.btnEdit).setOnClickListener { editEntry(entry) }
        card.findViewById<ImageButton>(R.id.btnDelete).setOnClickListener { deleteEntry(entry) }
        return card
    }

    private fun exportCsv() {
        if (allEntries.isEmpty()) { Toast.makeText(this, "No entries to export", Toast.LENGTH_SHORT).show(); return }
        val header = "Order No,Part No,Physical Quantity,Location,Remarks,Date\n"
        val rows   = allEntries.reversed().joinToString("\n") { e ->
            listOf(e.orderNo, e.partNo, e.quantity, e.location, e.remarks, e.timestamp)
                .joinToString(",") { v -> "\"${v.replace("\"", "\"\"")}\"" }
        }
        try {
            val file = File(cacheDir, "ant_inventory_${System.currentTimeMillis()}.csv")
            file.writeText(header + rows)
            val uri: Uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
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
