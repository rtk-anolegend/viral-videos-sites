package com.callmonitor.app;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    Button btnToggle;
    TextView tvStatus, tvInfo;
    boolean isRunning = false;
    DevicePolicyManager dpm;
    ComponentName adminComponent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        btnToggle = (Button) findViewById(R.id.btnToggle);
        tvStatus = (TextView) findViewById(R.id.tvStatus);
        tvInfo = (TextView) findViewById(R.id.tvInfo);

        dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, AdminReceiver.class);

        // Check and request all permissions
        checkAllPermissions();

        // Check if service was running
        isRunning = getSharedPreferences("prefs", MODE_PRIVATE)
            .getBoolean("running", false);
        updateUI();

        btnToggle.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					if(isRunning) {
						stopService();
					} else {
						// Check permissions before starting
						if(checkAllPermissions()) {
							startService();
						} else {
							Toast.makeText(MainActivity.this, 
										   "Please grant all permissions first", Toast.LENGTH_LONG).show();
						}
					}
				}
			});

        // Auto start if was running
        if(isRunning) {
            startService();
        }
    }

    boolean checkAllPermissions() {
        boolean allGranted = true;

        // Phone permission
        if(checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            allGranted = false;
        }
        // Contacts permission
        if(checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            allGranted = false;
        }
        // Call log permission
        if(checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            allGranted = false;
        }
        // Outgoing calls permission
        if(checkSelfPermission(android.Manifest.permission.PROCESS_OUTGOING_CALLS) != PackageManager.PERMISSION_GRANTED) {
            allGranted = false;
        }
        // SMS permission
        if(checkSelfPermission(android.Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            allGranted = false;
        }
        if(checkSelfPermission(android.Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            allGranted = false;
        }

        if(!allGranted && Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{
								   android.Manifest.permission.READ_PHONE_STATE,
								   android.Manifest.permission.READ_CONTACTS,
								   android.Manifest.permission.READ_CALL_LOG,
								   android.Manifest.permission.PROCESS_OUTGOING_CALLS,
								   android.Manifest.permission.READ_SMS,
								   android.Manifest.permission.RECEIVE_SMS
							   }, 100);
            return false;
        }

        // Check Admin permission
        if(!dpm.isAdminActive(adminComponent)) {
            Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, 
							"24/7 monitoring ke liye admin permission zaroori hai");
            startActivity(intent);
            return false;
        }

        // Battery optimization
        if(Build.VERSION.SDK_INT >= 23) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if(!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        }

        return allGranted;
    }

    void startService() {
        Intent intent = new Intent(this, MonitorService.class);
        startService(intent);

        isRunning = true;
        getSharedPreferences("prefs", MODE_PRIVATE).edit()
            .putBoolean("running", true).commit();
        updateUI();

        Toast.makeText(this, "Monitoring Started", Toast.LENGTH_SHORT).show();
    }

    void stopService() {
        Intent intent = new Intent(this, MonitorService.class);
        stopService(intent);

        isRunning = false;
        getSharedPreferences("prefs", MODE_PRIVATE).edit()
            .putBoolean("running", false).commit();
        updateUI();

        Toast.makeText(this, "Monitoring Stopped", Toast.LENGTH_SHORT).show();
    }

    void updateUI() {
        if(isRunning) {
            btnToggle.setText("STOP MONITORING");
            tvStatus.setText("STATUS: ACTIVE");
            tvStatus.setTextColor(0xFF00FF00);
            tvInfo.setText("✓ Monitoring Active\n✓ Calls: Live\n✓ SMS: Live\n✓ 24/7 Running");
        } else {
            btnToggle.setText("START MONITORING");
            tvStatus.setText("STATUS: STOPPED");
            tvStatus.setTextColor(0xFFFF0000);
            tvInfo.setText("Press START to monitor:\n• Incoming calls\n• Outgoing calls\n• Missed calls\n• SMS messages");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        boolean allGranted = true;
        for(int result : grantResults) {
            if(result != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if(allGranted) {
            Toast.makeText(this, "All permissions granted", Toast.LENGTH_SHORT).show();
            // Check admin and battery after permissions
            checkAllPermissions();
        } else {
            Toast.makeText(this, "Please grant all permissions", Toast.LENGTH_LONG).show();
        }
    }
}
