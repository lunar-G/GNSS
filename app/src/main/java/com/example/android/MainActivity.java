package com.example.android;

import android.Manifest;
import android.content.pm.PackageManager;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import android.location.GnssStatus;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private LocationManager locationManager;
    private GnssStatus.Callback gnssCallback;
    private LocationListener locationListener;
    private boolean isCollecting = false;
    private long samplingInterval = 500; // 调整采样频率为500毫秒

    private DatabaseHelper dbHelper;
    private SQLiteDatabase database;
    private ArrayList<Object[]> satelliteDataBuffer = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 初始化按钮
        Button startButton = findViewById(R.id.startButton);
        Button stopButton = findViewById(R.id.stopButton);

        // 初始化数据库
        dbHelper = new DatabaseHelper(this);
        database = dbHelper.getWritableDatabase();

        // 初始化 LocationManager
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        // 开始按钮逻辑
        startButton.setOnClickListener(v -> startCollecting());

        // 结束按钮逻辑
        stopButton.setOnClickListener(v -> stopCollecting());
    }


    private void stopCollecting() {
        if (!isCollecting) return;

        isCollecting = false;

        // 停止 GNSS 状态回调
        if (gnssCallback != null) {
            locationManager.unregisterGnssStatusCallback(gnssCallback);
        }

        // 停止位置更新
        if (locationListener != null) {
            locationManager.removeUpdates(locationListener);
        }

        // 关闭数据库
        if (database != null) {
            database.close();
            Log.d("DBClose", "数据库已关闭");
        }
    }

    private void insertDataAsTransaction(String timestamp, double latitude, double longitude, double accuracy) {
        database.beginTransaction();
        try {
            // 插入定位记录
            String locationInsertSQL = "INSERT INTO location_record (timestamp, latitude, longitude, accuracy) VALUES (?, ?, ?, ?)";
            database.execSQL(locationInsertSQL, new Object[]{timestamp, latitude, longitude, accuracy});

            // 获取插入的定位记录的 ID
            String lastInsertIdSQL = "SELECT last_insert_rowid()";
            int locationId = (int) database.compileStatement(lastInsertIdSQL).simpleQueryForLong();

            // 构建卫星记录的批量插入语句
            String satelliteInsertSQL = "INSERT INTO satellite_info (satellite_id, type, azimuth, elevation, snr, location_id) VALUES (?, ?, ?, ?, ?, ?)";
            SQLiteStatement statement = database.compileStatement(satelliteInsertSQL);

            // 批量插入卫星记录
            for (Object[] satellite : satelliteDataBuffer) {
                statement.clearBindings();
                statement.bindLong(1, (int) satellite[0]); // satellite_id
                statement.bindString(2, (String) satellite[1]); // type
                statement.bindDouble(3, (float) satellite[2]); // azimuth
                statement.bindDouble(4, (float) satellite[3]); // elevation
                statement.bindDouble(5, (float) satellite[4]); // snr
                statement.bindLong(6, locationId); // location_id
                statement.execute();
            }

            database.setTransactionSuccessful();
            Log.d("DBWrite", "事务插入成功 - 定位时间: " + timestamp);
        } catch (Exception e) {
            Log.e("DBWrite", "事务插入失败", e);
        } finally {
            database.endTransaction();
            satelliteDataBuffer.clear();
        }
    }


    private String getSatelliteType(int constellationType) {
        switch (constellationType) {
            case GnssStatus.CONSTELLATION_GPS:
                return "GPS";
            case GnssStatus.CONSTELLATION_GLONASS:
                return "GLONASS";
            case GnssStatus.CONSTELLATION_BEIDOU:
                return "BeiDou";
            case GnssStatus.CONSTELLATION_GALILEO:
                return "Galileo";
            case GnssStatus.CONSTELLATION_QZSS:
                return "QZSS";
            case GnssStatus.CONSTELLATION_SBAS:
                return "SBAS";
            case GnssStatus.CONSTELLATION_UNKNOWN:
                return "Unknown";
            default:
                return "Other";
        }
    }

    static class DatabaseHelper extends SQLiteOpenHelper {
        private static final String DB_NAME = "gnss_data.db";
        private static final int DB_VERSION = 1;

        public DatabaseHelper(MainActivity context) {
            super(context, new File(context.getExternalFilesDir(null), DB_NAME).getAbsolutePath(), null, DB_VERSION);
        }


        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE location_record (" + "id INTEGER PRIMARY KEY AUTOINCREMENT," + "timestamp TEXT," + "latitude REAL," + "longitude REAL," + "accuracy REAL)");

            db.execSQL("CREATE TABLE satellite_info (" + "id INTEGER PRIMARY KEY AUTOINCREMENT," + "satellite_id INTEGER," + "type TEXT," + "azimuth REAL," + "elevation REAL," + "snr REAL," + "location_id INTEGER," + "FOREIGN KEY(location_id) REFERENCES location_record(id))");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            db.execSQL("DROP TABLE IF EXISTS location_record");
            db.execSQL("DROP TABLE IF EXISTS satellite_info");
            onCreate(db);
        }
    }
}
