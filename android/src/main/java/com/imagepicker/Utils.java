package com.imagepicker;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.camera2.CameraCharacteristics;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.webkit.MimeTypeMap;

import androidx.core.app.ActivityCompat;
import androidx.core.content.FileProvider;
import androidx.exifinterface.media.ExifInterface;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.UUID;

import static com.imagepicker.ImagePickerModule.*;

public class Utils {
    public static String fileNamePrefix = "rn_image_picker_lib_temp_";

    public static String errCameraUnavailable = "camera_unavailable";
    public static String errPermission = "permission";
    public static String errOthers = "others";

    public static String mediaTypePhoto = "photo";
    public static String mediaTypeVideo = "video";

    public static String cameraPermissionDescription = "This library does not require Manifest.permission.CAMERA, if you add this permission in manifest then you have to obtain the same.";

    public static File createFile(Context reactContext, String fileType) {
        try {
            String filename = fileNamePrefix  + UUID.randomUUID() + "." + fileType;

            // getCacheDir will auto-clean according to android docs
            File fileDir = reactContext.getCacheDir();

            File file = new File(fileDir, filename);
            file.createNewFile();
            return file;

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static Uri createUri(File file, Context reactContext) {
        String authority = reactContext.getApplicationContext().getPackageName() + ".imagepickerprovider";
        return FileProvider.getUriForFile(reactContext, authority, file);
    }

    public static void saveToPublicDirectory(Uri uri, Context context, String mediaType) {
        ContentResolver resolver = context.getContentResolver();
        Uri mediaStoreUri;
        ContentValues fileDetails = new ContentValues();

        if (mediaType.equals("video")) {
            fileDetails.put(MediaStore.Video.Media.DISPLAY_NAME, UUID.randomUUID().toString());
            fileDetails.put(MediaStore.Video.Media.MIME_TYPE, resolver.getType(uri));
            mediaStoreUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, fileDetails);
        } else {
            fileDetails.put(MediaStore.Images.Media.DISPLAY_NAME, UUID.randomUUID().toString());
            fileDetails.put(MediaStore.Images.Media.MIME_TYPE, resolver.getType(uri));
            mediaStoreUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, fileDetails);
        }

        copyUri(uri, mediaStoreUri, resolver);
    }

