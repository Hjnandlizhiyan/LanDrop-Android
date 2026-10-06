package com.deepseeklover.landrop;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
import java.io.*;
import java.util.*;

/** Read-only, temporary URI grants for files in LanDrop's own receiving directory. */
public final class ReceivedFileProvider extends ContentProvider {
    static Uri uri(Context context, File file) {
        return new Uri.Builder().scheme("content").authority(context.getPackageName() + ".files").appendPath("received").appendPath(file.getAbsolutePath()).build();
    }
    @Override public boolean onCreate() { return true; }
    private File resolve(Uri uri) throws FileNotFoundException {
        try {
            if (!uri.getAuthority().equals(getContext().getPackageName() + ".files") || uri.getPathSegments().size() != 2 || !uri.getPathSegments().get(0).equals("received")) throw new SecurityException("Invalid URI");
            File candidate = new File(uri.getPathSegments().get(1)).getCanonicalFile();
            File external = getContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            File root = external == null ? getContext().getFilesDir() : external;
            File allowed = new File(root, "LanDrop").getCanonicalFile();
            if (!candidate.getParentFile().equals(allowed) || !candidate.isFile()) throw new SecurityException("File is not a received file");
            return candidate;
        } catch (IOException e) { throw new FileNotFoundException("Received file is unavailable"); }
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new SecurityException("Read-only URI");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            File file = resolve(uri);
            String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            MatrixCursor cursor = new MatrixCursor(columns); Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) { if (columns[i].equals(OpenableColumns.DISPLAY_NAME)) row[i] = file.getName(); else if (columns[i].equals(OpenableColumns.SIZE)) row[i] = file.length(); }
            cursor.addRow(row); return cursor;
        } catch (FileNotFoundException e) { return null; }
    }
    @Override public String getType(Uri uri) {
        try {
            String name = resolve(uri).getName(); int dot = name.lastIndexOf('.');
            String type = dot >= 0 ? MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT)) : null;
            return type == null ? "application/octet-stream" : type;
        } catch (FileNotFoundException e) { return "application/octet-stream"; }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new SecurityException("Read-only provider"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new SecurityException("Read-only provider"); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new SecurityException("Read-only provider"); }
}
