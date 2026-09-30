package com.carlos.escanertarjetas

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.Size
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    data class Lectura(val numero: String, val fecha: Long)

    private lateinit var previewView: PreviewView
    private lateinit var tvDetectado: TextView
    private lateinit var tvContador: TextView
    private lateinit var swAuto: SwitchCompat
    private lateinit var btnFlash: Button
    private lateinit var listView: ListView

    private val lecturas = mutableListOf<Lectura>()
    private val textos = mutableListOf<String>()
    private lateinit var adapter: ArrayAdapter<String>

    private lateinit var analisisExecutor: ExecutorService
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var camera: Camera? = null
    private var linternaOn = false
    private var tono: ToneGenerator? = null

    // Estado para confirmar que una lectura es estable antes de añadirla
    private var candidato: String? = null
    private var repeticiones = 0
    private var ultimoAnadido: String? = null
    private var framesSinUltimo = 0
    private val lecturasParaConfirmar = 3   // fotogramas seguidos con el mismo número
    private val framesParaRearmar = 10      // fotogramas sin ver la última tarjeta para "olvidarla"

    // Ajustes (se guardan en el móvil)
    private var minDigitos = 4
    private var maxDigitos = 20
    private var permitirDuplicados = false

    private val fechaFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val ficheroLecturas by lazy { File(filesDir, "lecturas.txt") }

    private val pedirPermiso =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) iniciarCamara()
            else toast("Sin permiso de cámara no se puede escanear")
        }
    private val guardarTxt =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            uri?.let { escribirEn(it, csv = false) }
        }
    private val guardarCsv =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            uri?.let { escribirEn(it, csv = true) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        previewView = findViewById(R.id.previewView)
        tvDetectado = findViewById(R.id.tvDetectado)
        tvContador = findViewById(R.id.tvContador)
        swAuto = findViewById(R.id.swAuto)
        btnFlash = findViewById(R.id.btnFlash)
        listView = findViewById(R.id.listView)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, textos)
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, pos, _ -> editarLectura(pos) }

        findViewById<Button>(R.id.btnAnadir).setOnClickListener {
            val c = candidato
            if (c == null) {
                toast("No hay ningún número detectado")
            } else {
                anadir(c)
                ultimoAnadido = c
                framesSinUltimo = 0
            }
        }
        findViewById<Button>(R.id.btnManual).setOnClickListener { dialogoManual() }
        btnFlash.setOnClickListener { alternarLinterna() }
        findViewById<Button>(R.id.btnAjustes).setOnClickListener { dialogoAjustes() }
        findViewById<Button>(R.id.btnTxt).setOnClickListener { exportar(csv = false) }
        findViewById<Button>(R.id.btnCsv).setOnClickListener { exportar(csv = true) }
        findViewById<Button>(R.id.btnBorrar).setOnClickListener { confirmarVaciar() }

        cargarAjustes()
        cargarLecturas()
        tono = try {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        } catch (e: RuntimeException) {
            null
        }
        analisisExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) iniciarCamara()
        else pedirPermiso.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroy() {
        super.onDestroy()
        analisisExecutor.shutdown()
        recognizer.close()
        tono?.release()
    }

    // ---------------------------------------------------------------- Cámara

    private fun iniciarCamara() {
        val futuro = ProcessCameraProvider.getInstance(this)
        futuro.addListener({
            val provider = futuro.get()
            val ratio = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY

            val preview = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build())
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val analisis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(ratio)
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1280, 960),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analisis.setAnalyzer(analisisExecutor) { proxy -> analizar(proxy) }

            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analisis
                )
                btnFlash.isEnabled = camera?.cameraInfo?.hasFlashUnit() == true
            } catch (e: Exception) {
                toast("Error al abrir la cámara: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun alternarLinterna() {
        linternaOn = !linternaOn
        camera?.cameraControl?.enableTorch(linternaOn)
        btnFlash.text = if (linternaOn) "Luz ON" else "Luz"
    }

    // ---------------------------------------------------------------- OCR

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun analizar(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null) {
            proxy.close()
            return
        }
        val rot = proxy.imageInfo.rotationDegrees
        val girada = rot == 90 || rot == 270
        val ancho = if (girada) proxy.height else proxy.width
        val alto = if (girada) proxy.width else proxy.height

        recognizer.process(InputImage.fromMediaImage(media, rot))
            .addOnSuccessListener { texto -> procesarCandidato(extraerNumero(texto, ancho, alto)) }
            .addOnCompleteListener { proxy.close() }
    }

    /** Busca el número más largo dentro del recuadro verde. */
    private fun extraerNumero(texto: Text, ancho: Int, alto: Int): String? {
        var mejor: String? = null
        for (bloque in texto.textBlocks) {
            for (linea in bloque.lines) {
                val caja = linea.boundingBox ?: continue
                val cx = caja.exactCenterX() / ancho
                val cy = caja.exactCenterY() / alto
                // Mismo área que el recuadro verde (85% ancho x 30% alto, centrado)
                if (cx < 0.075f || cx > 0.925f || cy < 0.35f || cy > 0.65f) continue

                // "1234 5678-90" -> "1234567890"
                val unida = linea.text.replace(Regex("(?<=\\d)[ .\\-](?=\\d)"), "")
                for (bruto in unida.split(Regex("\\s+"))) {
                    val token = bruto.trim { !it.isLetterOrDigit() }
                    if (token.isEmpty()) continue
                    val digitos = token.count { it.isDigit() }
                    if (digitos < token.length * 0.7) continue   // es texto, no un número
                    val corregido = token.map { corregirCaracter(it) }.joinToString("")
                    if (!corregido.all { it.isDigit() }) continue
                    if (corregido.length !in minDigitos..maxDigitos) continue
                    val actual = mejor
                    if (actual == null || corregido.length > actual.length) mejor = corregido
                }
            }
        }
        return mejor
    }

    /** Confusiones típicas del OCR cuando sabemos que solo hay números. */
    private fun corregirCaracter(c: Char): Char = when (c) {
        'O', 'o', 'D', 'Q' -> '0'
        'I', 'l', 'i', '|' -> '1'
        'Z', 'z' -> '2'
        'S', 's' -> '5'
        'B' -> '8'
        else -> c
    }

    private fun procesarCandidato(c: String?) {
        tvDetectado.text = c ?: "—"

        // No volver a añadir la misma tarjeta mientras siga delante de la cámara
        if (ultimoAnadido != null) {
            if (c == ultimoAnadido) {
                framesSinUltimo = 0
                candidato = c
                return
            }
            framesSinUltimo++
            if (framesSinUltimo >= framesParaRearmar) ultimoAnadido = null
        }

        if (c == candidato) repeticiones++ else {
            candidato = c
            repeticiones = 1
        }

        if (c != null && swAuto.isChecked && repeticiones >= lecturasParaConfirmar) {
            anadir(c)
            ultimoAnadido = c
            framesSinUltimo = 0
            repeticiones = 0
        }
    }

    // ---------------------------------------------------------------- Lista

    private fun anadir(numero: String) {
        if (!permitirDuplicados && lecturas.any { it.numero == numero }) {
            tono?.startTone(ToneGenerator.TONE_PROP_NACK, 150)
            toast("$numero ya estaba en la lista")
            return
        }
        lecturas.add(Lectura(numero, System.currentTimeMillis()))
        tono?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        refrescar()
        guardarLecturas()
        listView.post { listView.setSelection(textos.size - 1) }
    }

    private fun refrescar() {
        textos.clear()
        lecturas.forEachIndexed { i, l -> textos.add("${i + 1}.   ${l.numero}") }
        adapter.notifyDataSetChanged()
        tvContador.text = "Leídos: ${lecturas.size}   (toca uno para editar o borrar)"
    }

    private fun guardarLecturas() {
        ficheroLecturas.writeText(lecturas.joinToString("\n") { "${it.numero};${it.fecha}" })
    }

    private fun cargarLecturas() {
        if (ficheroLecturas.exists()) {
            ficheroLecturas.readLines().forEach { linea ->
                val p = linea.split(";")
                if (p.size == 2 && p[0].isNotBlank()) {
                    lecturas.add(Lectura(p[0], p[1].toLongOrNull() ?: 0L))
                }
            }
        }
        refrescar()
    }

    private fun conMargen(v: View): View = FrameLayout(this).apply {
        setPadding(56, 24, 56, 0)
        addView(v)
    }

    private fun campoNumerico(valor: String = ""): EditText = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(valor)
        setSelection(valor.length)
    }

    private fun editarLectura(pos: Int) {
        val campo = campoNumerico(lecturas[pos].numero)
        AlertDialog.Builder(this)
            .setTitle("Lectura ${pos + 1}")
            .setView(conMargen(campo))
            .setPositiveButton("Guardar") { _, _ ->
                val n = campo.text.toString().trim()
                if (n.isNotEmpty()) {
                    lecturas[pos] = lecturas[pos].copy(numero = n)
                    refrescar()
                    guardarLecturas()
                }
            }
            .setNeutralButton("Eliminar") { _, _ ->
                lecturas.removeAt(pos)
                refrescar()
                guardarLecturas()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun dialogoManual() {
        val campo = campoNumerico()
        AlertDialog.Builder(this)
            .setTitle("Escribir número")
            .setView(conMargen(campo))
            .setPositiveButton("Añadir") { _, _ ->
                val n = campo.text.toString().trim()
                if (n.isNotEmpty()) anadir(n)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmarVaciar() {
        if (lecturas.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Vaciar lista")
            .setMessage("¿Borrar los ${lecturas.size} números leídos? Exporta antes si los necesitas.")
            .setPositiveButton("Borrar todo") { _, _ ->
                lecturas.clear()
                ultimoAnadido = null
                refrescar()
                guardarLecturas()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------------------------------------------------------- Ajustes

    private fun cargarAjustes() {
        val p = getSharedPreferences("ajustes", MODE_PRIVATE)
        minDigitos = p.getInt("min", 4)
        maxDigitos = p.getInt("max", 20)
        permitirDuplicados = p.getBoolean("dup", false)
    }

    private fun guardarAjustes() {
        getSharedPreferences("ajustes", MODE_PRIVATE).edit()
            .putInt("min", minDigitos)
            .putInt("max", maxDigitos)
            .putBoolean("dup", permitirDuplicados)
            .apply()
    }

    private fun dialogoAjustes() {
        val etMin = campoNumerico(minDigitos.toString())
        val etMax = campoNumerico(maxDigitos.toString())
        val cbDup = CheckBox(this).apply {
            text = "Permitir números repetidos"
            isChecked = permitirDuplicados
        }
        val contenido = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 24, 56, 0)
            addView(TextView(context).apply { text = "Mínimo de dígitos" })
            addView(etMin)
            addView(TextView(context).apply { text = "Máximo de dígitos" })
            addView(etMax)
            addView(cbDup)
        }
        AlertDialog.Builder(this)
            .setTitle("Ajustes de lectura")
            .setView(contenido)
            .setPositiveButton("Guardar") { _, _ ->
                minDigitos = (etMin.text.toString().toIntOrNull() ?: 4).coerceIn(1, 40)
                maxDigitos = (etMax.text.toString().toIntOrNull() ?: 20).coerceIn(minDigitos, 40)
                permitirDuplicados = cbDup.isChecked
                guardarAjustes()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------------------------------------------------------- Exportar

    private fun contenido(csv: Boolean): String =
        if (csv) {
            buildString {
                append("n;numero;fecha_hora\r\n")
                lecturas.forEachIndexed { i, l ->
                    append("${i + 1};${l.numero};${fechaFmt.format(Date(l.fecha))}\r\n")
                }
            }
        } else {
            lecturas.joinToString("\r\n") { it.numero } + "\r\n"
        }

    private fun bytes(csv: Boolean): ByteArray {
        val datos = contenido(csv).toByteArray(Charsets.UTF_8)
        // BOM para que Excel abra bien el CSV con tildes
        return if (csv) byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + datos else datos
    }

    private fun exportar(csv: Boolean) {
        if (lecturas.isEmpty()) {
            toast("La lista está vacía")
            return
        }
        val ext = if (csv) "csv" else "txt"
        val nombre = "lecturas_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.$ext"
        AlertDialog.Builder(this)
            .setTitle("Exportar ${ext.uppercase()} (${lecturas.size} números)")
            .setItems(arrayOf("Guardar en el móvil", "Compartir (correo, WhatsApp, Drive…)")) { _, i ->
                if (i == 0) {
                    if (csv) guardarCsv.launch(nombre) else guardarTxt.launch(nombre)
                } else {
                    compartir(nombre, csv)
                }
            }
            .show()
    }

    private fun escribirEn(uri: Uri, csv: Boolean) {
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(bytes(csv)) }
            toast("Fichero guardado")
        } catch (e: Exception) {
            toast("No se pudo guardar: ${e.message}")
        }
    }

    private fun compartir(nombre: String, csv: Boolean) {
        try {
            val dir = File(cacheDir, "export").apply { mkdirs() }
            val f = File(dir, nombre)
            f.writeBytes(bytes(csv))
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = if (csv) "text/csv" else "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, nombre)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Enviar lecturas"))
        } catch (e: Exception) {
            toast("No se pudo compartir: ${e.message}")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
