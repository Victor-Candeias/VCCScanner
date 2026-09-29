package pt.vcc.scanner;

import android.content.Context;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import androidx.room.Room;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import java.util.zip.ZipFile;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DocumentFlowTest {
    @Test public void invoiceSurvivesImportEditExportAndReload() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();DocumentEngine engine=new DocumentEngine(context);Document doc=engine.create();
        File fixture=fixture(context,"invoice-fixture.png","FATURA","Empresa XYZ, Lda.","NIF: 501234567","Data: 25/09/2026","Total: 19,70 EUR");
        ScannerDatabase db=Room.inMemoryDatabaseBuilder(context,ScannerDatabase.class).build();
        try{
            engine.importUris(doc,List.of(Uri.fromFile(fixture)));assertEquals(1,engine.pages(doc).length());assertTrue(doc.text,doc.text.contains("501234567"));assertEquals("Faturas",doc.category);assertEquals("19,70",doc.total);
            db.documents().save(doc);assertEquals(doc.text,db.documents().all().get(0).text);
            assertEquals("Search should combine OCR terms. Recognized text: "+doc.text,1,db.documents().search(Metadata.ftsQuery("empresa 501234567")).size());
            assertEquals(1,db.documents().search(Metadata.ftsQuery("emp")).size());
            doc.tags="Saúde";db.documents().save(doc);assertEquals(1,db.documents().search(Metadata.ftsQuery("saude")).size());
            engine.crop(doc,0,new float[]{.01f,.01f,.99f,.01f,.99f,.99f,.01f,.99f});assertTrue(doc.text.contains("501234567"));
            engine.edit(doc,0,DocumentEngine.ACTION_GRAYSCALE);assertTrue(doc.text.contains("501234567"));assertEquals("Faturas",doc.category);assertEquals("501234567",doc.nif);assertEquals("19,70",doc.total);
            engine.edit(doc,0,DocumentEngine.ACTION_ROTATE);assertTrue(engine.pages(doc).getJSONObject(0).getInt("width")>engine.pages(doc).getJSONObject(0).getInt("height"));assertEquals("501234567",doc.nif);
            engine.edit(doc,0,DocumentEngine.ACTION_ORIGINAL);assertEquals(1200,engine.pages(doc).getJSONObject(0).getInt("width"));
            File pdf=engine.export(doc,"pdf");assertTrue(pdf.length()>1000);
            try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(pdf,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer renderer=new PdfRenderer(fd)){
                assertEquals(1,renderer.getPageCount());
                if(android.os.Build.VERSION.SDK_INT>=35)try(PdfRenderer.Page page=renderer.openPage(0)){
                    StringBuilder extracted=new StringBuilder();for(var item:page.getTextContents())extracted.append(item.getText());assertTrue("PDF must contain a real searchable text layer: "+extracted,extracted.toString().contains("501234567"));
                }
            }
            File text=engine.export(doc,"txt");assertTrue(new String(java.nio.file.Files.readAllBytes(text.toPath()),java.nio.charset.StandardCharsets.UTF_8).contains("501234567"));
            File images=engine.export(doc,"zip");try(ZipFile zip=new ZipFile(images)){assertNotNull(zip.getEntry("pagina-001.jpg"));}
            File png=engine.export(doc,"png.zip");try(ZipFile zip=new ZipFile(png)){assertNotNull(zip.getEntry("pagina-001.png"));}finally{png.delete();}
            Document imported=engine.create();try{engine.importUris(imported,List.of(Uri.fromFile(pdf)));assertTrue(imported.text.contains("501234567"));}finally{engine.deleteFiles(imported);}
            // Keep a diagnostic export inside the test cache for inspecting the searchable text layer.
            java.nio.file.Files.copy(pdf.toPath(),new File(context.getCacheDir(),"verified-searchable.pdf").toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            db.documents().delete(doc);assertTrue(db.documents().all().isEmpty());pdf.delete();text.delete();images.delete();
        }finally{db.close();engine.deleteFiles(doc);fixture.delete();}
    }
    @Test public void failedImportDoesNotChangeExistingPages() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();DocumentEngine engine=new DocumentEngine(context);Document d=engine.create();
        try{engine.importUris(d,List.of(Uri.parse("file:///missing-vcc-image.jpg")));fail("Import should fail");}catch(IOException expected){assertEquals("[]",d.pages);}finally{engine.deleteFiles(d);}
    }
    @Test public void databaseSurvivesReopenAndUpdatesIndex() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();String name="test-"+UUID.randomUUID()+".db";Document doc=new Document();doc.id="persistence";doc.title="Declaração";doc.text="Seguro de saúde";
        ScannerDatabase first=Room.databaseBuilder(context,ScannerDatabase.class,name).build();
        try{first.documents().save(doc);}finally{first.close();}
        ScannerDatabase second=Room.databaseBuilder(context,ScannerDatabase.class,name).build();
        try{assertEquals("Declaração",second.documents().all().get(0).title);assertEquals(1,second.documents().search(Metadata.ftsQuery("saude")).size());doc.text="Contrato de casa";second.documents().save(doc);assertTrue(second.documents().search(Metadata.ftsQuery("saude")).isEmpty());assertEquals(1,second.documents().search(Metadata.ftsQuery("casa")).size());second.documents().delete(doc);assertTrue(second.documents().search(Metadata.ftsQuery("casa")).isEmpty());}finally{second.close();context.deleteDatabase(name);}
    }
    @Test public void appendedPageRefreshesMetadataWithoutLosingUserEdits() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();DocumentEngine engine=new DocumentEngine(context);Document doc=engine.create();
        File notes=fixture(context,"notes-fixture.png","Lista de compras","Pao leite e fruta","Entrega ao sabado");
        File invoice=fixture(context,"append-fixture.png","FATURA","Empresa XYZ, Lda.","NIF: 501234567","Data: 25/09/2026","Total: 19,70 EUR");
        ScannerDatabase db=Room.inMemoryDatabaseBuilder(context,ScannerDatabase.class).build();
        try{
            engine.importUris(doc,List.of(Uri.fromFile(notes)));assertEquals(doc.text,"Outros",doc.category);assertEquals("",doc.nif);
            doc.company=Metadata.edit(doc,Metadata.COMPANY,doc.company,"Empresa Escolhida");db.documents().save(doc);
            engine.importUris(doc,List.of(Uri.fromFile(invoice)));db.documents().save(doc);
            assertEquals(2,engine.pages(doc).length());
            assertEquals(doc.text,"Faturas",doc.category);assertEquals(doc.text,"501234567",doc.nif);assertEquals("25/09/2026",doc.date);assertEquals("19,70",doc.total);
            assertEquals("Empresa Escolhida",doc.company);
            assertEquals(1,db.documents().search(Metadata.ftsQuery("501234567")).size());
        }finally{db.close();engine.deleteFiles(doc);notes.delete();invoice.delete();}
    }
    @Test public void migratesVersionTwoDatabaseAndKeepsAutomaticExtraction() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();String name="migration-"+UUID.randomUUID()+".db";
        File file=context.getDatabasePath(name);file.getParentFile().mkdirs();
        android.database.sqlite.SQLiteDatabase legacy=android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file,null);
        try{
            // Schema produced by ScannerDatabase version 2, before the `edited` column existed.
            legacy.execSQL("CREATE TABLE IF NOT EXISTS `documents` (`id` TEXT NOT NULL, `title` TEXT, `category` TEXT, `text` TEXT, `company` TEXT, `nif` TEXT, `date` TEXT, `total` TEXT, `tags` TEXT, `language` TEXT, `barcodes` TEXT, `pages` TEXT, `created` INTEGER NOT NULL, PRIMARY KEY(`id`))");
            legacy.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `document_index` USING FTS4(`documentId` TEXT, `content` TEXT)");
            legacy.execSQL("INSERT INTO documents(id,title,category,text,company,nif,date,total,tags,language,barcodes,pages,created) VALUES ('legacy','Fatura antiga','Faturas','FATURA\nEmpresa Antiga, Lda.\nNIF: 501234567','Empresa Antiga, Lda.','501234567','','','','pt','','[]',1)");
            legacy.execSQL("INSERT INTO document_index(documentId,content) VALUES ('legacy',?)",new Object[]{Metadata.normalize("Fatura antiga Empresa Antiga, Lda. 501234567")});
            legacy.setVersion(2);
        }finally{legacy.close();}
        ScannerDatabase db=Room.databaseBuilder(context,ScannerDatabase.class,name).addMigrations(ScannerDatabase.MIGRATION_1_2,ScannerDatabase.MIGRATION_2_3).build();
        try{
            Document migrated=db.documents().all().get(0);
            assertEquals("Fatura antiga",migrated.title);assertEquals("",migrated.edited);
            assertEquals(1,db.documents().search(Metadata.ftsQuery("antiga")).size());
            migrated.text="RECIBO\nEmpresa Nova, S.A.\nNIF: 502222222";Metadata.extract(migrated);
            assertEquals("Recibos",migrated.category);assertEquals("Empresa Nova, S.A.",migrated.company);assertEquals("502222222",migrated.nif);
            migrated.company=Metadata.edit(migrated,Metadata.COMPANY,migrated.company,"Empresa Corrigida");db.documents().save(migrated);
            assertEquals("company",db.documents().all().get(0).edited);
        }finally{db.close();context.deleteDatabase(name);}
    }
    @Test public void repeatedEditsDoNotAccumulateOrphanImages() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();DocumentEngine engine=new DocumentEngine(context);Document doc=engine.create();
        File first=fixture(context,"orphan-fixture.png","FATURA","Empresa XYZ, Lda.","NIF: 501234567");
        File second=fixture(context,"orphan-second.png","Anexo","Segunda pagina");
        try{
            engine.importUris(doc,List.of(Uri.fromFile(first)));assertEquals(1,files(engine,doc));
            for(int i=0;i<3;i++){engine.edit(doc,0,DocumentEngine.ACTION_ROTATE);engine.sweep(doc);}
            // Only the current image and the imported original may survive repeated edits.
            assertEquals(2,files(engine,doc));
            engine.crop(doc,0,new float[]{.01f,.01f,.99f,.01f,.99f,.99f,.01f,.99f});engine.sweep(doc);assertEquals(2,files(engine,doc));
            engine.edit(doc,0,DocumentEngine.ACTION_ORIGINAL);engine.sweep(doc);assertEquals(1200,engine.pages(doc).getJSONObject(0).getInt("width"));assertEquals(2,files(engine,doc));
            String current=engine.pages(doc).getJSONObject(0).getString("path");
            try{engine.edit(doc,5,DocumentEngine.ACTION_ROTATE);fail("Editing a missing page should fail");}catch(Exception expected){engine.sweep(doc);assertTrue("A failed edit must keep the referenced image",new File(current).exists());}
            engine.importUris(doc,List.of(Uri.fromFile(second)));engine.sweep(doc);
            org.json.JSONArray pages=engine.pages(doc);String removed=pages.getJSONObject(1).getString("path");
            pages.remove(1);doc.pages=pages.toString();engine.rebuild(doc);engine.sweep(doc);
            assertFalse("Deleting a page must delete its images",new File(removed).exists());
            assertEquals(2,files(engine,doc));assertTrue(new File(current).exists());
        }finally{engine.deleteFiles(doc);first.delete();second.delete();}
    }
    @Test public void automaticProcessingDeskewsAndCleansImportedPhoto() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();DocumentEngine engine=new DocumentEngine(context);Document doc=engine.create();
        File photo=photo(context,"skewed-fixture.png","FATURA","Empresa XYZ, Lda.","NIF: 501234567","Data: 25/09/2026","Total: 19,70 EUR");
        Bitmap raw=BitmapFactory.decodeFile(photo.getAbsolutePath());
        try{
            float[] corners=ImageProcessor.detect(raw);
            assertNotNull("The page outline must be detected in a photographed document",corners);
            Bitmap flat=ImageProcessor.warp(raw,corners);
            assertNotNull(flat);
            try{
                // The perspective is undone, so the page recovers the aspect ratio it was drawn with.
                assertEquals("Perspective must be corrected",PAGE_WIDTH/(float)PAGE_HEIGHT,flat.getWidth()/(float)flat.getHeight(),.08f);
                assertTrue("A shadowed photo must be reported as needing enhancement",ImageProcessor.needsEnhancement(flat));
                int before=illuminationRange(flat);
                Bitmap cleaned=ImageProcessor.enhance(flat);
                try{
                    assertTrue("Shadow removal must flatten the illumination: "+before+" -> "+illuminationRange(cleaned),illuminationRange(cleaned)*2<before);
                    assertFalse("The cleaned page must not need a second pass",ImageProcessor.needsEnhancement(cleaned));
                    assertTrue("Cleaning must not lose text: "+text(cleaned),found(text(cleaned))>=found(text(raw)));
                }finally{cleaned.recycle();}
            }finally{flat.recycle();}
            engine.importUris(doc,List.of(Uri.fromFile(photo)));
            assertEquals(1,engine.pages(doc).length());
            assertEquals("The corrected page and the untouched original",2,files(engine,doc));
            assertEquals(doc.text,"501234567",doc.nif);assertEquals("Faturas",doc.category);assertEquals("19,70",doc.total);assertEquals("25/09/2026",doc.date);
            assertTrue("The stored page must be the deskewed one",engine.pages(doc).getJSONObject(0).getInt("width")<raw.getWidth());
            // "Original" still undoes everything, including the automatic correction.
            engine.edit(doc,0,DocumentEngine.ACTION_ORIGINAL);assertEquals(raw.getWidth(),engine.pages(doc).getJSONObject(0).getInt("width"));
            engine.edit(doc,0,DocumentEngine.ACTION_AUTO);assertTrue(doc.text,doc.text.contains("501234567"));
        }finally{raw.recycle();engine.deleteFiles(doc);photo.delete();}
    }
    @Test public void flatScansAreLeftUntouchedByTheAutomaticCorrection() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File fixture=fixture(context,"flat-fixture.png","FATURA","Empresa XYZ, Lda.");
        Bitmap flat=BitmapFactory.decodeFile(fixture.getAbsolutePath());
        try{
            assertNull("A page that already fills the frame has nothing to deskew",ImageProcessor.detect(flat));
            assertFalse("An evenly lit scan has nothing to correct",ImageProcessor.needsEnhancement(flat));
            assertNull("Nothing to correct means no second copy of the page",ImageProcessor.process(flat));
        }finally{flat.recycle();fixture.delete();}
    }
    private static final int PAGE_WIDTH=840,PAGE_HEIGHT=1330;
    /** Corners of the page inside the photographed fixture, clockwise from the top left. */
    private static final float[] SKEWED={300,120,1130,230,1060,1560,230,1450};
    private static int found(String text){
        int count=0;
        for(String token:new String[]{"FATURA","Empresa","501234567","25/09/2026","19,70"})if(text.contains(token))count++;
        return count;
    }
    private static String text(Bitmap bitmap) throws Exception {
        var recognizer=com.google.mlkit.vision.text.TextRecognition.getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS);
        try{return com.google.android.gms.tasks.Tasks.await(recognizer.process(com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap,0)),90,java.util.concurrent.TimeUnit.SECONDS).getText();}finally{recognizer.close();}
    }
    /** Difference between the brightest and the darkest paper across the page, a shadow measure. */
    private static int illuminationRange(Bitmap bitmap){
        int min=255,max=0;
        for(int gy=0;gy<6;gy++)for(int gx=0;gx<6;gx++){
            int paper=0;
            for(int y=gy*bitmap.getHeight()/6;y<(gy+1)*bitmap.getHeight()/6;y+=4)for(int x=gx*bitmap.getWidth()/6;x<(gx+1)*bitmap.getWidth()/6;x+=4){
                int pixel=bitmap.getPixel(x,y),value=(Color.red(pixel)*77+Color.green(pixel)*151+Color.blue(pixel)*28)>>8;
                if(value>paper)paper=value;
            }
            min=Math.min(min,paper);max=Math.max(max,paper);
        }
        return max-min;
    }
    /** A page photographed at an angle over a dark surface, crossed by a shadow. */
    private static File photo(Context context,String name,String... lines) throws Exception {
        File file=new File(context.getCacheDir(),name);
        Bitmap page=Bitmap.createBitmap(PAGE_WIDTH,PAGE_HEIGHT,Bitmap.Config.ARGB_8888);
        Bitmap photo=Bitmap.createBitmap(1400,1700,Bitmap.Config.ARGB_8888);
        try{
            Canvas sheet=new Canvas(page);sheet.drawColor(Color.WHITE);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setTextSize(54);paint.setColor(Color.BLACK);
            for(int i=0;i<lines.length;i++)sheet.drawText(lines[i],70,200+i*120,paint);
            Canvas canvas=new Canvas(photo);canvas.drawColor(Color.rgb(62,60,58));
            Matrix matrix=new Matrix();
            assertTrue(matrix.setPolyToPoly(new float[]{0,0,PAGE_WIDTH,0,PAGE_WIDTH,PAGE_HEIGHT,0,PAGE_HEIGHT},0,SKEWED,0,4));
            canvas.drawBitmap(page,matrix,new Paint(Paint.FILTER_BITMAP_FLAG));
            Paint shadow=new Paint();shadow.setShader(new LinearGradient(0,0,photo.getWidth(),photo.getHeight(),0x00000000,0x78000000,Shader.TileMode.CLAMP));
            canvas.drawRect(0,0,photo.getWidth(),photo.getHeight(),shadow);
            try(OutputStream out=new FileOutputStream(file)){photo.compress(Bitmap.CompressFormat.PNG,100,out);}
        }finally{page.recycle();photo.recycle();}
        return file;
    }
    private static int files(DocumentEngine engine,Document d){File[] files=engine.directory(d.id).listFiles();return files==null?0:files.length;}
    private static File fixture(Context context,String name,String... lines) throws Exception {
        File file=new File(context.getCacheDir(),name);Bitmap bitmap=Bitmap.createBitmap(1200,1600,Bitmap.Config.ARGB_8888);
        try{Canvas canvas=new Canvas(bitmap);canvas.drawColor(Color.WHITE);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setTextSize(48);paint.setColor(Color.BLACK);
            for(int i=0;i<lines.length;i++)canvas.drawText(lines[i],100,180+i*100,paint);
            try(OutputStream out=new FileOutputStream(file)){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}
        }finally{bitmap.recycle();}
        return file;
    }
}
