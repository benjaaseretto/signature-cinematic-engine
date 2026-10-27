package com.signature.cinematic.record;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import com.signature.cinematic.color.ColorProfile;
import com.signature.cinematic.color.PhotoLook;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Procesa y guarda fotos en Pictures/SignatureCamera. */
public final class PhotoSaver {
    private static final String DIR = Environment.DIRECTORY_PICTURES + "/SignatureCamera";
    private static final String[] EXIF_TAGS = {
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_ISO_SPEED_RATINGS, ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_FLASH,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
    };

    private PhotoSaver() {
    }

    /**
     * Recorta a la proporción y aplica el estilo sobre la JPEG del ISP; conserva la orientación y los EXIF
     * de captura. Sin estilo ni recorte devuelve los mismos bytes.
     */
    public static byte[] process(Context ctx, byte[] jpeg, ColorProfile style, float ratio, boolean applyStyle)
            throws IOException {
        return process(ctx, jpeg, style, ratio, applyStyle, 0f, 0f, 0f, 0f, 0f);
    }

    public static byte[] process(Context ctx, byte[] jpeg, ColorProfile style, float ratio, boolean applyStyle,
            float tonePad, float colorPad, float softSkin, float glow, float grain)
            throws IOException {
        return process(ctx, jpeg, style, ratio, applyStyle, tonePad, colorPad, softSkin, glow, grain, false);
    }

