package com.example.streamer;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

final class StorageWriter {
    private StorageWriter() {}

    static String saveMp4(Context context, File source, String requestedName) throws IOException {
        String name = safeName(requestedName);
        if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".mp4")) name += ".mp4";
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, name);
            values.put(MediaStore.Downloads.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/StreamBrowser");
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("Android could not create the Downloads entry");
            boolean complete = false;
            try (OutputStream output = resolver.openOutputStream(uri, "w")) {
                if (output == null) throw new IOException("Android could not open the Downloads entry");
                copy(source, output);
                complete = true;
            } finally {
                if (!complete) resolver.delete(uri, null, null);
            }
            ContentValues finished = new ContentValues();
            finished.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(uri, finished, null, null);
            return "Downloads/StreamBrowser/" + name;
        }

        File directory = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "StreamBrowser");
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("Could not create Downloads folder");
        File target = uniqueFile(directory, name);
        try (OutputStream output = new FileOutputStream(target)) { copy(source, output); }
        return target.getAbsolutePath();
    }

    static String safeName(String value) {
        String cleaned = value == null ? "video" : value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        cleaned = cleaned.replaceAll("\\s+", " ");
        if (cleaned.isEmpty()) cleaned = "video";
        if (cleaned.length() > 120) cleaned = cleaned.substring(0, 120).trim();
        return cleaned;
    }

    private static File uniqueFile(File directory, String name) {
        File file = new File(directory, name);
        if (!file.exists()) return file;
        int dot = name.lastIndexOf('.');
        String base = dot < 0 ? name : name.substring(0, dot);
        String extension = dot < 0 ? "" : name.substring(dot);
        for (int index = 2; ; index++) {
            file = new File(directory, base + " (" + index + ")" + extension);
            if (!file.exists()) return file;
        }
    }

    private static void copy(File source, OutputStream output) throws IOException {
        try (InputStream input = new FileInputStream(source)) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
            output.flush();
        }
    }
}
