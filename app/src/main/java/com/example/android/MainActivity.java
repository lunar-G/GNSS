package com.example.android;

import android.Manifest;
import android.content.pm.PackageManager;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
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

                // 清空缓冲区并填充卫星状态数据
                satelliteDataBuffer.clear();
                for (int i = 0; i < status.getSatelliteCount(); i++) {
                    int svid = status.getSvid(i);
                    float azimuth = status.getAzimuthDegrees(i);
                    float elevation = status.getElevationDegrees(i);
                    float snr = status.getCn0DbHz(i); // 信噪比
                    String type = getSatelliteType(status.getConstellationType(i));

                    satelliteDataBuffer.add(new Object[]{svid, type, azimuth, elevation, snr});
                }
            }
        };

        locationManager.registerGnssStatusCallback(gnssCallback);

        // 启动位置更新
        locationListener = location -> {
            double latitude = location != null ? location.getLatitude() : 0.0;
            double longitude = location != null ? location.getLongitude() : 0.0;
            double accuracy = location != null ? location.getAccuracy() : 0.0;

            // 获取当前时间戳
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date());

            // 插入一次定位记录和对应的卫星状态
            insertDataAsTransaction(timestamp, latitude, longitude, accuracy);
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

            // 插入对应的卫星记录
            for (Object[] satellite : satelliteDataBuffer) {
                String satelliteInsertSQL = "INSERT INTO satellite_info (satellite_id, type, azimuth, elevation, snr, location_id) VALUES (?, ?, ?, ?, ?, ?)";
                database.execSQL(satelliteInsertSQL, new Object[]{satellite[0], satellite[1], satellite[2], satellite[3], satellite[4], locationId});
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
