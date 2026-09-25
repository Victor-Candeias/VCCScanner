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
            engine.edit(doc,0,"Escala de cinzentos");assertTrue(doc.text.contains("501234567"));assertEquals("Faturas",doc.category);assertEquals("501234567",doc.nif);assertEquals("19,70",doc.total);
            engine.edit(doc,0,"Rodar");assertTrue(engine.pages(doc).getJSONObject(0).getInt("width")>engine.pages(doc).getJSONObject(0).getInt("height"));assertEquals("501234567",doc.nif);
            engine.edit(doc,0,"Original");assertEquals(1200,engine.pages(doc).getJSONObject(0).getInt("width"));
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
    private static File fixture(Context context,String name,String... lines) throws Exception {
        File file=new File(context.getCacheDir(),name);Bitmap bitmap=Bitmap.createBitmap(1200,1600,Bitmap.Config.ARGB_8888);
        try{Canvas canvas=new Canvas(bitmap);canvas.drawColor(Color.WHITE);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setTextSize(48);paint.setColor(Color.BLACK);
            for(int i=0;i<lines.length;i++)canvas.drawText(lines[i],100,180+i*100,paint);
            try(OutputStream out=new FileOutputStream(file)){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}
        }finally{bitmap.recycle();}
        return file;
    }
}
