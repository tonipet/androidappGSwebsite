package com.example.gamemonitoringapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageStats;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.telephony.SmsManager;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.firestore.FirebaseFirestore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

public class MyService extends Service {

    private static final String CHANNEL_ID = "ForegroundServiceChannel";
    private Handler handler = new Handler();
    private FirebaseFirestore db;
    private PackageManager packageManager;
    private FirebaseAuth mAuth;
    private DatabaseReference userProfileRef;
    private ItexmoSmsSender smsSender;

    private static final String VONAGE_SMS_API_URL = "https://rest.nexmo.com/sms/json";
    private static final String API_KEY = "b12103ff";
    private static final String API_SECRET = "Welcome12@3";

    public interface IsSMSSentCallback {
        void onResult(boolean isSent);
    }
    public interface OnTotalTimeFetchedListener {
        void onTotalTimeFetched(long totalTimeInSeconds, String appUsageDetails);
        void onFailure(String errorMessage);
    }
    @Override
    public void onCreate() {
        userProfileRef = FirebaseDatabase.getInstance().getReference().child("user_profile");
        smsSender = new ItexmoSmsSender();

        super.onCreate();
        createNotificationChannel();
        Notification notification = getNotification();
        startForeground(1, notification);

        db = FirebaseFirestore.getInstance();
        packageManager = getPackageManager();
        mAuth = FirebaseAuth.getInstance();

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                insertDataIntoFirestore();
                handler.postDelayed(this, 60000); // 1 minute delay
            }
        }, 30000); // Initial delay of 30 seconds
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Foreground Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    private Notification getNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Game Monitoring Service")
                .setContentText("Monitoring game usage...")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .build();
    }

    private void sendSms(String phoneNumber, String message) {
        SmsManager sms = SmsManager.getDefault();
        ArrayList<String> parts = sms.divideMessage(message);
        sms.sendMultipartTextMessage(phoneNumber, null, parts, null, null);
    }

    private void IsSMSSent(String currentUserId, String currentDate, IsSMSSentCallback callback) {
        String documentName = currentUserId + "_" + currentDate;
        DatabaseReference dbRef = FirebaseDatabase.getInstance().getReference().child("User_SMS").child(documentName);

        dbRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                if (dataSnapshot.exists()) {
                    callback.onResult(true);
                } else {
                    saveIsSentInDatabase(documentName, true, currentUserId, currentDate, callback);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                Log.e("MyService", "Database error: " + databaseError.getMessage());
                callback.onResult(false);
            }
        });
    }

    private void saveIsSentInDatabase(String documentName, boolean isSent, String currentUserId, String currentDate, IsSMSSentCallback callback) {
        DatabaseReference dbRef = FirebaseDatabase.getInstance().getReference().child("User_SMS");
        Map<String, Object> data = new HashMap<>();
        data.put("isSent", isSent);
        data.put("currentUserId", currentUserId);
        data.put("currentDate", currentDate);

        dbRef.child(documentName).setValue(data)
                .addOnSuccessListener(aVoid -> {
                    Log.d("MyService", "Data saved in database for document: " + documentName);
                    callback.onResult(false);
                })
                .addOnFailureListener(e -> {
                    Log.e("MyService", "Failed to save data in database for document: " + documentName, e);
                    callback.onResult(true);
                });
    }

    private void insertDataIntoFirestore() {
        long startTime = getTodayStartTimeInMillis();
        long endTime = System.currentTimeMillis();
        FirebaseUser currentUser = mAuth.getCurrentUser();
        String currentDate = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        String currentUserId = currentUser != null ? currentUser.getUid() : "unknown_user";

        DatabaseReference dbRef = FirebaseDatabase.getInstance().getReference().child("usage_GameStatsInfo");
        List<UsageStats> stats = UsageStatsUtil.getUsageStats(this, startTime, endTime);

        Map<String, Long> appUsageDurations = new HashMap<>();
        for (UsageStats usageStats : stats) {
            try {
                ApplicationInfo appInfo = packageManager.getApplicationInfo(usageStats.getPackageName(), 0);
                if (isGameApp(appInfo)) {
                    String appName = usageStats.getPackageName();
                    long totalTimeInForegroundMillis = usageStats.getTotalTimeInForeground();

                    appUsageDurations.put(appName, appUsageDurations.getOrDefault(appName, 0L) + totalTimeInForegroundMillis);
                }
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
            }
        }

        StringBuilder usageDetails = new StringBuilder();

        for (Map.Entry<String, Long> entry : appUsageDurations.entrySet()) {
            String appName = entry.getKey();
            long totalTimeInForegroundMillis = entry.getValue();
            String totalTimeFormatted = formatTime(totalTimeInForegroundMillis);

            String packageName;
            String ApplicationName;
            try {
                ApplicationInfo appInfo = packageManager.getApplicationInfo(appName, 0);
                packageName = appInfo.packageName;
                ApplicationName = getAppName(packageName);
                String base64Logo = getBase64AppLogo(packageName);

                Map<String, Object> usageData = new HashMap<>();
                usageData.put("appName", ApplicationName);
                usageData.put("totalTimeInForeground", totalTimeFormatted);
                usageData.put("date", currentDate);
                usageData.put("userId", currentUserId);
                usageData.put("userIdDate", currentUserId + "_" + currentDate);
                usageData.put("appLogo", base64Logo);

                String documentName = currentUserId + "_" + ApplicationName + "_" + currentDate;

                dbRef.child(documentName).setValue(usageData)
                        .addOnSuccessListener(aVoid -> {
                            Log.d("MyService", "Data inserted into Firestore for app: " + ApplicationName);
                        })
                        .addOnFailureListener(e -> {
                            Log.e("MyService", "Failed to insert data into Firestore for app: " + ApplicationName, e);
                        });

                usageDetails.append(ApplicationName).append(": ").append(totalTimeFormatted).append(", ");

            } catch (PackageManager.NameNotFoundException e) {
                Log.e("MyService", "Failed to get package name for app: " + appName, e);
            }
        }

        Log.d("MyService", "Usage Details: " + usageDetails.toString());

        if (appUsageDurations.isEmpty()) {
            Log.d("MyService", "No app usage data to insert into Firestore.");
        }

        checkAndSendSMS(currentUserId, currentDate);
    }

    private boolean isMidnight() {
        Calendar calendar = Calendar.getInstance();
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        return hour == 0;
    }
    private void checkAndSendSMS(String currentUserId, String currentDate) {
        userProfileRef.child(currentUserId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                if (dataSnapshot.exists()) {
                    String noOfHoursStr = dataSnapshot.child("noofhours").getValue(String.class);
                    String CPNumber = dataSnapshot.child("phone").getValue(String.class);
                    String studentName = dataSnapshot.child("studentName").getValue(String.class);

                    if (noOfHoursStr != null) {
                        double noOfHours;
                        try {
                            noOfHours = Double.parseDouble(noOfHoursStr);
                        } catch (NumberFormatException e) {
                            Log.e("MyService", "Failed to parse noOfHours: " + noOfHoursStr, e);
                            return; // Exit if the value is not a valid number
                        }

                        long totalUsageTimeForDay = convertHoursToSeconds(noOfHours);

                        fetchTotalApplicationTime(new OnTotalTimeFetchedListener() {
                            @Override
                            public void onTotalTimeFetched(long totalTimeInSeconds, String fetchTotalApplicationTime) {
                                // Check if the actual usage exceeds the allowed time
                                if (totalUsageTimeForDay < totalTimeInSeconds) {
                                    IsSMSSent(currentUserId, currentDate, new IsSMSSentCallback() {
                                        @Override
                                        public void onResult(boolean isSent) {
                                            if (!isSent) {
                                                // Construct the SMS message
                                                String message = "This is to notify that " + studentName +
                                                        " has exceeded the allowed usage time.\n" +
                                                        formatTotalTimeSMS(totalTimeInSeconds) +
                                                        "\nList of Games:\n" + fetchTotalApplicationTime;

                                                // Send the SMS using the itextmo service
                                                //Toast.makeText(MyService.this, "sent sent sent", Toast.LENGTH_SHORT).show();
                                              SentSMSitextmo(CPNumber, message);
                                            }
                                        }
                                    });
                                }
                            }

                            @Override
                            public void onFailure(String errorMessage) {
                                // Handle the error
                                Toast.makeText(MyService.this, "Failed to fetch data: " + errorMessage, Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                Log.e("MyService", "Failed to load profile data: " + databaseError.getMessage());
                Toast.makeText(MyService.this, "Failed to load profile data", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private String formatTotalTimeSMS(long totalTimeSeconds) {
        long secondsInMinute = 60;
        long secondsInHour = secondsInMinute * 60;
        long secondsInDay = secondsInHour * 24;
        long secondsInMonth = secondsInDay * 30; // Approximation for a month
        long secondsInYear = secondsInDay * 365; // Approximation for a year

        long years = totalTimeSeconds / secondsInYear;
        long months = (totalTimeSeconds % secondsInYear) / secondsInMonth;
        long days = (totalTimeSeconds % secondsInMonth) / secondsInDay;
        long hours = (totalTimeSeconds % secondsInDay) / secondsInHour;
        long minutes = (totalTimeSeconds % secondsInHour) / secondsInMinute;
        long seconds = totalTimeSeconds % secondsInMinute;

        if (years > 0) {
            return String.format("Total Usage: %d year/s, %d month/s, %d day/s, %d hour/s, %d minute/s, %d second/s", years, months, days, hours, minutes, seconds);
        } else if (months > 0) {
            return String.format("Total Usage: %d month/s, %d day/s, %d hour/s, %d minute/s, %d second/s", months, days, hours, minutes, seconds);
        } else if (days > 0) {
            return String.format("Total Usage: %d day/s, %d hours, %d minute/s, %d second/s", days, hours, minutes, seconds);
        } else {
            return String.format("Total Usage: %d hour/s, %d minute/s, %d second/s", hours, minutes, seconds);
        }
    }


    private long convertHoursToSeconds(double hours) {
        return (long) (hours * 3600);
    }
    private long getTodayStartTimeInMillis() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private boolean isGameApp(ApplicationInfo appInfo) {
        return (appInfo.flags & ApplicationInfo.FLAG_IS_GAME) != 0 ||
                appInfo.category == ApplicationInfo.CATEGORY_GAME;
    }

    private String getAppName(String packageName) {
        try {
            ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, 0);
            return (String) packageManager.getApplicationLabel(appInfo);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return "Unknown App";
        }
    }

    private String getBase64AppLogo(String packageName) {
        try {
            ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, 0);
            Drawable appIcon = packageManager.getApplicationIcon(appInfo);
            if (appIcon instanceof BitmapDrawable) {
                Bitmap bitmap = ((BitmapDrawable) appIcon).getBitmap();
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream);
                byte[] byteArray = outputStream.toByteArray();
                return Base64.encodeToString(byteArray, Base64.DEFAULT);
            }
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        return "";
        }

        private void fetchTotalApplicationTime(OnTotalTimeFetchedListener listener) {
            FirebaseDatabase database = FirebaseDatabase.getInstance();
            DatabaseReference dbRef = database.getReference("usage_GameStatsInfo");
            String currentDate = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());

            // Get current user's UID from Firebase Authentication
            FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
            String uid = currentUser != null ? currentUser.getUid() : null;

            Query query = dbRef.orderByChild("userIdDate").equalTo(uid + "_" + currentDate);

            query.addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                    long totalApplicationTime = 0;  // Accumulator for total time in foreground
                    StringBuilder resultBuilder = new StringBuilder();
                    for (DataSnapshot snapshot : dataSnapshot.getChildren()) {
                        String totalTimeInForeground = snapshot.child("totalTimeInForeground").getValue(String.class);
                        String appName = snapshot.child("appName").getValue(String.class);


                        if (totalTimeInForeground != null) {
                            totalApplicationTime += parseTimeToSeconds(totalTimeInForeground);
                        }
                        resultBuilder.append(appName)
                                .append(": ")
                                .append(totalTimeInForeground)
                                .append("\n");
                    }

                    // Pass the result to the listener
                    if (listener != null) {
                        listener.onTotalTimeFetched(totalApplicationTime, resultBuilder.toString());
                    }
                }

                @Override
                public void onCancelled(@NonNull DatabaseError databaseError) {
                    // Pass the error message to the listener
                    if (listener != null) {
                        listener.onFailure(databaseError.getMessage());
                    }
                }
            });
        }

    private long parseTimeToSeconds(String timeString) {
        String[] timeParts = timeString.split(":");
        long hours = Long.parseLong(timeParts[0]);
        long minutes = Long.parseLong(timeParts[1]);
        long seconds = Long.parseLong(timeParts[2]);
        return (hours * 3600) + (minutes * 60) + seconds;
    }


    private String formatTime(long timeInMillis) {
        long seconds = timeInMillis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;

        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes % 60, seconds % 60);
    }

    private void SentSMSitextmo(String to, String message) {
        // Ensure recipient and message are not empty
        if (to == null || to.isEmpty() || message == null || message.isEmpty()) {
            Log.d("MyService", "Recipient or message cannot be empty");
            return;
        }

        // Call the sendSms method from ItexmoSmsSender
        smsSender.sendSms(to, message, new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                e.printStackTrace();
                // Use Log.d to log failure
                Log.d("MyService", "Failed to send SMS", e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    String responseBody = response.body().string();
                    // Use Log.d to log success
                    Log.d("MyService", "SMS sent: " + responseBody);
                } else {
                    // Use Log.d to log failure with response code
                    Log.d("MyService", "Failed: " + response.code());
                }
            }
        });
    }


}
