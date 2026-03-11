package com.example.test_ble;

import static android.bluetooth.BluetoothDevice.BOND_BONDED;
import static android.bluetooth.BluetoothDevice.BOND_NONE;
import static android.bluetooth.BluetoothDevice.TRANSPORT_LE;
import static android.bluetooth.BluetoothGatt.GATT_SUCCESS;
import static android.bluetooth.BluetoothGattCharacteristic.PERMISSION_READ;
import static android.bluetooth.BluetoothGattCharacteristic.PERMISSION_WRITE;
import static android.bluetooth.BluetoothGattCharacteristic.PROPERTY_INDICATE;
import static android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY;
import static android.bluetooth.BluetoothGattCharacteristic.PROPERTY_READ;
import static android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE;
import static android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;

import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import com.example.test_ble.R;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import java.util.UUID;

//класс обработчик соедения по BLE с датчиком rs485
//команда отправляется в характеристик с UUID FFE1
//для получшения данных необхдимо полкльчится к уведомлениям от этой характиристики

public class BLE_Processor
{
    private static final String TAG = "BLE_RS485";

    // UUID из логов
    public static final UUID FFE1_UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    private static final UUID CCC_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeScanner mScanner;
    private BluetoothDevice mBluetoothDevice;
    private BluetoothGatt mBluetoothGatt;
    private BluetoothGattCharacteristic mFFE1Characteristic;

    private final Context mContext;
    private final Handler mHandler;
    private BLEStateInterface mBLEStateInterface;

    private boolean mIsScanning = false;
    private boolean mNotificationsEnabled = false;

    public interface BLEStateInterface {
        void onScanStart();
        void onScanEnd(boolean success);
        void onConnectEnd(boolean success);
        void onResponse(String data);
        void onResponse(byte[] data);
        void onDisconnect();
        void onError(String error);
    }


    public void registerInterface(BLEStateInterface callback) {
        mBLEStateInterface = callback;
    }

