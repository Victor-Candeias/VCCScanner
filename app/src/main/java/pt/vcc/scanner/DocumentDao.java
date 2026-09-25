package pt.vcc.scanner;
import androidx.room.*;
import java.util.List;
@Dao public abstract class DocumentDao {
    @Query("SELECT * FROM documents ORDER BY created DESC") public abstract List<Document> all();
    @Query("SELECT * FROM documents WHERE id IN (SELECT documentId FROM document_index WHERE document_index MATCH :query) ORDER BY created DESC") public abstract List<Document> search(String query);
    @Insert(onConflict = OnConflictStrategy.REPLACE) protected abstract void insert(Document document);
    @Insert protected abstract void index(DocumentIndex index);
    @Query("DELETE FROM document_index WHERE documentId = :id") protected abstract void clearIndex(String id);
    @Delete protected abstract void remove(Document document);
    @Transaction public void save(Document document){insert(document);clearIndex(document.id);index(DocumentIndex.from(document));}
    @Transaction public void delete(Document document){clearIndex(document.id);remove(document);}
}
