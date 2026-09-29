package pt.vcc.scanner;
import org.junit.Test;
import static org.junit.Assert.*;

public class MetadataTest {
    @Test public void extractsPortugueseInvoice() {
        Document d=new Document();d.text="FATURA\nEmpresa XYZ, Lda.\nNIF: 501234567\nData: 25/09/2026\nTotal: 19,70 €";
        Metadata.extract(d);
        assertEquals("Faturas",d.category);assertEquals("501234567",d.nif);assertEquals("25/09/2026",d.date);assertEquals("19,70",d.total);assertEquals("Empresa XYZ, Lda.",d.company);
    }
    @Test public void doesNotConfuseSubtotalWithTotal() {
        Document d=new Document();d.text="Subtotal: 10,00\nIVA: 2,30\nTotal a pagar: 12,30";Metadata.extract(d);assertEquals("12,30",d.total);
    }
    @Test public void missingFieldsStayEmpty() {
        Document d=new Document();d.text="Fotografia de uma paisagem";Metadata.extract(d);assertEquals("Outros",d.category);assertEquals("",d.nif);assertEquals("",d.date);assertEquals("",d.total);
    }
    @Test public void searchIgnoresAccentsAndCase() {
        Document d=new Document();d.title="Declaração";d.tags="Saúde";d.nif="501234567";
        assertTrue(Metadata.matches(d,"DECLARACAO"));assertTrue(Metadata.matches(d,"saude"));assertTrue(Metadata.matches(d,"501234567"));assertFalse(Metadata.matches(d,"inexistente"));
    }
    @Test public void classifiesSupportedCategories() {
        assertEquals("Recibos",Metadata.category("RECIBO"));assertEquals("Contratos",Metadata.category("Contrato de arrendamento"));assertEquals("Manuais",Metadata.category("Instruções"));assertEquals("Pessoais",Metadata.category("Cartão de cidadão"));
    }
    @Test public void rejectsNifWithExtraDigits() {
        Document d=new Document();d.text="NIF: 1234567890";Metadata.extract(d);assertEquals("",d.nif);
    }
    @Test public void searchQueryUsesSafeFts4PrefixSyntax(){assertEquals("empresa* 501234567*",Metadata.ftsQuery("Empresa \"501234567\""));assertEquals("saude*",Metadata.ftsQuery("SAÚDE"));assertEquals("",Metadata.ftsQuery("*:()\""));}
    @Test public void userCorrectionsSurviveReExtraction() {
        Document d=new Document();d.text="FATURA\nEmpresa XYZ, Lda.\nNIF: 501234567\nTotal: 19,70";Metadata.extract(d);
        d.company=Metadata.edit(d,Metadata.COMPANY,d.company,"Empresa Corrigida, Lda.");
        d.text="RECIBO\nOutra Empresa, S.A.\nNIF: 502222222\nTotal: 5,00";Metadata.extract(d);
        assertEquals("Empresa Corrigida, Lda.",d.company);assertEquals("Recibos",d.category);assertEquals("502222222",d.nif);assertEquals("5,00",d.total);
    }
    @Test public void unchangedValuesKeepAutomaticExtraction() {
        Document d=new Document();d.text="FATURA\nEmpresa XYZ, Lda.\nNIF: 501234567";Metadata.extract(d);
        d.company=Metadata.edit(d,Metadata.COMPANY,d.company,d.company);assertEquals("",d.edited);
        d.text="FATURA\nOutra Empresa, S.A.\nNIF: 502222222";Metadata.extract(d);
        assertEquals("Outra Empresa, S.A.",d.company);assertEquals("502222222",d.nif);
    }
    @Test public void degradedOcrKeepsPreviouslyExtractedValues() {
        Document d=new Document();d.text="FATURA\nEmpresa XYZ, Lda.\nNIF: 501234567\nData: 25/09/2026\nTotal: 19,70";Metadata.extract(d);
        d.text="";Metadata.extract(d);
        assertEquals("Faturas",d.category);assertEquals("Empresa XYZ, Lda.",d.company);assertEquals("501234567",d.nif);assertEquals("25/09/2026",d.date);assertEquals("19,70",d.total);
    }
    @Test public void copyKeepsManualCorrections() {
        Document d=new Document();d.company=Metadata.edit(d,Metadata.COMPANY,d.company,"Empresa Corrigida");
        Document c=d.copy();c.text="FATURA\nOutra Empresa, S.A.\nNIF: 502222222";Metadata.extract(c);
        assertEquals("Empresa Corrigida",c.company);assertEquals("502222222",c.nif);
    }
    @Test public void readsDatesAsComparableNumbers() {
        assertEquals(20260925,Metadata.day("25/09/2026",true));
        assertEquals(20260925,Metadata.day("Data: 25-09-2026",false));
        assertEquals(20260901,Metadata.day("09/2026",true));
        assertEquals(20260931,Metadata.day("09/2026",false));
        assertEquals(20260101,Metadata.day("2026",true));
        assertEquals(20261231,Metadata.day("2026",false));
        assertEquals(Metadata.NO_DAY,Metadata.day("",true));
        assertEquals(Metadata.NO_DAY,Metadata.day("sem data",true));
    }
    @Test public void readsAmountsInBothSeparatorStyles() {
        assertEquals(1970,Metadata.cents("19,70"));
        assertEquals(1970,Metadata.cents("19.70"));
        assertEquals(1970,Metadata.cents("19,7"));
        assertEquals(123456,Metadata.cents("1.234,56"));
        assertEquals(123456,Metadata.cents("1,234.56"));
        assertEquals(123400,Metadata.cents("1.234"));
        assertEquals(500,Metadata.cents("5 €"));
        assertEquals(Metadata.NO_AMOUNT,Metadata.cents(""));
        assertEquals(Metadata.NO_AMOUNT,Metadata.cents("sem valor"));
    }
}
