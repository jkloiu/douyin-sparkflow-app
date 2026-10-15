package com.sparkflow.guard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机 / 本应用被覆盖安装后，把两个后台任务重新排上。
 * （JobScheduler 的 setPersisted(true) 大多数机型能自动恢复，这里再做一层兜底。）
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent == null ? "" : String.valueOf(intent.getAction());
        if (Intent.ACTION_BOOT_COMPLETED.equals(a)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            UpdateJobService.rescheduleAll(context);
        }
    }
}
