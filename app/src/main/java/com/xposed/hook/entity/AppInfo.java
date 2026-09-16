package com.xposed.hook.entity;

import java.io.Serializable;

public class AppInfo implements Serializable {

    public String title;
    public String packageName;
    /** PackageInfo.lastUpdateTime of the installed APK, used to invalidate cached metadata/icons. */
    public long lastUpdateTime;
    public boolean enabled;
    public boolean isSystem;
}