    //проверка разршений при вызове функиций bluettooth
    private boolean checkPermissions() {
        return ContextCompat.checkSelfPermission(mContext, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(mContext, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(mContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean checkBluetoothConnectPermission() {
        return ContextCompat.checkSelfPermission(mContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    public boolean isConnected() {
        return mBluetoothGatt != null && mNotificationsEnabled;
    }

    public String getDeviceAddress() {
        return mBluetoothDevice != null ? mBluetoothDevice.getAddress() : null;
    }

    public boolean isScanning() {return  mIsScanning;}

    public BLE_Processor(Context context) {
        mContext = context.getApplicationContext();
        mHandler = new Handler(Looper.getMainLooper());
    }

    //гинициализация BlueTooth
    public boolean initialize() {
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mBluetoothAdapter == null) {
            notifyError("Bluetooth не поддерживается");
            return false;
        }

        if (!mBluetoothAdapter.isEnabled()) {
            notifyError("Bluetooth выключен");
            return false;
        }

        mScanner = mBluetoothAdapter.getBluetoothLeScanner();
        if (mScanner == null) {
            notifyError("Не удалось получить сканер BLE");
            return false;
        }

        return true;
    }

    //запуск сканирования по MAc адерсу
    public void startScan(String deviceAddress) {
        if (!checkPermissions()) {
            notifyError("Недостаточно разрешений");
            return;
        }
        if (mIsScanning) {
            return;
        }
        mIsScanning = true;
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        List<android.bluetooth.le.ScanFilter> filters = new ArrayList<>();
        if (deviceAddress != null) {
            filters.add(new android.bluetooth.le.ScanFilter.Builder()
                    .setDeviceAddress(deviceAddress)
                    .build());
        }
        try {
            mScanner.startScan(filters, settings, mScanCallback);
            //посылем каллбек запуска сканивроания
            notifyScanStart();
            Log.d(TAG, "Сканирование начато");
        } catch (SecurityException e) {
            Log.e(TAG, "Ошибка безопасности при сканировании", e);
            notifyError("Ошибка безопасности: " + e.getMessage());
            mIsScanning = false;
        }
    }

    //перрыввем сканирование
    public void stopScan() {
        if (mScanner != null && mIsScanning) {
            try {
                mScanner.stopScan(mScanCallback);
                mIsScanning = false;
                Log.d(TAG, "Сканирование остановлено");
            } catch (SecurityException e) {
                Log.e(TAG, "Ошибка безопасности при остановке сканирования", e);
            }
        }
    }

    //празрываем соедениение
    public void disconnect() {
        if (mBluetoothGatt != null && checkBluetoothConnectPermission()) {
            try {
                mBluetoothGatt.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "Ошибка при отключении", e);
            }
        }
    }

    public void cleanup() {
        stopScan();
        disconnect();

        if (mBluetoothGatt != null && checkBluetoothConnectPermission()) {
            try {
                mBluetoothGatt.close();
                mBluetoothGatt = null;
            } catch (Exception e) {
                Log.e(TAG, "Ошибка при закрытии GATT", e);
            }
        }

        mFFE1Characteristic = null;
        mBluetoothDevice = null;
        mNotificationsEnabled = false;
    }

    //формируем  список адресов подключенных ранее устройств
    public boolean getBondedDevices(ArrayList<String> nameList, ArrayList<String> macList)
    {
        if (mBluetoothAdapter != null && checkPermissions() && checkBluetoothConnectPermission())
        {
            //получаем список подключенных ранее устройств
            Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();
            if (pairedDevices.size() > 0) {
                for (BluetoothDevice device : pairedDevices) {
                    String dev = device.getAddress();
                    macList.add(dev);
                    nameList.add(device.getName());
                    Log.i(TAG, "dev" + dev);
                }
                macList.add("48:87:2D:9D:05:5F");
                nameList.add("RS-485");
            }
            return true;
        }else  return false;
    }



    //каллбек сканирвоания устройства
    private final ScanCallback mScanCallback = new ScanCallback() {
        @Override
        //получили результат сканирования
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);

            mBluetoothDevice = result.getDevice();
            if (mBluetoothDevice == null) {
                return;
            }

            Log.i(TAG, "Найдено устройство: " + mBluetoothDevice.getAddress() +
                    ", RSSI: " + result.getRssi());
            stopScan();
            notifyScanEnd(true);
            if (checkBluetoothConnectPermission()) {
                try {
                    //пытаемся установить содеиение по протокул GAtt
                    mBluetoothGatt = mBluetoothDevice.connectGatt(
                            mContext,
                            false, // autoConnect = false
                            mGattCallback,
                            BluetoothDevice.TRANSPORT_LE
                    );
                    Log.d(TAG, "Подключение к " + mBluetoothDevice.getAddress());
                } catch (SecurityException e) {
                    Log.e(TAG, "SecurityException при подключении", e);
                    notifyError("Ошибка безопасности при подключении");
                }
            }
        }

        //ошибка сканирования
        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            mIsScanning = false;
            notifyScanEnd(false);
            Log.e(TAG, "Сканирование не удалось, код: " + errorCode);
        }
    };

    //каллбек результатов Gatt
    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback()
    {
        //обработка полкдчения/отключения от уствройства
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            Log.d(TAG, "onConnectionStateChange: newState=" + newState + ", status=" + status);

            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Подключено к устройству");

                // Запускаем поиск сервисов
                mHandler.postDelayed(() -> {
                    try {
                        boolean discoveryStarted = gatt.discoverServices();
                        Log.d(TAG, "discoverServices: " + discoveryStarted);

                        if (!discoveryStarted) {
                            notifyConnectEnd(false);
                        }
                    } catch (SecurityException e) {
                        Log.e(TAG, "SecurityException в discoverServices", e);
                        notifyConnectEnd(false);
                    }
                }, 100); // Небольшая задержка

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED)
            {
                Log.i(TAG, "Отключено от устройства");
                mNotificationsEnabled = false;
                mFFE1Characteristic = null;
                mHandler.post(() -> {
                    if (mBLEStateInterface != null) {
                        mBLEStateInterface.onDisconnect();
                    }

                    if (gatt != null && checkBluetoothConnectPermission()) {
                        try {
                            gatt.close();
                        } catch (Exception e) {
                            Log.e(TAG, "Ошибка при закрытии GATT", e);
                        }
                    }
                    mBluetoothGatt = null;
                });
            }
        }

