package com.example.test_ble;


import android.annotation.SuppressLint;
import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;


import android.os.CountDownTimer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Trace;
import android.os.health.TimerStat;
import android.view.KeyEvent;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import  android.widget.Toast;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.Manifest;


import android.util.Log;
import android.view.View;
import android.widget.Button;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.IllegalFormatConversionException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

import android.content.Intent;
import android.widget.TextView;

import com.example.test_ble.R;

import com.example.test_ble.BLE_Processor;

public class MainActivity extends AppCompatActivity {


    private String TAG = "BLE_API";

    //отправкиа команд
    Button button_send, button_cycle;
    //подключение
    Button button_conn, button_discon;
    TextView Label;
    //фомрирование команды
    EditText editText_addres, editText_Data, editText_Command;
    //парматры ответа
    TextView  label_Responde, label_res_Data;

    //спинер числа регисторв команды
    Spinner cmd_len_spinner = null;
    //спиннер мак аддресов
    Spinner mac_spinner = null;

    //имена текущих устройств
    ArrayList<String> mCurrentDevNames;
    //mac аддреса текущих устройств
    ArrayList<String> mCurrentMacs;
    //мак текущего устройства
    String mCurrentDevMAc;
    //текущая командв modbus
    String mTargetCommand;
    //текущая дистнция, мк
    int mTargetDist;

    //таймер отпрвки команды
    Timer mCmdTimer;
    //флан запуска конманды
    boolean mTimerStart = false;

    //обработчик преоьразователя RS_bluetooth
    BLE_Processor mRSBLEProc = null;

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        //создаем обьект класс обработчика
        mRSBLEProc = new BLE_Processor(getApplication());//, new Handler(Looper.getMainLooper()));
        //региструем интерфей калльеков
        mRSBLEProc.registerInterface(new BLEStateInterface());

        //получаем имена элмениов из ui
        button_send = (Button) findViewById(R.id.button_send);
        button_cycle = (Button) findViewById(R.id.button_cycle);
        button_conn = (Button) findViewById(R.id.button_connect);
        button_discon = (Button) findViewById(R.id.button_disconect);
        Label = findViewById(R.id.textView_ConnState);
        label_Responde = findViewById(R.id.textView_Response);
        label_res_Data = findViewById(R.id.textView_RespData);
        editText_addres = findViewById(R.id.editTextRegNumber);
        editText_Command = findViewById(R.id.editTextCommand);
        editText_Data = findViewById(R.id.editTextData);

        //фомриурем спинер числа регситорв
//        mSpinner = findViewById(R.id.regcountSpiner);
//        // Создаем адаптер ArrayAdapter с помощью массива строк и стандартной разметки элемета spinner
//        ArrayAdapter<String> adapter = new ArrayAdapter(this, android.R.layout.simple_spinner_item, new List<Integer>{1,2});
//        // Определяем разметку для использования при выборе элемента
//        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
//        // Применяем адаптер к элементу spinner
//        mSpinner.setAdapter(adapter);

        mTargetCommand = "01040000000271CB";
        editText_Command.setText(mTargetCommand);

        //списики адресов и уствройств
        mCurrentMacs = new ArrayList<String>();
        mCurrentDevNames = new ArrayList<String>();
        mCurrentDevMAc = "";

        mTimerStart = false;
        //инициализируем имя
        mCmdTimer = new Timer();


        if ((ContextCompat.checkSelfPermission(com.example.test_ble.MainActivity.this, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED)
                && (ContextCompat.checkSelfPermission(com.example.test_ble.MainActivity.this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED)
                && (ContextCompat.checkSelfPermission(com.example.test_ble.MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
                && (ContextCompat.checkSelfPermission(com.example.test_ble.MainActivity.this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
                && (ContextCompat.checkSelfPermission(com.example.test_ble.MainActivity.this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        ) {
            mRSBLEProc.initialize();
            //фомриуем спинер адресов
            initMacSpinner();
        }
        else
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION}, 1);

        //при нажатии на клваишу в поле edit text обнвелм команду
        editText_Command.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if(event.getAction() == KeyEvent.ACTION_DOWN &&
                        (keyCode == KeyEvent.KEYCODE_ENTER))
                {
                    // сохраняем текст, введённый до нажатия Enter в переменную
                    mTargetCommand = editText_Command.getText().toString();
                    return true;
                }
                return false;
            }
        });

        //запцускаем таймер


        //кнопка отправки команды
        button_send.setOnClickListener(new View.OnClickListener()  //Если будет нажата кнопка 1 то
        {
            public void onClick(View v) {
                if (mTargetCommand != null) {
                    Log.i(TAG, "ОТвпрака " + mTargetCommand);
                    mRSBLEProc.writeData(mTargetCommand);
                }
            }
        });

        button_cycle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                if (mTimerStart)
                {
                    Toast.makeText(getApplicationContext(), "Цикл остановлен", Toast.LENGTH_SHORT).show();
                    mTimerStart = false;
                }
                else {
                    Toast.makeText(getApplicationContext(), "Цикл запущен", Toast.LENGTH_SHORT).show();
                    mTimerStart = true;
                }

                //если таймер запущен - остановим
//                if (mTimerStart)
//                {
//                    if (mCmdTimer != null)
//                    {
//                        mCmdTimer.cancel();
//                        mCmdTimer = null;
//                    }
//                    mTimerStart = false;
//                    Toast.makeText(getApplicationContext(), "Цикл остановлен", Toast.LENGTH_SHORT).show();
//                }
//                else {
//                    Log.i(TAG, "timer strunging");
//                    if (mCmdTimer != null) {
//                        mCmdTimer.cancel();
//                    }
//                    //пересоздаем таймер и привязывам задачу
//                    mCmdTimer = new Timer();
//                    mCmdTimer.schedule(new TimerTask() {
//                        @Override
//                        public void run() {
//                            if (mTargetCommand != null) mRSBLEProc.writeData(mTargetCommand);
//                        }
//                    }, 0, 100);
//                    mTimerStart = true;
//                    Toast.makeText(getApplicationContext(), "Цикл запущен", Toast.LENGTH_SHORT).show();
//                    //зедсь надо блкировать осальные виждеты
//                }
            }
        });


