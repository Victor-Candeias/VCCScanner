package pt.vcc.scanner;
import androidx.room.*;
@Database(entities = {Document.class, DocumentIndex.class}, version = 3, exportSchema = false)
public abstract class ScannerDatabase extends RoomDatabase {
    public abstract DocumentDao documents();
    public static final androidx.room.migration.Migration MIGRATION_1_2 = new androidx.room.migration.Migration(1,2) {
        @Override public void migrate(androidx.sqlite.db.SupportSQLiteDatabase db) {
            db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `document_index` USING FTS4(`documentId` TEXT, `content` TEXT)");
            db.execSQL("DELETE FROM document_index");
            try(android.database.Cursor cursor=db.query("SELECT id, title, text, company, nif, date, total, tags, barcodes FROM documents")) {
                while(cursor.moveToNext()) {
                    StringBuilder content=new StringBuilder();for(int i=1;i<cursor.getColumnCount();i++)if(!cursor.isNull(i))content.append(cursor.getString(i)).append(' ');
                    db.execSQL("INSERT INTO document_index(documentId,content) VALUES (?,?)",new Object[]{cursor.getString(0),Metadata.normalize(content.toString())});
                }
            }
        }
    };
    public static final androidx.room.migration.Migration MIGRATION_2_3 = new androidx.room.migration.Migration(2,3) {
        @Override public void migrate(androidx.sqlite.db.SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE documents ADD COLUMN edited TEXT");
            db.execSQL("UPDATE documents SET edited = ''");
        }
    };
}
