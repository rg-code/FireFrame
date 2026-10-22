package com.canvashome;

import android.app.admin.DeviceAdminReceiver;

/**
 * Lets {@link Schedule} turn the screen off (the force-lock policy only). Made active over ADB
 * by ./install.sh with dpm set-active-admin.
 */
public class Admin extends DeviceAdminReceiver {
}
