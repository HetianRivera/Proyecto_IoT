package com.example.grupo_pereda_rivera   // <-- usa tu package real

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.UUID

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_BT_PERMS = 100
        private val PERMISSIONS_BLUETOOTH = arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    private lateinit var txtEstado: TextView
    private lateinit var txtValor: TextView
    private lateinit var btnBuscar: Button
    private lateinit var btnConectar: Button
    private lateinit var listDispositivos: ListView

    private lateinit var btAdapter: BluetoothAdapter
    private val dispositivos = mutableListOf<BluetoothDevice>()
    private lateinit var listaAdapter: ArrayAdapter<String>

    private var dispositivoSeleccionado: BluetoothDevice? = null
    private var socket: BluetoothSocket? = null
    private var leyendo = false

    private val UUID_SPP: UUID =
        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    // Receptor para resultados de la búsqueda Bluetooth
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device?.let {
                        if (!dispositivos.contains(it)) {
                            dispositivos.add(it)
                            val nombre = it.name ?: "Sin nombre"
                            listaAdapter.add("$nombre (${it.address})")
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    txtEstado.text = "Búsqueda finalizada"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Referencias UI
        txtEstado = findViewById(R.id.txtEstado)
        txtValor = findViewById(R.id.txtValor)
        btnBuscar = findViewById(R.id.btnBuscar)
        btnConectar = findViewById(R.id.btnConectar)
        listDispositivos = findViewById(R.id.listDispositivos)

        // Adapter Bluetooth
        btAdapter = BluetoothAdapter.getDefaultAdapter()
        if (btAdapter == null) {
            txtEstado.text = "Bluetooth no disponible en este dispositivo"
            btnBuscar.isEnabled = false
            btnConectar.isEnabled = false
            return
        }

        // Lista de dispositivos
        listaAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_single_choice
        )
        listDispositivos.adapter = listaAdapter
        listDispositivos.choiceMode = ListView.CHOICE_MODE_SINGLE

        listDispositivos.setOnItemClickListener { _, _, position, _ ->
            dispositivoSeleccionado = dispositivos[position]
            txtEstado.text = "Seleccionado: ${dispositivoSeleccionado?.name}"
        }

        // Pedimos permisos al inicio
        if (tienePermisosBluetooth()) {
            inicializarBluetooth()
        } else {
            pedirPermisosBluetooth()
        }
    }

    // ---------- PERMISOS ----------

    private fun tienePermisosBluetooth(): Boolean {
        return PERMISSIONS_BLUETOOTH.all { perm ->
            ContextCompat.checkSelfPermission(this, perm) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    private fun pedirPermisosBluetooth() {
        ActivityCompat.requestPermissions(
            this,
            PERMISSIONS_BLUETOOTH,
            REQUEST_BT_PERMS
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BT_PERMS) {
            if (grantResults.isNotEmpty() &&
                grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            ) {
                inicializarBluetooth()
            } else {
                txtEstado.text = "Sin permisos Bluetooth"
                Toast.makeText(
                    this,
                    "Debes aceptar los permisos para usar la app",
                    Toast.LENGTH_LONG
                ).show()
                btnBuscar.isEnabled = false
                btnConectar.isEnabled = false
            }
        }
    }

    // ---------- LÓGICA BLUETOOTH ----------

    private fun inicializarBluetooth() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiver(receiver, filter)

        txtEstado.text = "Listo para buscar dispositivos"

        btnBuscar.setOnClickListener {
            buscarDispositivos()
        }

        btnConectar.setOnClickListener {
            if (!leyendo) conectar() else desconectar()
        }
    }

    private fun buscarDispositivos() {
        if (!tienePermisosBluetooth()) {
            pedirPermisosBluetooth()
            return
        }

        if (!btAdapter.isEnabled) {
            // pide al usuario que encienda BT
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }

        listaAdapter.clear()
        dispositivos.clear()

        txtEstado.text = "Buscando dispositivos..."
        btAdapter.startDiscovery()
    }

    private fun conectar() {
        if (!tienePermisosBluetooth()) {
            pedirPermisosBluetooth()
            return
        }

        val device = dispositivoSeleccionado
            ?: run {
                Toast.makeText(this, "Selecciona un dispositivo", Toast.LENGTH_SHORT).show()
                return
            }

        txtEstado.text = "Conectando a ${device.name}..."
        btnConectar.isEnabled = false

        Thread {
            try {
                btAdapter.cancelDiscovery()
                val tmpSocket = device.createRfcommSocketToServiceRecord(UUID_SPP)
                tmpSocket.connect()
                socket = tmpSocket
                leyendo = true

                runOnUiThread {
                    txtEstado.text = "Conectado a ${device.name}"
                    btnConectar.text = "Desconectar"
                    btnConectar.isEnabled = true
                }

                leerDatos(tmpSocket)
            } catch (e: IOException) {
                e.printStackTrace()
                runOnUiThread {
                    txtEstado.text = "Error al conectar"
                    btnConectar.isEnabled = true
                    btnConectar.text = "Conectar seleccionado"
                }
            }
        }.start()
    }

    private fun leerDatos(btSocket: BluetoothSocket) {
        val input = btSocket.inputStream
        val buffer = ByteArray(1024)
        var acumulador = ""

        while (leyendo) {
            try {
                val bytes = input.read(buffer)
                if (bytes > 0) {
                    val texto = String(buffer, 0, bytes)
                    acumulador += texto

                    var fin = acumulador.indexOf(';')
                    while (fin != -1) {
                        val trama = acumulador.substring(0, fin + 1)
                        procesarTrama(trama)
                        acumulador = acumulador.substring(fin + 1)
                        fin = acumulador.indexOf(';')
                    }
                }
            } catch (e: IOException) {
                e.printStackTrace()
                leyendo = false
                runOnUiThread {
                    txtEstado.text = "Conexión perdida"
                    btnConectar.text = "Conectar seleccionado"
                }
            }
        }
    }

    private fun procesarTrama(trama: String) {
        val limpia = trama.trim()

        runOnUiThread {
            txtEstado.text = "Trama: $limpia"
        }

        if (limpia.startsWith("#AGUA:")) {
            val estado = limpia.substringAfter("#AGUA:").substringBefore(";")

            runOnUiThread {
                txtValor.text = estado
            }
        }
    }



    private fun desconectar() {
        leyendo = false
        try {
            socket?.close()
        } catch (_: IOException) { }
        socket = null
        txtEstado.text = "Desconectado"
        btnConectar.text = "Conectar seleccionado"
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) { }
        desconectar()
    }
}
