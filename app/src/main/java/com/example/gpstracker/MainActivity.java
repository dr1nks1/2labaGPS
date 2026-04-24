package com.example.gpstracker;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;

import org.osmdroid.config.Configuration;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polygon;
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private MapView map;
    private FusedLocationProviderClient fusedLocationClient;
    private EditText etMessage;
    private TextView tvStats;
    private MyLocationNewOverlay mLocationOverlay;

    private final List<GeoReminder> reminders = new ArrayList<>();
    private static final String CHANNEL_ID = "geo_reminders_final_v2";
    private static final double RADIUS_METERS = 100.0;

    // Класс для хранения данных точки
    static class GeoReminder {
        GeoPoint point;
        String message;
        boolean hasNotified = false;
        Polygon circleOverlay;
        Marker markerOverlay;

        GeoReminder(GeoPoint point, String message, Polygon circle, Marker marker) {
            this.point = point;
            this.message = message;
            this.circleOverlay = circle;
            this.markerOverlay = marker;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Важно для работы карт osmdroid
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this));
        setContentView(R.layout.activity_main);

        map = findViewById(R.id.map);
        etMessage = findViewById(R.id.etMessage);
        tvStats = findViewById(R.id.tvStats);

        initMap();
        createNotificationChannel();
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        checkPermissions();
    }

    private void initMap() {
        map.setMultiTouchControls(true);
        map.setBuiltInZoomControls(false); // Убрали + и -
        map.getController().setZoom(18.0);

        // Настройка провайдера "Я" (используем GPS)
        GpsMyLocationProvider provider = new GpsMyLocationProvider(this);
        provider.addLocationSource(LocationManager.GPS_PROVIDER);

        mLocationOverlay = new MyLocationNewOverlay(provider, map);
        mLocationOverlay.enableMyLocation();
        mLocationOverlay.enableFollowLocation(); // Карта следует за вами
        mLocationOverlay.setDrawAccuracyEnabled(false); // Убрали синий круг точности
        map.getOverlays().add(mLocationOverlay);

        // Обработка кликов по карте
        MapEventsReceiver mReceiver = new MapEventsReceiver() {
            @Override
            public boolean singleTapConfirmedHelper(GeoPoint p) {
                String msg = etMessage.getText().toString().trim();
                if (msg.isEmpty()) {
                    Toast.makeText(MainActivity.this, "Введите текст напоминания!", Toast.LENGTH_SHORT).show();
                    return false;
                }
                addGeoReminder(p, msg);
                etMessage.setText("");
                return true;
            }

            @Override
            public boolean longPressHelper(GeoPoint p) {
                removeNearestReminder(p);
                return true;
            }
        };
        map.getOverlays().add(new MapEventsOverlay(mReceiver));
    }

    private void addGeoReminder(GeoPoint point, String message) {
        // Отрисовка круга 100м
        Polygon circle = new Polygon();
        circle.setPoints(Polygon.pointsAsCircle(point, RADIUS_METERS));
        circle.getFillPaint().setColor(Color.argb(50, 255, 0, 0));
        circle.getOutlinePaint().setColor(Color.RED);
        circle.getOutlinePaint().setStrokeWidth(2.0f);

        // Отрисовка маркера с вашим текстом
        Marker marker = new Marker(map);
        marker.setPosition(point);
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        marker.setTitle(message);

        map.getOverlays().add(circle);
        map.getOverlays().add(marker);

        reminders.add(new GeoReminder(point, message, circle, marker));
        map.invalidate();

        // Сразу проверяем текущую позицию, чтобы пуш пришел мгновенно
        forceLocationCheck();
    }

    private void forceLocationCheck() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.getLastLocation().addOnSuccessListener(loc -> {
                if (loc != null) checkGeoFences(loc);
            });
        }
    }

    private void removeNearestReminder(GeoPoint tapPoint) {
        Iterator<GeoReminder> it = reminders.iterator();
        while (it.hasNext()) {
            GeoReminder r = it.next();
            float[] res = new float[1];
            Location.distanceBetween(tapPoint.getLatitude(), tapPoint.getLongitude(),
                    r.point.getLatitude(), r.point.getLongitude(), res);
            if (res[0] < 150) { // Если нажали в радиусе 150м от центра
                map.getOverlays().remove(r.circleOverlay);
                map.getOverlays().remove(r.markerOverlay);
                it.remove();
                map.invalidate();
                Toast.makeText(this, "Точка удалена", Toast.LENGTH_SHORT).show();
                break;
            }
        }
    }

    private void startLocationUpdates() {
        LocationRequest request = LocationRequest.create()
                .setInterval(10000) // 10 секунд для тестов (можно 30000 для экономии)
                .setFastestInterval(5000)
                .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(request, new LocationCallback() {
                @Override
                public void onLocationResult(@NonNull LocationResult result) {
                    Location loc = result.getLastLocation();
                    if (loc != null) {
                        checkGeoFences(loc);
                        tvStats.setText("GPS активен: " + new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date()));
                    }
                }
            }, null);
        }
    }

    private void checkGeoFences(Location currentLoc) {
        for (GeoReminder r : reminders) {
            float[] res = new float[1];
            Location.distanceBetween(currentLoc.getLatitude(), currentLoc.getLongitude(),
                    r.point.getLatitude(), r.point.getLongitude(), res);

            // Если зашли в 100 метров
            if (res[0] <= RADIUS_METERS) {
                if (!r.hasNotified) {
                    sendNotification(r.message);
                    r.hasNotified = true;
                }
            } else {
                // Сброс, чтобы при повторном входе снова пришел пуш
                r.hasNotified = false;
            }
        }
    }

    private void sendNotification(String message) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info) // Системная иконка
                .setContentTitle("Гео-напоминание")
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setDefaults(NotificationCompat.DEFAULT_ALL) // Звук, вибрация
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true);

        manager.notify((int) System.currentTimeMillis(), builder.build());
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, "GPS Reminders", NotificationManager.IMPORTANCE_HIGH);
                channel.enableVibration(true);
                channel.setLockscreenVisibility(NotificationCompat.VISIBILITY_PUBLIC);
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void checkPermissions() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);

        // Для новых версий Android (API 33+) нужно разрешение на пуши
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        ActivityCompat.requestPermissions(this, perms.toArray(new String[0]), 1);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        map.onResume();
        if (mLocationOverlay != null) mLocationOverlay.enableMyLocation();
    }

    @Override
    protected void onPause() {
        super.onPause();
        map.onPause();
        if (mLocationOverlay != null) mLocationOverlay.disableMyLocation();
    }
}