        //поллучили набор сервисо и характиреристик
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            Log.d(TAG, "onServicesDiscovered: status=" + status);

            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Логируем все сервисы для отладки
                logAllServices(gatt);
                // Ищем характеристику FFE1
                mFFE1Characteristic = findCharacteristic(gatt);
                if (mFFE1Characteristic != null) {
                    // Включаем уведомления
                    enableNotifications(gatt, mFFE1Characteristic);
                    Log.d(TAG, "Слушатель уведомлений установлен");
                }
                else {
                    Log.e(TAG, "Характеристика FFE1 не найдена");
                    notifyConnectEnd(false);
                }

            } else {
                Log.e(TAG, "Ошибка при поиске сервисов: " + status);
                notifyConnectEnd(false);
            }
        }

        //устаниовка десриптора сервиса
        @Override
        public void onDescriptorWrite(BluetoothGatt gatt,
                                      BluetoothGattDescriptor descriptor,
                                      int status) {
            Log.d(TAG, "onDescriptorWrite: status=" + status +
                    ", descriptor=" + descriptor.getUuid());

            if (status == BluetoothGatt.GATT_SUCCESS) {
                byte[] value = descriptor.getValue();
                if (value != null && value.length > 0) {
                    Log.d(TAG, "Значение дескриптора: " + bytesToHex(value));

                    // Проверяем, что уведомления включены
                    if (value[0] == 0x01 || value[0] == 0x02) {
                        mNotificationsEnabled = true;
                        Log.i(TAG, "✓ Уведомления успешно включены!");

                        mHandler.post(() -> {
                            if (mBLEStateInterface != null) {
                                mBLEStateInterface.onConnectEnd(true);
                            }
                        });

                        return;
                    }
                }
            }

            Log.e(TAG, "Не удалось включить уведомления");
            mNotificationsEnabled = false;
            notifyConnectEnd(false);
        }

        //обработчик уведомления изменения характеристики
        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                            BluetoothGattCharacteristic characteristic)
        {
            byte[] value = characteristic.getValue();
            if (value != null &&  value.length > 0) {
                String data = bytesToHex(value);
                Log.i(TAG, "Получены данные: "  + " (hex: " + bytesToHex(value) + ")");

                mHandler.post(() -> {
                    if (mBLEStateInterface != null) {
                        mBLEStateInterface.onResponse(data);
                        mBLEStateInterface.onResponse(value);
                    }
                });
            } else {
                Log.d(TAG, "Пустые данные в уведомлении");
            }
        }

        //сотбытите отправки даннхх
        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt,
                                          BluetoothGattCharacteristic characteristic,
                                          int status) {
            Log.d(TAG, "onCharacteristicWrite: status=" + status);

            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Данные успешно отправлены");
            } else {
                Log.e(TAG, "Ошибка отправки данных: " + status);
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic characteristic,
                                         int status) {
            Log.d(TAG, "onCharacteristicRead: status=" + status);
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            Log.d(TAG, "MTU изменен: " + mtu + ", status=" + status);
        }
    };


    //отправлем данные
    public boolean writeData(String str) {
        int len = str.length();
        //длина строки должна быть четной
        if (len % 2 !=0) return false;

        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(str.charAt(i), 16) << 4)
                    + Character.digit(str.charAt(i + 1), 16));
        }
        return writeData(data);
    }

    public boolean writeData(byte[] data) {
        if (mFFE1Characteristic == null || mBluetoothGatt == null || !mNotificationsEnabled) {
            Log.e(TAG, "Не готово для записи: characteristic=" + (mFFE1Characteristic != null) +
                    ", gatt=" + (mBluetoothGatt != null) + ", notifications=" + mNotificationsEnabled);
            return false;
        }

        if (!checkBluetoothConnectPermission()) {
            Log.e(TAG, "Нет разрешения BLUETOOTH_CONNECT");
            return false;
        }

        try {
            Log.d(TAG, "Отправка данных: " + bytesToHex(data));
            mFFE1Characteristic.setValue(data);
            mFFE1Characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            return mBluetoothGatt.writeCharacteristic(mFFE1Characteristic);
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException при записи", e);
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Ошибка при записи", e);
            return false;
        }
    }

    private BluetoothGattCharacteristic findCharacteristic(BluetoothGatt gatt) {
        List<BluetoothGattService> services = gatt.getServices();
        if (services == null) {
            return null;
        }

        for (BluetoothGattService service : services) {
            Log.d(TAG, "Сервис: " + service.getUuid());

            List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();
            if (characteristics != null) {
                for (BluetoothGattCharacteristic characteristic : characteristics) {
                    String uuid = characteristic.getUuid().toString();
                    int properties = characteristic.getProperties();

                    Log.d(TAG, "  Характеристика: " + uuid +
                            ", свойства: " + properties +
                            " (NOTIFY=" + ((properties & 0x10) != 0) +
                            ", INDICATE=" + ((properties & 0x20) != 0) + ")");

                    if (FFE1_UUID.equals(characteristic.getUuid())) {
                        Log.i(TAG, "✓ Найдена характеристика FFE1");
                        return characteristic;
                    }
                }
            }
        }

        return null;
    }

    //включение уведомлений
    private void enableNotifications(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        try {
            Log.d(TAG, "Включаем уведомления...");

            // Проверяем свойства
            int properties = characteristic.getProperties();
            boolean hasNotify = (properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
            boolean hasIndicate = (properties & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;

            if (!hasNotify && !hasIndicate) {
                Log.e(TAG, "Характеристика не поддерживает NOTIFY/INDICATE");
                notifyConnectEnd(false);
                return;
            }
            Log.d(TAG, "Характеристика поддерживает: NOTIFY=" + hasNotify + ", INDICATE=" + hasIndicate);

            //Включаем уведомления
            boolean notificationSet = gatt.setCharacteristicNotification(characteristic, true);
            Log.d(TAG, "setCharacteristicNotification: " + notificationSet);

            if (!notificationSet) {
                Log.e(TAG, "Не удалось установить уведомление");
                notifyConnectEnd(false);
                return;
            }

            //Ищем дескриптор
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCC_DESCRIPTOR_UUID);
            if (descriptor == null)
            {
                Log.e(TAG, "Дескриптор не найден по UUID: " + CCC_DESCRIPTOR_UUID);

                // Ищем любой дескриптор
                for (BluetoothGattDescriptor desc : characteristic.getDescriptors()) {
                    Log.d(TAG, "Доступный дескриптор: " + desc.getUuid());
                    if (desc.getUuid().toString().contains("2902")) {
                        descriptor = desc;
                        Log.d(TAG, "Используем дескриптор: " + desc.getUuid());
                        break;
                    }
                }
                if (descriptor == null) {
                    Log.e(TAG, "CCC дескриптор не найден!");
                    notifyConnectEnd(false);
                    return;
                }
            }
            //Устанавливаем значение
            byte[] enableValue;
            if (hasNotify) {
                enableValue = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
                Log.d(TAG, "Устанавливаем ENABLE_NOTIFICATION_VALUE (01 00)");
            }
            else {
                enableValue = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
                Log.d(TAG, "Устанавливаем ENABLE_INDICATION_VALUE (02 00)");

            }

            descriptor.setValue(enableValue);
            //Записываем дескриптор
            boolean writeSuccess = gatt.writeDescriptor(descriptor);
            Log.d(TAG, "writeDescriptor: " + writeSuccess);
            if (!writeSuccess) {
                notifyConnectEnd(false);
            }
        }
        catch (SecurityException e) {
            Log.e(TAG, "SecurityException при включении уведомлений", e);
            notifyConnectEnd(false);
        }
        catch (Exception e) {
            Log.e(TAG, "Ошибка при включении уведомлений", e);
            notifyConnectEnd(false);
        }
    }

    private void logAllServices(BluetoothGatt gatt) {
        Log.d(TAG, "=== ВСЕ СЕРВИСЫ И ХАРАКТЕРИСТИКИ ===");

        for (BluetoothGattService service : gatt.getServices()) {
            Log.d(TAG, "Сервис: " + service.getUuid());

            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                int props = characteristic.getProperties();
                Log.d(TAG, "  Характеристика: " + characteristic.getUuid() +
                        ", свойства: " + props);

                // Подробности свойств
                if ((props & 0x10) != 0) Log.d(TAG, "    - NOTIFY");
                if ((props & 0x20) != 0) Log.d(TAG, "    - INDICATE");
                if ((props & 0x02) != 0) Log.d(TAG, "    - READ");
                if ((props & 0x08) != 0) Log.d(TAG, "    - WRITE");

                // Дескрипторы
                for (BluetoothGattDescriptor descriptor : characteristic.getDescriptors()) {
                    Log.d(TAG, "    Дескриптор: " + descriptor.getUuid());
                }
            }
        }
    }


    public static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString();
    }

    //посылем каллбеки через события в обработчик
    private void notifyScanStart() {
        mHandler.post(() -> {
            if (mBLEStateInterface != null) {
                mBLEStateInterface.onScanStart();
            }
        });
    }

    private void notifyScanEnd(boolean success) {
        mHandler.post(() -> {
            if (mBLEStateInterface != null) {
                mBLEStateInterface.onScanEnd(success);
            }
        });
    }

    private void notifyConnectEnd(boolean success) {
        mHandler.post(() -> {
            if (mBLEStateInterface != null) {
                mBLEStateInterface.onConnectEnd(success);
            }
        });
    }

    private void notifyError(String error) {
        mHandler.post(() -> {
            if (mBLEStateInterface != null) {
                mBLEStateInterface.onError(error);
            }
        });
    }
}



