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
    val remarks: String = "",
    val scanResult: String = ""
)

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CAMERA = 1001
        private const val SCAN_RC        = 2001
        // Same rules as the Excel sheet: Order No = first 10 characters of the scan.
        private const val ORDER_NO_LENGTH = 10
    }

    private val prefs by lazy { getSharedPreferences("inventory", Context.MODE_PRIVATE) }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    private lateinit var etScanResult: EditText
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
        etScanResult    = findViewById(R.id.etScanResult)
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
        findViewById<ImageButton>(R.id.btnScan).setOnClickListener { requestCameraAndScan() }
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

    private fun requestCameraAndScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            launchScanner()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            launchScanner()
        } else {
            Toast.makeText(this, "Camera permission is required to scan barcodes", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchScanner() {
        startActivityForResult(Intent(this, ScannerActivity::class.java), SCAN_RC)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SCAN_RC || resultCode != RESULT_OK) return
        val scanned = data?.getStringExtra(ScannerActivity.EXTRA_BARCODE)?.trim() ?: return
        if (scanned.isEmpty()) return

        // Scan Result shows exactly what was scanned; the other three are calculated from it.
        etScanResult.setText(clean(scanned))
        val p = parseScan(scanned)
        etOrder.setText(p.order)
        etPart.setText(p.part)
        etQty.setText(p.qty)

        val shown = clean(scanned)
        val msg = if (p.part.isEmpty() || p.qty.isEmpty())
            "Scan read (${scanned.length} chars): \"$shown\"\nPart No / Quantity could not be found in it – please check"
        else
            "Order, Part and Qty filled from scan (${scanned.length} chars)"
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private data class ScanParts(val order: String, val part: String, val qty: String)

    /**
     * Same calculations as the Excel sheet (text in A2):
     *  Order No = LEFT(TRIM(A2),10)
     *  Part No  = chars 11-15 + "-" + chars 16+ of the first word (blank if the word has 10 chars or fewer)
     *  Quantity = last word after a space, if it is a number (blank otherwise)
     */
    private fun parseScan(raw: String): ScanParts {
        val t = raw.trim().replace(Regex("\\s+"), " ")
        val order = t.take(ORDER_NO_LENGTH)

        val firstWord = t.substringBefore(' ')
        val rest = if (firstWord.length > ORDER_NO_LENGTH) firstWord.substring(ORDER_NO_LENGTH) else ""
        val part = if (rest.isEmpty()) "" else rest.take(5) + "-" + rest.drop(5)

        val qty = if (' ' in t) normalizeQty(t.substringAfterLast(' ')) ?: "" else ""
        return ScanParts(order, part, qty)
    }

    /** "60" -> "60", "3.00" -> "3"; anything else (e.g. "3.5", "ABC") -> null. */
    private fun normalizeQty(s: String): String? =
        if (s.matches(Regex("\\d+(\\.0+)?"))) s.substringBefore('.') else null

    /** Removes tabs/newlines, which would break the stored row format. */
    private fun clean(s: String) = s.replace(Regex("[\\t\\r\\n]+"), " ").trim()

    private fun saveEntry() {
        val orderNo = clean(etOrder.text.toString())
        val partNo  = clean(etPart.text.toString())
        val qty     = clean(etQty.text.toString())
        val remarks = clean(etRemarks.text.toString())
        val scan    = clean(etScanResult.text.toString())
        val loc     = spinnerLocation.selectedItem.toString()
        if (orderNo.isEmpty() || partNo.isEmpty() || qty.isEmpty()) {
            Toast.makeText(this, "Order No, Part No and Quantity are required", Toast.LENGTH_SHORT).show()
            return
        }
        val entry = InventoryEntry(
            id         = UUID.randomUUID().toString(),
            orderNo    = orderNo,
            partNo     = partNo,
            quantity   = qty,
            location   = loc,
            timestamp  = dateFormat.format(Date()),
            remarks    = remarks,
            scanResult = scan
        )
        allEntries.add(0, entry)
        persistEntries()
        etScanResult.text.clear()
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
        val etS = field("Scan Result", entry.scanResult, InputType.TYPE_CLASS_TEXT)
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
                        orderNo    = o,
                        partNo     = p,
                        quantity   = q,
                        remarks    = clean(etR.text.toString()),
                        scanResult = clean(etS.text.toString()),
                        location   = sp.selectedItem.toString()
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

    /** Accepts 8-field (with scan result), 7-field (with remarks), 6-field and 5-field saved rows. */
    private fun parseLine(line: String): InventoryEntry? {
        val parts = line.split("\t")
        return when {
            parts.size >= 8 -> InventoryEntry(parts[0], parts[1], parts[2], parts[3], parts[4], parts[5], parts[6], parts[7])
            parts.size == 7 -> InventoryEntry(parts[0], parts[1], parts[2], parts[3], parts[4], parts[5], parts[6])
            parts.size == 6 -> InventoryEntry(parts[0], parts[1], parts[2], parts[3], parts[4], parts[5])
            parts.size == 5 -> InventoryEntry(UUID.randomUUID().toString(), parts[0], parts[1], parts[2], parts[3], parts[4])
            else -> null
        }
    }

    private fun persistEntries() {
        prefs.edit().putString("rows", allEntries.reversed().joinToString("\n") {
            "${it.id}\t${it.orderNo}\t${it.partNo}\t${it.quantity}\t${it.location}\t${it.timestamp}\t${it.remarks}\t${it.scanResult}"
        }).apply()
    }

    private fun refreshList() {
        listContainer.removeAllViews()
        val filtered = if (searchQuery.isEmpty()) allEntries
        else allEntries.filter { e ->
            e.orderNo.contains(searchQuery, ignoreCase = true) ||
            e.partNo.contains(searchQuery, ignoreCase = true) ||
            e.location.contains(searchQuery, ignoreCase = true) ||
            e.remarks.contains(searchQuery, ignoreCase = true) ||
            e.scanResult.contains(searchQuery, ignoreCase = true)
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
        val tvScan = card.findViewById<TextView>(R.id.tvScanResult)
        tvScan.text = "Scan: ${entry.scanResult}"
        tvScan.visibility = if (entry.scanResult.isBlank()) View.GONE else View.VISIBLE
        card.findViewById<TextView>(R.id.tvTimestamp).text = entry.timestamp
        card.findViewById<ImageButton>(R.id.btnEdit).setOnClickListener { editEntry(entry) }
        card.findViewById<ImageButton>(R.id.btnDelete).setOnClickListener { deleteEntry(entry) }
        return card
    }

    private fun exportCsv() {
        if (allEntries.isEmpty()) { Toast.makeText(this, "No entries to export", Toast.LENGTH_SHORT).show(); return }
        val header = "Scan Result,Order No,Part No,Physical Quantity,Location,Remarks,Date\n"
        val rows   = allEntries.reversed().joinToString("\n") { e ->
            listOf(e.scanResult, e.orderNo, e.partNo, e.quantity, e.location, e.remarks, e.timestamp)
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