        //запускаем сканировние по кнопке
        button_conn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!mCurrentDevMAc.isEmpty()) {
                    mRSBLEProc.startScan(mCurrentDevMAc);
                    Log.i(TAG, "scannong " + mCurrentDevMAc);
                }
                else {
                    Toast.makeText(getApplicationContext(), "Mac аддрес не выбран!", Toast.LENGTH_SHORT).show();
                    //ArrayList<String> st  = mRSBLEProc.getBondedDevices() ;
                }
            }
        });

        //отоединеяемся
        button_discon.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mRSBLEProc != null)
                {
                    //если запущено сканирование - остановим
                    if (mRSBLEProc.isScanning()) {
                        mRSBLEProc.stopScan();
                        Label.setText("Не подключено");
                        Toast.makeText(getApplicationContext(), "Остановлено польщователем", Toast.LENGTH_SHORT).show();
                    }
                    //пытаемся разорвать соедиение
                    else  mRSBLEProc.disconnect();
                }
            }
        });
    }


    //настройка спинера мак адресов
    private void initMacSpinner()
    {
        final Spinner macSpinner = findViewById(R.id.macSpiner);
        mCurrentDevNames.clear(); mCurrentMacs.clear();
        //если получили списки аддресов и названий
        if (mRSBLEProc.getBondedDevices(mCurrentDevNames, mCurrentMacs))
        {
            //устнваливаем в качаетве списка значения ключей (название ражимов) словоеар
            ArrayAdapter<String> adapter =
                    new ArrayAdapter<>(this, R.layout.item, R.id.itemText, mCurrentDevNames);
            macSpinner.setAdapter(adapter);

            if (!mCurrentDevNames.isEmpty()) {
                macSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    //обработчик события выобра элемента
                    @Override
                    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                        //определим индекс названия в массиве
                        int idx = mCurrentDevNames.indexOf(macSpinner.getItemAtPosition(position).toString());
                        mCurrentDevMAc =  mCurrentMacs.get(idx);
                        Log.i(TAG, "Curr MAc " + mCurrentDevMAc);
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {
                    }
                });
            } else Log.e(TAG, "No mac addres");
        }
        else Log.e(TAG, "No mac addres");
    }

    //реализция интерфейса каалбеков
    public class BLEStateInterface implements BLE_Processor.BLEStateInterface {
        @Override
        public void onConnectEnd(boolean state) {
            Label.setText(state ? "Подключено" : "Ошибка соедниения");
        }

        @Override
        public void onResponse(String data) {
            //выводи ответ на экран
            label_Responde.setText(data);
            //парси ответ
        }

        @Override
        public void onResponse(byte[] data) {
            //число байт ответа
            int res_size = data[2];
            if (res_size > 4) res_size = 4;
            Log.i(TAG, "res_size" + data);
            int value = 0;
            for (int i = 0; i < res_size; i++) {
                value |= (data[i + 3] & 0xFF) << ((res_size - 1 - i) * 8);
            }
            Log.i(TAG, "val" + String.valueOf(value));

            if (value == 0x7FFFFFFF) {
                label_res_Data.setText("---");
            }
            else {
                //запоними дистанцию
                mTargetDist = value;
                try {
                    label_res_Data.setText(String.format(Locale.US ,"%.2f", mTargetDist * 0.001));

                    if (mTargetCommand != null && mTimerStart ) mRSBLEProc.writeData(mTargetCommand);
                }
                catch (IllegalFormatConversionException e) {

                }
            }
        }

        @Override
        public void onScanStart() {
            Label.setText("Сканирование запущено");
        }

        @Override
        public void onError(String error) {
            Toast.makeText(getApplicationContext(), "Ошибка " + error, Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onScanEnd(boolean state) {
            if (state)  Label.setText("Устройсвто найдено, подключение");
            else {
                Label.setText("Не подключено");
                Toast.makeText(getApplicationContext(), "Устройство не обнаружено!", Toast.LENGTH_SHORT).show();
            }
        }

        @Override
        public void onDisconnect() {
            Label.setText("Не подключено");
            Toast.makeText(getApplicationContext(), "Устройство отключено!", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults)
    {
       //если раршения получены - запсустим обьект преобразователя
        if ((ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED)
          && (ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED)
          &&  (ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
          && (ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED))
        {
            Log.v(TAG, "Permissin granted ");
            mRSBLEProc.initialize();
            initMacSpinner();
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    protected  void onResume()
    {
        super.onResume();
    }

    @Override
    protected  void onPause()
    {
        super.onPause();
    }
}