//    private BluetoothManager mBluetoothManager;
//    private BluetoothAdapter mBluetoothAdapter;
//    private BluetoothDevice mBluetoothDevice;
//    private String mBluetoothDeviceAddress;
//    private BluetoothGatt mBluetoothGatt;
//    private BluetoothLeScanner mScaner;
//
//    private int mConnectionState = STATE_DISCONNECTED;
//
//    private String TAG = "BLE_RS485";
//
//    //состояние процессов содениения
//    private static final int STATE_DISCONNECTED = 0;
//    private static final int STATE_CONNECTING = 1;
//    private static final int STATE_CONNECTED = 2;
//
//    public final static String ACTION_GATT_CONNECTED =
//            "com.example.bluetooth.le.ACTION_GATT_CONNECTED";
//    public final static String ACTION_GATT_DISCONNECTED =
//            "com.example.bluetooth.le.ACTION_GATT_DISCONNECTED";
//    public final static String ACTION_GATT_SERVICES_DISCOVERED =
//            "com.example.bluetooth.le.ACTION_GATT_SERVICES_DISCOVERED";
//    public final static String ACTION_DATA_AVAILABLE =
//            "com.example.bluetooth.le.ACTION_DATA_AVAILABLE";
//    public final static String EXTRA_DATA =
//            "com.example.bluetooth.le.EXTRA_DATA";
//
//    //устанлвиаемм UUID преобразовтатля
//    public final static UUID FFE1_UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
//    public final static UUID FFE2_UUID = UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb");
//    //UUID дескриптора CCC для активации уведомлений
//    private final String CCC_DESCRIPTOR_UUID = "00002902-0000-1000-8000-00805f9b34fb";
//
//    //характеристрка модуля
//    BluetoothGattCharacteristic FFE1_Characteristic = null;
//
//    //хендлер основного потока
//    Handler mBLeHandler = null;
//    //контектси приложения
//    Context ApplicationContext = null;
//
//    private BLEStateInterface mBLEStateInterface;
//
//    //процедура регистрации интерфрейса каллбеков
//    public void registerInterface(BLEStateInterface callback) {
//        this.mBLEStateInterface = callback;
//    }
//
//    //конструктор
//    BLE_Processor(Context appContex, Handler handler) {
//        ApplicationContext = appContex;
//        mBLeHandler = handler;
//    }
//
//    //реаилзация интерфейса сканирования BLE
//    private  final ScanCallback scanCallback = new ScanCallback() {
//        @Override
//        //резсльтатт скнирования усатройства
//        public void onScanResult(int callbackType, ScanResult result) {
//            super.onScanResult(callbackType, result);
//            String msg = "Подключено, rssi " + Integer.toString(result.getRssi());
//            mBLEStateInterface.onScanEnd(true);
//            //получаем хендлер устаройства
//            mBluetoothDevice = result.getDevice();
//            //подключеамся к устроойству bluteTooth
//            if ((ContextCompat.checkSelfPermission(ApplicationContext, android.Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED)
//                    && (ContextCompat.checkSelfPermission(ApplicationContext, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED))  {
//                mBluetoothGatt = mBluetoothDevice.connectGatt(ApplicationContext, true, mGattCallback, TRANSPORT_LE);
//            }
//
//        }
//        @Override
//        public void onScanFailed(int errorCode) {
//            mBLEStateInterface.onScanEnd(false);
//        }
//    };
//
//    //реализация интерфейса обработки соединения BLE
//    private final BluetoothGattCallback mGattCallback =
//            new BluetoothGattCallback() {
//                //установелние/разрыв соединения
//                @Override
//                public void onConnectionStateChange(BluetoothGatt gatt, int status,
//                                                    int newState) {
//                    String intentAction;
//                    //соедиение установлено
//                    if ((newState == BluetoothProfile.STATE_CONNECTED)
//                            &&(ContextCompat.checkSelfPermission(ApplicationContext, android.Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED)
//                            && (ContextCompat.checkSelfPermission(ApplicationContext, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED))
//                    {
//                        mConnectionState = STATE_CONNECTED;
//                        int bondstate = mBluetoothDevice.getBondState();
//                        // Обрабатываем bondState
//                        if(bondstate == BOND_NONE || bondstate == BOND_BONDED) {
//                            Log.i(TAG, "Gatt Connected");
//                            // Подключились к устройству, вызываем discoverServices
//                            mBLeHandler.post(() -> {
//                                boolean result = gatt.discoverServices();
//                                if (!result) {
//                                    Log.e(TAG, "discoverServices failed to start");
//                                }
//                                mBLEStateInterface.onConnectEnd(true);
//                            });
//                        }
//                    }
//                    //разрыв соедениния
//                    else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
//                        //intentAction = ACTION_GATT_DISCONNECTED;
//                        mConnectionState = STATE_DISCONNECTED;
//                        Log.i(TAG, "Disconnected from GATT server.");
//                        if ((ContextCompat.checkSelfPermission(ApplicationContext, android.Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED)
//                                && (ContextCompat.checkSelfPermission(ApplicationContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED))
//                        {
//                            //вызываем метод close у gatt обьекта
//                            gatt.close();
//                        }
//                    }
//                }
//
//                @Override
//                // При обнаружении нового сервиса
//                public void onServicesDiscovered(BluetoothGatt gatt, int status) {
//                    if (status == GATT_SUCCESS) {
//                        broadcastUpdate(ACTION_GATT_SERVICES_DISCOVERED);
//                        List<BluetoothGattService> serviceList =  gatt.getServices();
//                        for (BluetoothGattService serv :serviceList) {
//                            Log.i(TAG, "Found servie " + serv.toString());
//                            List<BluetoothGattCharacteristic> charackList = serv.getCharacteristics();
//                            for (BluetoothGattCharacteristic characteristic :  charackList) {
//                                //нашли харакртериситу с нужным uuid
//                                if (characteristic.getUuid().equals(FFE1_UUID)) {
//                                    Log.i(TAG, "Found UU1D ");
//                                    FFE1_Characteristic = characteristic;
//                                    //включаем уведомление для характеристики
//                                    setNotify(FFE1_Characteristic, true);
//                                }
//                                else Log.i(TAG, "UUID " + characteristic.getUuid().toString());
//                            }
//                        }
//                    } else {
//                        Log.w(TAG, "onServicesDiscovered received: " + status);
//                    }
//                }
//
//                @Override
//                // Результат чтения характеристики
//                public void onCharacteristicRead(BluetoothGatt gatt,
//                                                 BluetoothGattCharacteristic characteristic,
//                                                 int status)
//                {
//                    if (status == GATT_SUCCESS) {
//                        broadcastUpdate(ACTION_DATA_AVAILABLE);//, characteristic);
//                        byte[] resp = characteristic.getValue();
//                        //формируем стрку ответа
//                        StringBuilder sb = new StringBuilder();
//                        for (byte b : resp) {
//                            sb.append(String.format("%02X ", b));
//                        }
//                        Log.i(TAG, "resp -  " + sb.toString());
//                    }
//                }
//                @Override
//                public void onCharacteristicWrite(BluetoothGatt gatt,
//                                                  BluetoothGattCharacteristic characteristic, int status)
//                {
//                    if (status == GATT_SUCCESS) {
//                        //broadcastUpdate(AC);
//                        Log.i(TAG, "writed  ");
//                        //пытаемся отправить запрос на чтение характеристики по этому UUID
//                        //readCharacteristic(FFE1_Characteristic);
//                    }
//                }
//
//                //влключение уведолмений
//                @Override
//                public void onDescriptorWrite(BluetoothGatt gatt,
//                                              final BluetoothGattDescriptor descriptor,
//                                              final int status)
//                {
//                    final BluetoothGattCharacteristic parentCharacteristic = descriptor.getCharacteristic();
//                    if(status==GATT_SUCCESS) {
//                        // Check if we were turning notify on or off
//                        byte[] value = descriptor.getValue();
//                        if (value != null && value[0] != 0) {
//                            Log.i(TAG, "Notify on");
//                        }
//                        else {
//                            Log.i(TAG, "Notify off");
//                        }
//                    }
//                    else {
//                        Log.e(TAG, String.format("ERROR: Write descriptor failed  characteristic: %s", parentCharacteristic.getUuid()));
//                    }
//                }
//
//                //обработки изменения характирстик (обработка уведомлений)
//                @Override
//                public void onCharacteristicChanged(@NonNull BluetoothGatt gatt,
//                                                    @NonNull BluetoothGattCharacteristic characteristic,
//                                                    @NonNull byte[] value)
//                {
//
//                    // Обработка полученных данных, если UUID совпадает с FFE1
//                    if (FFE1_UUID.equals(characteristic.getUuid())) {
//                        final byte[] data = characteristic.getValue();
//                        if (data != null && data.length > 0) {
//                            final String command = new String(data, java.nio.charset.StandardCharsets.UTF_8);
//                            Log.d(TAG, "Received data: " + command);
//
//                            // Вызываем метод интерфейса каллбэков в основном потоке через mBLeHandler
//                            if (mBLEStateInterface != null && mBLeHandler != null) {
//                                mBLeHandler.post(() -> {
//                                    mBLEStateInterface.onReponse(command);
//                                });
//                            }
//                        }
//                    }
//                }
//            };
//

