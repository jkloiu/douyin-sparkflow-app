package com.sparkflow.guard;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

/**
 * 极简 FileProvider：只服务 <files>/update/ 下的 APK。
 * 自己写是为了不引 AndroidX —— 安装 APK 在 Android 7+ 必须用 content:// 才不会被 FileUriExposed 拦掉。
 */
public class SimpleFileProvider extends ContentProvider {

    private static final String[] COLS = {
            OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE
    };

    @Override
    public boolean onCreate() {
        return true;
    }

    private File resolve(Uri uri) {
        List<String> seg = uri.getPathSegments();
        if (seg == null || seg.size() < 2 || !"update".equals(seg.get(0))) {
            return null;
        }
        String name = seg.get(1);
        if (name == null || name.length() == 0
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.contains("..")) {
            return null;   // 防目录穿越
        }
        return new File(UpdateManager.updateDir(getContext()), name);
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = resolve(uri);
        if (f == null || !f.exists()) {
            throw new FileNotFoundException(String.valueOf(uri));
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File f = resolve(uri);
        if (f == null || !f.exists()) {
            return null;
        }
        MatrixCursor c = new MatrixCursor(projection == null ? COLS : projection, 1);
        Object[] row = new Object[c.getColumnCount()];
        for (int i = 0; i < row.length; i++) {
            String name = c.getColumnName(i);
            if (OpenableColumns.DISPLAY_NAME.equals(name)) {
                row[i] = f.getName();
            } else if (OpenableColumns.SIZE.equals(name)) {
                row[i] = Long.valueOf(f.length());
            } else {
                row[i] = null;
            }
        }
        c.addRow(row);
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
