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

// permissions
import static android.Manifest.permission.READ_EXTERNAL_STORAGE;
import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;

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

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        BinaryMessenger messenger = flutterPluginBinding.getBinaryMessenger();
        channel = new MethodChannel(messenger, "plugins.marcin.wroblewscy.eu/image_crop_plus");
        channel.setMethodCallHandler(this);
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        if (channel != null) {
            channel.setMethodCallHandler(null);
            channel = null;
        }
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        this.binding = binding;
        this.activity = binding.getActivity();
        binding.addRequestPermissionsResultListener(this);
    }

    @Override public void onDetachedFromActivityForConfigChanges() {}

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

    @Override
    public void onMethodCall(MethodCall call, Result result) {
        switch (call.method) {
            case "cropImage": {
                String path = call.argument("path");
                double scale = call.argument("scale");
                double left = call.argument("left");
                double top = call.argument("top");
                double right = call.argument("right");
                double bottom = call.argument("bottom");
                RectF area = new RectF((float) left, (float) top, (float) right, (float) bottom);
                cropImage(path, area, (float) scale, result);
                break;
            }
            case "sampleImage": {
                String path = call.argument("path");
                int maxW = call.argument("maximumWidth");
                int maxH = call.argument("maximumHeight");
                sampleImage(path, maxW, maxH, result);
                break;
            }
            case "getImageOptions": {
                String path = call.argument("path");
                getImageOptions(path, result);
                break;
            }
            case "requestPermissions": {
                requestPermissions(result);
                break;
            }
            default:
                result.notImplemented();
        }
    }

    private synchronized void io(@NonNull Runnable runnable) {
        if (executor == null) {
            executor = Executors.newCachedThreadPool();
        }
        executor.execute(runnable);
    }

    private void ui(@NonNull Runnable runnable) {
        if (activity != null) {
            activity.runOnUiThread(runnable);
        }
    }

    private void cropImage(String path, RectF area, float scale, Result result) {
        io(() -> {
            File srcFile = new File(path);
            if (!srcFile.exists()) {
                ui(() -> result.error("INVALID", "Source cannot be opened", null));
                return;
            }
            Bitmap src = BitmapFactory.decodeFile(path);
            if (src == null) {
                ui(() -> result.error("INVALID", "Source cannot be decoded", null));
                return;
            }
            ImageOptions opts = decodeImageOptions(path);
            if (opts.isFlippedDimensions()) {
                Matrix m = new Matrix();
                m.postRotate(opts.getDegrees());
                Bitmap old = src;
                src = Bitmap.createBitmap(old, 0, 0, old.getWidth(), old.getHeight(), m, true);
                old.recycle();
            }
            int outW = (int) (opts.getWidth() * area.width() * scale);
            int outH = (int) (opts.getHeight() * area.height() * scale);
            Bitmap out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            Paint p = new Paint(); p.setAntiAlias(true); p.setFilterBitmap(true); p.setDither(true);
            Rect srcR = new Rect((int) (src.getWidth() * area.left), (int) (src.getHeight() * area.top),
                                  (int) (src.getWidth() * area.right), (int) (src.getHeight() * area.bottom));
            Rect dstR = new Rect(0, 0, outW, outH);
            c.drawBitmap(src, srcR, dstR, p);
            try {
                File dstFile = createTemporaryImageFile();
                compressBitmap(out, dstFile);
                ui(() -> result.success(dstFile.getAbsolutePath()));
            } catch (IOException e) {
                ui(() -> result.error("INVALID", "Could not save image", e));
            } finally {
                out.recycle();
                src.recycle();
            }
        });
    }

    private void sampleImage(String path, int maxW, int maxH, Result result) {
        io(() -> {
            File srcFile = new File(path);
            if (!srcFile.exists()) {
                ui(() -> result.error("INVALID", "Source cannot be opened", null));
                return;
            }
            ImageOptions opts = decodeImageOptions(path);
            BitmapFactory.Options decOpts = new BitmapFactory.Options();
            decOpts.inSampleSize = calculateInSampleSize(opts.getWidth(), opts.getHeight(), maxW, maxH);
            Bitmap bmp = BitmapFactory.decodeFile(path, decOpts);
            if (bmp == null) {
                ui(() -> result.error("INVALID", "Source cannot be decoded", null));
                return;
            }
            if (opts.getWidth() > maxW || opts.getHeight() > maxH) {
                float ratio = Math.max(maxW / (float) opts.getWidth(), maxH / (float) opts.getHeight());
                Bitmap tmp = bmp;
                bmp = Bitmap.createScaledBitmap(tmp, Math.round(tmp.getWidth() * ratio), Math.round(tmp.getHeight() * ratio), true);
                tmp.recycle();
            }
            try {
                File dst = createTemporaryImageFile();
                compressBitmap(bmp, dst);
                copyExif(srcFile, dst);
                ui(() -> result.success(dst.getAbsolutePath()));
            } catch (IOException e) {
                ui(() -> result.error("INVALID", "Could not save image", e));
            } finally {
                bmp.recycle();
            }
        });
    }

    private void compressBitmap(Bitmap bitmap, File file) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)) {
                throw new IOException("Compression failed");
            }
        }
    }

    private int calculateInSampleSize(int w, int h, int maxW, int maxH) {
        int inSampleSize = 1;
        if (h > maxH || w > maxW) {
            int halfH = h / 2;
            int halfW = w / 2;
            while ((halfH / inSampleSize) >= maxH && (halfW / inSampleSize) >= maxW) {
                inSampleSize <<= 1;
            }
        }
        return inSampleSize;
    }

    private void getImageOptions(String path, Result result) {
        io(() -> {
            File f = new File(path);
            if (!f.exists()) {
                ui(() -> result.error("INVALID", "Source cannot be opened", null));
                return;
            }
            ImageOptions opts = decodeImageOptions(path);
            Map<String, Object> props = new HashMap<>();
            props.put("width", opts.getWidth());
            props.put("height", opts.getHeight());
            ui(() -> result.success(props));
        });
    }

    private void requestPermissions(Result result) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && activity != null) {
            boolean granted = activity.checkSelfPermission(READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                              activity.checkSelfPermission(WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                result.success(true);
            } else {
                permissionRequestResult = result;
                activity.requestPermissions(new String[]{READ_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE}, PERMISSION_REQUEST_CODE);
            }
        } else {
            result.success(true);
        }
    }

    @Override
    public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST_CODE && permissionRequestResult != null) {
            boolean ok = getPermissionGrantResult(READ_EXTERNAL_STORAGE, permissions, grantResults) == PackageManager.PERMISSION_GRANTED &&
                         getPermissionGrantResult(WRITE_EXTERNAL_STORAGE, permissions, grantResults) == PackageManager.PERMISSION_GRANTED;
            permissionRequestResult.success(ok);
            permissionRequestResult = null;
            return true;
        }
        return false;
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
        File dir = activity.getCacheDir();
        String name = "image_crop_plus_" + UUID.randomUUID();
        return File.createTempFile(name, ".jpg", dir);
    }

    private ImageOptions decodeImageOptions(String path) {
        int deg = 0;
        try {
            ExifInterface exif = new ExifInterface(path);
            deg = exif.getRotationDegrees();
        } catch (IOException e) {
            Log.e("ImageCrop", "Cannot read EXIF: " + path, e);
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        return new ImageOptions(o.outWidth, o.outHeight, deg);
    }

    private void copyExif(File source, File dest) {
        try {
            ExifInterface src = new ExifInterface(source.getAbsolutePath());
            ExifInterface dst = new ExifInterface(dest.getAbsolutePath());
            List<String> tags = Arrays.asList(
                ExifInterface.TAG_DATETIME,
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.TAG_GPS_LATITUDE,
                ExifInterface.TAG_GPS_LATITUDE_REF,
                ExifInterface.TAG_GPS_LONGITUDE,
                ExifInterface.TAG_GPS_LONGITUDE_REF
            );
            for (String t : tags) {
                String val = src.getAttribute(t);
                if (val != null) dst.setAttribute(t, val);
            }
            dst.saveAttributes();
        } catch (IOException e) {
            Log.e("ImageCrop", "Failed to copy EXIF", e);
        }
    }

    private static final class ImageOptions {
        private final int width;
        private final int height;
        private final int degrees;

        ImageOptions(int width, int height, int degrees) {
            this.width = width;
            this.height = height;
            this.degrees = degrees;
        }

        int getWidth() { return isFlippedDimensions() ? height : width; }
        int getHeight() { return isFlippedDimensions() ? width : height; }
        int getDegrees() { return degrees; }
        boolean isFlippedDimensions() { return degrees == 90 || degrees == 270; }
    }
}