//
//    //устанавливаем обработчик уведомлений устроййства
//    public boolean setNotify(BluetoothGattCharacteristic characteristic,
//                             final boolean enable)
//    {
//        if ((ContextCompat.checkSelfPermission(ApplicationContext, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED)
//                && (ContextCompat.checkSelfPermission(ApplicationContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED))
//        {
//            try {
//                Log.d(TAG, "Включаем уведомления...");
//                //Включаем уведомления на уровне GATT
//                boolean set = mBluetoothGatt.setCharacteristicNotification(characteristic, true);
//                Log.d(TAG, "setCharacteristicNotification: " + set);
//                if (!set) {
//                    Log.e(TAG, "Не удалось включить уведомления");
//                    return false;
//                }
//                //Ищем дескриптор
//                BluetoothGattDescriptor descriptor = characteristic.getDescriptor(UUID.fromString(CCC_DESCRIPTOR_UUID));
//                if (descriptor == null) {
//                    Log.e(TAG, "Дескриптор не найден!");
//                    return false;
//                }
//                // Устанавливаем значение
//                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
//                // Записываем
//                boolean written = mBluetoothGatt.writeDescriptor(descriptor);
//                Log.d(TAG, "Запись дескриптора: " + written);
//            }
//            catch (Exception e) {
//                Log.e(TAG, "Ошибка включения уведомлений", e);
//                return false;
//            }
//            return true;
//        }
//        else return false;
//    }
//
//
//    //отключвам устройство
//    void GattDisconnect()
//    {
//        if (mBluetoothGatt != null) {
//            if ((ContextCompat.checkSelfPermission(ApplicationContext, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED)
//                    && (ContextCompat.checkSelfPermission(ApplicationContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED))
//            {
//                //разрываем соединение с устроййтсвом
//                mBluetoothGatt.disconnect();
//            }
//        }
//    }
//
//
