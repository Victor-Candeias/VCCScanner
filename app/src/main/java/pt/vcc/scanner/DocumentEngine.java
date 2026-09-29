package pt.vcc.scanner;

import android.content.Context;
import android.graphics.*;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import org.json.*;
import java.io.*;
import java.util.*;

public class DocumentEngine {
    public static final int MAX_PAGES = 50;
    /**
     * Stable keys for the page filters. They are deliberately not the menu labels: the labels live in
     * strings.xml and may be translated, while these identify the operation to apply.
     */
    public static final String ACTION_ROTATE = "rotate", ACTION_AUTO = "auto", ACTION_ORIGINAL = "original", ACTION_GRAYSCALE = "grayscale", ACTION_MONOCHROME = "monochrome", ACTION_DOCUMENT = "document", ACTION_PHOTO = "photo";
    private final Context context;
    public DocumentEngine(Context context) { this.context = context.getApplicationContext(); }
    public File directory(String id) { File dir = new File(context.getFilesDir(), "documents/" + id); dir.mkdirs(); return dir; }
    public JSONArray pages(Document d) throws JSONException { return new JSONArray(d.pages); }
    public Bitmap bitmap(String path) throws IOException {
        Bitmap b = BitmapFactory.decodeFile(path);
        if (b == null) throw new IOException(context.getString(R.string.engine_error_image_unreadable));
        return b;
    }
    public Document create() {
        Document d = new Document(); d.id = UUID.randomUUID().toString(); d.created = System.currentTimeMillis();
        d.title = context.getString(R.string.engine_default_title, new java.text.SimpleDateFormat(context.getString(R.string.engine_title_date_format), new Locale("pt", "PT")).format(new Date()));
        return d;
    }
    public void importUris(Document d, List<Uri> uris) throws Exception {
        JSONArray list = pages(d);
        List<File> added = new ArrayList<>();
        try {
            for (Uri uri : uris) {
                String mime = context.getContentResolver().getType(uri);
                if ("application/pdf".equals(mime) || (mime == null && uri.toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))) {
                    try (ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(uri, "r")) {
                        if (fd == null) throw new IOException(context.getString(R.string.engine_error_pdf_unreadable));
                        try (PdfRenderer renderer = new PdfRenderer(fd)) {
                            if (renderer.getPageCount() + list.length() > MAX_PAGES) throw new IOException(context.getResources().getQuantityString(R.plurals.engine_error_page_limit, MAX_PAGES, MAX_PAGES));
                            for (int i=0; i<renderer.getPageCount(); i++) {
                                try (PdfRenderer.Page page = renderer.openPage(i)) {
                                    float scale = Math.min(2.5f, 2200f / Math.max(page.getWidth(), page.getHeight()));
                                    Bitmap b = Bitmap.createBitmap(Math.max(1, Math.round(page.getWidth()*scale)), Math.max(1, Math.round(page.getHeight()*scale)), Bitmap.Config.ARGB_8888);
                                    try { b.eraseColor(Color.WHITE); page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT); add(d, list, b, added); }
                                    finally { b.recycle(); }
                                }
                            }
                        }
                    }
                } else {
                    if (list.length() >= MAX_PAGES) throw new IOException(context.getResources().getQuantityString(R.plurals.engine_error_page_limit, MAX_PAGES, MAX_PAGES));
                    ImageDecoder.Source source = ImageDecoder.createSource(context.getContentResolver(), uri);
                    Bitmap b = ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                        int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                        float scale = Math.min(1f, 2200f / Math.max(w, h));
                        decoder.setTargetSize(Math.max(1, Math.round(w*scale)), Math.max(1, Math.round(h*scale)));
                        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    });
                    try { add(d, list, b, added); } finally { b.recycle(); }
                }
            }
            if (list.length() == 0) throw new IOException(context.getString(R.string.engine_error_no_pages));
            d.pages = list.toString(); rebuild(d);
        } catch (Exception e) { for (File file : added) file.delete(); throw e; }
    }
    /**
     * Stores a page, correcting it first when it looks like a photo: contours are detected, the
     * perspective is flattened and the illumination is normalized. When that happens the untouched
     * image is kept as the original, so the "Original" filter still undoes everything.
     */
    private void add(Document d, JSONArray list, Bitmap b, List<File> added) throws Exception {
        Bitmap processed = ImageProcessor.process(b);
        try {
            Bitmap page = processed != null ? processed : b;
            File f = new File(directory(d.id), UUID.randomUUID()+".jpg"); added.add(f);
            write(f, page, 92);
            String original = f.getAbsolutePath();
            if (processed != null) { File raw = new File(directory(d.id), UUID.randomUUID()+".jpg"); added.add(raw); write(raw, b, 92); original = raw.getAbsolutePath(); }
            JSONObject item = recognize(page); item.put("path", f.getAbsolutePath()); item.put("original", original); list.put(item);
        } finally { if (processed != null) processed.recycle(); }
    }
    private void write(File file, Bitmap b, int quality) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) { if (!b.compress(Bitmap.CompressFormat.JPEG, quality, out)) throw new IOException(context.getString(R.string.engine_error_image_save)); }
    }
    private JSONObject recognize(Bitmap b) throws Exception {
        JSONObject page = new JSONObject(); JSONArray lines = new JSONArray();
        var recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        try {
            Text text = Tasks.await(recognizer.process(InputImage.fromBitmap(b, 0)), 90, java.util.concurrent.TimeUnit.SECONDS);
            page.put("text", text.getText());
            for (Text.TextBlock block : text.getTextBlocks()) for (Text.Line line : block.getLines()) {
                Rect r = line.getBoundingBox(); if (r == null) continue;
                lines.put(new JSONObject().put("text", line.getText()).put("x", r.left).put("y", r.top).put("w", r.width()).put("h", r.height()));
            }
        } finally { recognizer.close(); }
        var scanner = BarcodeScanning.getClient();
        try {
            StringBuilder codes = new StringBuilder();
            for (var code : Tasks.await(scanner.process(InputImage.fromBitmap(b, 0)), 60, java.util.concurrent.TimeUnit.SECONDS)) if (code.getRawValue() != null) codes.append(code.getRawValue()).append('\n');
            page.put("codes", codes.toString());
        } finally { scanner.close(); }
        return page.put("lines", lines).put("width", b.getWidth()).put("height", b.getHeight());
    }
    public void rebuild(Document d) throws Exception {
        JSONArray list = pages(d); StringBuilder text = new StringBuilder(), codes = new StringBuilder();
        for (int i=0; i<list.length(); i++) { if (i>0) text.append("\n\n"); text.append(list.getJSONObject(i).optString("text")); codes.append(list.getJSONObject(i).optString("codes")); }
        d.text = text.toString(); d.barcodes = codes.toString();
        var identifier = LanguageIdentification.getClient();
        try { d.language = d.text.trim().isEmpty() ? "und" : Tasks.await(identifier.identifyLanguage(d.text), 30, java.util.concurrent.TimeUnit.SECONDS); } finally { identifier.close(); }
        // Every page change reaches this point, so the extracted fields follow the recognized text.
        Metadata.extract(d);
    }
    public void edit(Document d, int index, String action) throws Exception {
        JSONArray list = pages(d); JSONObject old = list.getJSONObject(index);
        Bitmap source = bitmap(old.getString(ACTION_ORIGINAL.equals(action) ? "original" : "path"));
        Bitmap result = null;
        try {
            if (ACTION_ROTATE.equals(action)) {
                Matrix m = new Matrix(); m.postRotate(90); result = Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), m, true);
            } else if (ACTION_AUTO.equals(action)) {
                result = ImageProcessor.enhance(source);
            } else {
                result = Bitmap.createBitmap(source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
                ColorMatrix matrix = new ColorMatrix();
                // Only the colour filters keep the saturation: everything else is a document rendering.
                if (ACTION_PHOTO.equals(action)) matrix.setSaturation(1.3f);
                else if (!ACTION_ORIGINAL.equals(action)) matrix.setSaturation(0);
                if (ACTION_PHOTO.equals(action)) matrix.postConcat(new ColorMatrix(new float[]{1.12f,0,0,0,-12,0,1.12f,0,0,-12,0,0,1.12f,0,-12,0,0,0,1,0}));
                if (ACTION_DOCUMENT.equals(action)) matrix.postConcat(new ColorMatrix(new float[]{1.45f,0,0,0,-35,0,1.45f,0,0,-35,0,0,1.45f,0,-35,0,0,0,1,0}));
                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setColorFilter(new ColorMatrixColorFilter(matrix)); new Canvas(result).drawBitmap(source, 0, 0, paint);
                if (ACTION_MONOCHROME.equals(action)) {
                    int[] row = new int[result.getWidth()];
                    for (int y=0; y<result.getHeight(); y++) { result.getPixels(row, 0, row.length, 0, y, row.length, 1); for (int x=0; x<row.length; x++) row[x] = Color.red(row[x]) > 160 ? Color.WHITE : Color.BLACK; result.setPixels(row, 0, row.length, 0, y, row.length, 1); }
                }
            }
            JSONObject next = recognize(result); File f = new File(directory(d.id), UUID.randomUUID()+".jpg");
            write(f, result, 94);
            next.put("path", f.getAbsolutePath()).put("original", old.getString("original")); list.put(index, next); d.pages = list.toString(); rebuild(d);
        } finally { source.recycle(); if (result != null && result != source) result.recycle(); }
    }
    public void crop(Document d, int index, float[] corners) throws Exception {
        // Reject crossed, reversed or tiny quadrilaterals before computing a perspective matrix.
        for (int i=0;i<4;i++) {
            int a=i*2, b=((i+1)%4)*2, c=((i+2)%4)*2;
            float cross=(corners[b]-corners[a])*(corners[c+1]-corners[b+1])-(corners[b+1]-corners[a+1])*(corners[c]-corners[b]);
            if(cross<.005f)throw new IOException(context.getString(R.string.engine_error_crossed_corners));
        }
        JSONArray list=pages(d);JSONObject old=list.getJSONObject(index);Bitmap source=bitmap(old.getString("path"));Bitmap result=null;
        try {
            result=ImageProcessor.warp(source,corners);if(result==null)throw new IOException(context.getString(R.string.engine_error_invalid_crop));
            JSONObject next=recognize(result);File file=new File(directory(d.id),UUID.randomUUID()+".jpg");write(file,result,94);
            next.put("path",file.getAbsolutePath()).put("original",old.getString("original"));list.put(index,next);d.pages=list.toString();rebuild(d);
        }finally{source.recycle();if(result!=null)result.recycle();}
    }
    public File export(Document d, String type) throws Exception {
        File dir = new File(context.getCacheDir(), "exports"); dir.mkdirs();
        String name = d.title.replaceAll("[^\\p{L}\\p{N} ._-]", "_"); if (name.trim().isEmpty()) name = context.getString(R.string.engine_export_fallback_name);
        if (name.length() > 80) name = name.substring(0, 80);
        File file = new File(dir, name + "-" + System.currentTimeMillis() + "." + type);
        if (type.equals("txt")) { try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), java.nio.charset.StandardCharsets.UTF_8)) { writer.write(d.text); } return file; }
        JSONArray list = pages(d);
        if (type.equals("zip") || type.equals("png.zip")) {
            try (var zip = new java.util.zip.ZipOutputStream(new FileOutputStream(file))) {
                for (int i=0; i<list.length(); i++) {
                    boolean png=type.equals("png.zip");zip.putNextEntry(new java.util.zip.ZipEntry(String.format(Locale.ROOT, "pagina-%03d."+(png?"png":"jpg"), i+1)));
                    if(png){Bitmap b=bitmap(list.getJSONObject(i).getString("path"));try{b.compress(Bitmap.CompressFormat.PNG,100,zip);}finally{b.recycle();}}
                    else try (InputStream in = new FileInputStream(list.getJSONObject(i).getString("path"))) { byte[] buffer = new byte[8192]; int n; while ((n=in.read(buffer))!=-1) zip.write(buffer,0,n); }
                    zip.closeEntry();
                }
            }
            return file;
        }
        PdfDocument pdf = new PdfDocument();
        try {
            for (int i=0; i<list.length(); i++) {
                JSONObject item = list.getJSONObject(i); Bitmap b = bitmap(item.getString("path"));
                try {
                    float scale = 595f/b.getWidth(); int height = Math.max(1, Math.round(b.getHeight()*scale));
                    PdfDocument.Page page = pdf.startPage(new PdfDocument.PageInfo.Builder(595, height, i+1).create()); Canvas canvas = page.getCanvas();
                    // OCR text is drawn first, then covered by the opaque scan: searchable without visible duplication.
                    JSONArray lines = item.getJSONArray("lines"); Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setColor(Color.BLACK);
                    for (int j=0; j<lines.length(); j++) {
                        JSONObject line = lines.getJSONObject(j); String value = line.getString("text"); paint.setTextSize(Math.max(1, (float)line.getDouble("h")*scale)); paint.setTextScaleX(1);
                        float width = paint.measureText(value); if (width>0) paint.setTextScaleX((float)line.getDouble("w")*scale/width);
                        canvas.drawText(value, (float)line.getDouble("x")*scale, ((float)line.getDouble("y")+(float)line.getDouble("h"))*scale, paint);
                    }
                    canvas.drawBitmap(b, null, new Rect(0,0,595,height), new Paint(Paint.FILTER_BITMAP_FLAG)); pdf.finishPage(page);
                } finally { b.recycle(); }
            }
            try (OutputStream out = new FileOutputStream(file)) { pdf.writeTo(out); }
        } finally { pdf.close(); }
        return file;
    }
    /**
     * Deletes the images in the document directory that no page references any more, such as the ones
     * superseded by a rotation, filter or crop and the ones left behind by an interrupted operation.
     * Call it only after the new page list has been saved: while it is not, those files are still in use.
     */
    public void sweep(Document d) {
        File[] files = directory(d.id).listFiles(); if (files == null) return;
        Set<String> keep = new HashSet<>();
        try {
            JSONArray list = pages(d); if (list.length() == 0) return;
            for (int i=0; i<list.length(); i++) {
                JSONObject page = list.getJSONObject(i); String path = page.getString("path");
                // Compare by name: the stored absolute path may use a different prefix for the same directory.
                keep.add(new File(path).getName()); keep.add(new File(page.optString("original", path)).getName());
            }
        } catch (JSONException broken) { return; }
        for (File file : files) if (!keep.contains(file.getName())) file.delete();
    }
    public void deleteFiles(Document d) {
        File dir = directory(d.id); File[] files = dir.listFiles(); if (files != null) for (File file : files) file.delete(); dir.delete();
    }
}
