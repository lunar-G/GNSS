package com.example.android;

import android.Manifest;
import android.content.pm.PackageManager;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.text.SimpleDateFormat;
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

    private void startCollecting() {
        if (isCollecting) return;

        // 检查权限
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
            return;
        }

        isCollecting = true;

        // 启动 GNSS 状态回调
        gnssCallback = new GnssStatus.Callback() {
            @Override
            public void onSatelliteStatusChanged(GnssStatus status) {
                super.onSatelliteStatusChanged(status);
                int satelliteCount = status.getSatelliteCount();

                // 获取当前时间戳
                String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date());

                for (int i = 0; i < satelliteCount; i++) {
                    int svid = status.getSvid(i);
                    float azimuth = status.getAzimuthDegrees(i);
                    float elevation = status.getElevationDegrees(i);
                    float snr = status.getCn0DbHz(i); // 信噪比
                    String type = getSatelliteType(status.getConstellationType(i));

                    // 插入卫星记录
                    insertSatelliteRecord(timestamp, svid, type, azimuth, elevation, snr);
                }
            }
        };

        locationManager.registerGnssStatusCallback(gnssCallback);

        // 启动位置更新
        locationListener = location -> {
            double latitude = location.getLatitude();
            double longitude = location.getLongitude();
            float accuracy = location.getAccuracy();

            // 获取当前时间戳
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date());

            // 插入定位记录
            insertLocationRecord(timestamp, latitude, longitude, accuracy);
        };

        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, samplingInterval, 0, locationListener);
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
    }

    private void insertLocationRecord(String timestamp, double latitude, double longitude, float accuracy) {
        String sql = "INSERT INTO location_record (timestamp, latitude, longitude, accuracy) VALUES (?, ?, ?, ?)";
        database.execSQL(sql, new Object[]{timestamp, latitude, longitude, accuracy});
        Log.d("DBWrite", "定位记录已插入: " + timestamp);
    }

    private void insertSatelliteRecord(String timestamp, int svid, String type, float azimuth, float elevation, float snr) {
        String sql = "INSERT INTO satellite_info (timestamp, satellite_id, type, azimuth, elevation, snr) VALUES (?, ?, ?, ?, ?, ?)";
        database.execSQL(sql, new Object[]{timestamp, svid, type, azimuth, elevation, snr});
        Log.d("DBWrite", "卫星记录已插入: " + svid + " - " + timestamp);
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (database != null) database.close();
    }

    static class DatabaseHelper extends SQLiteOpenHelper {
        private static final String DB_NAME = "gnss_data.db";
        private static final int DB_VERSION = 1;

        public DatabaseHelper(MainActivity context) {
            super(context, context.getExternalFilesDir(null).getAbsolutePath() + "/" + DB_NAME, null, DB_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            // 创建定位记录表
            db.execSQL("CREATE TABLE location_record (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "timestamp TEXT," +
                    "latitude REAL," +
                    "longitude REAL," +
                    "accuracy REAL)");

            // 创建卫星记录表
            db.execSQL("CREATE TABLE satellite_info (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "timestamp TEXT," +
                    "satellite_id INTEGER," +
                    "type TEXT," +
                    "azimuth REAL," +
                    "elevation REAL," +
                    "snr REAL)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            db.execSQL("DROP TABLE IF EXISTS location_record");
            db.execSQL("DROP TABLE IF EXISTS satellite_info");
            onCreate(db);
        }
    }
}