    public static byte[] process(Context ctx, byte[] jpeg, ColorProfile style, float ratio, boolean applyStyle,
            float tonePad, float colorPad, float softSkin, float glow, float grain, boolean flipHorizontal)
            throws IOException {
        boolean crop = Math.abs(ratio - 4f / 3f) > 1e-3f;
        boolean hasTexture = Math.abs(tonePad) > 0.01f || Math.abs(colorPad) > 0.01f
                || softSkin > 0.01f || glow > 0.01f || grain > 0.01f;
        if (!applyStyle && !hasTexture && !crop && !flipHorizontal) {
            return jpeg;
        }
        ExifInterface src = new ExifInterface(new ByteArrayInputStream(jpeg));
        int exifOrientation = src.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        int degrees = 0;
        switch (exifOrientation) {
            case ExifInterface.ORIENTATION_ROTATE_90: degrees = 90; break;
            case ExifInterface.ORIENTATION_ROTATE_180: degrees = 180; break;
            case ExifInterface.ORIENTATION_ROTATE_270: degrees = 270; break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: flipHorizontal = !flipHorizontal; break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: degrees = 180; flipHorizontal = !flipHorizontal; break;
            case ExifInterface.ORIENTATION_TRANSPOSE: degrees = 90; flipHorizontal = !flipHorizontal; break;
            case ExifInterface.ORIENTATION_TRANSVERSE: degrees = 270; flipHorizontal = !flipHorizontal; break;
        }

        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        o.inMutable = true;
        Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
        if (bmp == null) {
            throw new IOException("No se pudo decodificar la foto");
        }

        // 1. Rotar físicamente a orientación vertical derecha según los EXIF de captura
        Matrix m = new Matrix();
        if (degrees != 0) {
            m.postRotate(degrees);
        }
        // 2. Si es cámara frontal, reflejar horizontalmente sobre la imagen ya derecha (selfie mirror como en pantalla)
        if (flipHorizontal) {
            m.postScale(-1f, 1f);
        }
        if (degrees != 0 || flipHorizontal) {
            Bitmap upright = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (upright != bmp) {
                bmp.recycle();
                bmp = upright;
            }
        }

        // 3. Recortar según la proporción seleccionada (4:3, 16:9, 1:1) sobre la imagen ya derecha
        if (crop) {
            int[] r = PhotoLook.cropRect(bmp.getWidth(), bmp.getHeight(), ratio);
            Bitmap c = Bitmap.createBitmap(bmp, r[0], r[1], r[2], r[3]);
            if (c != bmp) {
                bmp.recycle();
                bmp = c.isMutable() ? c : c.copy(Bitmap.Config.ARGB_8888, true);
            }
        }

        // 4. Aplicar estilo creativo, look fotográfico y textura de piel
        if (applyStyle || hasTexture) {
            int w = bmp.getWidth();
            int h = bmp.getHeight();
            int[] px = new int[w * h];
            bmp.getPixels(px, 0, w, 0, 0, w, h);
            PhotoLook.apply(px, w, h, style, Runtime.getRuntime().availableProcessors(),
                    tonePad, colorPad, softSkin, glow, grain);
            // Reconstrucción de micro-textura y reducción de ruido en alta frecuencia
            PhotoLook.enhanceMicroTexture(px, w, h, 0.28f, Runtime.getRuntime().availableProcessors());
            bmp.setPixels(px, 0, w, 0, 0, w, h);
        }

        // 5. Guardar en JPEG con orientación NORMAL (ya que los píxeles fueron rotados físicamente a su posición vertical)
        File tmp = File.createTempFile("photo", ".jpg", ctx.getCacheDir());
        try {
            try (OutputStream out = new FileOutputStream(tmp)) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 95, out);
            }
            bmp.recycle();
            ExifInterface dst = new ExifInterface(tmp.getAbsolutePath());
            for (String tag : EXIF_TAGS) {
                String v = src.getAttribute(tag);
                if (v != null) {
                    dst.setAttribute(tag, v);
                }
            }
            dst.setAttribute(ExifInterface.TAG_ORIENTATION,
                    String.valueOf(ExifInterface.ORIENTATION_NORMAL));
            dst.setAttribute(ExifInterface.TAG_SOFTWARE, "Signature Camera · " + style.name);
            dst.saveAttributes();
            return Files.readAllBytes(tmp.toPath());
        } finally {
            tmp.delete();
        }
    }

    public static byte[] encodeUpright(Context ctx, Bitmap upright, float ratio, int exifOrientation, String software)
            throws IOException {
        return encodeUpright(ctx, upright, ratio, exifOrientation, software, false);
    }

    /** Cuadro ya graduado de la GPU (Retrato): recorte, JPEG y orientación del teléfono. */
    public static byte[] encodeUpright(Context ctx, Bitmap upright, float ratio, int exifOrientation, String software, boolean flipHorizontal)
            throws IOException {
        Bitmap bmp = upright;
        if (Math.abs(ratio - 4f / 3f) > 1e-3f) {
            int[] r = PhotoLook.cropRect(bmp.getWidth(), bmp.getHeight(), ratio);
            bmp = Bitmap.createBitmap(upright, r[0], r[1], r[2], r[3]);
        }
        if (flipHorizontal) {
            Matrix m = new Matrix();
            m.preScale(-1f, 1f);
            Bitmap flipped = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (bmp != upright && bmp != flipped) {
                bmp.recycle();
            }
            bmp = flipped;
        }
        File tmp = File.createTempFile("portrait", ".jpg", ctx.getCacheDir());
        try {
            try (OutputStream out = new FileOutputStream(tmp)) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 95, out);
            }
            ExifInterface dst = new ExifInterface(tmp.getAbsolutePath());
            dst.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(ExifInterface.ORIENTATION_NORMAL));
            dst.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL,
                    new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(new Date()));
            dst.setAttribute(ExifInterface.TAG_MAKE, android.os.Build.MANUFACTURER);
            dst.setAttribute(ExifInterface.TAG_MODEL, android.os.Build.MODEL);
            dst.setAttribute(ExifInterface.TAG_SOFTWARE, software);
            dst.saveAttributes();
            return Files.readAllBytes(tmp.toPath());
        } finally {
            tmp.delete();
            if (bmp != upright) {
                bmp.recycle();
            }
        }
    }

    public static Uri saveJpeg(Context ctx, byte[] jpeg, String suffix) throws IOException {
        return save(ctx, jpeg, name(suffix) + ".jpg", "image/jpeg");
    }

    public static Uri saveDng(Context ctx, byte[] dng) throws IOException {
        return save(ctx, dng, name("") + ".dng", "image/x-adobe-dng");
    }

    private static String name(String suffix) {
        return "SC_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date()) + suffix;
    }

    private static Uri save(Context ctx, byte[] data, String name, String mime) throws IOException {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Images.Media.MIME_TYPE, mime);
        v.put(MediaStore.Images.Media.RELATIVE_PATH, DIR);
        v.put(MediaStore.Images.Media.IS_PENDING, 1);
        ContentResolver cr = ctx.getContentResolver();
        Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) {
            throw new IOException("No se pudo crear " + name);
        }
        try (OutputStream out = cr.openOutputStream(uri)) {
            if (out == null) {
                throw new IOException("Sin acceso a " + name);
            }
            out.write(data);
        } catch (IOException | RuntimeException e) {
            cr.delete(uri, null, null);
            throw e;
        }
        v.clear();
        v.put(MediaStore.Images.Media.IS_PENDING, 0);
        cr.update(uri, v, null, null);
        return uri;
    }

    /** Miniatura derecha para el botón de la galería. */
    public static Bitmap thumbnail(byte[] jpeg, int maxSide) {
        try {
            BitmapFactory.Options b = new BitmapFactory.Options();
            b.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, b);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = Math.max(1, Math.max(b.outWidth, b.outHeight) / maxSide);
            Bitmap t = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
            if (t == null) {
                return null;
            }
            int orientation = new ExifInterface(new ByteArrayInputStream(jpeg))
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            int deg = orientation == ExifInterface.ORIENTATION_ROTATE_90 ? 90
                    : orientation == ExifInterface.ORIENTATION_ROTATE_180 ? 180
                    : orientation == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
            if (deg == 0) {
                return t;
            }
            Matrix m = new Matrix();
            m.postRotate(deg);
            return Bitmap.createBitmap(t, 0, 0, t.getWidth(), t.getHeight(), m, true);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Miniatura de un video recién guardado (primer cuadro). */
    public static Bitmap videoThumbnail(Context ctx, Uri uri) {
        try {
            return ctx.getContentResolver().loadThumbnail(uri, new android.util.Size(256, 256), null);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
