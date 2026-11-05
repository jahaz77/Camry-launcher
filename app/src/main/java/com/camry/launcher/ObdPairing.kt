package com.camry.launcher

import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Toast

/**
 * Busca y empareja el adaptador OBD2 desde el launcher, usando el Bluetooth de Android
 * (el estéreo esconde sus ajustes). Pone solo el PIN 1234 o 0000.
 */
class ObdPairing(private val act: Activity, private val dialogBuilder: () -> AlertDialog.Builder) {

    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val found = mutableListOf<BluetoothDevice>()
    private lateinit var listAdapter: ArrayAdapter<String>
    private var dialog: AlertDialog? = null
    private var pinTry = 0
    private var target: BluetoothDevice? = null

    private fun toast(m: String) = Toast.makeText(act, m, Toast.LENGTH_LONG).show()

    /** Estado del Bluetooth de Android, para el diagnóstico. */
    companion object {
        fun status(): String {
            val a = BluetoothAdapter.getDefaultAdapter() ?: return "Bluetooth de Android: NO EXISTE en este estéreo"
            val st = when (a.state) {
                BluetoothAdapter.STATE_ON -> "encendido"
                BluetoothAdapter.STATE_OFF -> "APAGADO"
                BluetoothAdapter.STATE_TURNING_ON -> "encendiendo"
                else -> "apagándose"
            }
            val bonded = runCatching { a.bondedDevices?.joinToString { it.name ?: it.address } }.getOrNull().orEmpty()
            return "Bluetooth de Android: $st; emparejados: ${bonded.ifEmpty { "ninguno" }}"
        }

        private fun looksObd(name: String?): Boolean {
            val n = (name ?: "").uppercase()
            return n.contains("OBD") || n.contains("ELM") || n.contains("V-LINK") || n.contains("VLINK") ||
                n.contains("KONNWEI") || n.contains("VGATE") || n.contains("ICAR") || n.contains("CAR SCANNER")
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    @Suppress("DEPRECATION")
                    val d: BluetoothDevice = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) ?: return
                    if (found.any { it.address == d.address }) return
                    found.add(d)
                    found.sortByDescending { looksObd(it.name) }
                    refreshList()
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> dialog?.setTitle(
                    if (found.isEmpty()) "No encontré nada. ¿Motor encendido?" else "Toca tu adaptador OBD2"
                )
                BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                    @Suppress("DEPRECATION")
                    val d: BluetoothDevice = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) ?: return
                    if (d.address != target?.address) return
                    val pin = if (pinTry == 0) "1234" else "0000"
                    runCatching { d.setPin(pin.toByteArray()) }
                    runCatching { abortBroadcast() }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    @Suppress("DEPRECATION")
                    val d: BluetoothDevice = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) ?: return
                    if (d.address != target?.address) return
                    when (i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                        BluetoothDevice.BOND_BONDED -> {
                            EventLog.add(act, "OBD2 emparejado: ${d.name}")
                            Obd.setAddress(act, d.address)
                            toast("Listo: ${d.name} emparejado. Conectando…")
                            finish()
                        }
                        BluetoothDevice.BOND_NONE -> {
                            if (pinTry == 0) {
                                pinTry = 1
                                toast("Probando con PIN 0000…")
                                handler.postDelayed({ runCatching { d.createBond() } }, 1200L)
                            } else {
                                toast("No se pudo emparejar. Revisa que el adaptador tenga corriente e inténtalo otra vez.")
                                EventLog.add(act, "OBD2: falló el emparejamiento con ${d.name}")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun refreshList() {
        if (!::listAdapter.isInitialized) return
        listAdapter.clear()
        listAdapter.addAll(found.map { (it.name ?: "Sin nombre") + "  ·  " + it.address + if (looksObd(it.name)) "  ← OBD2" else "" })
        listAdapter.notifyDataSetChanged()
    }

    fun start() {
        val a = adapter
        if (a == null) {
            toast("Este estéreo no deja usar el Bluetooth de Android a las apps. Habría que usar un OBD2 por WiFi.")
            return
        }
        if (!a.isEnabled) {
            toast("Encendiendo el Bluetooth de Android…")
            @Suppress("DEPRECATION")
            runCatching { a.enable() }
            handler.postDelayed({ if (a.isEnabled) start() else toast("No pude encender el Bluetooth de Android") }, 3000L)
            return
        }
        act.registerReceiver(receiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY - 1
        })
        // Los ya emparejados también aparecen en la lista
        runCatching { a.bondedDevices?.let { found.addAll(it) } }
        found.sortByDescending { looksObd(it.name) }
        listAdapter = ArrayAdapter(act, android.R.layout.simple_list_item_1, mutableListOf())
        refreshList()
        dialog = dialogBuilder()
            .setTitle("Buscando adaptadores…")
            .setAdapter(listAdapter) { _, which -> pick(found[which]) }
            .setNegativeButton("Cerrar") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
        runCatching { a.cancelDiscovery(); a.startDiscovery() }
            .onFailure { toast("No pude buscar: ${it.message}") }
    }

    private fun pick(d: BluetoothDevice) {
        adapter?.cancelDiscovery()
        target = d
        if (d.bondState == BluetoothDevice.BOND_BONDED) {
            Obd.setAddress(act, d.address)
            toast("Conectando a ${d.name}…")
            finish()
            return
        }
        pinTry = 0
        toast("Emparejando con ${d.name ?: d.address} (PIN 1234)…")
        // la lista se cierra al elegir; el receptor sigue escuchando hasta terminar
        if (!runCatching { d.createBond() }.getOrDefault(false)) toast("No se pudo iniciar el emparejamiento")
        handler.postDelayed({ finish() }, 60_000L)
    }

    private fun finish() {
        runCatching { adapter?.cancelDiscovery() }
        runCatching { act.unregisterReceiver(receiver) }
        runCatching { dialog?.dismiss() }
    }
}