    public static void copyUri(Uri fromUri, Uri toUri, ContentResolver resolver) {
        try {
            OutputStream os = resolver.openOutputStream(toUri);
            InputStream is = resolver.openInputStream(fromUri);

            byte[] buffer = new byte[8192];
            int bytesRead;

            while ((bytesRead = is.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Make a copy of shared storage files inside app specific storage so that users can access it later.
    public static Uri getAppSpecificStorageUri(Uri sharedStorageUri, Context context) {
        if (sharedStorageUri == null) {
            return null;
        }
        ContentResolver contentResolver = context.getContentResolver();
        String fileType = getFileTypeFromMime(contentResolver.getType(sharedStorageUri));
        Uri toUri =  Uri.fromFile(createFile(context, fileType));
        copyUri(sharedStorageUri, toUri, contentResolver);
        return toUri;
    }

    public static boolean isCameraAvailable(Context reactContext) {
        return reactContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA)
                || reactContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
    }

    // Opening front camera is not officially supported in android, the below hack is obtained from various online sources
    public static void setFrontCamera(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            intent.putExtra("android.intent.extras.CAMERA_FACING", CameraCharacteristics.LENS_FACING_FRONT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                intent.putExtra("android.intent.extra.USE_FRONT_CAMERA", true);
            }
        } else {
            intent.putExtra("android.intent.extras.CAMERA_FACING", 1);
        }
    }

    public static int[] getImageDimensions(Uri uri, Context reactContext) {
        InputStream inputStream;
        try {
            inputStream = reactContext.getContentResolver().openInputStream(uri);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            return new int[]{0, 0};
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeStream(inputStream,null, options);
        return new int[]{options.outWidth, options.outHeight};
    }

    static boolean hasPermission(final Activity activity) {
        final int writePermission = ActivityCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE);
        return writePermission == PackageManager.PERMISSION_GRANTED ? true : false;
    }

    static String getBase64String(Uri uri, Context reactContext) {
        InputStream inputStream;
        try {
            inputStream = reactContext.getContentResolver().openInputStream(uri);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            return null;
        }

        byte[] bytes;
        byte[] buffer = new byte[8192];
        int bytesRead;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        bytes = output.toByteArray();
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    // Resize image
    // When decoding a jpg to bitmap all exif meta data will be lost, so make sure to copy orientation exif to new file else image might have wrong orientations
    public static Uri resizeImage(Uri uri, Context context, Options options) {
        try {
            int[] origDimens = getImageDimensions(uri, context);

            if (!shouldResizeImage(origDimens[0], origDimens[1], options)) {
                return uri;
            }

            int[] newDimens = getImageDimensBasedOnConstraints(origDimens[0], origDimens[1], options);

            InputStream imageStream = context.getContentResolver().openInputStream(uri);
            String mimeType =  getMimeTypeFromFileUri(uri);
            Bitmap b = BitmapFactory.decodeStream(imageStream);
            b = Bitmap.createScaledBitmap(b, newDimens[0], newDimens[1], true);
            String originalOrientation = getOrientation(uri, context);

            File file = createFile(context, getFileTypeFromMime(mimeType));
            OutputStream os = context.getContentResolver().openOutputStream(Uri.fromFile(file));
            b.compress(getBitmapCompressFormat(mimeType), options.quality, os);
            setOrientation(file, originalOrientation, context);
            return Uri.fromFile(file);

        } catch (Exception e) {
            e.printStackTrace();
            return  null;
        }
    }

    static String getOrientation(Uri uri, Context context) throws IOException {
        ExifInterface exifInterface = new ExifInterface(context.getContentResolver().openInputStream(uri));
        return exifInterface.getAttribute(ExifInterface.TAG_ORIENTATION);
    }

    // ExifInterface.saveAttributes is costly operation so don't set exif for unnecessary orientations
    static void setOrientation(File file, String orientation, Context context) throws IOException {
        if (orientation.equals(String.valueOf(ExifInterface.ORIENTATION_NORMAL)) || orientation.equals(String.valueOf(ExifInterface.ORIENTATION_UNDEFINED))) {
            return;
        }
        ExifInterface exifInterface = new ExifInterface(file);
        exifInterface.setAttribute(ExifInterface.TAG_ORIENTATION, orientation);
        exifInterface.saveAttributes();
    }

    static int[] getImageDimensBasedOnConstraints(int origWidth, int origHeight, Options options) {
        int width = origWidth;
        int height = origHeight;

        if (options.maxWidth == 0 || options.maxHeight == 0) {
            return new int[]{width, height};
        }

        if (options.maxWidth < width) {
            height = (int) (((float) options.maxWidth / width) * height);
            width = options.maxWidth;
        }

        if (options.maxHeight < height) {
            width = (int) (((float) options.maxHeight / height) * width);
            height = options.maxHeight;
        }

        return new int[]{width, height};
    }

    static double getFileSize(Uri uri, Context context) {
        try {
            ParcelFileDescriptor f = context.getContentResolver().openFileDescriptor(uri, "r");
            return f.getStatSize();
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }

    static int getDuration(Uri uri, Context context) {
        MediaMetadataRetriever m = new MediaMetadataRetriever();
        m.setDataSource(context, uri);
        int duration = Math.round(Float.parseFloat(m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION))) / 1000;
        m.release();
        return duration;
    }

    static boolean shouldResizeImage(int origWidth, int origHeight, Options options) {
        if ((options.maxWidth == 0 || options.maxHeight == 0) && options.quality == 100) {
            return false;
        }

        if (options.maxWidth >= origWidth && options.maxHeight >= origHeight && options.quality == 100) {
            return false;
        }

        return true;
    }

    static Bitmap.CompressFormat getBitmapCompressFormat(String mimeType) {
        switch (mimeType) {
            case "image/jpeg": return Bitmap.CompressFormat.JPEG;
            case "image/png": return Bitmap.CompressFormat.PNG;
        }
        return Bitmap.CompressFormat.JPEG;
    }

    static String getFileTypeFromMime(String mimeType) {
        if (mimeType == null) {
            return "jpg";
        }
        switch (mimeType) {
            case "image/jpeg": return "jpg";
            case "image/png": return "png";
        }
        return "jpg";
    }

    static void deleteFile(Uri uri) {
        new File(uri.getPath()).delete();
    }

    static String getMimeTypeFromFileUri(Uri uri) {
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(uri.toString()));
    }

    // Since library users can have many modules in their project, we should respond to onActivityResult only for our request.
    static boolean isValidRequestCode(int requestCode) {
        switch (requestCode) {
            case REQUEST_LAUNCH_IMAGE_CAPTURE:
            case REQUEST_LAUNCH_VIDEO_CAPTURE:
            case REQUEST_LAUNCH_LIBRARY: return true;
            default: return false;
        }
    }

    // This library does not require Manifest.permission.CAMERA permission, but if user app declares as using this permission which is not granted, then attempting to use ACTION_IMAGE_CAPTURE|ACTION_VIDEO_CAPTURE will result in a SecurityException.
    // https://issuetracker.google.com/issues/37063818
    public static boolean isCameraPermissionFulfilled(Context context, Activity activity) {
        try {
             String[] declaredPermissions = context.getPackageManager()
                     .getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS)
                     .requestedPermissions;

             if (declaredPermissions == null) {
                 return true;
             }

            if (Arrays.asList(declaredPermissions).contains(Manifest.permission.CAMERA)
                    && ActivityCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }

            return true;

        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return true;
        }
    }

    static boolean isImageType(Uri uri, Context context) {
        ContentResolver contentResolver = context.getContentResolver();
        return contentResolver.getType(uri).contains("image/");
    }

    static boolean isVideoType(Uri uri, Context context) {
        ContentResolver contentResolver = context.getContentResolver();
        return contentResolver.getType(uri).contains("video/");
    }

    static ReadableMap getResponseMap(Uri uri, Options options, Context context) {
        String fileName = uri.getLastPathSegment();
        int[] dimensions = getImageDimensions(uri, context);

        WritableMap map = Arguments.createMap();
        map.putString("uri", uri.toString());
        map.putDouble("fileSize", getFileSize(uri, context));
        map.putString("fileName", fileName);
        map.putString("type", getMimeTypeFromFileUri(uri));
        map.putInt("width", dimensions[0]);
        map.putInt("height", dimensions[1]);

        if (options.includeBase64) {
            map.putString("base64", getBase64String(uri, context));
        }
        return map;
    }

    static ReadableMap getVideoResponseMap(Uri uri, Context context) {
        String fileName = uri.getLastPathSegment();
        WritableMap map = Arguments.createMap();
        map.putString("uri", uri.toString());
        map.putDouble("fileSize", getFileSize(uri, context));
        map.putInt("duration", getDuration(uri, context));
        map.putString("fileName", fileName);
        return map;
    }

    static ReadableMap getErrorMap(String errCode, String errMsg) {
        WritableMap map = Arguments.createMap();
        map.putString("errorCode", errCode);
        if (errMsg != null) {
            map.putString("errorMessage", errMsg);
        }
        return map;
    }

    static ReadableMap getCancelMap() {
        WritableMap map = Arguments.createMap();
        map.putBoolean("didCancel", true);
        return map;
    }
}
