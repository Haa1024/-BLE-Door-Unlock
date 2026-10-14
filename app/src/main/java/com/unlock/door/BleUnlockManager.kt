package com.unlock.door

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * BLE 开门管理器 - 事件驱动版
 *
 * 零轮询，零盲等。所有异步操作通过回调 → CompletableDeferred 精确触发。
 */
class BleUnlockManager(
    private val context: Context,
    private val onLog: (String) -> Unit
) {
    init {
        // 确保 SettingsManager 已初始化（无论从 Activity 还是 Service 进入）
        if (!SettingsManager.isInitialized()) {
            SettingsManager.init(context.applicationContext)
        }
    }

    companion object {
        // 主界面和小组件共用操作锁，避免同时向同一门锁发送命令。
        private val operationMutex = Mutex()
    }

    enum class State { IDLE, SCANNING, CONNECTING, UNLOCKING, SUCCESS, FAILED }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state
    private val _statusText = MutableStateFlow("就绪")
    val statusText: StateFlow<String> = _statusText

    private val bluetoothManager by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager.adapter

    @Volatile private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    // 事件驱动：每个 BLE 操作对应一个 CompletableDeferred
    @Volatile private var servicesDeferred: CompletableDeferred<Unit>? = null
    @Volatile private var responseDeferred: CompletableDeferred<ByteArray>? = null
    @Volatile private var cccdDeferred: CompletableDeferred<Unit>? = null
    @Volatile private var expectedResponseCommand: Int? = null

    private val serviceUuid = UUID.fromString(Crypto.SERVICE_UUID)
    private val writeUuid = UUID.fromString(Crypto.WRITE_UUID)
    private val readUuid = UUID.fromString(Crypto.READ_UUID)

    private fun log(msg: String) {
        onLog(msg)
        android.util.Log.d("BleUnlock", msg)
    }

    fun hasPermissions(): Boolean {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val allGranted = perms.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!allGranted) {
            log("权限不足: ${perms.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }}")
        }
        return allGranted
    }

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    private var attemptDescription = ""
    private var doorOpened = false

    private fun progress(text: String, state: State = State.UNLOCKING) {
        _state.value = state
        _statusText.value = if (attemptDescription.isBlank()) text else "$attemptDescription\n$text"
    }

    suspend fun unlock() {
        if (!operationMutex.tryLock()) {
            _state.value = State.FAILED
            _statusText.value = "另一个开门任务正在进行，请等待完成"
            throw UserActionRequiredException(_statusText.value)
        }
        try {
            attemptDescription = ""
            doorOpened = false
            progress("准备中…")
            if (!hasPermissions()) throw UserActionRequiredException("请先打开 App 授权蓝牙权限")
            if (!isBluetoothEnabled()) throw UserActionRequiredException("请先开启蓝牙")
            if (!SettingsManager.isConfigured() || SettingsManager.deviceId <= 0 || SettingsManager.projectId <= 0 ||
                !SettingsManager.chainKey.all { it in "0123456789abcdefABCDEF" }) {
                throw UserActionRequiredException("门锁参数不完整或凭证格式错误，请在设置中重新获取参数")
            }
            // 留出 shortService 的退出时间，避免多轮网络恢复超过系统时限。
            try {
                withTimeout(150_000) {
                    UnlockRecovery(
                        unlockOnce = { unlockOnce() },
                        refreshCredentials = { refreshChainKeyFromServer() },
                        login = { relogin() },
                        onProgress = {
                            attemptDescription = it
                            _state.value = State.UNLOCKING
                            _statusText.value = it
                            log(it)
                        },
                    ).run()
                }
            } catch (e: TimeoutCancellationException) {
                if (doorOpened) {
                    _state.value = State.SUCCESS
                    _statusText.value = "开门成功，凭证更新超时，下次可能需要联网刷新 🔓"
                } else {
                    throw Exception("开门恢复耗时过长，已停止尝试，请检查网络和门锁连接", e)
                }
            }
        } catch (e: CancellationException) {
            _state.value = State.IDLE
            _statusText.value = "开门已取消"
            throw e
        } catch (e: Exception) {
            log("失败: ${e.message}")
            _state.value = State.FAILED
            _statusText.value = e.message ?: "开门失败"
            throw e
        } finally {
            disconnect()
            operationMutex.unlock()
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun unlockOnce() {
        try {
            if (!isBluetoothEnabled()) throw UserActionRequiredException("蓝牙已关闭，请开启后重试")
            val mac = SettingsManager.deviceMac.takeIf { it.isNotBlank() }
            if (mac != null) {
                progress("连接门锁…", State.CONNECTING)
                connectAndDiscover(mac)
            } else {
                progress("扫描门锁…", State.SCANNING)
                val address = scanDevice() ?: throw Exception("未发现门锁设备 (XN-${SettingsManager.deviceId})，请靠近门锁")
                progress("连接门锁…", State.CONNECTING)
                connectAndDiscover(address)
            }
            progress("发送开门凭证…")
            val head = Crypto.buildCredentialHead(SettingsManager.chainKey, SettingsManager.deviceId, SettingsManager.projectId)
            val headResult = exchange(head, Crypto.RESP_CERT_HEAD, "0x74")
            if (!headResult.isSuccess) throw Exception("0x74 凭证头被拒绝：错误码 ${headResult.resultCode}")

            val packs = Crypto.buildCredentialPacks(SettingsManager.chainKey, headResult.ran, SettingsManager.deviceId, SettingsManager.projectId)
            for ((i, packet) in packs.withIndex()) {
                progress("传输开门凭证（${i + 1}/${packs.size}）…")
                if (!exchange(packet, Crypto.RESP_CERT_PACK, "0x75 分包 ${i + 1}").isSuccess) {
                    throw Exception("0x75 分包 ${i + 1} 失败")
                }
            }
            progress("凭证已传输，正在开锁…")
            val result = exchange(Crypto.buildUnlockCommand(SettingsManager.deviceId), Crypto.RESP_CHAIN_KEY, "0x78")
            log("0x78 响应: resultCode=${result.resultCode}")
            when {
                result.isSuccess -> {
                    doorOpened = true
                    _state.value = State.SUCCESS
                    _statusText.value = if (result.doorAlreadyOpen) "门已打开 🔓" else "开门成功 🔓"
                }
                result.needUpdateKey -> {
                    doorOpened = true
                    // 门已开；更新失败不能把开门结果改成失败，也不能触发再次开锁。
                    progress("门已打开，正在更新下次开门凭证…")
                    val key = try {
                        withTimeoutOrNull(10_000) { updateChainKey() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log("凭证更新失败: ${e.message}")
                        null
                    }
                    val valid = key != null && key.length == 64 && !key.all { it == '0' }
                    if (valid) SettingsManager.chainKey = key!!
                    _state.value = State.SUCCESS
                    _statusText.value = if (valid) "开门成功，凭证已更新 🔓" else "开门成功，凭证更新失败，下次可能需要联网刷新 🔓"
                }
                result.resultCode == 24 -> throw CredentialRefreshRequiredException("开门凭证已过期，需要联网刷新")
                result.resultCode == 25 -> throw CredentialRefreshRequiredException("离线开门次数已用完，需要联网刷新")
                else -> throw Exception("开锁失败: 错误码 ${result.resultCode}")
            }
        } finally {
            // 每一轮都关闭旧 GATT；下一轮重新发现服务、启用通知和发送凭证。
            disconnect()
        }
    }

    private suspend fun exchange(packet: ByteArray, expectedCommand: Int, stage: String): Crypto.ParseResult {
        val response = sendPacketAndWait(packet, expectedCommand)
            ?: throw Exception("$stage 超时，门锁未回复")
        val result = Crypto.parseResponse(response, SettingsManager.deviceId)
        if (!result.success) throw Exception("$stage 解析失败: ${result.error}")
        return result
    }

    // ─── 密钥更新 ───
    @SuppressLint("MissingPermission")
    private suspend fun updateChainKey(): String? {
        val reqPkt = Crypto.buildRechainKeyRequest(SettingsManager.credentialId, SettingsManager.deviceId)
        val resp = sendPacketAndWait(reqPkt) ?: return null
        val result = Crypto.parseResponse(resp, SettingsManager.deviceId)
        if (!result.isSuccess) return null

        val packCount = result.packCount
        val totalLen = result.totalLength
        val expectedCrc = result.crc8Val
        log("密钥分包: $packCount, 总长: $totalLen")

        val packs = mutableListOf<Crypto.PackData>()
        for (i in 0 until packCount) {
            val readPkt = Crypto.buildPackReadRequest(i, SettingsManager.deviceId)
            val readResp = sendPacketAndWait(readPkt) ?: return null
            val readResult = Crypto.parseResponse(readResp, SettingsManager.deviceId)
            if (!readResult.success) return null
            packs.add(Crypto.PackData(readResult.packIndex, readResult.resultData))
        }

        return try {
            Crypto.bytesToHex(Crypto.assembleChainKey(packs, totalLen, expectedCrc))
        } catch (e: Exception) {
            log("chainKey 拼装失败: ${e.message}")
            null
        }
    }

    private suspend fun relogin() {
        val phone = SettingsManager.loginPhone
        val password = SettingsManager.loginPassword
        if (phone.isBlank() || password.isBlank()) {
            throw UserActionRequiredException("需要重新登录，但未保存账号密码，请先在设置中登录")
        }
        val result = ServerApi.login(phone, password)
        if (result.requiresVerification) throw VerificationRequiredException()
        if (!result.success) throw Exception(result.error.ifBlank { "重新登录失败" })
    }

    private suspend fun refreshChainKeyFromServer() {
        val address = ServerApi.fetchAddress(targetDeviceId = SettingsManager.deviceId)
        // 多门锁账号只能保存当前设备的凭证，不能用列表第一个设备覆盖。
        if (address.deviceId != SettingsManager.deviceId) throw Exception("服务器未返回当前门锁的凭证")
        val key = address.chainKey
        if (key.length != 64 || !key.all { it in "0123456789abcdefABCDEF" } || key.all { it == '0' }) {
            throw Exception("服务器未返回有效的开门凭证，请在设置中重新获取参数")
        }
        SettingsManager.chainKey = key
        SettingsManager.credentialId = address.credentialId
    }

    @SuppressLint("MissingPermission")
    private suspend fun scanDevice(): String? = withContext(Dispatchers.Main) {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: throw Exception("无法启动蓝牙扫描")
        val targetName = "XN-${SettingsManager.deviceId}"
        withTimeoutOrNull(5000) {
            suspendCancellableCoroutine { cont ->
                val callback = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        if (!cont.isActive) return
                        val name = result.device.name ?: result.scanRecord?.deviceName ?: ""
                        if (name == targetName) {
                            scanner.stopScan(this)
                            cont.resume(result.device.address)
                        }
                    }
                    override fun onScanFailed(errorCode: Int) {
                        if (cont.isActive) cont.resumeWithException(Exception("蓝牙扫描失败：错误码 $errorCode"))
                    }
                }
                cont.invokeOnCancellation {
                    try { scanner.stopScan(callback) } catch (_: SecurityException) { }
                }
                try {
                    scanner.startScan(emptyList(), ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
                } catch (e: SecurityException) {
                    if (cont.isActive) cont.resumeWithException(UserActionRequiredException("缺少蓝牙扫描权限，请在设置中授权"))
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectAndDiscover(address: String) = withContext(Dispatchers.Main) {
        val device = bluetoothAdapter?.getRemoteDevice(address) ?: throw Exception("无法获取远程设备")
        val ready = CompletableDeferred<Unit>()
        servicesDeferred = ready
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
            ?: throw Exception("无法建立蓝牙连接")
        try {
            withTimeout(5000) { ready.await() }
        } catch (e: TimeoutCancellationException) {
            throw Exception("服务发现超时，请靠近门锁", e)
        }
        val gatt = bluetoothGatt ?: throw Exception("门锁连接已断开")
        val service = gatt.getService(serviceUuid) ?: throw Exception("未找到 BLE 服务")
        writeCharacteristic = service.getCharacteristic(writeUuid) ?: throw Exception("未找到 write characteristic")
        val read = service.getCharacteristic(readUuid) ?: throw Exception("未找到 read characteristic")
        if (!gatt.setCharacteristicNotification(read, true)) throw Exception("无法开启门锁响应通知")
        val cccd = read.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            ?: throw Exception("门锁缺少响应通知配置")
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val notified = CompletableDeferred<Unit>()
        cccdDeferred = notified
        if (!gatt.writeDescriptor(cccd)) throw Exception("响应通知配置写入失败")
        try {
            withTimeout(1500) { notified.await() }
        } catch (e: TimeoutCancellationException) {
            throw Exception("开启门锁响应通知超时", e)
        } finally {
            cccdDeferred = null
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendPacketAndWait(packet: ByteArray, expectedCommand: Int = packet[2].toInt() and 0xFF): String? = withContext(Dispatchers.Main) {
        val gatt = bluetoothGatt ?: throw Exception("门锁连接已断开")
        val char = writeCharacteristic ?: throw Exception("门锁写入通道不可用")
        val deferred = CompletableDeferred<ByteArray>()
        responseDeferred = deferred
        expectedResponseCommand = expectedCommand
        try {
            char.value = packet
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            if (!gatt.writeCharacteristic(char)) throw Exception("蓝牙数据写入失败（命令 0x${expectedCommand.toString(16)}）")
            try {
                Crypto.bytesToHex(withTimeout(3000) { deferred.await() })
            } catch (e: TimeoutCancellationException) {
                null
            }
        } finally {
            responseDeferred = null
            expectedResponseCommand = null
        }
    }

    fun disconnect() {
        // 先使旧回调失效，再关闭旧连接。
        val old = bluetoothGatt
        bluetoothGatt = null
        servicesDeferred?.cancel()
        cccdDeferred?.cancel()
        responseDeferred?.cancel()
        servicesDeferred = null
        cccdDeferred = null
        responseDeferred = null
        expectedResponseCommand = null
        writeCharacteristic = null
        try {
            old?.close()
        } catch (_: SecurityException) {
            log("蓝牙权限已撤销，连接清理已停止")
        }
    }

    fun cancel() {
        disconnect()
        _state.value = State.IDLE
        _statusText.value = "就绪"
    }

    private fun failPending(message: String) {
        val error = Exception(message)
        servicesDeferred?.completeExceptionally(error)
        cccdDeferred?.completeExceptionally(error)
        responseDeferred?.completeExceptionally(error)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (gatt !== bluetoothGatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failPending("蓝牙连接失败：GATT 错误码 $status")
            } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (!gatt.discoverServices()) failPending("无法开始发现门锁服务")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                failPending("门锁蓝牙连接已断开")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (gatt !== bluetoothGatt) return
            if (status == BluetoothGatt.GATT_SUCCESS) servicesDeferred?.complete(Unit)
            else failPending("门锁服务发现失败：GATT 错误码 $status")
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (gatt !== bluetoothGatt || characteristic.uuid != readUuid) return
            val data = characteristic.value?.copyOf() ?: return
            if (data.size < 20) {
                responseDeferred?.completeExceptionally(Exception("门锁响应长度错误：${data.size}"))
            } else if ((data[2].toInt() and 0xFF) == expectedResponseCommand) {
                responseDeferred?.complete(data)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (gatt !== bluetoothGatt || characteristic.uuid != writeUuid) return
            if (status != BluetoothGatt.GATT_SUCCESS) failPending("蓝牙数据写入失败：GATT 错误码 $status")
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (gatt !== bluetoothGatt) return
            if (status == BluetoothGatt.GATT_SUCCESS) cccdDeferred?.complete(Unit)
            else failPending("开启门锁响应通知失败：GATT 错误码 $status")
        }
    }
}
