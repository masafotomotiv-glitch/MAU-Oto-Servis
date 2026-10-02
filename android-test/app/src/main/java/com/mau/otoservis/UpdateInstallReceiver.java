package com.mau.otoservis;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.widget.Toast;

public class UpdateInstallReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(
                PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE
        );

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmIntent;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                confirmIntent = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
            } else {
                //noinspection deprecation
                confirmIntent = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            }

            if (confirmIntent != null) {
                confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirmIntent);
            }
            return;
        }

        if (status == PackageInstaller.STATUS_SUCCESS) {
            Toast.makeText(context, "MAU Oto Servis güncellendi.", Toast.LENGTH_LONG).show();
            return;
        }

        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        Toast.makeText(
                context,
                "Güncelleme kurulamadı: " + (message == null ? "Android kurulum hatası" : message),
                Toast.LENGTH_LONG
        ).show();
    }
}
