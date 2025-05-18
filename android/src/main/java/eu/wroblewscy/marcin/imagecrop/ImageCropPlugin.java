package eu.wroblewscy.marcin.imagecrop;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.annotation.NonNull;
import androidx.exifinterface.media.ExifInterface;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry.RequestPermissionsResultListener;

/**
 * ImageCropPlugin implements FlutterPlugin v2 embedding and handles image cropping.
 */
public final class ImageCropPlugin implements FlutterPlugin, ActivityAware, MethodCallHandler, RequestPermissionsResultListener {
    private static final int PERMISSION_REQUEST_CODE = 13094;

    private MethodChannel channel;
    private Activity activity;
    private ActivityPluginBinding binding;
    private Result permissionRequestResult;
    private ExecutorService executor;

    public ImageCropPlugin() {}

    // Called when plugin is attached to Flutter engine
    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        BinaryMessenger messenger = flutterPluginBinding.getBinaryMessenger();
        channel = new MethodChannel(messenger, "plugins.marcin.wroblewscy.eu/image_crop_plus");
        channel.setMethodCallHandler(this);
    }

    // Called when plugin is detached from Flutter engine
    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        channel.setMethodCallHandler(null);
        channel = null;
    }

    // Called when an Activity is available
    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        this.binding = binding;
        this.activity = binding.getActivity();
        binding.addRequestPermissionsResultListener(this);
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        // No-op
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        onAttachedToActivity(binding);
    }

    @Override
    public void onDetachedFromActivity() {
        if (this.binding != null) {
            this.binding.removeRequestPermissionsResultListener(this);
        }
        this.activity = null;
    }

    // Handle incoming method calls from Dart
    @Override
    public void onMethodCall(MethodCall call, Result result) {
        String method = call.method;
        if ("cropImage".equals(method)) {
            String path = call.argument("path");
            double scale = call.argument("scale");
            double left = call.argument("left");
            double top = call.argument("top");
            double right = call.argument("right");
            double bottom = call.argument("bottom");
            RectF area = new RectF((float) left, (float) top, (float) right, (float) bottom);
            cropImage(path, area, (float) scale, result);
        } else if ("sampleImage".equals(method)) {
            String path = call.argument("path");
            int maximumWidth = call.argument("maximumWidth");
            int maximumHeight = call.argument("maximumHeight");
            sampleImage(path, maximumWidth, maximumHeight, result);
        } else if ("getImageOptions".equals(method)) {
            String path = call.argument("path");
            getImageOptions(path, result);
        } else if ("requestPermissions".equals(method)) {
            requestPermissions(result);
        } else {
            result.notImplemented();
        }
    }

    // Run tasks off the UI thread
    private synchronized void io(@NonNull Runnable runnable) {
        if (executor == null) {
            executor = Executors.newCachedThreadPool();
        }
        executor.execute(runnable);
    }

    // Run tasks on the UI thread
    private void ui(@NonNull Runnable runnable) {
        activity.runOnUiThread(runnable);
    }

    private void cropImage(final String path, final RectF area, final float scale, final Result result) {
        io(new Runnable() {
            @Override
            public void run() {
                File srcFile = new File(path);
                if (!srcFile.exists()) {
                    ui(() -> result.error("INVALID", "Image source cannot be opened", null));
                    return;
                }

                Bitmap srcBitmap = BitmapFactory.decodeFile(path, null);
                if (srcBitmap == null) {
                    ui(() -> result.error("INVALID", "Image source cannot be decoded", null));
                    return;
                }

                ImageOptions options = decodeImageOptions(path);
                if (options.isFlippedDimensions()) {
                    Matrix transformations = new Matrix();
                    transformations.postRotate(options.getDegrees());
                    Bitmap oldBitmap = srcBitmap;
                    srcBitmap = Bitmap.createBitmap(oldBitmap,
                                                    0, 0,
                                                    oldBitmap.getWidth(), oldBitmap.getHeight(),
                                                    transformations, true);
                    oldBitmap.recycle();
                }

                int width = (int) (options.getWidth() * area.width() * scale);
                int height = (int) (options.getHeight() * area.height() * scale);

                Bitmap dstBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(dstBitmap);
                Paint paint = new Paint();
                paint.setAntiAlias(true);
                paint.setFilterBitmap(true);
                paint.setDither(true);

                Rect srcRect = new Rect(
                        (int) (srcBitmap.getWidth() * area.left),
                        (int) (srcBitmap.getHeight() * area.top),
                        (int) (srcBitmap.getWidth() * area.right),
                        (int) (srcBitmap.getHeight() * area.bottom)
                );
                Rect dstRect = new Rect(0, 0, width, height);
                canvas.drawBitmap(srcBitmap, srcRect, dstRect, paint);

                try {
                    final File dstFile = createTemporaryImageFile();
                    compressBitmap(dstBitmap, dstFile);
                    ui(() -> result.success(dstFile.getAbsolutePath()));
                } catch (final IOException e) {
                    ui(() -> result.error("INVALID", "Image could not be saved", e));
                } finally {
                    canvas.setBitmap(null);
                    dstBitmap.recycle();
                    srcBitmap.recycle();
                }
            }
        });
    }

    private void sampleImage(final String path, final int maximumWidth, final int maximumHeight, final Result result) {
        io(() -> {
            File srcFile = new File(path);
            if (!srcFile.exists()) {
                ui(() -> result.error("INVALID", "Image source cannot be opened", null));
                return;
            }

            ImageOptions options = decodeImageOptions(path);
            BitmapFactory.Options bitmapOptions = new BitmapFactory.Options();
            bitmapOptions.inSampleSize = calculateInSampleSize(options.getWidth(), options.getHeight(),
                                                               maximumWidth, maximumHeight);

            Bitmap bitmap = BitmapFactory.decodeFile(path, bitmapOptions);
            if (bitmap == null) {
                ui(() -> result.error("INVALID", "Image source cannot be decoded", null));
                return;
            }

            if (options.getWidth() > maximumWidth && options.getHeight() > maximumHeight) {
                float ratio = Math.max(
                        maximumWidth / (float) options.getWidth(),
                        maximumHeight / (float) options.getHeight()
                );
                Bitmap sample = bitmap;
                bitmap = Bitmap.createScaledBitmap(sample,
                                                   Math.round(bitmap.getWidth() * ratio),
                                                   Math.round(bitmap.getHeight() * ratio),
                                                   true);
                sample.recycle();
            }

            try {
                final File dstFile = createTemporaryImageFile();
                compressBitmap(bitmap, dstFile);
                copyExif(srcFile, dstFile);
                ui(() -> result.success(dstFile.getAbsolutePath()));
            } catch (final IOException e) {
                ui(() -> result.error("INVALID", "Image could not be saved", e));
            } finally {
                bitmap.recycle();
            }
        });
    }

    private void compressBitmap(Bitmap bitmap, File file) throws IOException {
        OutputStream outputStream = new FileOutputStream(file);
        try {
            boolean compressed = bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream);
            if (!compressed) {
                throw new IOException("Failed to compress bitmap into JPEG");
            }
        } finally {
            try {
                outputStream.close();
            } catch (IOException ignore) {}
        }
    }

    private int calculateInSampleSize(int width, int height, int maximumWidth, int maximumHeight) {
        int inSampleSize = 1;
        if (height > maximumHeight || width > maximumWidth) {
            int halfHeight = height / 2;
            int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= maximumHeight && (halfWidth / inSampleSize) >= maximumWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    private void getImageOptions(final String path, final Result result) {
        io(() -> {
            File file = new File(path);
            if (!file.exists()) {
                result.error("INVALID", "Image source cannot be opened", null);
                return;
            }
            ImageOptions options = decodeImageOptions(path);
            final Map<String, Object> properties = new HashMap<>();
            properties.put("width", options.getWidth());
            properties.put("height", options.getHeight());
            ui(() -> result.success(properties));
        });
    }

    private void requestPermissions(Result result) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (activity.checkSelfPermission(READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                activity.checkSelfPermission(WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                result.success(true);
            } else {
                permissionRequestResult = result;
                activity.requestPermissions(
                    new String[]{READ_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE}, PERMISSION_REQUEST_CODE
                );
            }
        } else {
            result.success(true);
        }
    }

    @Override
    public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST_CODE && permissionRequestResult != null) {
            int read = getPermissionGrantResult(READ_EXTERNAL_STORAGE, permissions, grantResults);
            int write = getPermissionGrantResult(WRITE_EXTERNAL_STORAGE, permissions, grantResults);
            permissionRequestResult.success(
                read == PackageManager.PERMISSION_GRANTED && write == PackageManager.PERMISSION_GRANTED
            );
            permissionRequestResult = null;
        }
        return true;
    }

    private int getPermissionGrantResult(String permission, String[] permissions, int[] grantResults) {
        for (int i = 0; i < permissions.length; i++) {
            if (permission.equals(permissions[i])) {
                return grantResults[i];
            }
        }
        return PackageManager.PERMISSION_DENIED;
    }

    private File createTemporaryImageFile() throws IOException {
        File directory = activity.getCacheDir();
        String name = "image_crop_plus_" + UUID.randomUUID();
        return File.createTempFile(name, ".jpg", directory);
    }

    private ImageOptions decodeImageOptions(String path) {
        int rotationDegrees = 0;
        try {
            ExifInterface exif = new ExifInterface(path);
            rotationDegrees = exif.getRotationDegrees();
        } catch (IOException e) {
            Log.e("ImageCrop", "Failed to read a file " + path, e);
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, opts);
        return new ImageOptions(opts.outWidth, opts.outHeight, rotationDegrees);
    }

    private void copyExif(File source, File destination) {
        try {
            ExifInterface srcExif = new ExifInterface(source.getAbsolutePath());
            ExifInterface dstExif = new ExifInterface(destination.getAbsolutePath());
            List<String> tags = Arrays.asList(
                ExifInterface.TAG_F_NUMBER,
                ExifInterface.TAG_EXPOSURE_TIME,
                ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
                ExifInterface.TAG_GPS_ALTITUDE,
                ExifInterface.TAG_GPS_ALTITUDE_REF,
                ExifInterface.TAG_FOCAL_LENGTH,
                ExifInterface.TAG_GPS_DATESTAMP,
                ExifInterface.TAG_WHITE_BALANCE,
                ExifInterface.TAG_GPS_PROCESSING_METHOD,
                ExifInterface.TAG_GPS_TIMESTAMP,
                ExifInterface.TAG_DATETIME,
                ExifInterface.TAG_FLASH,
                ExifInterface.TAG_GPS_LATITUDE,
                ExifInterface.TAG_GPS_LATITUDE_REF,
                ExifInterface.TAG_GPS_LONGITUDE,
                ExifInterface.TAG_GPS_LONGITUDE_REF,
                ExifInterface.TAG_MAKE,
                ExifInterface.TAG_MODEL,
                ExifInterface.TAG_ORIENTATION
            );
            for (String tag : tags) {
                String attr = srcExif.getAttribute(tag);
                if (attr != null) dstExif.setAttribute(tag, attr);
            }
            dstExif.saveAttributes();
        } catch (IOException e) {
            Log.e("ImageCrop", "Failed to preserve Exif information", e);
        }
    }

    // Helper class to hold image dimensions and rotation
    private static final class ImageOptions {
        private final int width;
        private final int height;
        private final int degrees;

        ImageOptions(int width, int height, int degrees) {
            this.width = width;
            this.height = height;
            this.degrees = degrees;
        }

        int getHeight() {
            return isFlippedDimensions() ? width : height;
        }

        int getWidth() {
            return isFlippedDimensions() ? height : width;
        }

        int getDegrees() {
            return degrees;
        }

        boolean isFlippedDimensions() {
            return degrees == 90 || degrees == 270;
        }
    }
}

